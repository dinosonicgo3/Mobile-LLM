package com.oracleairescue;

class RepairSafety {
    static boolean isDangerous(String command) {
        if (command == null) return true;
        String c = command.trim().toLowerCase();
        if (c.length() == 0) return true;
        String[] banned = new String[] {
            "rm -rf /", "rm -rf *", "mkfs", "dd if=", ":(){", "chmod -r 777", "chown -r",
            "curl ", "wget ", "| bash", "| sh", "shutdown", "poweroff", "reboot", "> /dev/sd", "wipefs"
        };
        for (String b : banned) if (c.contains(b)) return true;
        return false;
    }
}
