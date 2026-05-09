package com.oracleairescue;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import android.app.Activity;

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
    private EditText chatInput;
    private static final int REQUEST_IMPORT_SSH_KEY = 8801;
    private EditText sshPrivateKeyEditor;
    private TextView sshKeyStatusLabel;

    private final String[] providerLabels = new String[] {"Google Gemini", "NVIDIA NIM", "Kaggle Qwen / OpenAI 相容", "自訂 OpenAI 相容"};
    private final String[] providerCodes = new String[] {"gemini", "nim", "kaggle", "custom"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SecureStore(this);
        modelSettings = store.loadModel();
        serverSettings = store.loadServer();
        updateSettings = store.loadUpdateSettings();
        runtimeConfig = store.loadRuntimeConfig();
        chatMessages.addAll(store.loadChat());
        showShell("聊天");
        showChatPage();
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
        title.setText("甲骨文雲端AI  v1.3.3");
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextSize(20);
        title.setPadding(dp(12), dp(12), dp(12), dp(4));
        root.addView(title);

        status = new TextView(this);
        status.setText("就緒｜供應商：" + providerTitle(modelSettings.provider) + "｜模型：" + modelSettings.modelName + "｜設定版：" + runtimeConfig.version);
        status.setPadding(dp(12), 0, dp(12), dp(8));
        root.addView(status);

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(6), dp(4), dp(6), dp(4));
        root.addView(nav);
        addNav(nav, "聊天", selected, v -> showChatPage());
        addNav(nav, "設定", selected, v -> showSettingsPage());
        addNav(nav, "Oracle", selected, v -> showServerPage());
        addNav(nav, "維修", selected, v -> showRepairPage());
        addNav(nav, "更新", selected, v -> showUpdatePage());
    }

    private void addNav(LinearLayout nav, String text, String selected, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text.equals(selected) ? "● " + text : text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        nav.addView(b, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
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
        LinearLayout box = page("聊天");
        addSection(box, "聊天與上下文");
        TextView hint = label("聊天會自動帶入最近上下文。若你前一句要求規劃，下一句說『實行』，App 會把前文一起送給模型。", 14, false);
        box.addView(hint);

        chatModelSpinner = new Spinner(this);
        refreshChatModelSpinner();
        box.addView(chatModelSpinner);

        chatLogView = label("", 14, false);
        chatLogView.setTextIsSelectable(true);
        chatLogView.setMovementMethod(new ScrollingMovementMethod());
        chatLogView.setBackgroundColor(0xffffffff);
        chatLogView.setPadding(dp(10), dp(10), dp(10), dp(10));
        box.addView(chatLogView, new LinearLayout.LayoutParams(-1, dp(360)));
        renderChatLog();

        chatInput = edit("輸入訊息，例如：幫我檢查 Oracle AI 助理為什麼故障", true);
        chatInput.setMinLines(3);
        box.addView(chatInput);

        LinearLayout row = row();
        Button send = button("送出");
        send.setOnClickListener(v -> sendChat());
        Button clear = button("清空聊天");
        clear.setOnClickListener(v -> confirm("確定清空聊天紀錄？", () -> {
            chatMessages.clear(); store.clearChat(); renderChatLog(); toast("已清空");
        }));
        row.addView(send, weight());
        row.addView(clear, weight());
        box.addView(row);
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
        chatInput.setText("");
        chatMessages.add(new ChatMessage("user", text));
        renderChatLog();
        List<ChatMessage> payload = buildContextMessages(runtimeConfig.systemPrompt, chatMessages, modelSettings.maxContextCharacters);
        runTask("正在呼叫模型…", () -> {
            String reply = llm.sendChat(modelSettings.copy(), payload);
            ui.post(() -> {
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
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : chatMessages) {
            sb.append("user".equals(m.role) ? "你：\n" : "AI：\n").append(m.content).append("\n\n");
        }
        chatLogView.setText(sb.length() == 0 ? "尚無聊天紀錄。" : sb.toString());
    }

    private void showSettingsPage() {
        LinearLayout box = page("設定");
        addSection(box, "模型平台與常用模型");
        Spinner providerSpinner = new Spinner(this);
        providerSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, providerLabels));
        providerSpinner.setSelection(providerIndex(modelSettings.provider));
        box.addView(providerSpinner);

        EditText apiKey = edit("API Key，Kaggle 若無驗證可留空", false);
        apiKey.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        apiKey.setText(modelSettings.apiKey);
        box.addView(apiKey);

        EditText baseUrl = edit("Base URL，例如 https://integrate.api.nvidia.com/v1 或 Kaggle 隧道 /v1", false);
        baseUrl.setText(modelSettings.baseUrl);
        box.addView(baseUrl);

        EditText modelName = edit("目前模型名稱", false);
        modelName.setText(modelSettings.modelName);
        box.addView(modelName);

        EditText temperature = edit("Temperature，例如 0.2", false);
        temperature.setText(String.valueOf(modelSettings.temperature));
        box.addView(temperature);

        EditText context = edit("上下文保留字元數，預設 60000", false);
        context.setInputType(InputType.TYPE_CLASS_NUMBER);
        context.setText(String.valueOf(modelSettings.maxContextCharacters));
        box.addView(context);

        TextView catalogInfo = label("模型清單：" + store.loadCatalog(modelSettings.provider).size() + "｜常用模型：" + store.loadFavorites(modelSettings.provider).size(), 14, false);
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
                    catalogInfo.setText("模型清單：" + store.loadCatalog(code).size() + "｜常用模型：" + store.loadFavorites(code).size());
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
                    catalogInfo.setText("模型清單：" + models.size() + "｜常用模型：" + store.loadFavorites(modelSettings.provider).size());
                    setStatus("已取得 " + models.size() + " 個模型");
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
            catalogInfo.setText("模型清單：" + store.loadCatalog(modelSettings.provider).size() + "｜常用模型：" + fav.size());
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

        addSection(box, "Kaggle Qwen 設定提示");
        box.addView(label("Kaggle 端若用 vLLM/FastAPI，請把 Base URL 設為你的 ngrok/cloudflared 隧道網址加 /v1，例如：https://xxxx.ngrok-free.app/v1。模型名稱必須與 Kaggle 伺服器啟動時一致，例如 Qwen/Qwen2.5-7B-Instruct、你自己的 Qwen 27B/35B 名稱。若沒有 API Key 可留空。", 14, false));
    }

    private void openFavoritesDialog(EditText modelName, TextView catalogInfo) {
        List<ModelOption> catalog = store.loadCatalog(modelSettings.provider);
        if (catalog.isEmpty()) {
            String current = modelName.getText().toString().trim();
            if (current.isEmpty()) { toast("沒有模型清單，請先取得模型或手動輸入模型名稱。 "); return; }
            List<ModelOption> fav = store.loadFavorites(modelSettings.provider);
            fav.add(new ModelOption(current, current, "手動加入"));
            store.saveFavorites(modelSettings.provider, fav);
            toast("已加入常用模型");
            return;
        }
        List<ModelOption> fav = store.loadFavorites(modelSettings.provider);
        Set<String> favIds = new HashSet<>();
        for (ModelOption m : fav) favIds.add(m.id);
        String[] items = new String[catalog.size()];
        boolean[] checked = new boolean[catalog.size()];
        for (int i = 0; i < catalog.size(); i++) {
            ModelOption m = catalog.get(i);
            items[i] = m.id + (m.description.isEmpty() ? "" : "\n" + m.description);
            checked[i] = favIds.contains(m.id);
        }
        new AlertDialog.Builder(this)
            .setTitle("勾選常用模型")
            .setMultiChoiceItems(items, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
            .setPositiveButton("儲存", (d, w) -> {
                List<ModelOption> selected = new ArrayList<>();
                for (int i = 0; i < catalog.size(); i++) if (checked[i]) selected.add(catalog.get(i));
                store.saveFavorites(modelSettings.provider, selected);
                if (!selected.isEmpty()) {
                    modelSettings.modelName = selected.get(0).id;
                    modelName.setText(modelSettings.modelName);
                    store.saveModel(modelSettings);
                }
                catalogInfo.setText("模型清單：" + catalog.size() + "｜常用模型：" + selected.size());
                toast("已儲存常用模型");
            })
            .setNegativeButton("取消", null)
            .show();
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
        box.addView(label("這裡會從你的 GitHub 倉庫讀取 oracle-ai-rescue-config.json，可更新系統提示詞與額外診斷指令。若更新造成問題，可一鍵回滾到上一份設定。", 14, false));
        EditText owner = edit("GitHub owner，例如你的帳號", false); owner.setText(updateSettings.owner); box.addView(owner);
        EditText repo = edit("Repository 名稱", false); repo.setText(updateSettings.repo); box.addView(repo);
        EditText branch = edit("分支，通常 main", false); branch.setText(updateSettings.branch); box.addView(branch);
        EditText configPath = edit("設定檔路徑", false); configPath.setText(updateSettings.configPath); box.addView(configPath);
        TextView current = label("目前設定版：" + runtimeConfig.version + "\n額外診斷指令：" + runtimeConfig.extraDiagnosticCommands.size(), 14, false);
        box.addView(current);

        LinearLayout row1 = row();
        Button save = button("儲存 GitHub 設定");
        save.setOnClickListener(v -> {
            updateSettings.owner = owner.getText().toString().trim();
            updateSettings.repo = repo.getText().toString().trim();
            updateSettings.branch = branch.getText().toString().trim();
            updateSettings.configPath = configPath.getText().toString().trim();
            store.saveUpdateSettings(updateSettings);
            toast("已儲存");
        });
        Button fetch = button("抓取並套用設定");
        fetch.setOnClickListener(v -> { save.performClick(); runTask("正在抓取 GitHub 設定…", () -> {
            RuntimeConfig cfg = llm.fetchRuntimeConfig(updateSettings);
            store.backupRuntimeConfig();
            store.saveRuntimeConfig(cfg);
            runtimeConfig = cfg;
            ui.post(() -> { current.setText("目前設定版：" + runtimeConfig.version + "\n額外診斷指令：" + runtimeConfig.extraDiagnosticCommands.size()); showTextDialog("已套用設定", "版本：" + cfg.version + "\n\n系統提示詞：\n" + cfg.systemPrompt + "\n\n額外診斷指令：\n" + cfg.extraDiagnosticCommands); });
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

    private void runTask(String begin, Task task) {
        setStatus(begin);
        bg.submit(() -> {
            try { task.run(); }
            catch (Exception e) { ui.post(() -> { setStatus("發生錯誤"); showTextDialog("錯誤", e.getClass().getSimpleName() + "：" + e.getMessage()); }); }
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
    private void setStatus(String s) { if (status != null) status.setText(s); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private int providerIndex(String code) {
        for (int i = 0; i < providerCodes.length; i++) if (providerCodes[i].equals(code)) return i;
        return 0;
    }
    private String providerTitle(String code) { return providerLabels[providerIndex(code)]; }

    private ModelSettings defaultsFor(String provider) {
        ModelSettings m = new ModelSettings();
        m.provider = provider;
        if ("gemini".equals(provider)) { m.baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai"; m.modelName = "gemini-2.5-flash"; }
        else if ("nim".equals(provider)) { m.baseUrl = "https://integrate.api.nvidia.com/v1"; m.modelName = "meta/llama-3.1-70b-instruct"; }
        else if ("kaggle".equals(provider)) { m.baseUrl = "https://你的-kaggle-隧道網址/v1"; m.modelName = "Qwen/Qwen2.5-7B-Instruct"; }
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
