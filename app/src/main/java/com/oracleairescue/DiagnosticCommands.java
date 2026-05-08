package com.oracleairescue;

import java.util.ArrayList;
import java.util.List;

class DiagnosticCommands {
    static List<String> build(ServerSettings s, RuntimeConfig cfg) {
        List<String> cmds = new ArrayList<>();
        cmds.add("hostname && whoami && date && uptime");
        cmds.add("free -h || true");
        cmds.add("df -h || true");
        cmds.add("systemctl --failed --no-pager || true");
        cmds.add("docker ps -a || true");
        cmds.add("journalctl -n 200 --no-pager || true");
        if (s != null && s.serviceName != null && s.serviceName.trim().length() > 0) {
            String service = shellQuote(s.serviceName.trim());
            cmds.add("systemctl status " + service + " --no-pager || true");
            cmds.add("journalctl -u " + service + " -n 240 --no-pager || true");
        }
        if (s != null && s.dockerContainer != null && s.dockerContainer.trim().length() > 0) {
            String container = shellQuote(s.dockerContainer.trim());
            cmds.add("docker logs --tail 240 " + container + " 2>&1 || true");
        }
        if (s != null && s.projectPath != null && s.projectPath.trim().length() > 0) {
            String p = shellQuote(s.projectPath.trim());
            cmds.add("cd " + p + " && pwd && ls -la && git status --short 2>/dev/null || true");
        }
        if (cfg != null) cmds.addAll(cfg.extraDiagnosticCommands);
        return cmds;
    }

    static String shellQuote(String input) {
        if (input == null) return "''";
        return "'" + input.replace("'", "'\\''") + "'";
    }
}
