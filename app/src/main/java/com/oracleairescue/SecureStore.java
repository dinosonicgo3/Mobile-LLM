package com.oracleairescue;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

class SecureStore {
    private final SharedPreferences prefs;
    private final String keyAlias = "oracle_ai_rescue_aes_key_v2";

    SecureStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("oracle_ai_rescue_secure_java", Context.MODE_PRIVATE);
    }

    void saveModel(ModelSettings m) {
        String p = safeProvider(m.provider);
        put("model.provider", p);

        // v1.4.2 起，API Key / Base URL / 模型名稱都依平台分開保存。
        // 不再寫入舊版共用鍵 model.apiKey，避免 Google KEY 在切換到 NVIDIA NIM 時被誤顯示。
        put("model." + p + ".apiKey", m.apiKey);
        put("model." + p + ".baseUrl", m.baseUrl);
        put("model." + p + ".modelName", m.modelName);
        put("model." + p + ".temperature", String.valueOf(m.temperature));
        put("model." + p + ".maxContextCharacters", String.valueOf(m.maxContextCharacters));
        put("model." + p + ".geminiReasoningEffort", m.geminiReasoningEffort);
        put("model." + p + ".hideThoughts", String.valueOf(m.hideThoughts));
        put("model.renderMarkdown", String.valueOf(m.renderMarkdown));
    }

    ModelSettings loadModel() {
        return loadModelFor(get("model.provider", "gemini"));
    }

    ModelSettings loadModelFor(String provider) {
        String p = safeProvider(provider);
        ModelSettings d = defaultModelFor(p);
        ModelSettings m = new ModelSettings();
        m.provider = p;

        // 只允許 Gemini 從舊版共用鍵遷移一次；其他平台不可拿到 Google 的 KEY。
        String legacyApiKey = "gemini".equals(p) ? get("model.apiKey", d.apiKey) : d.apiKey;
        String legacyBaseUrl = "gemini".equals(p) ? get("model.baseUrl", d.baseUrl) : d.baseUrl;
        String legacyModelName = "gemini".equals(p) ? get("model.modelName", d.modelName) : d.modelName;

        m.apiKey = get("model." + p + ".apiKey", legacyApiKey);
        m.baseUrl = get("model." + p + ".baseUrl", legacyBaseUrl);
        m.modelName = get("model." + p + ".modelName", legacyModelName);
        m.temperature = parseDouble(get("model." + p + ".temperature", "0.2"), 0.2);
        m.maxContextCharacters = Math.max(8000, Math.min(200000, parseInt(get("model." + p + ".maxContextCharacters", "60000"), 60000)));
        m.geminiReasoningEffort = get("model." + p + ".geminiReasoningEffort", "high");
        m.hideThoughts = Boolean.parseBoolean(get("model." + p + ".hideThoughts", "true"));
        m.renderMarkdown = Boolean.parseBoolean(get("model.renderMarkdown", "true"));
        return m;
    }

    void saveServer(ServerSettings s) {
        put("server.name", s.name);
        put("server.host", s.host);
        put("server.port", String.valueOf(s.port));
        put("server.username", s.username);
        put("server.password", s.password);
        put("server.privateKey", s.privateKey);
        put("server.privateKeyPassphrase", s.privateKeyPassphrase);
        put("server.projectPath", s.projectPath);
        put("server.serviceName", s.serviceName);
        put("server.dockerContainer", s.dockerContainer);
        put("server.strictHostKeyChecking", String.valueOf(s.strictHostKeyChecking));
        put("server.allowSudoCommands", String.valueOf(s.allowSudoCommands));
    }

    ServerSettings loadServer() {
        ServerSettings s = new ServerSettings();
        s.name = get("server.name", "我的 Oracle AI 助理");
        s.host = get("server.host", "");
        s.port = parseInt(get("server.port", "22"), 22);
        s.username = get("server.username", "ubuntu");
        s.password = get("server.password", "");
        s.privateKey = get("server.privateKey", "");
        s.privateKeyPassphrase = get("server.privateKeyPassphrase", "");
        s.projectPath = get("server.projectPath", "");
        s.serviceName = get("server.serviceName", "");
        s.dockerContainer = get("server.dockerContainer", "");
        s.strictHostKeyChecking = Boolean.parseBoolean(get("server.strictHostKeyChecking", "false"));
        s.allowSudoCommands = Boolean.parseBoolean(get("server.allowSudoCommands", "false"));
        return s;
    }

    void saveUpdateSettings(UpdateSettings u) {
        put("update.owner", u.owner);
        put("update.repo", u.repo);
        put("update.branch", u.branch);
        put("update.configPath", u.configPath);
        put("update.githubToken", u.githubToken);
        put("update.kaggleStartWorkflow", u.kaggleStartWorkflow);
        put("update.kaggleIdleMinutes", String.valueOf(u.kaggleIdleMinutes));
        put("update.kaggleWeeklyQuotaHours", String.valueOf(u.kaggleWeeklyQuotaHours));
    }

    UpdateSettings loadUpdateSettings() {
        UpdateSettings u = new UpdateSettings();
        u.owner = get("update.owner", "dinosonicgo3");
        u.repo = get("update.repo", "Mobile-LLM");
        u.branch = get("update.branch", "main");
        u.configPath = get("update.configPath", "oracle-ai-rescue-config.json");
        u.githubToken = get("update.githubToken", "");
        u.kaggleStartWorkflow = get("update.kaggleStartWorkflow", "start-kaggle-qwen.yml");
        u.kaggleIdleMinutes = Math.max(5, Math.min(120, parseInt(get("update.kaggleIdleMinutes", "15"), 15)));
        u.kaggleWeeklyQuotaHours = Math.max(1, Math.min(80, parseInt(get("update.kaggleWeeklyQuotaHours", "30"), 30)));
        if (u.owner.trim().isEmpty()) u.owner = "dinosonicgo3";
        if (u.repo.trim().isEmpty()) u.repo = "Mobile-LLM";
        if (u.branch.trim().isEmpty()) u.branch = "main";
        if (u.configPath.trim().isEmpty()) u.configPath = "oracle-ai-rescue-config.json";
        if (u.kaggleStartWorkflow.trim().isEmpty()) u.kaggleStartWorkflow = "start-kaggle-qwen.yml";
        return u;
    }

    void saveCatalog(String provider, List<ModelOption> models) { put("catalog." + provider, encodeModels(models, 600)); }
    List<ModelOption> loadCatalog(String provider) { return decodeModels(get("catalog." + provider, "")); }
    void saveFavorites(String provider, List<ModelOption> models) { put("favorites." + provider, encodeModels(models, 150)); }
    List<ModelOption> loadFavorites(String provider) {
        List<ModelOption> saved = decodeModels(get("favorites." + provider, ""));
        if (!saved.isEmpty()) return saved;
        return defaultFavorites(safeProvider(provider));
    }

    void saveChat(List<ChatMessage> messages) {
        JSONArray arr = new JSONArray();
        int start = Math.max(0, messages.size() - 100);
        for (int i = start; i < messages.size(); i++) {
            ChatMessage m = messages.get(i);
            if (!isAllowedRole(m.role) || m.content == null || m.content.trim().isEmpty()) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("role", m.role);
                o.put("content", limit(m.content, 60000));
                arr.put(o);
            } catch (Exception ignored) {}
        }
        put("chat.messages", limit(arr.toString(), 300000));
    }

    List<ChatMessage> loadChat() {
        List<ChatMessage> list = new ArrayList<>();
        String raw = get("chat.messages", "");
        if (raw.trim().isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String role = o.optString("role");
                String content = o.optString("content");
                if (isAllowedRole(role) && content.length() > 0) list.add(new ChatMessage(role, content));
            }
        } catch (Exception ignored) {}
        return list;
    }

    void clearChat() { put("chat.messages", ""); }

    void appendHistory(RepairHistory h) {
        String old = get("history", "");
        String item = sanitize(h.timestamp) + "\u001F" + sanitize(h.title) + "\u001F" + sanitize(h.content);
        put("history", limit(item + "\u001E" + old, 160000));
    }

    List<RepairHistory> loadHistory() {
        List<RepairHistory> list = new ArrayList<>();
        String raw = get("history", "");
        if (raw.trim().isEmpty()) return list;
        String[] rows = raw.split("\u001E");
        for (String row : rows) {
            if (row.trim().isEmpty()) continue;
            String[] p = row.split("\u001F", 3);
            if (p.length == 3) list.add(new RepairHistory(p[0], p[1], p[2]));
        }
        return list;
    }

    void clearHistory() { put("history", ""); }

    String loadVerifier31BModelName() {
        return get("verify31b.modelName", "gemma-4-31b-it");
    }

    void saveVerifier31BModelName(String modelName) {
        String v = modelName == null ? "" : modelName.trim();
        if (v.isEmpty()) v = "gemma-4-31b-it";
        put("verify31b.modelName", v);
    }

    String loadVerifierNim31BModelName() {
        return get("verify31b.nimModelName", "google/gemma-4-31b-it");
    }

    void saveVerifierNim31BModelName(String modelName) {
        String v = modelName == null ? "" : modelName.trim();
        if (v.isEmpty()) v = "google/gemma-4-31b-it";
        put("verify31b.nimModelName", v);
    }

    void appendAppLog(String line) {
        String old = get("app.logs", "");
        String item = sanitize(line);
        put("app.logs", limit(item + "\n" + old, 240000));
    }

    String loadAppLogs() { return get("app.logs", ""); }

    void clearAppLogs() { put("app.logs", ""); }

    void saveRuntimeConfig(RuntimeConfig cfg) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", cfg.version);
            root.put("systemPrompt", cfg.systemPrompt);
            JSONArray arr = new JSONArray();
            for (String c : cfg.extraDiagnosticCommands) arr.put(c);
            root.put("extraDiagnosticCommands", arr);
            JSONObject kaggle = new JSONObject();
            kaggle.put("baseUrl", cfg.kaggleBaseUrl);
            kaggle.put("apiKey", cfg.kaggleApiKey);
            kaggle.put("defaultModel", cfg.kaggleDefaultModel);
            JSONArray km = new JSONArray();
            for (String m : cfg.kaggleModels) km.put(m);
            kaggle.put("models", km);
            kaggle.put("state", cfg.kaggleState);
            kaggle.put("lastHeartbeatUtc8", cfg.kaggleLastHeartbeatUtc8);
            kaggle.put("startedAtUtc8", cfg.kaggleStartedAtUtc8);
            kaggle.put("stoppedAtUtc8", cfg.kaggleStoppedAtUtc8);
            kaggle.put("message", cfg.kaggleMessage);
            kaggle.put("idleShutdownMinutes", cfg.kaggleIdleShutdownMinutes);
            kaggle.put("weeklyQuotaHours", cfg.kaggleWeeklyQuotaHours);
            kaggle.put("estimatedUsedMinutes", cfg.kaggleEstimatedUsedMinutes);
            kaggle.put("estimatedRemainingMinutes", cfg.kaggleEstimatedRemainingMinutes);
            kaggle.put("weekResetAtUtc8", cfg.kaggleWeekResetAtUtc8);
            root.put("kaggle", kaggle);
            put("runtime.config", root.toString());
        } catch (Exception ignored) {}
    }

    RuntimeConfig loadRuntimeConfig() {
        RuntimeConfig cfg = new RuntimeConfig();
        String raw = get("runtime.config", "");
        if (raw.trim().isEmpty()) return cfg;
        try {
            JSONObject root = new JSONObject(raw);
            cfg.version = root.optString("version", "自訂");
            cfg.systemPrompt = root.optString("systemPrompt", RepairPrompts.DEFAULT_SYSTEM_PROMPT);
            JSONArray arr = root.optJSONArray("extraDiagnosticCommands");
            if (arr != null) for (int i = 0; i < arr.length(); i++) cfg.extraDiagnosticCommands.add(arr.optString(i));
            JSONObject kaggle = root.optJSONObject("kaggle");
            if (kaggle != null) {
                cfg.kaggleBaseUrl = kaggle.optString("baseUrl", "");
                cfg.kaggleApiKey = kaggle.optString("apiKey", "");
                cfg.kaggleDefaultModel = kaggle.optString("defaultModel", cfg.kaggleDefaultModel);
                JSONArray km = kaggle.optJSONArray("models");
                if (km != null) for (int i = 0; i < km.length(); i++) {
                    String m = km.optString(i);
                    if (m.trim().length() > 0) cfg.kaggleModels.add(m);
                }
                cfg.kaggleState = kaggle.optString("state", cfg.kaggleState);
                cfg.kaggleLastHeartbeatUtc8 = kaggle.optString("lastHeartbeatUtc8", "");
                cfg.kaggleStartedAtUtc8 = kaggle.optString("startedAtUtc8", "");
                cfg.kaggleStoppedAtUtc8 = kaggle.optString("stoppedAtUtc8", "");
                cfg.kaggleMessage = kaggle.optString("message", "");
                cfg.kaggleIdleShutdownMinutes = kaggle.optInt("idleShutdownMinutes", cfg.kaggleIdleShutdownMinutes);
                cfg.kaggleWeeklyQuotaHours = kaggle.optInt("weeklyQuotaHours", cfg.kaggleWeeklyQuotaHours);
                cfg.kaggleEstimatedUsedMinutes = kaggle.optInt("estimatedUsedMinutes", cfg.kaggleEstimatedUsedMinutes);
                cfg.kaggleEstimatedRemainingMinutes = kaggle.optInt("estimatedRemainingMinutes", cfg.kaggleEstimatedRemainingMinutes);
                cfg.kaggleWeekResetAtUtc8 = kaggle.optString("weekResetAtUtc8", "");
            }
        } catch (Exception ignored) {}
        return cfg;
    }

    void backupRuntimeConfig() { put("runtime.config.previous", get("runtime.config", "")); }
    boolean rollbackRuntimeConfig() {
        String prev = get("runtime.config.previous", "");
        if (prev.trim().isEmpty()) return false;
        put("runtime.config", prev);
        return true;
    }

    void markCrash(String text) { put("last.crash", text == null ? "" : text); }
    String consumeCrash() {
        String value = get("last.crash", "");
        put("last.crash", "");
        return value;
    }

    private String encodeModels(List<ModelOption> models, int max) {
        JSONArray arr = new JSONArray();
        int count = 0;
        for (ModelOption m : models) {
            if (m == null || m.id == null || m.id.trim().isEmpty()) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("id", m.id);
                o.put("displayName", m.displayName);
                o.put("description", m.description);
                arr.put(o);
            } catch (Exception ignored) {}
            count++;
            if (count >= max) break;
        }
        return arr.toString();
    }

    private List<ModelOption> decodeModels(String raw) {
        List<ModelOption> list = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) return list;
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id");
                if (id.trim().isEmpty()) continue;
                list.add(new ModelOption(id, o.optString("displayName", id), o.optString("description", "")));
            }
        } catch (Exception ignored) {}
        return list;
    }

    private void put(String key, String value) {
        try { prefs.edit().putString(key, encrypt(value == null ? "" : value)).apply(); }
        catch (Exception e) { prefs.edit().putString(key, value == null ? "" : value).apply(); }
    }

    private String get(String key, String def) {
        String value = prefs.getString(key, null);
        if (value == null) return def;
        try { return decrypt(value); }
        catch (Exception e) { return value == null ? def : value; }
    }

    private String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secretKey());
        byte[] data = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP);
    }

    private String decrypt(String value) throws Exception {
        String[] parts = value.split(":", 2);
        if (parts.length != 2) return value;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }

    private SecretKey secretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        KeyStore.Entry entry = ks.getEntry(keyAlias, null);
        if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build();
        gen.init(spec);
        return gen.generateKey();
    }


    private static String safeProvider(String provider) {
        if ("nim".equals(provider) || "kaggle".equals(provider) || "local_gemma".equals(provider) || "custom".equals(provider)) return provider;
        return "gemini";
    }

    private static ModelSettings defaultModelFor(String provider) {
        ModelSettings m = new ModelSettings();
        m.provider = provider;
        if ("nim".equals(provider)) { m.baseUrl = "https://integrate.api.nvidia.com/v1"; m.modelName = "meta/llama-3.1-70b-instruct"; }
        else if ("kaggle".equals(provider)) { m.baseUrl = "https://你的-kaggle-隧道網址/v1"; m.modelName = "qwen36-27b-q4-gguf"; }
        else if ("local_gemma".equals(provider)) { m.baseUrl = ""; m.modelName = "gemma-4-E2B-it.litertlm"; }
        else if ("custom".equals(provider)) { m.baseUrl = "https://example.com/v1"; m.modelName = "your-model-name"; }
        else { m.baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai"; m.modelName = "gemini-2.5-flash"; }
        return m;
    }


    private static List<ModelOption> defaultFavorites(String provider) {
        List<ModelOption> out = new ArrayList<>();
        if ("kaggle".equals(provider)) {
            out.add(new ModelOption("qwen36-27b-q4-gguf", "Qwen3.6 27B Q4 GGUF", "Kaggle / llama.cpp OpenAI 相容端點；對應 dinosonicgo/qwen36-27b-q4-gguf-cache。"));
            out.add(new ModelOption("qwen3.6-27b-q4", "qwen3.6-27b-q4", "備用短名稱，供 llama.cpp alias 或自訂端點使用。"));
            out.add(new ModelOption("qwen3.6-27b", "qwen3.6-27b", "備用短名稱，供自訂 OpenAI 相容服務使用。"));
        } else if ("nim".equals(provider)) {
            out.add(new ModelOption("google/gemma-4-31b-it", "Gemma 4 31B IT（官方 NIM ID）", "NVIDIA 官方 API Reference / Build NVIDIA 確認的 Gemma 4 31B 模型 ID；用於後段驗證備援。"));
            out.add(new ModelOption("meta/llama-3.1-70b-instruct", "Llama 3.1 70B Instruct", "NVIDIA NIM 常用聊天模型。"));
            out.add(new ModelOption("qwen/qwen2.5-coder-32b-instruct", "Qwen2.5 Coder 32B", "NVIDIA NIM 常用程式模型，實際可用性以 /models 回傳為準。"));
        } else if ("local_gemma".equals(provider)) {
            out.add(new ModelOption("gemma-4-E2B-it.litertlm", "本機 Gemma 4 E2B 加速", "手機本地 LiteRT-LM 模型，支援 speculative decoding / MTP。"));
            out.add(new ModelOption("gemma-4-E4B-it.litertlm", "本機 Gemma 4 E4B 加速", "手機本地 LiteRT-LM 模型，支援 speculative decoding / MTP，高階手機建議。"));
        } else if ("gemini".equals(provider)) {
            out.add(new ModelOption("gemini-2.5-flash", "Gemini 2.5 Flash", "Google API 常用快速模型。"));
            out.add(new ModelOption("gemini-2.5-pro", "Gemini 2.5 Pro", "Google API 常用高能力模型。"));
        }
        return out;
    }

    private static boolean isAllowedRole(String role) { return "system".equals(role) || "user".equals(role) || "assistant".equals(role); }
    private static int parseInt(String v, int def) { try { return Integer.parseInt(v.trim()); } catch (Exception e) { return def; } }
    private static double parseDouble(String v, double def) { try { return Double.parseDouble(v.trim()); } catch (Exception e) { return def; } }
    private static String sanitize(String s) { return (s == null ? "" : s).replace('\u001E', ' ').replace('\u001F', ' '); }
    private static String limit(String s, int max) { return s == null ? "" : (s.length() > max ? s.substring(0, max) : s); }
}
