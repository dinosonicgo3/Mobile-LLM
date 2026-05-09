package com.oracleairescue;

import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import android.app.Activity;
import androidx.core.content.FileProvider;
import io.noties.markwon.Markwon;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private SecureStore store;
    private final LlmClient llm = new LlmClient();
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout root;
    private TextView status;

    private ModelSettings modelSettings;
    private ServerSettings serverSettings;
    private UpdateSettings updateSettings;
    private RuntimeConfig runtimeConfig;
    private final List<ChatMessage> chatMessages = new ArrayList<>();

    private Spinner chatModelSpinner;
    private TextView chatLogView;
    private ScrollView chatScrollView;
    private Markwon markwon;
    private EditText chatInput;
    private static final int REQUEST_IMPORT_SSH_KEY = 8801;
    private EditText sshPrivateKeyEditor;
    private TextView sshKeyStatusLabel;

    private final String[] providerLabels = new String[] {"Google Gemini", "NVIDIA NIM", "Kaggle Qwen / OpenAI 相容", "本機 Gemma 4", "自訂 OpenAI 相容"};
    private final String[] providerCodes = new String[] {"gemini", "nim", "kaggle", "local_gemma", "custom"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SecureStore(this);
        markwon = Markwon.create(this);
        modelSettings = store.loadModel();
        serverSettings = store.loadServer();
        updateSettings = store.loadUpdateSettings();
        runtimeConfig = store.loadRuntimeConfig();
        chatMessages.addAll(store.loadChat());
        showShell("聊天");
        showChatPage();
        appLog("APP 啟動 v1.5.5｜目前平台：" + providerTitle(modelSettings.provider) + "｜模型：" + modelSettings.modelName);
        autoSyncKaggleEndpointQuietly();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_IMPORT_SSH_KEY) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            toast("已取消匯入私鑰");
            return;
        }
        Uri uri = data.getData();
        try {
            try {
                final int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                if (flags != 0) getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception ignored) {}
            String keyText = normalizeImportedPrivateKey(readTextFromUri(uri));
            if (keyText.trim().isEmpty()) {
                showTextDialog("匯入失敗", "檔案內容是空的。請確認選的是 ssh-key-2026-02-26.key 這類 SSH 私鑰檔。");
                return;
            }
            if (!keyText.contains("-----BEGIN ") || !keyText.contains("-----END ")) {
                showTextDialog("匯入提醒", "這個檔案未偵測到 BEGIN/END 私鑰標記。\n\n請確認你選的是 SSH 私鑰，不是 .pub 公鑰或其他文字檔。\n\nApp 仍會先放入欄位，請按『檢查私鑰格式』確認。");
            }
            serverSettings.privateKey = keyText;
            store.saveServer(serverSettings);
            if (sshPrivateKeyEditor != null) sshPrivateKeyEditor.setText(keyText);
            updateSshKeyStatus(keyText);
            toast("已匯入並加密保存 SSH 私鑰");
        } catch (Exception e) {
            showTextDialog("匯入 SSH 私鑰失敗", e.getClass().getSimpleName() + "：" + e.getMessage() + "\n\n請確認檔案可讀，並選擇 ssh-key-2026-02-26.key 這類純文字私鑰檔。");
        }
    }

    private void showShell(String selected) {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xfff7f7f7);
        setContentView(root);

        TextView title = new TextView(this);
        title.setText("甲骨文雲端AI  v1.5.5");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(20);
        title.setPadding(dp(12), dp(12), dp(12), dp(4));
        root.addView(title);

        status = new TextView(this);
        status.setText("就緒｜供應商：" + providerTitle(modelSettings.provider) + "｜模型：" + modelSettings.modelName + "｜設定版：" + runtimeConfig.version);
        status.setPadding(dp(12), 0, dp(12), dp(8));
        root.addView(status);

        HorizontalScrollView navScroll = new HorizontalScrollView(this);
        navScroll.setHorizontalScrollBarEnabled(false);
        navScroll.setFillViewport(false);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(6), dp(4), dp(6), dp(4));
        navScroll.addView(nav, new HorizontalScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(navScroll, new LinearLayout.LayoutParams(-1, dp(50)));

        addNav(nav, "聊天", selected, v -> showChatPage());
        addNav(nav, "設定", selected, v -> showSettingsPage());
        addNav(nav, "本機", selected, v -> showLocalGemmaPage());
        addNav(nav, "Oracle", selected, v -> showServerPage());
        addNav(nav, "Kaggle", selected, v -> showKagglePage());
        addNav(nav, "維修", selected, v -> showRepairPage());
        addNav(nav, "更新", selected, v -> showUpdatePage());
    }

    private void addNav(LinearLayout nav, String text, String selected, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text.equals(selected) ? "● " + text : text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setGravity(Gravity.CENTER);
        b.setSingleLine(true);
        b.setMinWidth(0);
        b.setMinHeight(0);
        b.setPadding(dp(2), 0, dp(2), 0);
        b.setOnClickListener(l);
        nav.addView(b, new LinearLayout.LayoutParams(dp(78), dp(40)));
    }

    private LinearLayout page(String selected) {
        showShell(selected);
        ScrollView sv = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(8), dp(12), dp(24));
        sv.addView(box);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1));
        return box;
    }

    private void showChatPage() {
        showShell("聊天");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(6), dp(12), dp(8));
        root.addView(box, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout chatTop = new LinearLayout(this);
        chatTop.setOrientation(LinearLayout.HORIZONTAL);
        chatTop.setGravity(Gravity.CENTER_VERTICAL);

        TextView chatTitle = label("聊天與上下文", 18, true);
        chatTop.addView(chatTitle, new LinearLayout.LayoutParams(0, -2, 1));

        Button chatTools = button("⚙");
        chatTools.setTextSize(22);
        chatTools.setMinWidth(dp(48));
        chatTools.setMinHeight(dp(42));
        chatTools.setOnClickListener(v -> openChatToolsMenu());
        chatTop.addView(chatTools, new LinearLayout.LayoutParams(dp(52), dp(46)));
        box.addView(chatTop);

        TextView hint = label("一般 LLM 聊天 / Oracle 維修；支援 Markdown，回覆會自動捲到底。", 13, false);
        box.addView(hint);

        chatModelSpinner = new Spinner(this);
        refreshChatModelSpinner();
        box.addView(chatModelSpinner, new LinearLayout.LayoutParams(-1, dp(44)));

        TextView meta = label("平台：" + providerTitle(modelSettings.provider) + "｜Temp " + modelSettings.temperature + "｜上下文 " + modelSettings.maxContextCharacters + "｜MD 已啟用", 12, false);
        box.addView(meta);

        chatScrollView = new ScrollView(this);
        chatScrollView.setFillViewport(true);
        chatScrollView.setSmoothScrollingEnabled(true);
        chatScrollView.setBackgroundColor(0xffffffff);

        chatLogView = label("", 14, false);
        chatLogView.setTextIsSelectable(false);
        chatLogView.setPadding(dp(10), dp(10), dp(10), dp(10));
        chatScrollView.addView(chatLogView, new ScrollView.LayoutParams(-1, -2));
        box.addView(chatScrollView, new LinearLayout.LayoutParams(-1, 0, 1));
        renderChatLog();

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(0, dp(6), 0, 0);

        chatInput = edit("輸入訊息", true);
        chatInput.setMinLines(1);
        chatInput.setMaxLines(3);

        Button send = button("➤");
        send.setTextSize(20);
        send.setMinWidth(dp(48));
        send.setMinHeight(dp(46));
        send.setOnClickListener(v -> sendChat());

        inputRow.addView(chatInput, new LinearLayout.LayoutParams(0, dp(54), 1));
        inputRow.addView(send, new LinearLayout.LayoutParams(dp(54), dp(54)));
        box.addView(inputRow);
    }

    private void refreshChatModelSpinner() {
        if (chatModelSpinner == null) return;
        List<ModelOption> favorites = store.loadFavorites(modelSettings.provider);
        List<String> ids = new ArrayList<>();
        if (favorites.isEmpty()) ids.add(modelSettings.modelName == null || modelSettings.modelName.isEmpty() ? "請先到設定選模型" : modelSettings.modelName);
        else for (ModelOption m : favorites) ids.add(m.id);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ids);
        chatModelSpinner.setAdapter(adapter);
        int idx = Math.max(0, ids.indexOf(modelSettings.modelName));
        chatModelSpinner.setSelection(idx);
        chatModelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (!ids.isEmpty()) {
                    modelSettings.modelName = ids.get(position);
                    store.saveModel(modelSettings);
                    setStatus("目前模型：" + modelSettings.modelName);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    private void sendChat() {
        String text = chatInput.getText().toString().trim();
        if (text.isEmpty()) return;
        appLog("CHAT 送出訊息｜平台：" + providerTitle(modelSettings.provider) + "｜模型：" + modelSettings.modelName + "｜長度：" + text.length());
        chatInput.setText("");
        chatMessages.add(new ChatMessage("user", text));
        renderChatLog();
        runTask("正在呼叫模型…", () -> {
            if ("local_gemma".equals(modelSettings.provider)) {
                String modelPath = localGemmaPathFor(modelSettings.modelName);
                File modelFile = new File(modelPath);
                if (!modelFile.exists() || modelFile.length() < 1000000000L) {
                    throw new IllegalStateException("本機模型尚未下載完成：" + modelSettings.modelName + "\n請到「本機」頁一鍵下載 E2B 或 E4B。");
                }
                String localPrompt = buildLocalPrompt(chatMessages, modelSettings.maxContextCharacters);
                String reply = LocalGemmaRunner.generate(this, modelPath, localPrompt, runtimeConfig.systemPrompt);
                reply = cleanModelThoughts(reply);
                String finalReply = reply;
                ui.post(() -> {
                    appLog("LOCAL GEMMA 收到回覆｜長度：" + (finalReply == null ? 0 : finalReply.length()));
                    chatMessages.add(new ChatMessage("assistant", finalReply));
                    store.saveChat(chatMessages);
                    renderChatLog();
                    setStatus("本機 Gemma 回覆完成");
                });
                return;
            }
            if (isKaggleEndpointMissing(modelSettings)) {
                RuntimeConfig cfg = llm.fetchRuntimeConfig(updateSettings);
                store.backupRuntimeConfig();
                store.saveRuntimeConfig(cfg);
                runtimeConfig = cfg;
                applyKaggleConfig(cfg, false);
            }
            List<ChatMessage> payload = buildContextMessages(runtimeConfig.systemPrompt, chatMessages, modelSettings.maxContextCharacters);
            String reply = llm.sendChat(modelSettings.copy(), payload);
            ui.post(() -> {
                appLog("CHAT 收到回覆｜長度：" + (reply == null ? 0 : reply.length()));
                chatMessages.add(new ChatMessage("assistant", reply));
                store.saveChat(chatMessages);
                renderChatLog();
                setStatus("回覆完成");
            });
        });
    }

    private List<ChatMessage> buildContextMessages(String systemPrompt, List<ChatMessage> source, int maxChars) {
        List<ChatMessage> out = new ArrayList<>();
        out.add(new ChatMessage("system", systemPrompt));
        int used = systemPrompt == null ? 0 : systemPrompt.length();
        for (int i = source.size() - 1; i >= 0; i--) {
            ChatMessage m = source.get(i);
            int len = m.content == null ? 0 : m.content.length();
            if (used + len > maxChars && out.size() > 1) break;
            out.add(1, m);
            used += len;
        }
        return out;
    }

    private void renderChatLog() {
        if (chatLogView == null) return;
        if (chatMessages.isEmpty()) {
            chatLogView.setText("尚無聊天紀錄。");
            scrollChatToBottom();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : chatMessages) {
            if ("user".equals(m.role)) sb.append("### 你\n\n");
            else sb.append("### AI\n\n");
            sb.append(m.content == null ? "" : m.content.trim()).append("\n\n---\n\n");
        }
        String text = sb.toString();
        if (modelSettings != null && modelSettings.renderMarkdown && markwon != null) {
            markwon.setMarkdown(chatLogView, text);
        } else {
            chatLogView.setText(text);
        }
        scrollChatToBottom();
    }

    private void scrollChatToBottom() {
        if (chatScrollView == null) return;
        chatScrollView.postDelayed(() -> {
            try { chatScrollView.fullScroll(View.FOCUS_DOWN); } catch (Exception ignored) {}
        }, 80);
    }

    private void showSettingsPage() {
        LinearLayout box = page("設定");
        addSection(box, "模型平台與常用模型");
        Spinner providerSpinner = new Spinner(this);
        providerSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, providerLabels));
        providerSpinner.setSelection(providerIndex(modelSettings.provider));
        box.addView(providerSpinner);
        TextView providerExplain = label("目前平台：" + providerTitle(modelSettings.provider) + "｜各平台的 KEY、Base URL、模型清單、常用模型都分開保存。", 13, false);
        box.addView(providerExplain);

        box.addView(label("API Key：只屬於目前選擇的平台；Google/NVIDIA/Kaggle/自訂不會共用。", 13, true));
        EditText apiKey = edit("API Key，本機 Gemma 不需要；Kaggle 若無驗證可留空", false);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setText(modelSettings.apiKey);
        box.addView(apiKey);

        box.addView(label("Base URL：只屬於目前平台。", 13, true));
        EditText baseUrl = edit("Base URL，例如 https://integrate.api.nvidia.com/v1 或 Kaggle 隧道 /v1；本機 Gemma 可留空", false);
        baseUrl.setText(modelSettings.baseUrl);
        box.addView(baseUrl);

        box.addView(label("目前模型名稱：聊天時實際呼叫的模型。", 13, true));
        EditText modelName = edit("目前模型名稱", false);
        modelName.setText(modelSettings.modelName);
        box.addView(modelName);

        box.addView(label("Temperature：回覆創意程度。0.2 較穩、較少亂飄；數字越高越發散。", 13, true));
        EditText temperature = edit("例如 0.2", false);
        temperature.setText(String.valueOf(modelSettings.temperature));
        box.addView(temperature);

        box.addView(label("上下文保留字元數：送給模型的最近對話量。60000 代表最多帶入約 6 萬字元。", 13, true));
        EditText context = edit("預設 60000", false);
        context.setInputType(InputType.TYPE_CLASS_NUMBER);
        context.setText(String.valueOf(modelSettings.maxContextCharacters));
        box.addView(context);

        box.addView(label("Google Gemini / Gemma 思考：預設啟用 high；App 會隱藏思考內容，只顯示最終回答。NVIDIA/Kaggle/自訂平台會忽略此設定。", 13, true));
        Spinner thinkingSpinner = new Spinner(this);
        String[] thinkingLabels = new String[] {"high：強思考，適合修程式/診斷", "medium：平衡", "low：較快", "none：關閉，僅部分 Gemini 2.5 支援"};
        String[] thinkingCodes = new String[] {"high", "medium", "low", "none"};
        thinkingSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, thinkingLabels));
        thinkingSpinner.setSelection(reasoningIndex(modelSettings.geminiReasoningEffort));
        box.addView(thinkingSpinner);

        box.addView(label("Markdown 顯示：已啟用。AI 回覆的標題、列表、程式碼區塊會用 Markdown 方式顯示。", 13, true));

        TextView catalogInfo = label("目前平台：" + providerTitle(modelSettings.provider) + "｜本平台模型清單：" + store.loadCatalog(modelSettings.provider).size() + "｜本平台常用模型：" + store.loadFavorites(modelSettings.provider).size(), 14, false);
        box.addView(catalogInfo);

        providerSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String code = providerCodes[position];
                if (!code.equals(modelSettings.provider)) {
                    modelSettings = store.loadModelFor(code);
                    baseUrl.setText(modelSettings.baseUrl);
                    modelName.setText(modelSettings.modelName);
                    apiKey.setText(modelSettings.apiKey);
                    temperature.setText(String.valueOf(modelSettings.temperature));
                    context.setText(String.valueOf(modelSettings.maxContextCharacters));
                    thinkingSpinner.setSelection(reasoningIndex(modelSettings.geminiReasoningEffort));
                    providerExplain.setText("目前平台：" + providerTitle(code) + "｜各平台的 KEY、Base URL、模型清單、常用模型都分開保存。");
                    catalogInfo.setText("目前平台：" + providerTitle(code) + "｜本平台模型清單：" + store.loadCatalog(code).size() + "｜本平台常用模型：" + store.loadFavorites(code).size());
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        LinearLayout row1 = row();
        Button save = button("儲存設定");
        save.setOnClickListener(v -> {
            modelSettings.provider = providerCodes[providerSpinner.getSelectedItemPosition()];
            modelSettings.apiKey = apiKey.getText().toString().trim();
            modelSettings.baseUrl = baseUrl.getText().toString().trim();
            modelSettings.modelName = modelName.getText().toString().trim();
            modelSettings.temperature = parseDouble(temperature.getText().toString(), 0.2);
            modelSettings.maxContextCharacters = Math.max(8000, Math.min(200000, parseInt(context.getText().toString(), 60000)));
            modelSettings.geminiReasoningEffort = thinkingCodes[thinkingSpinner.getSelectedItemPosition()];
            modelSettings.hideThoughts = true;
            modelSettings.renderMarkdown = true;
            store.saveModel(modelSettings);
            toast("已儲存，之後開啟不用重填 KEY。也請不要把 KEY 貼給任何 AI。 ");
        });
        Button defaults = button("套用平台預設");
        defaults.setOnClickListener(v -> {
            ModelSettings d = defaultsFor(providerCodes[providerSpinner.getSelectedItemPosition()]);
            baseUrl.setText(d.baseUrl); modelName.setText(d.modelName);
        });
        row1.addView(save, weight()); row1.addView(defaults, weight()); box.addView(row1);

        LinearLayout row2 = row();
        Button fetch = button("取得模型清單");
        fetch.setOnClickListener(v -> {
            modelSettings.provider = providerCodes[providerSpinner.getSelectedItemPosition()];
            modelSettings.apiKey = apiKey.getText().toString().trim();
            modelSettings.baseUrl = baseUrl.getText().toString().trim();
            modelSettings.modelName = modelName.getText().toString().trim();
            store.saveModel(modelSettings);
            runTask("正在取得模型清單…", () -> {
                List<ModelOption> models = llm.listModels(modelSettings.copy());
                ui.post(() -> {
                    if (models.isEmpty()) toast("沒有取得模型；Kaggle/vLLM 若未開 /models，可手動輸入模型名稱後加入常用。");
                    store.saveCatalog(modelSettings.provider, models);
                    catalogInfo.setText("目前平台：" + providerTitle(modelSettings.provider) + "｜本平台模型清單：" + models.size() + "｜本平台常用模型：" + store.loadFavorites(modelSettings.provider).size());
                    setStatus("已取得 " + providerTitle(modelSettings.provider) + " 的 " + models.size() + " 個模型");
                    if (!models.isEmpty()) openFavoritesDialog(modelName, catalogInfo);
                });
            });
        });
        Button favorites = button("管理常用模型");
        favorites.setOnClickListener(v -> openFavoritesDialog(modelName, catalogInfo));
        row2.addView(fetch, weight()); row2.addView(favorites, weight()); box.addView(row2);

        LinearLayout row3 = row();
        Button addCurrent = button("把目前模型加入常用");
        addCurrent.setOnClickListener(v -> {
            modelSettings.provider = providerCodes[providerSpinner.getSelectedItemPosition()];
            modelSettings.modelName = modelName.getText().toString().trim();
            if (modelSettings.modelName.isEmpty()) { toast("請先輸入模型名稱"); return; }
            List<ModelOption> fav = store.loadFavorites(modelSettings.provider);
            boolean exists = false;
            for (ModelOption m : fav) if (m.id.equals(modelSettings.modelName)) exists = true;
            if (!exists) fav.add(new ModelOption(modelSettings.modelName, modelSettings.modelName, "手動加入"));
            store.saveFavorites(modelSettings.provider, fav);
            store.saveModel(modelSettings);
            catalogInfo.setText("目前平台：" + providerTitle(modelSettings.provider) + "｜本平台模型清單：" + store.loadCatalog(modelSettings.provider).size() + "｜本平台常用模型：" + fav.size());
            toast("已加入常用模型");
        });
        Button test = button("測試聊天 API");
        test.setOnClickListener(v -> {
            modelSettings.provider = providerCodes[providerSpinner.getSelectedItemPosition()];
            modelSettings.apiKey = apiKey.getText().toString().trim();
            modelSettings.baseUrl = baseUrl.getText().toString().trim();
            modelSettings.modelName = modelName.getText().toString().trim();
            store.saveModel(modelSettings);
            List<ChatMessage> testMsgs = Arrays.asList(new ChatMessage("system", "請只回答：OK"), new ChatMessage("user", "測試"));
            runTask("正在測試 API…", () -> {
                String reply = llm.sendChat(modelSettings.copy(), testMsgs);
                ui.post(() -> showTextDialog("API 測試結果", reply));
            });
        });
        row3.addView(addCurrent, weight()); row3.addView(test, weight()); box.addView(row3);

        addSection(box, "Kaggle Qwen 自動端點");
        box.addView(label("你不需要知道隧道網址。App 會從 GitHub 倉庫的 oracle-ai-rescue-config.json 讀取 Kaggle 目前公開端點。Kaggle 端程式只要把目前 ngrok/cloudflared 網址寫進該設定檔，手機就能自動套用。", 14, false));
        Button syncKaggle = button("自動同步 Kaggle 端點");
        syncKaggle.setOnClickListener(v -> runTask("正在同步 Kaggle 端點…", () -> {
            RuntimeConfig cfg = llm.fetchRuntimeConfig(updateSettings);
            store.backupRuntimeConfig();
            store.saveRuntimeConfig(cfg);
            runtimeConfig = cfg;
            boolean ok = applyKaggleConfig(cfg, true);
            ui.post(() -> {
                if (ok) {
                    modelSettings = store.loadModelFor("kaggle");
                    providerSpinner.setSelection(providerIndex("kaggle"));
                    apiKey.setText(modelSettings.apiKey);
                    baseUrl.setText(modelSettings.baseUrl);
                    modelName.setText(modelSettings.modelName);
                    catalogInfo.setText("目前平台：" + providerTitle("kaggle") + "｜本平台模型清單：" + store.loadCatalog("kaggle").size() + "｜本平台常用模型：" + store.loadFavorites("kaggle").size());
                    showTextDialog("Kaggle 端點已同步", "Base URL：" + modelSettings.baseUrl + "\n模型：" + modelSettings.modelName + "\n\n之後聊天頁選 Kaggle 模型即可直接使用。 ");
                } else {
                    showTextDialog("尚未取得 Kaggle 端點", "GitHub 設定檔中還沒有 kaggle.baseUrl / kaggleBaseUrl。\n\n這代表 Kaggle 端程式尚未把目前隧道網址發布到 GitHub。App 無法憑空知道動態隧道網址。 ");
                }
            });
        }));
        box.addView(syncKaggle);

        addSection(box, "Kaggle Qwen 設定提示");
        box.addView(label("Kaggle 端若用 vLLM/FastAPI，可由 Kaggle 程式把 Base URL 自動發布到 GitHub 設定檔；手機端按『自動同步 Kaggle 端點』即可，不必手動輸入網址。若你仍想手動填，Base URL 格式為：https://xxxx.ngrok-free.app/v1 或 https://xxxx.trycloudflare.com/v1。", 14, false));
    }

    private void openFavoritesDialog(EditText modelName, TextView catalogInfo) {
        final String provider = modelSettings.provider;
        List<ModelOption> catalog = store.loadCatalog(provider);
        if (catalog.isEmpty()) {
            String current = modelName.getText().toString().trim();
            if (current.isEmpty()) { toast("沒有模型清單，請先取得模型或手動輸入模型名稱。 "); return; }
            List<ModelOption> fav = store.loadFavorites(provider);
            fav.add(new ModelOption(current, current, "手動加入"));
            store.saveFavorites(provider, fav);
            toast("已加入常用模型");
            return;
        }

        List<ModelOption> fav = store.loadFavorites(provider);
        Set<String> selectedIds = new HashSet<>();
        for (ModelOption m : fav) selectedIds.add(m.id);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(8), dp(10), dp(4));

        TextView title = label("目前平台：" + providerTitle(provider) + "\n每一列是一個模型。勾選後只會加入此平台的常用模型，不會影響其他平台。", 14, false);
        content.addView(title);

        EditText search = edit("搜尋本平台模型，例如 qwen、llama、gemini、70b", false);
        content.addView(search);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        List<View> rowViews = new ArrayList<>();
        List<ModelOption> rowModels = new ArrayList<>();

        for (ModelOption m : catalog) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setPadding(dp(8), dp(8), dp(8), dp(8));
            item.setBackgroundColor(0xffffffff);

            CheckBox cb = new CheckBox(this);
            String main = m.displayName != null && !m.displayName.equals(m.id) ? m.displayName + "\n" + m.id : m.id;
            cb.setText(main);
            cb.setTextSize(15);
            cb.setSingleLine(false);
            cb.setChecked(selectedIds.contains(m.id));
            cb.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (isChecked) selectedIds.add(m.id);
                else selectedIds.remove(m.id);
            });
            item.addView(cb);

            if (m.description != null && m.description.trim().length() > 0) {
                TextView desc = label(m.description, 12, false);
                desc.setPadding(dp(34), 0, dp(4), dp(4));
                item.addView(desc);
            }

            TextView divider = label(" ", 2, false);
            divider.setBackgroundColor(0xffeeeeee);
            list.addView(item);
            list.addView(divider);
            rowViews.add(item);
            rowModels.add(m);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, dp(520)));

        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence q, int start, int before, int count) {
                String needle = q == null ? "" : q.toString().trim().toLowerCase(Locale.ROOT);
                for (int i = 0; i < rowViews.size(); i++) {
                    ModelOption m = rowModels.get(i);
                    String hay = (m.id + " " + m.displayName + " " + m.description).toLowerCase(Locale.ROOT);
                    rowViews.get(i).setVisibility(needle.isEmpty() || hay.contains(needle) ? View.VISIBLE : View.GONE);
                }
            }
            @Override public void afterTextChanged(android.text.Editable e) {}
        });

        new AlertDialog.Builder(this)
            .setTitle("管理常用模型｜" + providerTitle(provider))
            .setView(content)
            .setPositiveButton("儲存勾選", (d, w) -> {
                List<ModelOption> selected = new ArrayList<>();
                for (ModelOption m : catalog) if (selectedIds.contains(m.id)) selected.add(m);
                store.saveFavorites(provider, selected);
                if (!selected.isEmpty()) {
                    modelSettings = store.loadModelFor(provider);
                    modelSettings.modelName = selected.get(0).id;
                    modelName.setText(modelSettings.modelName);
                    store.saveModel(modelSettings);
                }
                catalogInfo.setText("目前平台：" + providerTitle(provider) + "｜本平台模型清單：" + catalog.size() + "｜本平台常用模型：" + selected.size());
                toast("已儲存 " + providerTitle(provider) + " 的常用模型");
                refreshChatModelSpinner();
            })
            .setNeutralButton("清空本平台常用", (d, w) -> {
                store.saveFavorites(provider, new ArrayList<>());
                catalogInfo.setText("目前平台：" + providerTitle(provider) + "｜本平台模型清單：" + catalog.size() + "｜本平台常用模型：0");
                toast("已清空 " + providerTitle(provider) + " 的常用模型");
                refreshChatModelSpinner();
            })
            .setNegativeButton("取消", null)
            .show();
    }


    private void showLocalGemmaPage() {
        LinearLayout box = page("本機");
        addSection(box, "本機 Gemma 4 E2B / E4B 加速推論");
        box.addView(label("這裡是手機本地運行，不是 Google API、不是 NVIDIA NIM、不是甲骨文雲端。模型會下載到此 App 的本機資料夾，使用 LiteRT-LM 與 speculative decoding / MTP。E2B 約 2.6GB，E4B 約 3.7GB，請使用 Wi‑Fi 並確認手機空間足夠。", 14, false));

        Button statusBtn = button("檢查本機模型狀態");
        statusBtn.setOnClickListener(v -> showTextDialog("本機模型狀態", buildLocalGemmaStatus()));
        box.addView(statusBtn);

        LinearLayout row1 = row();
        Button e2b = button("一鍵下載 E2B 加速模型");
        e2b.setOnClickListener(v -> confirm("將下載 Gemma 4 E2B LiteRT-LM 本機模型，約 2.6GB。若之前下載過舊版，建議重新下載以取得 speculative decoding / MTP 支援。", () -> startLocalGemmaDownload("E2B")));
        Button e4b = button("一鍵下載 E4B 加速模型");
        e4b.setOnClickListener(v -> confirm("將下載 Gemma 4 E4B LiteRT-LM 本機模型，約 3.7GB。高階手機建議，若手機記憶體不足可能啟動失敗。", () -> startLocalGemmaDownload("E4B")));
        row1.addView(e2b, weight());
        row1.addView(e4b, weight());
        box.addView(row1);

        LinearLayout row2 = row();
        Button useE2B = button("使用本機 E2B");
        useE2B.setOnClickListener(v -> applyLocalGemma("gemma-4-E2B-it.litertlm"));
        Button useE4B = button("使用本機 E4B");
        useE4B.setOnClickListener(v -> applyLocalGemma("gemma-4-E4B-it.litertlm"));
        row2.addView(useE2B, weight());
        row2.addView(useE4B, weight());
        box.addView(row2);

        Button info = button("本機 Gemma 說明");
        info.setOnClickListener(v -> showTextDialog("本機 Gemma 說明",
            "本機 Gemma 4 使用 LiteRT-LM，模型在手機內運行，不需要 API Key。\\n\\n"
            + "E2B：較適合先測，檔案約 2.6GB。\\n"
            + "E4B：能力較好，但需要更高 RAM / GPU，檔案約 3.7GB。\\n\\n"
            + "若你之前在 AI Edge Gallery 下載過模型，通常不能直接共用，因為 Android App 之間資料互相隔離。這個 App 會自己下載一份到本機資料夾。\\n\\n"
            + "如果聊天時啟動失敗，請匯出 LOG 回報給我。"));
        box.addView(info);
    }

    private void startLocalGemmaDownload(String size) {
        try {
            LocalModelInfo info = localModelInfo(size);
            File dir = getExternalFilesDir("models");
            if (dir == null) throw new IllegalStateException("無法取得 App 本機模型資料夾。");
            if (!dir.exists()) dir.mkdirs();
            File target = new File(dir, info.fileName);

            if (target.exists() && target.length() > info.minimumBytes) {
                showTextDialog("模型已存在", info.title + " 已存在：\\n" + target.getAbsolutePath() + "\\n大小：" + formatBytes(target.length()) + "\\n\\n可以直接按「使用本機 " + size + "」。");
                return;
            }

            DownloadManager dm = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
            DownloadManager.Request req = new DownloadManager.Request(Uri.parse(info.url));
            req.setTitle("甲骨文雲端AI｜下載 " + info.title);
            req.setDescription("本機 Gemma 4 LiteRT-LM 加速模型下載中");
            req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            req.setDestinationUri(Uri.fromFile(target));
            req.setAllowedOverMetered(false);
            req.setAllowedOverRoaming(false);
            long id = dm.enqueue(req);
            appLog("LOCAL GEMMA 下載開始｜" + info.title + "｜downloadId=" + id + "｜" + info.url);
            showTextDialog("已開始下載", info.title + " 已開始下載。\\n\\n檔案位置：\\n" + target.getAbsolutePath() + "\\n\\n下載很大，請保持 Wi‑Fi。下載完成後回到「本機」頁按「檢查本機模型狀態」，再按「使用本機 " + size + "」。");
        } catch (Exception e) {
            showTextDialog("下載失敗", e.getClass().getSimpleName() + "：" + e.getMessage());
        }
    }

    private void applyLocalGemma(String fileName) {
        File file = new File(localGemmaPathFor(fileName));
        if (!file.exists() || file.length() < 1000000000L) {
            showTextDialog("模型尚未下載完成", "找不到完整模型：\\n" + file.getAbsolutePath() + "\\n\\n請先按一鍵下載，或確認下載是否完成。");
            return;
        }
        modelSettings = store.loadModelFor("local_gemma");
        modelSettings.provider = "local_gemma";
        modelSettings.modelName = fileName;
        modelSettings.apiKey = "";
        modelSettings.baseUrl = "";
        store.saveModel(modelSettings);
        List<ModelOption> fav = store.loadFavorites("local_gemma");
        boolean exists = false;
        for (ModelOption m : fav) if (m.id.equals(fileName)) exists = true;
        if (!exists) {
            String display = fileName.contains("E4B") ? "本機 Gemma 4 E4B 加速" : "本機 Gemma 4 E2B 加速";
            fav.add(new ModelOption(fileName, display, "手機本地 LiteRT-LM 模型"));
            store.saveFavorites("local_gemma", fav);
        }
        refreshChatModelSpinner();
        showTextDialog("已切換到本機模型", "目前平台：本機 Gemma 4\\n模型：" + fileName + "\\n\\n之後回到聊天頁即可直接本機推論。第一次回覆會比較慢，因為需要載入模型。");
    }

    private String buildLocalGemmaStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("本機模型資料夾：\\n");
        File dir = getExternalFilesDir("models");
        sb.append(dir == null ? "無法取得" : dir.getAbsolutePath()).append("\\n\\n");
        for (String size : new String[] {"E2B", "E4B"}) {
            LocalModelInfo info = localModelInfo(size);
            File file = new File(localGemmaPathFor(info.fileName));
            sb.append(info.title).append("\\n");
            sb.append("檔名：").append(info.fileName).append("\\n");
            sb.append("狀態：").append(file.exists() ? "已找到" : "未下載").append("\\n");
            sb.append("大小：").append(file.exists() ? formatBytes(file.length()) : "—").append("\\n");
            sb.append("路徑：").append(file.getAbsolutePath()).append("\\n\\n");
        }
        return sb.toString();
    }

    private String localGemmaPathFor(String modelName) {
        File dir = getExternalFilesDir("models");
        if (dir == null) dir = new File(getFilesDir(), "models");
        if (!dir.exists()) dir.mkdirs();
        String fileName = modelName == null || modelName.trim().isEmpty() ? "gemma-4-E2B-it.litertlm" : modelName.trim();
        return new File(dir, fileName).getAbsolutePath();
    }

    private LocalModelInfo localModelInfo(String size) {
        if ("E4B".equalsIgnoreCase(size)) {
            return new LocalModelInfo(
                "Gemma 4 E4B LiteRT-LM",
                "gemma-4-E4B-it.litertlm",
                "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm?download=true",
                3000000000L
            );
        }
        return new LocalModelInfo(
            "Gemma 4 E2B LiteRT-LM",
            "gemma-4-E2B-it.litertlm",
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true",
            2000000000L
        );
    }

    private String buildLocalPrompt(List<ChatMessage> source, int maxChars) {
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int start = Math.max(0, source.size() - 12);
        for (int i = start; i < source.size(); i++) {
            ChatMessage m = source.get(i);
            String line = ("user".equals(m.role) ? "使用者：" : "助理：") + (m.content == null ? "" : m.content) + "\\n";
            if (used + line.length() > maxChars) break;
            sb.append(line);
            used += line.length();
        }
        sb.append("\\n請根據以上對話，回答最後一個使用者問題。");
        return sb.toString();
    }


    private static String cleanModelThoughts(String text) {
        if (text == null) return "";
        String x = text;

        // 移除常見顯式思考區塊。這是顯示層清理，不會改變模型本身能力。
        x = x.replaceAll("(?is)<think>.*?</think>", "");
        x = x.replaceAll("(?is)<thinking>.*?</thinking>", "");
        x = x.replaceAll("(?is)<thought>.*?</thought>", "");
        x = x.replaceAll("(?is)<reasoning>.*?</reasoning>", "");

        // 移除 Markdown code fence 形式的 thinking/reasoning。
        x = x.replaceAll("(?is)```\\s*(thinking|think|thought|reasoning)\\s*\\n.*?```", "");

        // 移除常見標題段落：思考、推理、Reasoning、Chain of thought。
        x = x.replaceAll("(?is)(^|\\n)\\s*(思考過程|推理過程|內部思考|Reasoning|Thought process|Chain of thought)\\s*[:：]\\s*.*?(?=\\n\\s*(最終答案|答案|Final answer|回覆)\\s*[:：]|\\z)", "\n");

        // 若模型輸出「最終答案：」，只保留後面比較乾淨。
        String[] markers = new String[] {"最終答案：", "最終答案:", "Final answer:", "Final Answer:"};
        for (String m : markers) {
            int idx = x.indexOf(m);
            if (idx >= 0 && idx + m.length() < x.length()) {
                x = x.substring(idx + m.length()).trim();
                break;
            }
        }

        return x.trim();
    }

    private static String formatBytes(long bytes) {
        double v = bytes;
        String[] units = {"B", "KB", "MB", "GB"};
        int i = 0;
        while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
        return String.format(Locale.TAIWAN, "%.2f %s", v, units[i]);
    }

    private static class LocalModelInfo {
        final String title;
        final String fileName;
        final String url;
        final long minimumBytes;
        LocalModelInfo(String title, String fileName, String url, long minimumBytes) {
            this.title = title;
            this.fileName = fileName;
            this.url = url;
            this.minimumBytes = minimumBytes;
        }
    }

    private void showServerPage() {
        LinearLayout box = page("Oracle");
        addSection(box, "Oracle Cloud SSH 設定");
        EditText name = edit("顯示名稱", false); name.setText(serverSettings.name); box.addView(name);
        EditText host = edit("主機 IP 或網域", false); host.setText(serverSettings.host); box.addView(host);
        EditText port = edit("SSH Port", false); port.setInputType(InputType.TYPE_CLASS_NUMBER); port.setText(String.valueOf(serverSettings.port)); box.addView(port);
        EditText username = edit("使用者，例如 ubuntu/opc", false); username.setText(serverSettings.username); box.addView(username);
        EditText password = edit("密碼，可留空，建議用私鑰", false); password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); password.setText(serverSettings.password); box.addView(password);
        EditText privateKey = edit("SSH 私鑰內容，包含 BEGIN RSA PRIVATE KEY 或 BEGIN OPENSSH PRIVATE KEY", true); privateKey.setMinLines(5); privateKey.setText(serverSettings.privateKey); box.addView(privateKey);
        sshPrivateKeyEditor = privateKey;
        sshKeyStatusLabel = label(describePrivateKeyForUi(serverSettings.privateKey), 13, false);
        sshKeyStatusLabel.setTextIsSelectable(true);
        box.addView(sshKeyStatusLabel);
        LinearLayout keyRow = row();
        Button importKey = button("從檔案匯入 SSH 私鑰");
        importKey.setOnClickListener(v -> importSshPrivateKeyFile());
        Button checkKey = button("檢查私鑰格式");
        checkKey.setOnClickListener(v -> {
            String keyText = privateKey.getText().toString();
            updateSshKeyStatus(keyText);
            showTextDialog("私鑰格式檢查", describePrivateKeyForUi(keyText));
        });
        keyRow.addView(importKey, weight()); keyRow.addView(checkKey, weight()); box.addView(keyRow);
        box.addView(label("建議使用『從檔案匯入 SSH 私鑰』選擇 ssh-key-2026-02-26.key，避免手動貼上時漏掉換行或貼到錯欄位。", 13, false));
        EditText passphrase = edit("私鑰密碼，可留空", false); passphrase.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); passphrase.setText(serverSettings.privateKeyPassphrase); box.addView(passphrase);
        EditText projectPath = edit("專案路徑，例如 /home/ubuntu/my-ai", false); projectPath.setText(serverSettings.projectPath); box.addView(projectPath);
        EditText serviceName = edit("systemd 服務名，例如 my-ai.service", false); serviceName.setText(serverSettings.serviceName); box.addView(serviceName);
        EditText docker = edit("Docker 容器名，可留空", false); docker.setText(serverSettings.dockerContainer); box.addView(docker);
        CheckBox allowSudo = new CheckBox(this); allowSudo.setText("允許手動執行 sudo 指令，仍需確認"); allowSudo.setChecked(serverSettings.allowSudoCommands); box.addView(allowSudo);

        LinearLayout row = row();
        Button save = button("儲存 SSH 設定");
        save.setOnClickListener(v -> {
            serverSettings.name = name.getText().toString().trim();
            serverSettings.host = host.getText().toString().trim();
            serverSettings.port = parseInt(port.getText().toString(), 22);
            serverSettings.username = username.getText().toString().trim();
            serverSettings.password = password.getText().toString();
            serverSettings.privateKey = privateKey.getText().toString();
            serverSettings.privateKeyPassphrase = passphrase.getText().toString();
            serverSettings.projectPath = projectPath.getText().toString().trim();
            serverSettings.serviceName = serviceName.getText().toString().trim();
            serverSettings.dockerContainer = docker.getText().toString().trim();
            serverSettings.allowSudoCommands = allowSudo.isChecked();
            store.saveServer(serverSettings);
            updateSshKeyStatus(serverSettings.privateKey);
            toast("已加密儲存在手機本機");
        });
        Button test = button("測試 SSH");
        test.setOnClickListener(v -> { save.performClick(); runTask("正在測試 SSH…", () -> {
            CommandResult r = new SshClient(serverSettings).testConnection();
            ui.post(() -> showTextDialog("SSH 測試結果", r.asText()));
        });});
        Button smartTest = button("智慧診斷 SSH");
        smartTest.setOnClickListener(v -> { save.performClick(); runTask("正在智慧診斷 SSH…", () -> {
            String r = new SshClient(serverSettings).testConnectionDetailed();
            ui.post(() -> showTextDialog("SSH 智慧診斷結果", r));
        });});
        row.addView(save, weight()); row.addView(test, weight()); box.addView(row);
        box.addView(smartTest);
    }


    private void showKagglePage() {
        LinearLayout box = page("Kaggle");
        addSection(box, "Kaggle Qwen 自動啟動與端點同步");
        box.addView(label("這頁用來從手機觸發 GitHub Actions，讓 GitHub Actions 啟動 Kaggle Notebook。Kaggle Notebook 會啟動 Qwen API、建立 cloudflared 隧道、把網址寫回 GitHub 設定檔，手機再自動同步。", 14, false));

        EditText ghToken = edit("GitHub Fine-grained Token，只需此 repo 的 Actions: Read and write", false);
        ghToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ghToken.setText(updateSettings.githubToken);
        box.addView(ghToken);

        EditText workflow = edit("啟動 workflow 檔名", false);
        workflow.setText(updateSettings.kaggleStartWorkflow);
        box.addView(workflow);

        EditText idle = edit("閒置自動關閉分鐘數，建議 15", false);
        idle.setInputType(InputType.TYPE_CLASS_NUMBER);
        idle.setText(String.valueOf(updateSettings.kaggleIdleMinutes));
        box.addView(idle);

        EditText quota = edit("每週 GPU 額度小時，預設 30；實際以 Kaggle 顯示為準", false);
        quota.setInputType(InputType.TYPE_CLASS_NUMBER);
        quota.setText(String.valueOf(updateSettings.kaggleWeeklyQuotaHours));
        box.addView(quota);

        TextView state = label(kaggleStatusText(runtimeConfig), 14, false);
        state.setTextIsSelectable(true);
        state.setBackgroundColor(0xffffffff);
        state.setPadding(dp(10), dp(10), dp(10), dp(10));
        box.addView(state);

        LinearLayout row0 = row();
        Button save = button("儲存 Kaggle 控制設定");
        save.setOnClickListener(v -> {
            updateSettings.githubToken = ghToken.getText().toString().trim();
            updateSettings.kaggleStartWorkflow = workflow.getText().toString().trim();
            updateSettings.kaggleIdleMinutes = Math.max(5, Math.min(120, parseInt(idle.getText().toString(), 15)));
            updateSettings.kaggleWeeklyQuotaHours = Math.max(1, Math.min(80, parseInt(quota.getText().toString(), 30)));
            store.saveUpdateSettings(updateSettings);
            toast("已儲存 Kaggle 控制設定");
        });
        Button sync = button("同步 Kaggle 狀態/端點");
        sync.setOnClickListener(v -> { save.performClick(); syncKaggleStatusToUi(state, true); });
        row0.addView(save, weight()); row0.addView(sync, weight()); box.addView(row0);

        LinearLayout row1 = row();
        Button start = button("啟動 Kaggle Qwen");
        start.setOnClickListener(v -> confirm("會觸發 GitHub Actions，啟動 Kaggle GPU Notebook。啟動後通常需要數分鐘載入模型。確定？", () -> {
            save.performClick();
            runTask("正在觸發 Kaggle 啟動 workflow…", () -> {
                org.json.JSONObject inputs = new org.json.JSONObject()
                    .put("idle_minutes", String.valueOf(updateSettings.kaggleIdleMinutes))
                    .put("weekly_quota_hours", String.valueOf(updateSettings.kaggleWeeklyQuotaHours));
                llm.dispatchWorkflow(updateSettings, updateSettings.kaggleStartWorkflow, inputs);
                ui.post(() -> showTextDialog("已送出啟動要求", "GitHub Actions 已接受啟動要求。\n\n請等待 2～8 分鐘後按『同步 Kaggle 狀態/端點』。如果模型很大，可能需要更久。"));
            });
        }));
        Button stop = button("停止 Kaggle API");
        stop.setOnClickListener(v -> confirm("會呼叫目前 Kaggle API 的 /shutdown，讓 Kaggle 端程式結束以停止消耗 GPU。確定？", () -> {
            runTask("正在要求 Kaggle 停止…", () -> {
                ModelSettings kg = store.loadModelFor("kaggle");
                String out = llm.shutdownKaggle(kg);
                ui.post(() -> showTextDialog("停止要求已送出", out));
            });
        }));
        row1.addView(start, weight()); row1.addView(stop, weight()); box.addView(row1);

        addSection(box, "額度與重置時間，UTC+8");
        box.addView(label("Kaggle 沒有穩定公開 API 可讓手機直接讀取你帳號的剩餘 GPU 額度；App 顯示的是由 Kaggle 端程式寫回 GitHub 的估算值。Kaggle 額度一般每週六 00:00 UTC 重置，也就是台灣時間 UTC+8 的週六 08:00。", 14, false));
        Button resetEstimate = button("把本週估算用量歸零");
        resetEstimate.setOnClickListener(v -> showTextDialog("說明", "目前估算用量由 Kaggle 端寫回 GitHub 設定檔。若要歸零，請等週六 08:00 後啟動 Kaggle，Kaggle 端程式會自動建立新週期。"));
        box.addView(resetEstimate);

        addSection(box, "你在 Kaggle/GitHub 需要做的一次性設定");
        box.addView(label("Kaggle 使用者名稱已內建：dinosonicgo。\nGitHub Secrets 只需要：KAGGLE_API_TOKEN 與 GH_CONFIG_PAT。\nKAGGLE_API_TOKEN 是 Kaggle 新版 API Token，通常以 KGAT_ 開頭。\nGH_CONFIG_PAT 是 GitHub Token，需 Contents: Read and write。\n手機 App 第一欄 GitHub Token 需 Actions: Read and write，用來觸發啟動 workflow；可與 GH_CONFIG_PAT 使用同一把新 token。", 14, false));
    }

    private void syncKaggleStatusToUi(TextView target, boolean showDialog) {
        runTask("正在同步 Kaggle 狀態…", () -> {
            RuntimeConfig cfg = llm.fetchRuntimeConfig(updateSettings);
            store.backupRuntimeConfig();
            store.saveRuntimeConfig(cfg);
            runtimeConfig = cfg;
            boolean ok = applyKaggleConfig(cfg, true);
            ui.post(() -> {
                target.setText(kaggleStatusText(runtimeConfig));
                if (showDialog) showTextDialog("Kaggle 狀態", kaggleStatusText(runtimeConfig) + "\n\n端點同步：" + (ok ? "成功" : "尚未取得 Base URL"));
            });
        });
    }

    private String kaggleStatusText(RuntimeConfig cfg) {
        int remaining = cfg.kaggleEstimatedRemainingMinutes > 0 ? cfg.kaggleEstimatedRemainingMinutes : Math.max(0, updateSettings.kaggleWeeklyQuotaHours * 60 - cfg.kaggleEstimatedUsedMinutes);
        return "狀態：" + cfg.kaggleState + "\n" +
               "Base URL：" + (cfg.kaggleBaseUrl == null || cfg.kaggleBaseUrl.isEmpty() ? "尚未發布" : cfg.kaggleBaseUrl) + "\n" +
               "模型：" + cfg.kaggleDefaultModel + "\n" +
               "最後心跳，UTC+8：" + emptyDash(cfg.kaggleLastHeartbeatUtc8) + "\n" +
               "啟動時間，UTC+8：" + emptyDash(cfg.kaggleStartedAtUtc8) + "\n" +
               "停止時間，UTC+8：" + emptyDash(cfg.kaggleStoppedAtUtc8) + "\n" +
               "閒置自動關閉：" + cfg.kaggleIdleShutdownMinutes + " 分鐘\n" +
               "本週估算已用：" + formatMinutes(cfg.kaggleEstimatedUsedMinutes) + "\n" +
               "本週估算剩餘：" + formatMinutes(remaining) + "\n" +
               "每週重置，UTC+8：" + (cfg.kaggleWeekResetAtUtc8 == null || cfg.kaggleWeekResetAtUtc8.isEmpty() ? nextKaggleResetUtc8() : cfg.kaggleWeekResetAtUtc8) + "\n" +
               "訊息：" + emptyDash(cfg.kaggleMessage);
    }

    private static String emptyDash(String s) { return s == null || s.trim().isEmpty() ? "—" : s; }
    private static String formatMinutes(int minutes) { return (minutes / 60) + " 小時 " + (minutes % 60) + " 分鐘"; }

    private static String nextKaggleResetUtc8() {
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Taipei"));
        cal.set(java.util.Calendar.HOUR_OF_DAY, 8);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        int dow = cal.get(java.util.Calendar.DAY_OF_WEEK);
        int days = java.util.Calendar.SATURDAY - dow;
        if (days < 0) days += 7;
        if (days == 0 && java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Asia/Taipei")).after(cal)) days = 7;
        cal.add(java.util.Calendar.DAY_OF_MONTH, days);
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC+8'", java.util.Locale.US).format(cal.getTime());
    }

    private void showRepairPage() {
        LinearLayout box = page("維修");
        addSection(box, "一鍵診斷");
        Button diag = button("一鍵診斷 Oracle Cloud 並交給 AI 分析");
        diag.setOnClickListener(v -> runDiagnostics());
        box.addView(diag);

        addSection(box, "安全執行指令");
        EditText cmd = edit("輸入要執行的指令，例如 systemctl status xxx --no-pager", true);
        cmd.setMinLines(2); box.addView(cmd);
        Button run = button("檢查安全後執行");
        run.setOnClickListener(v -> {
            String c = cmd.getText().toString().trim();
            if (c.isEmpty()) return;
            if (RepairSafety.isDangerous(c)) { showTextDialog("已阻擋危險指令", "此指令包含高風險操作，App 不會執行：\n" + c); return; }
            if (c.contains("sudo") && !serverSettings.allowSudoCommands) { toast("尚未在 Oracle 設定頁允許 sudo 指令"); return; }
            confirm("確定執行？\n\n" + c, () -> runTask("正在執行指令…", () -> {
                CommandResult r = new SshClient(serverSettings).runCommand(c, 120000);
                store.appendHistory(new RepairHistory(now(), "執行指令", r.asText()));
                ui.post(() -> showTextDialog("指令結果", r.asText()));
            }));
        });
        box.addView(run);

        addSection(box, "AI 修正遠端檔案");
        EditText path = edit("遠端檔案路徑，例如 /home/ubuntu/my-ai/main.py", false);
        box.addView(path);
        EditText instruction = edit("你要 AI 怎麼修，例如：修正啟動失敗錯誤，保留原功能", true);
        instruction.setMinLines(3); box.addView(instruction);
        TextView fileBox = label("尚未讀取檔案", 13, false);
        fileBox.setTextIsSelectable(true); fileBox.setBackgroundColor(0xffffffff); fileBox.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.addView(fileBox, new LinearLayout.LayoutParams(-1, dp(220)));
        final String[] original = new String[] {""};
        final String[] proposed = new String[] {""};
        LinearLayout row1 = row();
        Button read = button("讀取檔案");
        read.setOnClickListener(v -> runTask("正在讀取檔案…", () -> {
            String content = new SshClient(serverSettings).readFile(path.getText().toString().trim());
            original[0] = content;
            ui.post(() -> fileBox.setText(limit(content, 30000)));
        }));
        Button fix = button("AI 產生修正版");
        fix.setOnClickListener(v -> {
            if (original[0].isEmpty()) { toast("請先讀取檔案"); return; }
            List<ChatMessage> msgs = new ArrayList<>();
            msgs.add(new ChatMessage("system", "你是程式修復助手。請只輸出修正後完整檔案內容，不要 Markdown，不要解釋。"));
            msgs.add(new ChatMessage("user", "檔案路徑：" + path.getText().toString().trim() + "\n修正要求：" + instruction.getText().toString().trim() + "\n\n原始檔案：\n" + original[0]));
            runTask("正在讓 AI 修正檔案…", () -> {
                String content = llm.sendChat(modelSettings.copy(), msgs);
                proposed[0] = stripCodeFence(content);
                String diff = DiffUtil.unifiedDiff(original[0], proposed[0]);
                ui.post(() -> showTextDialog("修改差異", diff));
            });
        });
        row1.addView(read, weight()); row1.addView(fix, weight()); box.addView(row1);
        Button write = button("建立備份並寫回修正版");
        write.setOnClickListener(v -> {
            if (proposed[0].isEmpty()) { toast("尚未產生修正版"); return; }
            confirm("會先備份原檔，再寫回修正版。確定執行？", () -> runTask("正在備份並寫回…", () -> {
                String backup = new SshClient(serverSettings).writeFileWithBackup(path.getText().toString().trim(), proposed[0]);
                store.appendHistory(new RepairHistory(now(), "寫回檔案", "檔案：" + path.getText().toString().trim() + "\n備份：" + backup));
                ui.post(() -> showTextDialog("完成", "已建立備份並寫回。\n備份檔：" + backup + "\n\n若要重啟服務，請使用安全執行指令或一鍵診斷建議。"));
            }));
        });
        box.addView(write);

        addSection(box, "維修紀錄");
        Button history = button("查看維修紀錄");
        history.setOnClickListener(v -> {
            StringBuilder sb = new StringBuilder();
            for (RepairHistory h : store.loadHistory()) sb.append(h.timestamp).append("｜").append(h.title).append("\n").append(h.content).append("\n\n");
            showTextDialog("維修紀錄", sb.length() == 0 ? "尚無紀錄" : sb.toString());
        });
        box.addView(history);
    }

    private void runDiagnostics() {
        List<String> commands = DiagnosticCommands.build(serverSettings, runtimeConfig);
        runTask("正在診斷 Oracle Cloud…", () -> {
            List<CommandResult> results = new SshClient(serverSettings).runCommands(commands, 90000);
            StringBuilder raw = new StringBuilder();
            for (CommandResult r : results) raw.append(r.asText()).append("\n");
            List<ChatMessage> msgs = new ArrayList<>();
            msgs.add(new ChatMessage("system", runtimeConfig.systemPrompt));
            msgs.add(new ChatMessage("user", "請根據以下 Oracle Cloud 診斷輸出，判斷故障原因、風險、建議修復步驟。不要要求我重新提供上一輪已存在的資訊。\n\n" + raw));
            String analysis = llm.sendChat(modelSettings.copy(), msgs);
            store.appendHistory(new RepairHistory(now(), "一鍵診斷", "診斷輸出：\n" + raw + "\n\nAI 分析：\n" + analysis));
            ui.post(() -> showTextDialog("診斷與 AI 分析", "【原始診斷】\n" + raw + "\n\n【AI 分析】\n" + analysis));
        });
    }

    private void showUpdatePage() {
        LinearLayout box = page("更新");
        addSection(box, "GitHub 設定熱更新，不用重裝 APK");
        box.addView(label("這裡會從你的 GitHub 倉庫讀取 oracle-ai-rescue-config.json，可更新系統提示詞、額外診斷指令，也可同步 Kaggle Qwen 的動態隧道端點。若更新造成問題，可一鍵回滾到上一份設定。", 14, false));
        EditText owner = edit("GitHub owner，例如你的帳號", false); owner.setText(updateSettings.owner); box.addView(owner);
        EditText repo = edit("Repository 名稱", false); repo.setText(updateSettings.repo); box.addView(repo);
        EditText branch = edit("分支，通常 main", false); branch.setText(updateSettings.branch); box.addView(branch);
        EditText configPath = edit("設定檔路徑", false); configPath.setText(updateSettings.configPath); box.addView(configPath);
        EditText ghToken = edit("GitHub Token，私人倉庫查詢設定/Releases 必填；可與 Kaggle 頁同一把", false);
        ghToken.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        ghToken.setText(updateSettings.githubToken);
        box.addView(ghToken);
        box.addView(label("你的 Mobile-LLM 是私人倉庫，所以『抓取設定』與『查看 Releases』需要 GitHub Token。這個 Token 只會加密保存在手機本機，不會寫入 GitHub 倉庫。", 13, false));
        TextView current = label("目前設定版：" + runtimeConfig.version + "\n額外診斷指令：" + runtimeConfig.extraDiagnosticCommands.size(), 14, false);
        box.addView(current);

        LinearLayout row1 = row();
        Button save = button("儲存 GitHub 設定");
        save.setOnClickListener(v -> {
            updateSettings.owner = owner.getText().toString().trim();
            updateSettings.repo = repo.getText().toString().trim();
            updateSettings.branch = branch.getText().toString().trim();
            updateSettings.configPath = configPath.getText().toString().trim();
            updateSettings.githubToken = ghToken.getText().toString().trim();
            store.saveUpdateSettings(updateSettings);
            toast("已儲存");
        });
        Button fetch = button("抓取並套用設定");
        fetch.setOnClickListener(v -> { save.performClick(); runTask("正在抓取 GitHub 設定…", () -> {
            RuntimeConfig cfg = llm.fetchRuntimeConfig(updateSettings);
            store.backupRuntimeConfig();
            store.saveRuntimeConfig(cfg);
            runtimeConfig = cfg;
            boolean kg = applyKaggleConfig(cfg, false);
            ui.post(() -> { current.setText("目前設定版：" + runtimeConfig.version + "\n額外診斷指令：" + runtimeConfig.extraDiagnosticCommands.size()); showTextDialog("已套用設定", "版本：" + cfg.version + "\n\nKaggle 端點：" + (kg ? cfg.kaggleBaseUrl : "未提供") + "\nKaggle 模型：" + cfg.kaggleModels + "\n\n系統提示詞：\n" + cfg.systemPrompt + "\n\n額外診斷指令：\n" + cfg.extraDiagnosticCommands); });
        });});
        row1.addView(save, weight()); row1.addView(fetch, weight()); box.addView(row1);

        Button rollback = button("救援回滾：回到上一份熱更新設定");
        rollback.setOnClickListener(v -> {
            boolean ok = store.rollbackRuntimeConfig();
            runtimeConfig = store.loadRuntimeConfig();
            current.setText("目前設定版：" + runtimeConfig.version + "\n額外診斷指令：" + runtimeConfig.extraDiagnosticCommands.size());
            toast(ok ? "已回滾設定" : "沒有可回滾的上一份設定");
        });
        box.addView(rollback);

        addSection(box, "APK 版本更新與回滾");
        box.addView(label("App 會讀取 GitHub Releases。你可以下載新版 APK；若新版壞掉，可回到 Releases 下載舊版 APK 重新安裝。若 App 已經完全打不開，請直接用瀏覽器進 GitHub Releases 下載舊版，這是最可靠的救援回滾。", 14, false));
        Button releases = button("查看 Releases / 下載 APK");
        releases.setOnClickListener(v -> { save.performClick(); runTask("正在讀取 GitHub Releases…", () -> {
            List<ReleaseInfo> list = llm.listReleases(updateSettings);
            ui.post(() -> openReleaseDialog(list));
        });});
        box.addView(releases);
    }

    private void openReleaseDialog(List<ReleaseInfo> list) {
        if (list.isEmpty()) { showTextDialog("Releases", "這個倉庫沒有 Releases。請用 GitHub Actions 的 Build Release APK 建立版本。 "); return; }
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) items[i] = list.get(i).tag + "｜" + (list.get(i).name.isEmpty() ? "未命名" : list.get(i).name);
        new AlertDialog.Builder(this)
            .setTitle("選擇版本")
            .setItems(items, (d, which) -> {
                ReleaseInfo r = list.get(which);
                String url = r.apkUrl.isEmpty() ? r.pageUrl : r.apkUrl;
                if (url == null || url.isEmpty()) { toast("此版本沒有 APK 或頁面連結"); return; }
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
            })
            .setNegativeButton("取消", null)
            .show();
    }



    private void openChatToolsMenu() {
        new AlertDialog.Builder(this)
            .setTitle("聊天工具")
            .setItems(new String[] {
                "匯出 LOG 回報",
                "查看 LOG 回報內容",
                "清空聊天紀錄",
                "清空本機 LOG"
            }, (d, which) -> {
                if (which == 0) openReportDialog();
                else if (which == 1) showTextDialog("LOG 回報內容", buildSupportReportMarkdown());
                else if (which == 2) confirm("確定清空聊天紀錄？\n\n這會清除目前 App 內的聊天紀錄，但不會刪除 API Key、SSH 設定或模型設定。", () -> {
                    chatMessages.clear();
                    store.clearChat();
                    renderChatLog();
                    appLog("清空聊天紀錄");
                    toast("已清空聊天紀錄");
                });
                else if (which == 3) confirm("確定清空本機 LOG？\n\n這不會清空聊天，也不會刪除 API Key 或 SSH 設定。", () -> {
                    store.clearAppLogs();
                    appLog("LOG 已清空後重新開始記錄");
                    toast("已清空本機 LOG");
                });
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void openReportDialog() {
        new AlertDialog.Builder(this)
            .setTitle("LOG 回報")
            .setItems(new String[] {
                "儲存 Markdown 到下載資料夾（推薦）",
                "儲存 TXT 到下載資料夾",
                "分享 Markdown 報告（備用，可能開新話題）",
                "分享 TXT 報告（備用，可能開新話題）",
                "查看報告內容",
                "清空本機 LOG"
            }, (d, which) -> {
                if (which == 0) saveSupportReportToDownloads("md");
                else if (which == 1) saveSupportReportToDownloads("txt");
                else if (which == 2) shareSupportReport("md");
                else if (which == 3) shareSupportReport("txt");
                else if (which == 4) showTextDialog("LOG 回報內容", buildSupportReportMarkdown());
                else confirm("確定清空本機 LOG？\n\n這不會清空聊天，也不會刪除 API KEY 或 SSH 設定。", () -> { store.clearAppLogs(); appLog("LOG 已清空後重新開始記錄"); toast("已清空本機 LOG"); });
            })
            .setNegativeButton("取消", null)
            .show();
    }

    private void saveSupportReportToDownloads(String ext) {
        try {
            String report = "txt".equals(ext) ? buildSupportReportText() : buildSupportReportMarkdown();
            report = maskSensitive(report);
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.TAIWAN).format(new Date());
            String fileName = "OracleCloudAI_report_" + stamp + "." + ext;
            String mime = "md".equals(ext) ? "text/markdown" : "text/plain";

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OracleCloudAI");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("無法建立下載檔案 URI");

                try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                    if (out == null) throw new IllegalStateException("無法開啟下載檔案輸出串流");
                    out.write(report.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }

                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(uri, values, null, null);

                appLog("REPORT 儲存到下載資料夾｜Download/OracleCloudAI/" + fileName);
                showTextDialog("已儲存 LOG 回報", "已儲存到手機下載資料夾：\n\nDownload/OracleCloudAI/" + fileName + "\n\n請回到原本這個 ChatGPT 對話，用附件上傳這個 .md / .txt 檔案給我。\n\n不要用分享功能，分享可能會開成新話題。");
            } else {
                File base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (base == null) base = new File(getFilesDir(), "reports");
                File dir = new File(base, "OracleCloudAI");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, fileName);
                try (FileOutputStream out = new FileOutputStream(file)) {
                    out.write(report.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                appLog("REPORT 儲存到 App 本機資料夾｜" + file.getAbsolutePath());
                showTextDialog("已儲存 LOG 回報", "已儲存到 App 本機資料夾：\n\n" + file.getAbsolutePath() + "\n\n如果檔案管理器看不到，請使用『查看報告內容』複製文字，或改用分享備用功能。");
            }
        } catch (Exception e) {
            appLog("REPORT 儲存失敗｜" + e.getClass().getSimpleName() + "：" + e.getMessage());
            showTextDialog("儲存失敗", e.getClass().getSimpleName() + "：" + e.getMessage() + "\n\n你仍可選擇『查看報告內容』後複製文字，或使用分享備用功能。");
        }
    }

    private void shareSupportReport(String ext) {
        try {
            String report = "txt".equals(ext) ? buildSupportReportText() : buildSupportReportMarkdown();
            report = maskSensitive(report);
            File dir = new File(getCacheDir(), "reports");
            if (!dir.exists()) dir.mkdirs();
            String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.TAIWAN).format(new Date());
            File file = new File(dir, "OracleCloudAI_report_" + stamp + "." + ext);
            FileOutputStream out = new FileOutputStream(file);
            out.write(report.getBytes(StandardCharsets.UTF_8));
            out.close();

            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", file);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("md".equals(ext) ? "text/markdown" : "text/plain");
            send.putExtra(Intent.EXTRA_SUBJECT, "甲骨文雲端AI 問題回報 " + stamp);
            send.putExtra(Intent.EXTRA_TEXT, "這是甲骨文雲端AI自動產生的問題回報。已自動遮蔽常見 Token / Key。");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            appLog("REPORT 分享備用｜" + file.getName());
            startActivity(Intent.createChooser(send, "分享 LOG 回報（備用）"));
        } catch (Exception e) {
            appLog("REPORT 匯出失敗｜" + e.getClass().getSimpleName() + "：" + e.getMessage());
            showTextDialog("匯出失敗", e.getClass().getSimpleName() + "：" + e.getMessage() + "\n\n你仍可選擇『查看報告內容』後複製文字貼給我。");
        }
    }

    private String buildSupportReportText() {
        return buildSupportReportMarkdown()
            .replace("# ", "")
            .replace("## ", "")
            .replace("```text", "")
            .replace("```", "");
    }

    private String buildSupportReportMarkdown() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 甲骨文雲端AI 問題回報\n\n");
        sb.append("- 產生時間：").append(now()).append(" UTC+8\n");
        sb.append("- App 版本：v1.5.5\n");
        sb.append("- 設定版：").append(runtimeConfig == null ? "未知" : runtimeConfig.version).append("\n\n");

        sb.append("## 目前模型設定\n\n");
        sb.append("- 平台：").append(providerTitle(modelSettings.provider)).append(" (`").append(modelSettings.provider).append("`)\n");
        sb.append("- 模型：").append(emptyDash(modelSettings.modelName)).append("\n");
        sb.append("- Base URL：").append(maskUrl(modelSettings.baseUrl)).append("\n");
        sb.append("- API Key：").append(hasText(modelSettings.apiKey) ? "已填入（已遮蔽）" : "未填入").append("\n");
        sb.append("- Temperature：").append(modelSettings.temperature).append("\n");
        sb.append("- 上下文保留字元數：").append(modelSettings.maxContextCharacters).append("\n\n");

        sb.append("## Oracle SSH / 維修設定\n\n");
        sb.append("- 主機：").append(emptyDash(serverSettings.host)).append("\n");
        sb.append("- Port：").append(serverSettings.port).append("\n");
        sb.append("- 使用者：").append(emptyDash(serverSettings.username)).append("\n");
        sb.append("- 專案路徑：").append(emptyDash(serverSettings.projectPath)).append("\n");
        sb.append("- Service：").append(emptyDash(serverSettings.serviceName)).append("\n");
        sb.append("- Docker Container：").append(emptyDash(serverSettings.dockerContainer)).append("\n");
        sb.append("- SSH 私鑰：").append(hasText(serverSettings.privateKey) ? "已填入（內容不匯出）" : "未填入").append("\n\n");

        sb.append("## GitHub / Kaggle 狀態\n\n");
        sb.append("- GitHub repo：").append(updateSettings.owner).append("/").append(updateSettings.repo).append("\n");
        sb.append("- GitHub Token：").append(hasText(updateSettings.githubToken) ? "已填入（已遮蔽）" : "未填入").append("\n");
        sb.append("- Kaggle 狀態：").append(runtimeConfig.kaggleState).append("\n");
        sb.append("- Kaggle Base URL：").append(maskUrl(runtimeConfig.kaggleBaseUrl)).append("\n");
        sb.append("- Kaggle 最後心跳：").append(emptyDash(runtimeConfig.kaggleLastHeartbeatUtc8)).append("\n");
        sb.append("- Kaggle 估算剩餘：").append(formatMinutes(runtimeConfig.kaggleEstimatedRemainingMinutes)).append("\n\n");

        sb.append("## 最近 App LOG\n\n```text\n");
        sb.append(limit(store.loadAppLogs(), 50000));
        sb.append("\n```\n\n");

        sb.append("## 最近維修紀錄\n\n");
        List<RepairHistory> history = store.loadHistory();
        if (history.isEmpty()) sb.append("無\n\n");
        else {
            int count = 0;
            for (RepairHistory h : history) {
                if (count++ >= 10) break;
                sb.append("### ").append(h.timestamp).append("｜").append(h.title).append("\n\n```text\n");
                sb.append(limit(h.content, 8000)).append("\n```\n\n");
            }
        }

        sb.append("## 最近聊天內容\n\n");
        int start = Math.max(0, chatMessages.size() - 20);
        if (chatMessages.isEmpty()) sb.append("無\n");
        for (int i = start; i < chatMessages.size(); i++) {
            ChatMessage cm = chatMessages.get(i);
            sb.append("### ").append("user".equals(cm.role) ? "你" : "AI").append("\n\n```text\n");
            sb.append(limit(cm.content, 6000)).append("\n```\n\n");
        }

        sb.append("## 備註\n\n");
        sb.append("此報告由 App 自動產生，已嘗試遮蔽常見 GitHub/Kaggle/Google/NVIDIA Token 與私鑰內容。\n");
        return maskSensitive(sb.toString());
    }

    private void appLog(String line) {
        try {
            if (store != null && line != null) store.appendAppLog(now() + "｜" + line);
        } catch (Exception ignored) {}
    }

    private static boolean hasText(String s) { return s != null && s.trim().length() > 0; }

    private static String maskUrl(String s) {
        if (s == null || s.trim().isEmpty()) return "—";
        String t = s.trim();
        if (t.length() <= 80) return t;
        return t.substring(0, 60) + "...";
    }

    private static String maskSensitive(String input) {
        if (input == null) return "";
        String x = input;
        x = x.replaceAll("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----", "[已遮蔽 SSH/PRIVATE KEY]");
        x = x.replaceAll("github_pat_[A-Za-z0-9_]+", "[已遮蔽 GitHub Token]");
        x = x.replaceAll("ghp_[A-Za-z0-9]+", "[已遮蔽 GitHub Token]");
        x = x.replaceAll("KGAT_[A-Za-z0-9]+", "[已遮蔽 Kaggle Token]");
        x = x.replaceAll("AIza[0-9A-Za-z_\\-]{20,}", "[已遮蔽 Google API Key]");
        x = x.replaceAll("nvapi-[0-9A-Za-z_\\-]+", "[已遮蔽 NVIDIA API Key]");
        x = x.replaceAll("sk-[0-9A-Za-z_\\-]{20,}", "[已遮蔽 API Key]");
        return x;
    }

    private void runTask(String begin, Task task) {
        setStatus(begin);
        appLog("TASK 開始｜" + begin);
        bg.submit(() -> {
            try { task.run(); }
            catch (Exception e) {
                appLog("ERROR｜" + begin + "｜" + e.getClass().getSimpleName() + "：" + e.getMessage());
                ui.post(() -> { setStatus("發生錯誤"); showTextDialog("錯誤", e.getClass().getSimpleName() + "：" + e.getMessage()); });
            }
        });
    }

    private interface Task { void run() throws Exception; }
    private interface Confirmed { void run(); }

    private void confirm(String msg, Confirmed yes) {
        new AlertDialog.Builder(this).setTitle("請確認").setMessage(msg).setPositiveButton("確定", (d, w) -> yes.run()).setNegativeButton("取消", null).show();
    }

    private void importSshPrivateKeyFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(Intent.createChooser(intent, "選擇 ssh-key-2026-02-26.key"), REQUEST_IMPORT_SSH_KEY);
        } catch (Exception e) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
            fallback.addCategory(Intent.CATEGORY_OPENABLE);
            fallback.setType("*/*");
            fallback.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(Intent.createChooser(fallback, "選擇 SSH 私鑰檔"), REQUEST_IMPORT_SSH_KEY);
        }
    }

    private String readTextFromUri(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IllegalArgumentException("無法開啟檔案");
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > 512 * 1024) throw new IllegalArgumentException("檔案太大，不像 SSH 私鑰檔");
                out.write(buf, 0, n);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
    }

    private static String normalizeImportedPrivateKey(String raw) {
        if (raw == null) return "";
        String s = raw.replace("\uFEFF", "");
        s = s.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (s.contains("\\n") && !s.contains("\n-----END")) s = s.replace("\\n", "\n");
        if (!s.endsWith("\n")) s += "\n";
        return s;
    }

    private void updateSshKeyStatus(String keyText) {
        if (sshKeyStatusLabel != null) sshKeyStatusLabel.setText(describePrivateKeyForUi(keyText));
    }

    private String describePrivateKeyForUi(String raw) {
        String k = normalizeImportedPrivateKey(raw);
        if (k.trim().isEmpty()) return "私鑰狀態：未填入。請貼上完整私鑰，或按『從檔案匯入 SSH 私鑰』選擇 ssh-key-2026-02-26.key。";
        String[] lines = k.split("\n");
        String first = "";
        String last = "";
        for (String line : lines) if (!line.trim().isEmpty()) { first = line.trim(); break; }
        for (int i = lines.length - 1; i >= 0; i--) if (!lines[i].trim().isEmpty()) { last = lines[i].trim(); break; }
        String type;
        if (first.contains("RSA PRIVATE KEY")) type = "RSA SSH 私鑰，可用於 Oracle Compute SSH";
        else if (first.contains("OPENSSH PRIVATE KEY")) type = "OpenSSH 私鑰，可用於 Oracle Compute SSH";
        else if (first.contains("PRIVATE KEY")) type = "私鑰格式，但不確定是否為 SSH 私鑰";
        else if (first.startsWith("ssh-rsa") || first.startsWith("ssh-ed25519")) type = "這是公鑰 .pub，不是私鑰，不能登入";
        else type = "未辨識格式，可能不是 SSH 私鑰";
        return "私鑰狀態：已填入\n" +
               "偵測格式：" + type + "\n" +
               "第一行：" + maskForUi(first) + "\n" +
               "最後一行：" + maskForUi(last) + "\n" +
               "行數：約 " + lines.length + " 行｜長度：約 " + k.length() + " 字元\n" +
               "BEGIN：" + (k.contains("-----BEGIN ") ? "有" : "沒有") + "｜END：" + (k.contains("-----END ") ? "有" : "沒有");
    }

    private static String maskForUi(String line) {
        if (line == null || line.length() == 0) return "空";
        if (line.length() <= 24) return line;
        return line.substring(0, 16) + "..." + line.substring(line.length() - 10);
    }

    private void showTextDialog(String title, String text) {
        appLog("DIALOG｜" + title + "｜" + limit(text == null ? "" : text, 1800));
        TextView tv = label(text == null ? "" : text, 13, false);
        tv.setTextIsSelectable(true);
        tv.setPadding(dp(12), dp(12), dp(12), dp(12));
        ScrollView sv = new ScrollView(this); sv.addView(tv);
        new AlertDialog.Builder(this).setTitle(title).setView(sv).setPositiveButton("關閉", null).show();
    }

    private void addSection(LinearLayout box, String title) {
        TextView tv = label(title, 18, true);
        tv.setPadding(0, dp(14), 0, dp(6));
        box.addView(tv);
    }

    private TextView label(String text, int sp, boolean bold) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(sp);
        tv.setTextColor(0xff222222);
        if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(4), 0, dp(4));
        return tv;
    }

    private EditText edit(String hint, boolean multi) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setTextSize(14);
        e.setSingleLine(!multi);
        if (multi) { e.setGravity(Gravity.TOP | Gravity.START); e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE); }
        e.setPadding(dp(8), dp(8), dp(8), dp(8));
        return e;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout row() { LinearLayout r = new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); return r; }
    private LinearLayout.LayoutParams weight() { return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1); }
    private int dp(int n) { return (int) (n * getResources().getDisplayMetrics().density + 0.5f); }
    private void setStatus(String s) { if (status != null) status.setText(s); appLog("STATUS｜" + s); }
    private void toast(String s) { appLog("TOAST｜" + s); Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private int reasoningIndex(String code) {
        if ("medium".equals(code)) return 1;
        if ("low".equals(code)) return 2;
        if ("none".equals(code)) return 3;
        return 0;
    }

    private int providerIndex(String code) {
        for (int i = 0; i < providerCodes.length; i++) if (providerCodes[i].equals(code)) return i;
        return 0;
    }
    private String providerTitle(String code) { return providerLabels[providerIndex(code)]; }

    private boolean isKaggleEndpointMissing(ModelSettings m) {
        if (m == null || !"kaggle".equals(m.provider)) return false;
        String b = m.baseUrl == null ? "" : m.baseUrl.trim();
        return b.length() == 0 || b.contains("你的-kaggle") || b.contains("example.com") || b.contains("隧道網址");
    }

    private void autoSyncKaggleEndpointQuietly() {
        if (!isKaggleEndpointMissing(store.loadModelFor("kaggle"))) return;
        bg.submit(() -> {
            try {
                RuntimeConfig cfg = llm.fetchRuntimeConfig(updateSettings);
                store.saveRuntimeConfig(cfg);
                runtimeConfig = cfg;
                boolean ok = applyKaggleConfig(cfg, false);
                if (ok) ui.post(() -> setStatus("已自動同步 Kaggle 端點"));
            } catch (Exception ignored) {}
        });
    }

    private boolean applyKaggleConfig(RuntimeConfig cfg, boolean switchToKaggle) {
        if (cfg == null) return false;
        String base = cfg.kaggleBaseUrl == null ? "" : cfg.kaggleBaseUrl.trim();
        if (base.length() == 0 || base.contains("your-") || base.contains("你的")) return false;
        ModelSettings kg = store.loadModelFor("kaggle");
        kg.provider = "kaggle";
        kg.baseUrl = base.replaceAll("/+$", "");
        if (cfg.kaggleApiKey != null && cfg.kaggleApiKey.trim().length() > 0) kg.apiKey = cfg.kaggleApiKey.trim();
        if (cfg.kaggleDefaultModel != null && cfg.kaggleDefaultModel.trim().length() > 0) kg.modelName = cfg.kaggleDefaultModel.trim();
        store.saveModel(kg);
        List<ModelOption> models = new ArrayList<>();
        for (String id : cfg.kaggleModels) {
            if (id != null && id.trim().length() > 0) models.add(new ModelOption(id.trim(), id.trim(), "GitHub 設定檔同步"));
        }
        if (!models.isEmpty()) {
            store.saveCatalog("kaggle", models);
            store.saveFavorites("kaggle", models);
        }
        if (switchToKaggle || "kaggle".equals(modelSettings.provider)) modelSettings = kg;
        return true;
    }

    private ModelSettings defaultsFor(String provider) {
        ModelSettings m = new ModelSettings();
        m.provider = provider;
        if ("gemini".equals(provider)) { m.baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai"; m.modelName = "gemini-2.5-flash"; }
        else if ("nim".equals(provider)) { m.baseUrl = "https://integrate.api.nvidia.com/v1"; m.modelName = "meta/llama-3.1-70b-instruct"; }
        else if ("kaggle".equals(provider)) { m.baseUrl = ""; m.modelName = "Qwen/Qwen3.6-27B"; }
        else { m.baseUrl = "https://example.com/v1"; m.modelName = "your-model-name"; }
        return m;
    }

    private static int parseInt(String v, int def) { try { return Integer.parseInt(v.trim()); } catch (Exception e) { return def; } }
    private static double parseDouble(String v, double def) { try { return Double.parseDouble(v.trim()); } catch (Exception e) { return def; } }
    private static String now() { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.TAIWAN).format(new Date()); }
    private static String limit(String s, int max) { return s == null ? "" : (s.length() > max ? s.substring(0, max) + "\n...已截斷..." : s); }
    private static String stripCodeFence(String s) {
        if (s == null) return "";
        String t = s.trim();
        if (t.startsWith("```")) {
            int first = t.indexOf('\n');
            int last = t.lastIndexOf("```");
            if (first >= 0 && last > first) return t.substring(first + 1, last).trim();
        }
        return s;
    }
}
