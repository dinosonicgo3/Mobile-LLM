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
        put("model." + p + ".apiKey", m.apiKey);
        put("model." + p + ".baseUrl", m.baseUrl);
        put("model." + p + ".modelName", m.modelName);
        put("model." + p + ".temperature", String.valueOf(m.temperature));
        put("model." + p + ".maxContextCharacters", String.valueOf(m.maxContextCharacters));
        // 舊版相容鍵
        put("model.apiKey", m.apiKey);
        put("model.baseUrl", m.baseUrl);
        put("model.modelName", m.modelName);
        put("model.temperature", String.valueOf(m.temperature));
        put("model.maxContextCharacters", String.valueOf(m.maxContextCharacters));
    }

    ModelSettings loadModel() {
        return loadModelFor(get("model.provider", "gemini"));
    }

    ModelSettings loadModelFor(String provider) {
        String p = safeProvider(provider);
        ModelSettings d = defaultModelFor(p);
        ModelSettings m = new ModelSettings();
        m.provider = p;
        m.apiKey = get("model." + p + ".apiKey", get("model.apiKey", d.apiKey));
        m.baseUrl = get("model." + p + ".baseUrl", d.baseUrl);
        m.modelName = get("model." + p + ".modelName", d.modelName);
        m.temperature = parseDouble(get("model." + p + ".temperature", get("model.temperature", "0.2")), 0.2);
        m.maxContextCharacters = Math.max(8000, Math.min(200000, parseInt(get("model." + p + ".maxContextCharacters", get("model.maxContextCharacters", "60000")), 60000)));
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
    }

    UpdateSettings loadUpdateSettings() {
        UpdateSettings u = new UpdateSettings();
        u.owner = get("update.owner", "");
        u.repo = get("update.repo", "");
        u.branch = get("update.branch", "main");
        u.configPath = get("update.configPath", "oracle-ai-rescue-config.json");
        return u;
    }

    void saveCatalog(String provider, List<ModelOption> models) { put("catalog." + provider, encodeModels(models, 600)); }
    List<ModelOption> loadCatalog(String provider) { return decodeModels(get("catalog." + provider, "")); }
    void saveFavorites(String provider, List<ModelOption> models) { put("favorites." + provider, encodeModels(models, 150)); }
    List<ModelOption> loadFavorites(String provider) { return decodeModels(get("favorites." + provider, "")); }

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

    void saveRuntimeConfig(RuntimeConfig cfg) {
        try {
            JSONObject root = new JSONObject();
            root.put("version", cfg.version);
            root.put("systemPrompt", cfg.systemPrompt);
            JSONArray arr = new JSONArray();
            for (String c : cfg.extraDiagnosticCommands) arr.put(c);
            root.put("extraDiagnosticCommands", arr);
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
        if ("nim".equals(provider) || "kaggle".equals(provider) || "custom".equals(provider)) return provider;
        return "gemini";
    }

    private static ModelSettings defaultModelFor(String provider) {
        ModelSettings m = new ModelSettings();
        m.provider = provider;
        if ("nim".equals(provider)) { m.baseUrl = "https://integrate.api.nvidia.com/v1"; m.modelName = "meta/llama-3.1-70b-instruct"; }
        else if ("kaggle".equals(provider)) { m.baseUrl = "https://你的-kaggle-隧道網址/v1"; m.modelName = "Qwen/Qwen2.5-7B-Instruct"; }
        else if ("custom".equals(provider)) { m.baseUrl = "https://example.com/v1"; m.modelName = "your-model-name"; }
        else { m.baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai"; m.modelName = "gemini-2.5-flash"; }
        return m;
    }

    private static boolean isAllowedRole(String role) { return "system".equals(role) || "user".equals(role) || "assistant".equals(role); }
    private static int parseInt(String v, int def) { try { return Integer.parseInt(v.trim()); } catch (Exception e) { return def; } }
    private static double parseDouble(String v, double def) { try { return Double.parseDouble(v.trim()); } catch (Exception e) { return def; } }
    private static String sanitize(String s) { return (s == null ? "" : s).replace('\u001E', ' ').replace('\u001F', ' '); }
    private static String limit(String s, int max) { return s == null ? "" : (s.length() > max ? s.substring(0, max) : s); }
}
