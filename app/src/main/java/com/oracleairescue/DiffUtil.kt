package com.oracleairescue

object DiffUtil {
    fun unifiedDiff(oldText: String, newText: String, maxLines: Int = 1200): String {
        val oldLines = oldText.split("\n")
        val newLines = newText.split("\n")
        val out = StringBuilder()
        out.appendLine("--- 原檔案")
        out.appendLine("+++ 修改後")
        var i = 0
        var j = 0
        var shown = 0
        while ((i < oldLines.size || j < newLines.size) && shown < maxLines) {
            val oldLine = oldLines.getOrNull(i)
            val newLine = newLines.getOrNull(j)
            when {
                oldLine == newLine -> {
                    if (oldLine != null) {
                        out.appendLine("  $oldLine")
                        i++; j++; shown++
                    } else break
                }
                newLine != null && oldLines.drop(i + 1).take(6).contains(newLine) -> {
                    out.appendLine("- ${oldLine.orEmpty()}")
                    i++; shown++
                }
                oldLine != null && newLines.drop(j + 1).take(6).contains(oldLine) -> {
                    out.appendLine("+ ${newLine.orEmpty()}")
                    j++; shown++
                }
                oldLine != null && newLine != null -> {
                    out.appendLine("- $oldLine")
                    out.appendLine("+ $newLine")
                    i++; j++; shown += 2
                }
                oldLine != null -> {
                    out.appendLine("- $oldLine")
                    i++; shown++
                }
                newLine != null -> {
                    out.appendLine("+ $newLine")
                    j++; shown++
                }
            }
        }
        if (i < oldLines.size || j < newLines.size) out.appendLine("... diff 過長，已截斷 ...")
        return out.toString()
    }

    fun stripCodeFence(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return text.trimEnd()
        val lines = trimmed.lines().toMutableList()
        if (lines.isNotEmpty() && lines.first().startsWith("```")) lines.removeAt(0)
        if (lines.isNotEmpty() && lines.last().startsWith("```")) lines.removeAt(lines.lastIndex)
        return lines.joinToString("\n").trimEnd()
    }
}
