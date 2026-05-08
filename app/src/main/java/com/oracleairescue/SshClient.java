package com.oracleairescue;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
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

    String testConnectionDetailed() {
        StringBuilder sb = new StringBuilder();
        sb.append("【SSH 智慧診斷】\n");
        sb.append("主機：").append(nullSafe(settings.host)).append("\n");
        sb.append("Port：").append(settings.port).append("\n");
        sb.append("目前使用者：").append(nullSafe(settings.username)).append("\n\n");

        sb.append("【私鑰檢查】\n");
        sb.append(keyReport(settings.privateKey)).append("\n");

        sb.append("【TCP 連線測試】\n");
        try {
            tcpProbe(settings.host, settings.port, 12000);
            sb.append("成功：手機可以連到 ").append(settings.host).append(":").append(settings.port).append("\n\n");
        } catch (Exception e) {
            sb.append("失敗：").append(e.getClass().getSimpleName()).append("：").append(e.getMessage()).append("\n");
            sb.append("判斷：通常是 IP 錯誤、SSH Port 不是 22、Oracle Security List / NSG 沒開 22、手機網路被擋，或主機沒開機。\n\n");
            return sb.toString();
        }

        List<String> users = new ArrayList<>();
        String u = settings.username == null ? "" : settings.username.trim();
        if (!u.isEmpty()) users.add(u);
        if (!users.contains("ubuntu")) users.add("ubuntu");
        if (!users.contains("opc")) users.add("opc");

        sb.append("【帳號/金鑰登入測試】\n");
        for (String user : users) {
            sb.append("嘗試使用者：").append(user).append("\n");
            try {
                ServerSettings clone = cloneWithUser(user);
                CommandResult r = new SshClient(clone).testConnection();
                sb.append("成功。\n").append(r.asText()).append("\n");
                sb.append("建議：把 Oracle 設定頁的使用者固定為 ").append(user).append("。\n");
                return sb.toString();
            } catch (Exception e) {
                sb.append("失敗：").append(e.getClass().getSimpleName()).append("：").append(e.getMessage()).append("\n");
                sb.append(explainSshError(e)).append("\n");
            }
        }

        sb.append("\n【結論】\n");
        sb.append("TCP 可連，但 ubuntu/opc 都無法登入，最可能是：\n");
        sb.append("1. 貼到 App 的不是『Compute Instance SSH 私鑰』，而是 OCI 後台 API Signing Key。\n");
        sb.append("2. 這把私鑰對應的公鑰沒有放在該主機的 ~/.ssh/authorized_keys。\n");
        sb.append("3. 私鑰有 passphrase，但 App 沒填私鑰密碼。\n");
        sb.append("4. 這台主機不是 Ubuntu/Oracle Linux 預設帳號，需填你實際 SSH 使用者。\n");
        sb.append("5. 從電腦可登入的話，請對照電腦指令 ssh -i xxx 使用的是哪個帳號與哪個 key。\n");
        return sb.toString();
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
        String normalizedKey = normalizePrivateKey(settings.privateKey);
        if (normalizedKey.trim().length() > 0) {
            byte[] key = normalizedKey.getBytes(StandardCharsets.UTF_8);
            byte[] pass = settings.privateKeyPassphrase == null || settings.privateKeyPassphrase.length() == 0 ? null : settings.privateKeyPassphrase.getBytes(StandardCharsets.UTF_8);
            try {
                jsch.addIdentity("oracle-ai-rescue-key", key, null, pass);
            } catch (JSchException e) {
                throw new JSchException("私鑰格式無法解析。請確認貼的是完整 SSH 私鑰，包含 BEGIN/END 與所有換行；不要貼 OCI API Key 或 .ppk。原始錯誤：" + e.getMessage(), e);
            }
        }
        Session session = jsch.getSession(settings.username, settings.host, settings.port);
        if (settings.password != null && settings.password.length() > 0) session.setPassword(settings.password);
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", settings.strictHostKeyChecking ? "yes" : "no");
        config.put("PreferredAuthentications", "publickey,password,keyboard-interactive");
        session.setConfig(config);
        try {
            session.connect(20000);
        } catch (JSchException e) {
            throw new JSchException(e.getMessage() + "\n\n" + explainSshError(e), e);
        }
        return session;
    }

    private static String normalizePrivateKey(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        s = s.replace("\r\n", "\n").replace('\r', '\n');
        if (s.contains("\\n") && !s.contains("\n-----")) s = s.replace("\\n", "\n");
        s = s.replace("\uFEFF", "");
        // 若使用者從某些剪貼簿貼上時把 BEGIN/END 前後多了空白，盡量清掉。
        s = s.replace("-----BEGIN ", "-----BEGIN ").replace(" -----END ", "\n-----END ");
        if (!s.endsWith("\n")) s += "\n";
        return s;
    }

    private static String keyReport(String raw) {
        String k = normalizePrivateKey(raw);
        if (k.trim().isEmpty()) return "未填私鑰。若主機不允許密碼登入，會登入失敗。";
        String first = "";
        String last = "";
        String[] lines = k.split("\n");
        for (String line : lines) { if (!line.trim().isEmpty()) { first = line.trim(); break; } }
        for (int i = lines.length - 1; i >= 0; i--) { if (!lines[i].trim().isEmpty()) { last = lines[i].trim(); break; } }
        String type;
        if (first.contains("OPENSSH PRIVATE KEY")) type = "OpenSSH 私鑰";
        else if (first.contains("RSA PRIVATE KEY")) type = "RSA PEM 私鑰";
        else if (first.contains("EC PRIVATE KEY")) type = "EC 私鑰";
        else if (first.contains("DSA PRIVATE KEY")) type = "DSA 私鑰";
        else if (first.contains("BEGIN PGP")) type = "看起來不是 SSH 私鑰";
        else if (first.contains("PuTTY")) type = "PuTTY PPK，需轉成 OpenSSH 格式";
        else type = "未知格式";
        return "格式：" + type + "\n" +
               "第一行：" + maskLine(first) + "\n" +
               "最後一行：" + maskLine(last) + "\n" +
               "行數：約 " + lines.length + " 行\n" +
               "長度：約 " + k.length() + " 字元\n" +
               "包含 BEGIN：" + (k.contains("-----BEGIN ") ? "是" : "否") + "\n" +
               "包含 END：" + (k.contains("-----END ") ? "是" : "否");
    }

    private static String maskLine(String line) {
        if (line == null || line.isEmpty()) return "空";
        if (line.length() <= 18) return line;
        return line.substring(0, 12) + "..." + line.substring(line.length() - 8);
    }

    private static void tcpProbe(String host, int port, int timeoutMs) throws Exception {
        if (host == null || host.trim().isEmpty()) throw new IllegalArgumentException("主機 IP 或網域是空的");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host.trim(), port), timeoutMs);
        }
    }

    private ServerSettings cloneWithUser(String user) {
        ServerSettings s = new ServerSettings();
        s.name = settings.name;
        s.host = settings.host;
        s.port = settings.port;
        s.username = user;
        s.password = settings.password;
        s.privateKey = settings.privateKey;
        s.privateKeyPassphrase = settings.privateKeyPassphrase;
        s.projectPath = settings.projectPath;
        s.serviceName = settings.serviceName;
        s.dockerContainer = settings.dockerContainer;
        s.strictHostKeyChecking = settings.strictHostKeyChecking;
        s.allowSudoCommands = settings.allowSudoCommands;
        return s;
    }

    private static String explainSshError(Exception e) {
        String msg = e == null || e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (msg.contains("auth fail") || msg.contains("authentication")) {
            return "判斷：連得到主機，但登入驗證失敗。通常是使用者名稱不對、私鑰不是這台主機的 SSH key、公鑰未放入 authorized_keys，或私鑰密碼未填。";
        }
        if (msg.contains("invalid privatekey") || msg.contains("privatekey") || msg.contains("pem")) {
            return "判斷：App 無法解析私鑰。請確認貼的是完整 SSH 私鑰，不是 OCI API Signing Key；若是 .ppk，請先轉成 OpenSSH 格式；若有 passphrase，請填私鑰密碼。";
        }
        if (msg.contains("timeout") || msg.contains("timed out") || msg.contains("connection refused") || msg.contains("no route")) {
            return "判斷：網路或 SSH Port 問題。請確認 Oracle 主機公網 IP 正確、Port 通常是 22、Security List / NSG 有開 TCP 22、主機正在運行。";
        }
        if (msg.contains("unknownhost") || msg.contains("resolve")) {
            return "判斷：主機名稱解析失敗。若你填的是網域，請改填公網 IP 測試。";
        }
        return "判斷：需要看完整錯誤。請複製這個診斷結果，但不要貼私鑰內容。";
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }
}
