package com.oracleairescue

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Properties

class SshClient(private val settings: ServerSettings) {
    suspend fun testConnection(): CommandResult = runCommand("whoami && hostname && uptime", 20_000)

    suspend fun runCommand(command: String, timeoutMs: Long = 45_000): CommandResult = withContext(Dispatchers.IO) {
        validateServerSettings()
        connect().useSession { session ->
            execute(session, command, timeoutMs)
        }
    }

    suspend fun runCommands(commands: List<String>, timeoutMsEach: Long = 45_000): List<CommandResult> = withContext(Dispatchers.IO) {
        validateServerSettings()
        connect().useSession { session ->
            commands.map { command -> execute(session, command, timeoutMsEach) }
        }
    }

    suspend fun readFile(path: String): String = withContext(Dispatchers.IO) {
        require(path.isNotBlank()) { "請輸入遠端檔案路徑。" }
        validateServerSettings()
        connect().useSession { session ->
            val channel = session.openChannel("sftp") as ChannelSftp
            channel.connect(15_000)
            try {
                channel.get(path).use { input ->
                    input.readBytes().toString(StandardCharsets.UTF_8)
                }
            } finally {
                channel.disconnect()
            }
        }
    }

    suspend fun writeFileWithBackup(path: String, newContent: String): String = withContext(Dispatchers.IO) {
        require(path.isNotBlank()) { "請輸入遠端檔案路徑。" }
        validateServerSettings()
        connect().useSession { session ->
            val channel = session.openChannel("sftp") as ChannelSftp
            channel.connect(15_000)
            try {
                val oldBytes = channel.get(path).use { input -> input.readBytes() }
                val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val backupPath = "$path.bak_$stamp"
                channel.put(ByteArrayInputStream(oldBytes), backupPath)
                channel.put(ByteArrayInputStream(newContent.toByteArray(StandardCharsets.UTF_8)), path)
                backupPath
            } finally {
                channel.disconnect()
            }
        }
    }

    private fun validateServerSettings() {
        require(settings.host.isNotBlank()) { "請先在設定頁輸入 Oracle 主機 IP 或網域。" }
        require(settings.username.isNotBlank()) { "請先在設定頁輸入 SSH 使用者名稱。" }
        require(settings.privateKey.isNotBlank() || settings.password.isNotBlank()) { "請輸入 SSH 私鑰或密碼。建議使用私鑰。" }
    }

    private fun connect(): Session {
        val jsch = JSch()
        if (settings.privateKey.isNotBlank()) {
            val passphrase = settings.privateKeyPassphrase.takeIf { it.isNotBlank() }?.toByteArray(StandardCharsets.UTF_8)
            jsch.addIdentity(
                "oracle-ai-rescue-key",
                settings.privateKey.replace("\r\n", "\n").toByteArray(StandardCharsets.UTF_8),
                null,
                passphrase
            )
        }
        val session = jsch.getSession(settings.username, settings.host, settings.port)
        if (settings.password.isNotBlank()) session.setPassword(settings.password)
        val config = Properties().apply {
            put("StrictHostKeyChecking", if (settings.strictHostKeyChecking) "yes" else "no")
            put("PreferredAuthentications", "publickey,password,keyboard-interactive")
        }
        session.setConfig(config)
        session.connect(20_000)
        return session
    }

    private fun execute(session: Session, command: String, timeoutMs: Long): CommandResult {
        val channel = session.openChannel("exec") as ChannelExec
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val wrapped = "bash -lc ${shellQuote(command)}"
        channel.setCommand(wrapped)
        channel.setInputStream(null)
        channel.setOutputStream(stdout)
        channel.setErrStream(stderr)
        val start = System.currentTimeMillis()
        var timedOut = false
        try {
            channel.connect(10_000)
            while (!channel.isClosed) {
                Thread.sleep(200)
                if (System.currentTimeMillis() - start > timeoutMs) {
                    timedOut = true
                    channel.disconnect()
                    break
                }
            }
            return CommandResult(
                command = command,
                exitCode = if (timedOut) -1 else channel.exitStatus,
                stdout = stdout.toString(StandardCharsets.UTF_8.name()),
                stderr = stderr.toString(StandardCharsets.UTF_8.name()),
                timedOut = timedOut
            )
        } finally {
            if (channel.isConnected) channel.disconnect()
        }
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}

private inline fun <T> Session.useSession(block: (Session) -> T): T {
    return try {
        block(this)
    } finally {
        if (isConnected) disconnect()
    }
}

object RepairSafety {
    private val dangerousPatterns = listOf(
        Regex("rm\\s+-rf\\s+(/|\\*|~|\\$HOME)", RegexOption.IGNORE_CASE),
        Regex("mkfs\\.", RegexOption.IGNORE_CASE),
        Regex("dd\\s+if=", RegexOption.IGNORE_CASE),
        Regex("chmod\\s+-R\\s+777", RegexOption.IGNORE_CASE),
        Regex("chown\\s+-R\\s+", RegexOption.IGNORE_CASE),
        Regex("curl\\s+.*\\|\\s*(sudo\\s+)?bash", RegexOption.IGNORE_CASE),
        Regex("wget\\s+.*\\|\\s*(sudo\\s+)?bash", RegexOption.IGNORE_CASE),
        Regex("shutdown\\b", RegexOption.IGNORE_CASE),
        Regex("reboot\\b", RegexOption.IGNORE_CASE),
        Regex(":\\(\\)\\s*\\{", RegexOption.IGNORE_CASE)
    )

    fun isDangerous(command: String): Boolean = dangerousPatterns.any { it.containsMatchIn(command) }

    fun shQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}

object DiagnosticCommands {
    fun build(settings: ServerSettings): List<String> {
        val commands = mutableListOf(
            "echo '=== host ==='; hostnamectl 2>/dev/null || hostname",
            "echo '=== user ==='; whoami; id",
            "echo '=== uptime ==='; uptime",
            "echo '=== memory ==='; free -h",
            "echo '=== disk ==='; df -h",
            "echo '=== failed systemd units ==='; systemctl --failed --no-pager 2>/dev/null || true",
            "echo '=== listening ports ==='; ss -tulpn 2>/dev/null | head -n 80 || true",
            "echo '=== recent journal ==='; journalctl -n 220 --no-pager 2>/dev/null || true",
            "echo '=== docker containers ==='; docker ps -a --no-trunc 2>/dev/null || true",
            "echo '=== docker stats ==='; docker stats --no-stream 2>/dev/null || true"
        )
        if (settings.serviceName.isNotBlank()) {
            val service = RepairSafety.shQuote(settings.serviceName)
            commands += "echo '=== service status: ${settings.serviceName} ==='; systemctl status $service --no-pager 2>/dev/null || true"
            commands += "echo '=== service logs: ${settings.serviceName} ==='; journalctl -u $service -n 300 --no-pager 2>/dev/null || true"
        }
        if (settings.dockerContainer.isNotBlank()) {
            val container = RepairSafety.shQuote(settings.dockerContainer)
            commands += "echo '=== docker logs: ${settings.dockerContainer} ==='; docker logs --tail 300 $container 2>&1 || true"
        }
        if (settings.projectPath.isNotBlank()) {
            val path = RepairSafety.shQuote(settings.projectPath)
            commands += "echo '=== project status ==='; cd $path && pwd && ls -la && git status --short 2>/dev/null || true"
            commands += "echo '=== project recent files ==='; cd $path && find . -maxdepth 2 -type f | sed 's#^./##' | head -n 120"
        }
        return commands
    }
}
