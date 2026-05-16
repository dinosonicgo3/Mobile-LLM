package com.oracleairescue;

import java.util.ArrayList;
import java.util.List;

class ModelSettings {
    String provider = "gemini";
    String apiKey = "";
    String baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai";
    String modelName = "gemini-2.5-flash";
    double temperature = 0.2;
    int maxContextCharacters = 60000;
    String geminiReasoningEffort = "high";
    boolean hideThoughts = true;
    boolean renderMarkdown = true;

    ModelSettings copy() {
        ModelSettings m = new ModelSettings();
        m.provider = provider;
        m.apiKey = apiKey;
        m.baseUrl = baseUrl;
        m.modelName = modelName;
        m.temperature = temperature;
        m.maxContextCharacters = maxContextCharacters;
        m.geminiReasoningEffort = geminiReasoningEffort;
        m.hideThoughts = hideThoughts;
        m.renderMarkdown = renderMarkdown;
        return m;
    }
}

class ServerSettings {
    String name = "我的 Oracle AI 助理";
    String host = "";
    int port = 22;
    String username = "ubuntu";
    String password = "";
    String privateKey = "";
    String privateKeyPassphrase = "";
    String projectPath = "";
    String serviceName = "";
    String dockerContainer = "";
    boolean strictHostKeyChecking = false;
    boolean allowSudoCommands = false;
}

class UpdateSettings {
    String owner = "dinosonicgo3";
    String repo = "Mobile-LLM";
    String branch = "main";
    String configPath = "oracle-ai-rescue-config.json";
    String githubToken = "";
    String kaggleStartWorkflow = "start-kaggle-qwen.yml";
    int kaggleIdleMinutes = 15;
    int kaggleWeeklyQuotaHours = 30;
}

class ChatMessage {
    String role;
    String content;
    ChatMessage(String role, String content) { this.role = role; this.content = content; }
}

class ModelOption {
    String id;
    String displayName;
    String description;
    ModelOption(String id, String displayName, String description) {
        this.id = id == null ? "" : id;
        this.displayName = (displayName == null || displayName.length() == 0) ? this.id : displayName;
        this.description = description == null ? "" : description;
    }
}

class CommandResult {
    String command;
    int exitCode;
    String stdout;
    String stderr;
    boolean timedOut;
    CommandResult(String command, int exitCode, String stdout, String stderr, boolean timedOut) {
        this.command = command;
        this.exitCode = exitCode;
        this.stdout = stdout == null ? "" : stdout;
        this.stderr = stderr == null ? "" : stderr;
        this.timedOut = timedOut;
    }
    String asText() {
        StringBuilder sb = new StringBuilder();
        sb.append("$ ").append(command).append("\n");
        sb.append("exitCode=").append(exitCode).append(" timedOut=").append(timedOut).append("\n");
        if (!stdout.trim().isEmpty()) sb.append("--- stdout ---\n").append(stdout.trim()).append("\n");
        if (!stderr.trim().isEmpty()) sb.append("--- stderr ---\n").append(stderr.trim()).append("\n");
        return sb.toString();
    }
}

class RepairHistory {
    String timestamp;
    String title;
    String content;
    RepairHistory(String timestamp, String title, String content) {
        this.timestamp = timestamp;
        this.title = title;
        this.content = content;
    }
}

class RuntimeConfig {
    String version = "內建";
    String systemPrompt = RepairPrompts.DEFAULT_SYSTEM_PROMPT;
    List<String> extraDiagnosticCommands = new ArrayList<>();
    String kaggleBaseUrl = "";
    String kaggleApiKey = "";
    String kaggleDefaultModel = "qwen36-27b-q4-gguf";
    List<String> kaggleModels = new ArrayList<>();
    String kaggleState = "unknown";
    String kaggleLastHeartbeatUtc8 = "";
    String kaggleStartedAtUtc8 = "";
    String kaggleStoppedAtUtc8 = "";
    String kaggleMessage = "";
    int kaggleIdleShutdownMinutes = 15;
    int kaggleWeeklyQuotaHours = 30;
    int kaggleEstimatedUsedMinutes = 0;
    int kaggleEstimatedRemainingMinutes = 30 * 60;
    String kaggleWeekResetAtUtc8 = "";
}

class ReleaseInfo {
    String tag;
    String name;
    String body;
    String apkUrl;
    String pageUrl;
    ReleaseInfo(String tag, String name, String body, String apkUrl, String pageUrl) {
        this.tag = tag == null ? "" : tag;
        this.name = name == null ? "" : name;
        this.body = body == null ? "" : body;
        this.apkUrl = apkUrl == null ? "" : apkUrl;
        this.pageUrl = pageUrl == null ? "" : pageUrl;
    }
}
