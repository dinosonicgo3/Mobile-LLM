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
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

class LlmClient {
    private final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        // NVIDIA NIM 是公共平台；大型模型回覆可能較慢。模型回覆等待上限固定為 5 分鐘。
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(300, TimeUnit.SECONDS)
        // 保留額外連線與傳輸緩衝，避免剛好 300 秒邊界被整體呼叫提前中斷。
        .callTimeout(360, TimeUnit.SECONDS)
        .build();
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    String sendChat(ModelSettings settings, List<ChatMessage> messages) throws Exception {
        if (settings.baseUrl.trim().isEmpty()) throw new IllegalArgumentException("請先輸入 Base URL。");
        if (isPlaceholderBaseUrl(settings.baseUrl)) throw new IllegalArgumentException("目前 Base URL 仍是占位文字，還沒有同步到真正的 Kaggle 隧道網址。請先到 Kaggle 頁啟動 worker，再到設定頁按『自動同步 Kaggle 端點』。");
        if (settings.modelName.trim().isEmpty()) throw new IllegalArgumentException("請先選擇或輸入模型名稱。");
        if (!"kaggle".equals(settings.provider) && !"local_gemma".equals(settings.provider) && settings.apiKey.trim().isEmpty()) throw new IllegalArgumentException("請先輸入 API Key。");

        JSONArray arr = new JSONArray();
        for (ChatMessage m : messages) arr.put(new JSONObject().put("role", m.role).put("content", m.content));
        JSONObject payload = new JSONObject()
            .put("model", settings.modelName)
            .put("messages", arr)
            .put("temperature", settings.temperature)
            .put("max_tokens", 4096);

        // Google Gemini / Gemma over Gemini OpenAI-compatible API:
        // 使用官方 reasoning_effort 參數啟用 thinking，但不請求 thought summaries。
        if ("gemini".equals(settings.provider)) {
            String effort = settings.geminiReasoningEffort == null ? "high" : settings.geminiReasoningEffort.trim();
            if (effort.length() > 0 && !"default".equalsIgnoreCase(effort)) payload.put("reasoning_effort", effort);
        }

        try {
            return executeChatRequest(settings, payload);
        } catch (RuntimeException e) {
            // 某些 Google 模型或代理端點若尚未支援 reasoning_effort，避免整體聊天故障，退回不帶 thinking 參數重試。
            if ("gemini".equals(settings.provider) && payload.has("reasoning_effort") && e.getMessage() != null && e.getMessage().contains("HTTP 400")) {
                payload.remove("reasoning_effort");
                return executeChatRequest(settings, payload);
            }
            throw e;
        }
    }

    private String executeChatRequest(ModelSettings settings, JSONObject payload) throws Exception {
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
        String body;
        if (u.githubToken != null && u.githubToken.trim().length() > 0) {
            String apiPath = u.configPath.trim().replace(" ", "%20");
            String url = "https://api.github.com/repos/" + u.owner.trim() + "/" + u.repo.trim() + "/contents/" + apiPath + "?ref=" + URLEncoder.encode(u.branch.trim(), "UTF-8");
            Request request = new Request.Builder()
                .url(url)
                .addHeader("Accept", "application/vnd.github.raw+json")
                .addHeader("Authorization", "Bearer " + u.githubToken.trim())
                .addHeader("X-GitHub-Api-Version", "2022-11-28")
                .get()
                .build();
            try (Response response = client.newCall(request).execute()) {
                body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) throw new RuntimeException("取得私人倉庫設定失敗：HTTP " + response.code() + "\n" + body + "\n\n請確認 GitHub Token 對 dinosonicgo3/Mobile-LLM 有 Contents: Read 權限。");
            }
        } else {
            String url = "https://raw.githubusercontent.com/" + u.owner.trim() + "/" + u.repo.trim() + "/" + u.branch.trim() + "/" + u.configPath.trim();
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = client.newCall(request).execute()) {
                body = response.body() == null ? "" : response.body().string();
                if (!response.isSuccessful()) throw new RuntimeException("取得公開倉庫設定失敗：HTTP " + response.code() + "\n" + body + "\n\n如果這是私人倉庫，請在更新頁或 Kaggle 頁填入 GitHub Token。");
            }
        }
        JSONObject root = new JSONObject(body);
            RuntimeConfig cfg = new RuntimeConfig();
            cfg.version = root.optString("version", "repo-config");
            cfg.systemPrompt = root.optString("systemPrompt", RepairPrompts.DEFAULT_SYSTEM_PROMPT);
            JSONArray arr = root.optJSONArray("extraDiagnosticCommands");
            if (arr != null) for (int i = 0; i < arr.length(); i++) {
                String c = arr.optString(i);
                if (c.trim().length() > 0 && !RepairSafety.isDangerous(c)) cfg.extraDiagnosticCommands.add(c);
            }

            JSONObject kaggle = root.optJSONObject("kaggle");
            if (kaggle != null) {
                cfg.kaggleBaseUrl = kaggle.optString("baseUrl", kaggle.optString("apiBaseUrl", kaggle.optString("url", ""))).trim();
                cfg.kaggleApiKey = kaggle.optString("apiKey", "").trim();
                cfg.kaggleDefaultModel = kaggle.optString("defaultModel", kaggle.optString("model", cfg.kaggleDefaultModel)).trim();
                JSONArray models = kaggle.optJSONArray("models");
                if (models != null) for (int i = 0; i < models.length(); i++) {
                    String m = models.optString(i).trim();
                    if (m.length() > 0) cfg.kaggleModels.add(m);
                }
                cfg.kaggleState = kaggle.optString("state", kaggle.optString("status", cfg.kaggleState));
                cfg.kaggleLastHeartbeatUtc8 = kaggle.optString("lastHeartbeatUtc8", kaggle.optString("lastHeartbeat", ""));
                cfg.kaggleStartedAtUtc8 = kaggle.optString("startedAtUtc8", kaggle.optString("startedAt", ""));
                cfg.kaggleStoppedAtUtc8 = kaggle.optString("stoppedAtUtc8", kaggle.optString("stoppedAt", ""));
                cfg.kaggleMessage = kaggle.optString("message", "");
                cfg.kaggleIdleShutdownMinutes = kaggle.optInt("idleShutdownMinutes", kaggle.optInt("idleMinutes", cfg.kaggleIdleShutdownMinutes));
                cfg.kaggleWeeklyQuotaHours = kaggle.optInt("weeklyQuotaHours", cfg.kaggleWeeklyQuotaHours);
                cfg.kaggleEstimatedUsedMinutes = kaggle.optInt("estimatedUsedMinutes", cfg.kaggleEstimatedUsedMinutes);
                cfg.kaggleEstimatedRemainingMinutes = kaggle.optInt("estimatedRemainingMinutes", cfg.kaggleEstimatedRemainingMinutes);
                cfg.kaggleWeekResetAtUtc8 = kaggle.optString("weekResetAtUtc8", "");
            }
            String flatBase = root.optString("kaggleBaseUrl", root.optString("kaggleApiBaseUrl", "")).trim();
            if (cfg.kaggleBaseUrl.length() == 0 && flatBase.length() > 0) cfg.kaggleBaseUrl = flatBase;
            String flatKey = root.optString("kaggleApiKey", "").trim();
            if (cfg.kaggleApiKey.length() == 0 && flatKey.length() > 0) cfg.kaggleApiKey = flatKey;
            String flatModel = root.optString("kaggleDefaultModel", root.optString("kaggleModel", "")).trim();
            if (flatModel.length() > 0) cfg.kaggleDefaultModel = flatModel;
            JSONArray flatModels = root.optJSONArray("kaggleModels");
            if (flatModels != null) for (int i = 0; i < flatModels.length(); i++) {
                String m = flatModels.optString(i).trim();
                if (m.length() > 0) cfg.kaggleModels.add(m);
            }
            if (cfg.kaggleModels.isEmpty()) {
                cfg.kaggleModels.add("qwen36-27b-q4-gguf");
                cfg.kaggleModels.add("qwen3.6-27b-q4");
                cfg.kaggleModels.add("qwen3.6-27b");
            }
            if (cfg.kaggleDefaultModel == null || cfg.kaggleDefaultModel.trim().isEmpty()) cfg.kaggleDefaultModel = cfg.kaggleModels.get(0);
            return cfg;
        }


    void dispatchWorkflow(UpdateSettings u, String workflowFile, JSONObject inputs) throws Exception {
        if (u.owner.trim().isEmpty() || u.repo.trim().isEmpty()) throw new IllegalArgumentException("請先填 GitHub owner/repo。");
        if (u.githubToken.trim().isEmpty()) throw new IllegalArgumentException("請先在 Kaggle 頁填入 GitHub Fine-grained Token。權限只需要此 repo 的 Actions: Read and write。");
        String wf = (workflowFile == null || workflowFile.trim().isEmpty()) ? "start-kaggle-qwen.yml" : workflowFile.trim();
        JSONObject payload = new JSONObject().put("ref", u.branch.trim().isEmpty() ? "main" : u.branch.trim());
        if (inputs != null) payload.put("inputs", inputs);
        String url = "https://api.github.com/repos/" + u.owner.trim() + "/" + u.repo.trim() + "/actions/workflows/" + wf + "/dispatches";
        Request request = new Request.Builder()
            .url(url)
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("Authorization", "Bearer " + u.githubToken.trim())
            .post(RequestBody.create(payload.toString(), JSON))
            .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (response.code() != 204) throw new RuntimeException("觸發 GitHub Actions 失敗：HTTP " + response.code() + "\n" + body);
        }
    }

    String shutdownKaggle(ModelSettings settings) throws Exception {
        if (settings.baseUrl.trim().isEmpty()) throw new IllegalArgumentException("尚未同步 Kaggle Base URL，無法停止。");
        Request.Builder b = new Request.Builder()
            .url(trim(settings.baseUrl) + "/shutdown")
            .addHeader("Content-Type", "application/json")
            .post(RequestBody.create("{}", JSON));
        addAuth(b, settings.apiKey);
        try (Response response = client.newCall(b.build()).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new RuntimeException("呼叫 Kaggle 停止端點失敗：HTTP " + response.code() + "\n" + body);
            return body.length() == 0 ? "已送出停止要求" : body;
        }
    }

    List<ReleaseInfo> listReleases(UpdateSettings u) throws Exception {
        if (u.owner.trim().isEmpty() || u.repo.trim().isEmpty()) throw new IllegalArgumentException("請先填 GitHub owner/repo。");
        String url = "https://api.github.com/repos/" + u.owner.trim() + "/" + u.repo.trim() + "/releases?per_page=8";
        Request.Builder rb = new Request.Builder()
            .url(url)
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .get();
        if (u.githubToken != null && u.githubToken.trim().length() > 0) rb.addHeader("Authorization", "Bearer " + u.githubToken.trim());
        Request request = rb.build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new RuntimeException("查詢 GitHub Releases 失敗：HTTP " + response.code() + "\n" + body + "\n\n如果這是私人倉庫，請在更新頁或 Kaggle 頁填入 GitHub Token，且 Token 需要 Contents: Read 權限。");
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
                String apkApi = "";
                if (assets != null) for (int j = 0; j < assets.length(); j++) {
                    JSONObject a = assets.optJSONObject(j);
                    if (a == null) continue;
                    String name = a.optString("name").toLowerCase();
                    if (name.endsWith(".apk")) { apkApi = a.optString("url"); break; }
                }
                out.add(new ReleaseInfo(r.optString("tag_name"), r.optString("name"), r.optString("body"), apk, apkApi, r.optString("html_url")));
            }
            return out;
        }
    }

    WorkflowStatus latestWorkflowStatus(UpdateSettings u, String workflowFile) throws Exception {
        if (u.owner.trim().isEmpty() || u.repo.trim().isEmpty()) throw new IllegalArgumentException("請先填 GitHub owner/repo。");
        if (u.githubToken.trim().isEmpty()) throw new IllegalArgumentException("請先填 GitHub Token，需 Actions: Read 權限。");
        String wf = (workflowFile == null || workflowFile.trim().isEmpty()) ? "start-kaggle-qwen.yml" : workflowFile.trim();
        String url = "https://api.github.com/repos/" + u.owner.trim() + "/" + u.repo.trim() + "/actions/workflows/" + wf + "/runs?branch=" + URLEncoder.encode(u.branch.trim(), "UTF-8") + "&event=workflow_dispatch&per_page=1";
        Request request = new Request.Builder()
            .url(url)
            .addHeader("Accept", "application/vnd.github+json")
            .addHeader("Authorization", "Bearer " + u.githubToken.trim())
            .addHeader("X-GitHub-Api-Version", "2022-11-28")
            .get()
            .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new RuntimeException("查詢 GitHub Actions 狀態失敗：HTTP " + response.code() + "\n" + body + "\n\nToken 需要 Actions: Read 權限。");
            JSONObject root = new JSONObject(body);
            JSONArray arr = root.optJSONArray("workflow_runs");
            if (arr == null || arr.length() == 0) throw new RuntimeException("找不到此 workflow 的執行紀錄：" + wf);
            JSONObject r = arr.getJSONObject(0);
            WorkflowStatus st = new WorkflowStatus();
            st.status = r.optString("status", "");
            st.conclusion = r.optString("conclusion", "");
            st.name = r.optString("name", wf);
            st.htmlUrl = r.optString("html_url", "");
            st.createdAt = r.optString("created_at", "");
            st.updatedAt = r.optString("updated_at", "");
            st.runNumber = String.valueOf(r.optLong("run_number", 0));
            st.headBranch = r.optString("head_branch", "");
            return st;
        }
    }

    File downloadApk(UpdateSettings u, ReleaseInfo r, File dest) throws Exception {
        if (r == null) throw new IllegalArgumentException("沒有選擇 Release。");
        String url = r.apkApiUrl != null && r.apkApiUrl.trim().length() > 0 ? r.apkApiUrl.trim() : r.apkUrl.trim();
        if (url.length() == 0) throw new IllegalArgumentException("此 Release 沒有 APK 檔。");
        Request.Builder rb = new Request.Builder().url(url).get();
        if (r.apkApiUrl != null && r.apkApiUrl.trim().length() > 0) {
            rb.addHeader("Accept", "application/octet-stream");
            rb.addHeader("X-GitHub-Api-Version", "2022-11-28");
        }
        if (u.githubToken != null && u.githubToken.trim().length() > 0) rb.addHeader("Authorization", "Bearer " + u.githubToken.trim());
        try (Response response = client.newCall(rb.build()).execute()) {
            if (!response.isSuccessful()) {
                String body = response.body() == null ? "" : response.body().string();
                throw new RuntimeException("下載 APK 失敗：HTTP " + response.code() + "\n" + body);
            }
            File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();
            if (response.body() == null) throw new RuntimeException("下載 APK 失敗：空回應");
            try (InputStream in = response.body().byteStream(); FileOutputStream out = new FileOutputStream(dest)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) out.write(buf, 0, n);
            }
            if (dest.length() < 1024 * 1024) throw new RuntimeException("下載的檔案太小，可能不是 APK：" + dest.length() + " bytes");
            return dest;
        }
    }

    private static void addAuth(Request.Builder b, String key) {
        if (key != null && key.trim().length() > 0) b.addHeader("Authorization", "Bearer " + key.trim());
    }

    private static boolean isPlaceholderBaseUrl(String s) {
        if (s == null) return true;
        String x = s.trim();
        return x.length() == 0 || x.contains("你的-kaggle") || x.contains("隧道網址") || x.contains("example.com") || x.contains("xn---kaggle--");
    }

    private static String trim(String url) { return url == null ? "" : url.trim().replaceAll("/+$", ""); }

    private static String parseChat(String body) throws Exception {
        JSONObject root = new JSONObject(body);
        JSONArray choices = root.optJSONArray("choices");
        if (choices == null || choices.length() == 0) throw new RuntimeException("API 回應沒有 choices：" + body);
        JSONObject first = choices.getJSONObject(0);
        JSONObject msg = first.optJSONObject("message");
        if (msg != null && msg.optString("content").trim().length() > 0) return stripThinkingText(msg.optString("content"));
        if (first.optString("text").trim().length() > 0) return stripThinkingText(first.optString("text"));
        return stripThinkingText(body);
    }

    private static String stripThinkingText(String raw) {
        if (raw == null) return "";
        String s = raw;
        // 隱藏常見模型直接吐出的思考區塊，避免顯示到聊天欄。
        s = s.replaceAll("(?is)<think>.*?</think>", "");
        s = s.replaceAll("(?is)<thinking>.*?</thinking>", "");
        s = s.replaceAll("(?is)<thought>.*?</thought>", "");
        s = s.replaceAll("(?is)```\\s*(thinking|thought|thoughts|reasoning|思考)[^\\n]*\\n.*?```", "");
        s = s.replaceAll("(?is)^\\s*(思考過程|思考|推理過程|reasoning|thinking)\\s*[:：]\\s*\\n+.*?\\n+(答案|回覆|final|answer)\\s*[:：]\\s*", "");
        return s.trim();
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
