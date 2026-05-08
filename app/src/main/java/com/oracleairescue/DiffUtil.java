package com.oracleairescue;

class DiffUtil {
    static String stripCodeFence(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            int lastFence = t.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) t = t.substring(firstNewline + 1, lastFence).trim();
        }
        return t;
    }

    static String unifiedDiff(String oldText, String newText) {
        String[] a = oldText == null ? new String[0] : oldText.split("\\R", -1);
        String[] b = newText == null ? new String[0] : newText.split("\\R", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("--- original\n+++ modified\n");
        int max = Math.max(a.length, b.length);
        int shown = 0;
        for (int i = 0; i < max; i++) {
            String left = i < a.length ? a[i] : null;
            String right = i < b.length ? b[i] : null;
            if (left == null) { sb.append("+").append(right).append("\n"); shown++; }
            else if (right == null) { sb.append("-").append(left).append("\n"); shown++; }
            else if (!left.equals(right)) {
                sb.append("-").append(left).append("\n");
                sb.append("+").append(right).append("\n");
                shown++;
            }
            if (shown > 2000) { sb.append("...diff 過長，已截斷...\n"); break; }
        }
        if (shown == 0) sb.append("沒有差異。\n");
        return sb.toString();
    }
}
