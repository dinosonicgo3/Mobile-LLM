package com.oracleairescue;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.Session;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

class SshClient {
    private final ServerSettings settings;
    SshClient(ServerSettings settings) { this.settings = settings; }

    CommandResult testConnection() throws Exception {
        return runCommand("whoami && hostname && uptime", 30000);
    }

    List<CommandResult> runCommands(List<String> commands, long timeoutMs) throws Exception {
        List<CommandResult> out = new ArrayList<>();
        for (String c : commands) out.add(runCommand(c, timeoutMs));
        return out;
    }

    CommandResult runCommand(String command, long timeoutMs) throws Exception {
        if (settings.host == null || settings.host.trim().isEmpty()) throw new IllegalArgumentException("請先設定 Oracle 主機 IP 或網域。");
        Session session = openSession();
        ChannelExec channel = null;
        try {
            channel = (ChannelExec) session.openChannel("exec");
            channel.setCommand(command);
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            channel.setErrStream(err);
            InputStream in = channel.getInputStream();
            channel.connect(15000);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long deadline = System.currentTimeMillis() + timeoutMs;
            byte[] buf = new byte[4096];
            boolean timedOut = false;
            while (true) {
                while (in.available() > 0) {
                    int n = in.read(buf, 0, buf.length);
                    if (n < 0) break;
                    out.write(buf, 0, n);
                }
                if (channel.isClosed()) break;
                if (System.currentTimeMillis() > deadline) { timedOut = true; break; }
                Thread.sleep(100);
            }
            int exit = timedOut ? -1 : channel.getExitStatus();
            if (timedOut) try { channel.disconnect(); } catch (Exception ignored) {}
            return new CommandResult(command, exit,
                out.toString(StandardCharsets.UTF_8.name()),
                err.toString(StandardCharsets.UTF_8.name()), timedOut);
        } finally {
            if (channel != null && channel.isConnected()) channel.disconnect();
            session.disconnect();
        }
    }

    String readFile(String path) throws Exception {
        if (path == null || path.trim().isEmpty()) throw new IllegalArgumentException("請輸入檔案路徑。");
        CommandResult r = runCommand("cat -- " + DiagnosticCommands.shellQuote(path.trim()), 60000);
        if (r.exitCode != 0) throw new RuntimeException(r.asText());
        return r.stdout;
    }

    String writeFileWithBackup(String path, String content) throws Exception {
        if (path == null || path.trim().isEmpty()) throw new IllegalArgumentException("請輸入檔案路徑。");
        Session session = openSession();
        ChannelSftp sftp = null;
        try {
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect(15000);
            String backup = path + ".bak_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.TAIWAN).format(new Date());
            try {
                ByteArrayOutputStream old = new ByteArrayOutputStream();
                sftp.get(path, old);
                sftp.put(new ByteArrayInputStream(old.toByteArray()), backup);
            } catch (Exception e) {
                throw new RuntimeException("建立備份失敗，不會寫回：" + e.getMessage(), e);
            }
            byte[] data = content.getBytes(StandardCharsets.UTF_8);
            sftp.put(new ByteArrayInputStream(data), path);
            return backup;
        } finally {
            if (sftp != null && sftp.isConnected()) sftp.disconnect();
            session.disconnect();
        }
    }

    private Session openSession() throws Exception {
        JSch jsch = new JSch();
        if (settings.privateKey != null && settings.privateKey.trim().length() > 0) {
            byte[] key = settings.privateKey.getBytes(StandardCharsets.UTF_8);
            byte[] pass = settings.privateKeyPassphrase == null || settings.privateKeyPassphrase.length() == 0 ? null : settings.privateKeyPassphrase.getBytes(StandardCharsets.UTF_8);
            jsch.addIdentity("oracle-ai-rescue-key", key, null, pass);
        }
        Session session = jsch.getSession(settings.username, settings.host, settings.port);
        if (settings.password != null && settings.password.length() > 0) session.setPassword(settings.password);
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", settings.strictHostKeyChecking ? "yes" : "no");
        session.setConfig(config);
        session.connect(20000);
        return session;
    }
}
