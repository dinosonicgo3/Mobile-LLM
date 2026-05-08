package com.oracleairescue;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

class LlmClient {
    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(90, TimeUnit.SECONDS)
        .build();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    String sendChat(ModelSettings settings, List<ChatMessage> messages) throws Exception {
        if (settings.baseUrl.trim().isEmpty()) throw new IllegalArgumentException("請先輸入 Base URL。");
        if (settings.modelName.trim().isEmpty()) throw new IllegalArgumentException("請先選擇或輸入模型名稱。");
        if (!"kaggle".equals(settings.provider) && settings.apiKey.trim().isEmpty()) throw new IllegalArgumentException("請先輸入 API Key。");

        JSONArray arr = new JSONArray();
        for (ChatMessage m : messages) arr.put(new JSONObject().put("role", m.role).put("content", m.content));
        JSONObject payload = new JSONObject()
            .put("model", settings.modelName)
            .put("messages", arr)
            .put("temperature", settings.temperature)
            .put("max_tokens", 4096);

        Request.Builder b = new Request.Builder()
            .url(trim(settings.baseUrl) + "/chat/completions")
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create(payload.toString(), JSON));
        addAuth(b, settings.apiKey);
        try (Response response = client.newCall(b.build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new RuntimeException("LLM API 呼叫失敗：HTTP " + response.code() + "\n" + body);
            return parseChat(body);
        }
    }

    List<ModelOption> listModels(ModelSettings settings) throws Exception {
        if (settings.baseUrl.trim().isEmpty()) throw new IllegalArgumentException("請先輸入 Base URL。");
        List<ModelOption> result;
        if ("gemini".equals(settings.provider)) result = listGemini(settings);
        else result = listOpenAiCompatible(settings);
        result.sort(Comparator.comparing(o -> o.id));
        return distinct(result);
    }

    private List<ModelOption> listGemini(ModelSettings settings) throws Exception {
        if (settings.apiKey.trim().isEmpty()) throw new IllegalArgumentException("請先輸入 Google API Key。");
        String base = trim(settings.baseUrl);
        if (base.endsWith("/openai")) base = base.substring(0, base.length() - "/openai".length());
        String url = base + "/models?key=" + URLEncoder.encode(settings.apiKey, "UTF-8");
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new RuntimeException("取得 Gemini 模型清單失敗：HTTP " + response.code() + "\n" + body);
            JSONObject root = new JSONObject(body);
            JSONArray arr = root.optJSONArray("models");
            List<ModelOption> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("name").replace("models/", "");
                if (id.trim().isEmpty()) continue;
                JSONArray methods = o.optJSONArray("supportedGenerationMethods");
                boolean ok = methods == null;
                if (methods != null) for (int j = 0; j < methods.length(); j++) if ("generateContent".equalsIgnoreCase(methods.optString(j))) ok = true;
                if (!ok) continue;
                String desc = o.optString("description");
                int in = o.optInt("inputTokenLimit", 0);
                int outLimit = o.optInt("outputTokenLimit", 0);
                if (in > 0) desc += (desc.length() > 0 ? "｜" : "") + "輸入上限：" + in;
                if (outLimit > 0) desc += (desc.length() > 0 ? "｜" : "") + "輸出上限：" + outLimit;
                out.add(new ModelOption(id, o.optString("displayName", id), desc));
            }
            return out;
        }
    }

    private List<ModelOption> listOpenAiCompatible(ModelSettings settings) throws Exception {
        Request.Builder b = new Request.Builder().url(trim(settings.baseUrl) + "/models").get().addHeader("Content-Type", "application/json");
        addAuth(b, settings.apiKey);
        try (Response response = client.newCall(b.build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new RuntimeException("取得模型清單失敗：HTTP " + response.code() + "\n" + body);
            JSONObject root = new JSONObject(body);
            JSONArray arr = root.optJSONArray("data");
            if (arr == null) arr = root.optJSONArray("models");
            List<ModelOption> out = new ArrayList<>();
            if (arr == null) return out;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String id = o.optString("id", o.optString("name"));
                if (id.trim().isEmpty()) continue;
                String display = o.optString("display_name", o.optString("displayName", id));
                String desc = o.optString("description", o.optString("owned_by", ""));
                out.add(new ModelOption(id, display, desc));
            }
            return out;
        }
    }

    RuntimeConfig fetchRuntimeConfig(UpdateSettings u) throws Exception {
        if (u.owner.trim().isEmpty() || u.repo.trim().isEmpty()) throw new IllegalArgumentException("請先填 GitHub owner/repo。");
        String url = "https://raw.githubusercontent.com/" + u.owner.trim() + "/" + u.repo.trim() + "/" + u.branch.trim() + "/" + u.configPath.trim();
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new RuntimeException("取得倉庫設定失敗：HTTP " + response.code() + "\n" + body);
            JSONObject root = new JSONObject(body);
            RuntimeConfig cfg = new RuntimeConfig();
            cfg.version = root.optString("version", "repo-config");
            cfg.systemPrompt = root.optString("systemPrompt", RepairPrompts.DEFAULT_SYSTEM_PROMPT);
            JSONArray arr = root.optJSONArray("extraDiagnosticCommands");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                String c = arr.optString(i);
                if (c.trim().length() > 0 && !RepairSafety.isDangerous(c)) cfg.extraDiagnosticCommands.add(c);
            }
            return cfg;
        }
    }

    List<ReleaseInfo> listReleases(UpdateSettings u) throws Exception {
        if (u.owner.trim().isEmpty() || u.repo.trim().isEmpty()) throw new IllegalArgumentException("請先填 GitHub owner/repo。");
        String url = "https://api.github.com/repos/" + u.owner.trim() + "/" + u.repo.trim() + "/releases?per_page=8";
        Request request = new Request.Builder().url(url).addHeader("Accept", "application/vnd.github+json").get().build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new RuntimeException("查詢 GitHub Releases 失敗：HTTP " + response.code() + "\n" + body);
            JSONArray arr = new JSONArray(body);
            List<ReleaseInfo> out = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject r = arr.optJSONObject(i);
                if (r == null) continue;
                String apk = "";
                JSONArray assets = r.optJSONArray("assets");
                if (assets != null) for (int j = 0; j < assets.length(); j++) {
                    JSONObject a = assets.optJSONObject(j);
                    if (a == null) continue;
                    String name = a.optString("name").toLowerCase();
                    String dl = a.optString("browser_download_url");
                    if ((name.endsWith(".apk") || name.endsWith(".zip")) && dl.length() > 0) { apk = dl; break; }
                }
                out.add(new ReleaseInfo(r.optString("tag_name"), r.optString("name"), r.optString("body"), apk, r.optString("html_url")));
            }
            return out;
        }
    }

    private static void addAuth(Request.Builder b, String key) {
        if (key != null && key.trim().length() > 0) b.addHeader("Authorization", "Bearer " + key.trim());
    }

    private static String trim(String url) { return url == null ? "" : url.trim().replaceAll("/+$", ""); }

    private static String parseChat(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new RuntimeException("API 回應沒有 choices：" + body);
        JSONObject first = choices.getJSONObject(0);
        JSONObject msg = first.optJSONObject("message");
        if (msg != null && msg.optString("content").trim().length() > 0) return msg.optString("content");
        if (first.optString("text").trim().length() > 0) return first.optString("text");
        return body;
    }

    private static List<ModelOption> distinct(List<ModelOption> src) {
        List<ModelOption> out = new ArrayList<>();
        for (ModelOption m : src) {
            boolean exists = false;
            for (ModelOption o : out) if (o.id.equals(m.id)) { exists = true; break; }
            if (!exists) out.add(m);
        }
        return out;
    }
}
