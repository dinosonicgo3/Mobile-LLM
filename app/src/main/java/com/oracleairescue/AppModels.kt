package com.oracleairescue

data class ModelSettings(
    val provider: String = "gemini",
    val apiKey: String = "",
    val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta/openai",
    val modelName: String = "gemini-2.5-flash",
    val temperature: Double = 0.2,
    val maxContextCharacters: Int = 60_000
)

data class ServerSettings(
    val name: String = "我的 Oracle AI 助理",
    val host: String = "",
    val port: Int = 22,
    val username: String = "ubuntu",
    val password: String = "",
    val privateKey: String = "",
    val privateKeyPassphrase: String = "",
    val projectPath: String = "",
    val serviceName: String = "",
    val dockerContainer: String = "",
    val strictHostKeyChecking: Boolean = false,
    val allowSudoCommands: Boolean = false
)

data class ChatMessage(
    val role: String,
    val content: String
)

data class ModelOption(
    val id: String,
    val displayName: String = id,
    val description: String = ""
)

data class CommandResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false
) {
    fun asText(): String = buildString {
        appendLine("$ $command")
        appendLine("exitCode=$exitCode timedOut=$timedOut")
        if (stdout.isNotBlank()) {
            appendLine("--- stdout ---")
            appendLine(stdout.trimEnd())
        }
        if (stderr.isNotBlank()) {
            appendLine("--- stderr ---")
            appendLine(stderr.trimEnd())
        }
    }
}

data class RepairHistory(
    val timestamp: String,
    val title: String,
    val content: String
)

data class FileSnapshot(
    val path: String,
    val content: String
)
