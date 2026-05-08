package com.oracleairescue

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OracleAIRescueApp(SecureStore(this))
        }
    }
}

private enum class Tab(val title: String) {
    Chat("聊天"), Diagnosis("診斷"), Files("修檔"), Settings("設定"), History("紀錄")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OracleAIRescueApp(store: SecureStore) {
    var modelSettings by remember { mutableStateOf(store.loadModelSettings()) }
    var serverSettings by remember { mutableStateOf(store.loadServerSettings()) }
    var selectedTab by remember { mutableStateOf(Tab.Chat) }
    var historyRefreshKey by remember { mutableStateOf(0) }
    var favoritesRefreshKey by remember { mutableStateOf(0) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = {
                            Column {
                                Text("Oracle AI Rescue", fontWeight = FontWeight.Bold)
                                Text("手機直連 LLM API 與 Oracle SSH 的救援工具", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    )
                },
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            selected = selectedTab == Tab.Chat,
                            onClick = { selectedTab = Tab.Chat },
                            icon = { Icon(Icons.Default.Chat, contentDescription = null) },
                            label = { Text(Tab.Chat.title) }
                        )
                        NavigationBarItem(
                            selected = selectedTab == Tab.Diagnosis,
                            onClick = { selectedTab = Tab.Diagnosis },
                            icon = { Icon(Icons.Default.Build, contentDescription = null) },
                            label = { Text(Tab.Diagnosis.title) }
                        )
                        NavigationBarItem(
                            selected = selectedTab == Tab.Files,
                            onClick = { selectedTab = Tab.Files },
                            icon = { Icon(Icons.Default.Description, contentDescription = null) },
                            label = { Text(Tab.Files.title) }
                        )
                        NavigationBarItem(
                            selected = selectedTab == Tab.Settings,
                            onClick = { selectedTab = Tab.Settings },
                            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                            label = { Text(Tab.Settings.title) }
                        )
                        NavigationBarItem(
                            selected = selectedTab == Tab.History,
                            onClick = { selectedTab = Tab.History },
                            icon = { Icon(Icons.Default.History, contentDescription = null) },
                            label = { Text(Tab.History.title) }
                        )
                    }
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .imePadding()
                ) {
                    when (selectedTab) {
                        Tab.Chat -> ChatScreen(
                            modelSettings = modelSettings,
                            store = store,
                            favoritesRefreshKey = favoritesRefreshKey,
                            onModelChanged = { updated ->
                                modelSettings = updated
                                store.saveModelSettings(updated)
                            }
                        )
                        Tab.Diagnosis -> DiagnosisScreen(
                            modelSettings = modelSettings,
                            serverSettings = serverSettings,
                            onHistory = {
                                store.appendHistory(it)
                                historyRefreshKey++
                            }
                        )
                        Tab.Files -> FileRepairScreen(
                            modelSettings = modelSettings,
                            serverSettings = serverSettings,
                            onHistory = {
                                store.appendHistory(it)
                                historyRefreshKey++
                            }
                        )
                        Tab.Settings -> SettingsScreen(
                            initialModel = modelSettings,
                            initialServer = serverSettings,
                            loadCatalog = { provider -> store.loadModelCatalog(provider) },
                            loadFavorites = { provider -> store.loadFavoriteModels(provider) },
                            onSave = { m, s, catalog, favorites ->
                                modelSettings = m
                                serverSettings = s
                                store.saveModelSettings(m)
                                store.saveServerSettings(s)
                                store.saveModelCatalog(m.provider, catalog)
                                store.saveFavoriteModels(m.provider, favorites)
                                favoritesRefreshKey++
                            }
                        )
                        Tab.History -> HistoryScreen(
                            key = historyRefreshKey,
                            store = store,
                            onCleared = { historyRefreshKey++ }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatScreen(
    modelSettings: ModelSettings,
    store: SecureStore,
    favoritesRefreshKey: Int,
    onModelChanged: (ModelSettings) -> Unit
) {
    val scope = rememberCoroutineScope()
    val llm = remember { LlmClient() }
    val favorites = remember(modelSettings.provider, favoritesRefreshKey) { store.loadFavoriteModels(modelSettings.provider) }
    val messages = remember {
        mutableStateListOf<ChatMessage>().apply {
            val saved = store.loadChatMessages()
            if (saved.isEmpty()) {
                add(ChatMessage("system", RepairPrompts.systemPrompt))
            } else {
                if (saved.none { it.role == "system" }) add(ChatMessage("system", RepairPrompts.systemPrompt))
                addAll(saved.filter { it.role in setOf("system", "user", "assistant") })
            }
        }
    }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val modelChoices = if (favorites.isNotEmpty()) favorites else listOf(ModelOption(modelSettings.modelName, modelSettings.modelName, "目前手動輸入的模型"))

    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (error.isNotBlank()) ErrorCard(error) { error = "" }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("目前模型", fontWeight = FontWeight.Bold)
                ModelSelector(modelSettings.modelName, modelChoices) { selected ->
                    onModelChanged(modelSettings.copy(modelName = selected.id))
                }
                Text(
                    if (favorites.isEmpty()) "尚未設定常用模型；請到設定頁取得模型清單並勾選常用模型。" else "只顯示你在設定頁勾選的常用模型。",
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages.filter { it.role != "system" }) { message ->
                MessageBubble(message)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = {
                messages.clear()
                messages += ChatMessage("system", RepairPrompts.systemPrompt)
                store.clearChatMessages()
            }) { Text("清除對話") }
            Text("對話會保存在手機端；送出時會帶入最近上下文。", style = MaterialTheme.typography.labelSmall)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                minLines = 2,
                maxLines = 6,
                label = { Text("輸入問題") },
                placeholder = { Text("例如：剛剛那份規劃請直接實行，並沿用前面的目標。") }
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = !busy && input.isNotBlank(),
                onClick = {
                    val user = input.trim()
                    input = ""
                    messages += ChatMessage("user", user)
                    store.saveChatMessages(messages.toList())
                    busy = true
                    scope.launch {
                        runCatching {
                            val context = buildChatContext(messages.toList(), modelSettings.maxContextCharacters)
                            llm.sendChat(modelSettings, context)
                        }.onSuccess { reply ->
                            messages += ChatMessage("assistant", reply)
                            store.saveChatMessages(messages.toList())
                        }.onFailure { e ->
                            error = e.message ?: e.toString()
                        }
                        busy = false
                    }
                }
            ) { Text(if (busy) "送出中" else "送出") }
        }
    }
}

@Composable
private fun DiagnosisScreen(
    modelSettings: ModelSettings,
    serverSettings: ServerSettings,
    onHistory: (RepairHistory) -> Unit
) {
    val scope = rememberCoroutineScope()
    val llm = remember { LlmClient() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var rawDiagnostics by remember { mutableStateOf("") }
    var aiAnalysis by remember { mutableStateOf("") }
    var manualCommand by remember { mutableStateOf("") }
    var commandOutput by remember { mutableStateOf("") }
    var pendingCommand by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        WarningCard("診斷功能預設只執行讀取狀態與讀取 log 的指令。任何修復指令都需要你在畫面上確認後才會執行。")
        if (error.isNotBlank()) ErrorCard(error) { error = "" }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy, onClick = {
                busy = true
                rawDiagnostics = ""
                aiAnalysis = ""
                scope.launch {
                    runCatching {
                        val runner = SshClient(serverSettings)
                        val results = runner.runCommands(DiagnosticCommands.build(serverSettings), 60_000)
                        val raw = results.joinToString("\n\n") { it.asText() }
                        rawDiagnostics = raw
                        val reply = llm.sendChat(
                            modelSettings,
                            listOf(
                                ChatMessage("system", RepairPrompts.systemPrompt),
                                ChatMessage("user", RepairPrompts.diagnosisPrompt(raw.take(60_000)))
                            )
                        )
                        aiAnalysis = reply
                        onHistory(RepairHistory(now(), "一鍵診斷", reply))
                    }.onFailure { e -> error = e.message ?: e.toString() }
                    busy = false
                }
            }) { Text(if (busy) "診斷中" else "一鍵診斷並交給 AI 分析") }

            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching { SshClient(serverSettings).testConnection() }
                        .onSuccess { commandOutput = it.asText() }
                        .onFailure { e -> error = e.message ?: e.toString() }
                    busy = false
                }
            }) { Text("測試 SSH") }
        }

        SectionTitle("AI 分析")
        OutputBox(aiAnalysis.ifBlank { "尚未分析。" })

        SectionTitle("原始診斷資料")
        OutputBox(rawDiagnostics.ifBlank { "尚未收集診斷資料。" })

        SectionTitle("經確認後執行單一指令")
        OutlinedTextField(
            value = manualCommand,
            onValueChange = { manualCommand = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 5,
            label = { Text("指令") },
            placeholder = { Text("例如：sudo systemctl restart 你的服務名稱") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            ElevatedButton(enabled = manualCommand.isNotBlank() && !busy, onClick = {
                pendingCommand = manualCommand.trim()
            }) { Text("檢查並準備執行") }
            Text("高風險指令會被阻擋，除非你在設定頁允許 sudo 且仍需確認。", style = MaterialTheme.typography.labelSmall)
        }
        OutputBox(commandOutput.ifBlank { "尚未執行指令。" })
    }

    pendingCommand?.let { command ->
        val sudoBlocked = command.trim().startsWith("sudo ") && !serverSettings.allowSudoCommands
        val dangerous = RepairSafety.isDangerous(command) || sudoBlocked
        AlertDialog(
            onDismissRequest = { pendingCommand = null },
            title = { Text(if (dangerous) "已阻擋高風險指令" else "確認執行指令") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(command, fontFamily = FontFamily.Monospace)
                    if (sudoBlocked) Text("設定頁尚未允許 sudo 指令，App 不會執行。")
                    else if (dangerous) Text("這個指令符合高風險規則，App 不會執行。")
                    else Text("確認後，App 會透過 SSH 在 Oracle Cloud 上執行這個指令。")
                }
            },
            confirmButton = {
                if (!dangerous) {
                    Button(onClick = {
                        pendingCommand = null
                        busy = true
                        scope.launch {
                            runCatching { SshClient(serverSettings).runCommand(command, 90_000) }
                                .onSuccess {
                                    commandOutput = it.asText()
                                    onHistory(RepairHistory(now(), "執行指令", it.asText()))
                                }
                                .onFailure { e -> error = e.message ?: e.toString() }
                            busy = false
                        }
                    }) { Text("確認執行") }
                }
            },
            dismissButton = { TextButton(onClick = { pendingCommand = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun FileRepairScreen(
    modelSettings: ModelSettings,
    serverSettings: ServerSettings,
    onHistory: (RepairHistory) -> Unit
) {
    val scope = rememberCoroutineScope()
    val llm = remember { LlmClient() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("") }
    var instruction by remember { mutableStateOf("") }
    var original by remember { mutableStateOf("") }
    var modified by remember { mutableStateOf("") }
    var diff by remember { mutableStateOf("") }
    var backupInfo by remember { mutableStateOf("") }
    var confirmWrite by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        WarningCard("修檔流程會先讀取檔案，再讓 AI 產生完整新檔案，顯示差異後才允許你備份並寫回。")
        if (error.isNotBlank()) ErrorCard(error) { error = "" }

        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("遠端檔案路徑") },
            placeholder = { Text("/home/ubuntu/my-ai-bot/main.py") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = path.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching { SshClient(serverSettings).readFile(path.trim()) }
                        .onSuccess {
                            original = it
                            modified = ""
                            diff = ""
                            backupInfo = "已讀取檔案：${it.length} 字元"
                        }
                        .onFailure { e -> error = e.message ?: e.toString() }
                    busy = false
                }
            }) { Text(if (busy) "處理中" else "讀取檔案") }
        }

        OutlinedTextField(
            value = instruction,
            onValueChange = { instruction = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 8,
            label = { Text("你想讓 AI 怎麼修") },
            placeholder = { Text("例如：修正啟動時 API key 讀不到的問題，保留原本功能。") }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ElevatedButton(enabled = original.isNotBlank() && instruction.isNotBlank() && !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching {
                        val reply = llm.sendChat(
                            modelSettings,
                            listOf(
                                ChatMessage("system", RepairPrompts.systemPrompt),
                                ChatMessage("user", RepairPrompts.fileRepairPrompt(path.trim(), original.take(80_000), instruction.trim()))
                            )
                        )
                        DiffUtil.stripCodeFence(reply)
                    }.onSuccess { newContent ->
                        modified = newContent
                        diff = DiffUtil.unifiedDiff(original, newContent)
                    }.onFailure { e -> error = e.message ?: e.toString() }
                    busy = false
                }
            }) { Text("讓 AI 產生修改") }

            OutlinedButton(enabled = modified.isNotBlank() && !busy, onClick = { confirmWrite = true }) {
                Text("備份並寫回")
            }
        }

        if (backupInfo.isNotBlank()) Text(backupInfo, style = MaterialTheme.typography.bodySmall)

        SectionTitle("修改差異")
        OutputBox(diff.ifBlank { "尚未產生修改差異。" })

        SectionTitle("原檔案預覽")
        OutputBox(original.ifBlank { "尚未讀取。" }.take(20_000))

        SectionTitle("修改後預覽")
        OutputBox(modified.ifBlank { "尚未產生。" }.take(20_000))
    }

    if (confirmWrite) {
        AlertDialog(
            onDismissRequest = { confirmWrite = false },
            title = { Text("確認寫回遠端檔案") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("App 會先建立備份檔，再覆寫：")
                    Text(path, fontFamily = FontFamily.Monospace)
                    Text("請確認差異內容合理。")
                }
            },
            confirmButton = {
                Button(onClick = {
                    confirmWrite = false
                    busy = true
                    scope.launch {
                        runCatching { SshClient(serverSettings).writeFileWithBackup(path.trim(), modified) }
                            .onSuccess { backupPath ->
                                backupInfo = "已寫回。原檔備份：$backupPath"
                                onHistory(RepairHistory(now(), "修正檔案 $path", "備份：$backupPath\n\n$diff"))
                            }
                            .onFailure { e -> error = e.message ?: e.toString() }
                        busy = false
                    }
                }) { Text("確認備份並寫回") }
            },
            dismissButton = { TextButton(onClick = { confirmWrite = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SettingsScreen(
    initialModel: ModelSettings,
    initialServer: ServerSettings,
    loadCatalog: (String) -> List<ModelOption>,
    loadFavorites: (String) -> List<ModelOption>,
    onSave: (ModelSettings, ServerSettings, List<ModelOption>, List<ModelOption>) -> Unit
) {
    val scope = rememberCoroutineScope()
    val llm = remember { LlmClient() }
    var model by remember { mutableStateOf(initialModel) }
    var server by remember { mutableStateOf(initialServer) }
    var saved by remember { mutableStateOf(false) }
    var showApiKey by remember { mutableStateOf(false) }
    var showPassword by remember { mutableStateOf(false) }
    var modelOptions by remember { mutableStateOf(loadCatalog(initialModel.provider)) }
    var favoriteModels by remember { mutableStateOf(loadFavorites(initialModel.provider)) }
    var modelFilter by remember { mutableStateOf("") }
    var modelFetchMessage by remember { mutableStateOf("") }
    var modelFetchBusy by remember { mutableStateOf(false) }

    LaunchedEffect(model.provider) {
        modelOptions = loadCatalog(model.provider)
        favoriteModels = loadFavorites(model.provider)
        modelFilter = ""
        modelFetchMessage = ""
        model = when (model.provider) {
            "gemini" -> model.copy(
                baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
                modelName = if (model.modelName.isBlank() || model.provider != initialModel.provider) "gemini-2.5-flash" else model.modelName
            )
            "nvidia" -> model.copy(
                baseUrl = "https://integrate.api.nvidia.com/v1",
                modelName = if (model.modelName.isBlank() || model.provider != initialModel.provider) "meta/llama-3.3-70b-instruct" else model.modelName
            )
            else -> model
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        WarningCard("金鑰不需要寫死進程式，也不要交給任何聊天 AI。你在這裡輸入一次並按儲存後，App 會存在手機的加密儲存區，下次開啟會自動帶入。")
        SectionTitle("模型設定")
        ProviderSelector(model.provider) { provider -> model = modelForProvider(model, provider) }
        SecretTextField("API Key", model.apiKey, { model = model.copy(apiKey = it) }, showApiKey) { showApiKey = !showApiKey }
        OutlinedTextField(model.baseUrl, { model = model.copy(baseUrl = it) }, modifier = Modifier.fillMaxWidth(), label = { Text("Base URL") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                model.modelName,
                { model = model.copy(modelName = it) },
                modifier = Modifier.weight(1f),
                label = { Text("Model Name") }
            )
            ElevatedButton(
                enabled = !modelFetchBusy && model.apiKey.isNotBlank() && model.baseUrl.isNotBlank(),
                onClick = {
                    modelFetchBusy = true
                    modelFetchMessage = "正在向官方 API 取得模型清單..."
                    scope.launch {
                        runCatching { llm.listModels(model) }
                            .onSuccess { models ->
                                modelOptions = models
                                if (favoriteModels.isEmpty()) {
                                    favoriteModels = models.filter { it.id == model.modelName }.take(1)
                                }
                                modelFetchMessage = if (models.isEmpty()) {
                                    "沒有取得模型清單。你可以手動輸入模型名稱。"
                                } else {
                                    "已取得 ${models.size} 個模型。你可以勾選常用模型，之後聊天頁只顯示你勾選的模型。"
                                }
                            }
                            .onFailure { e ->
                                modelOptions = emptyList()
                                modelFetchMessage = e.message ?: e.toString()
                            }
                        modelFetchBusy = false
                    }
                }
            ) { Text(if (modelFetchBusy) "取得中" else "取得模型") }
        }
        if (modelFetchMessage.isNotBlank()) Text(modelFetchMessage, style = MaterialTheme.typography.bodySmall)

        if (favoriteModels.isNotEmpty()) {
            SectionTitle("目前使用模型")
            ModelSelector(model.modelName, favoriteModels) { selected ->
                model = model.copy(modelName = selected.id)
            }
        } else if (modelOptions.isNotEmpty()) {
            SectionTitle("目前使用模型")
            ModelSelector(model.modelName, modelOptions) { selected ->
                model = model.copy(modelName = selected.id)
            }
        }

        FavoriteModelChooser(
            allModels = modelOptions,
            favorites = favoriteModels,
            filter = modelFilter,
            onFilterChange = { modelFilter = it },
            onToggle = { option, checked ->
                favoriteModels = if (checked) {
                    (favoriteModels + option).distinctBy { it.id }
                } else {
                    favoriteModels.filterNot { it.id == option.id }
                }
                if (checked) model = model.copy(modelName = option.id)
            },
            onUseNow = { option -> model = model.copy(modelName = option.id) }
        )

        OutlinedTextField(model.temperature.toString(), { model = model.copy(temperature = it.toDoubleOrNull() ?: model.temperature) }, modifier = Modifier.fillMaxWidth(), label = { Text("Temperature") })
        OutlinedTextField(
            model.maxContextCharacters.toString(),
            { model = model.copy(maxContextCharacters = it.toIntOrNull()?.coerceIn(8_000, 200_000) ?: model.maxContextCharacters) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("聊天上下文保留字元數") },
            supportingText = { Text("建議 60000。數字越大越不容易忘記前文，但 API 成本與失敗機率也可能提高。") }
        )

        Divider()
        SectionTitle("Oracle SSH 設定")
        OutlinedTextField(server.name, { server = server.copy(name = it) }, modifier = Modifier.fillMaxWidth(), label = { Text("主機名稱備註") })
        OutlinedTextField(server.host, { server = server.copy(host = it) }, modifier = Modifier.fillMaxWidth(), label = { Text("主機 IP 或網域") })
        OutlinedTextField(server.port.toString(), { server = server.copy(port = it.toIntOrNull() ?: server.port) }, modifier = Modifier.fillMaxWidth(), label = { Text("SSH Port") })
        OutlinedTextField(server.username, { server = server.copy(username = it) }, modifier = Modifier.fillMaxWidth(), label = { Text("SSH 使用者") })
        SecretTextField("SSH 密碼，不推薦，可留空", server.password, { server = server.copy(password = it) }, showPassword) { showPassword = !showPassword }
        OutlinedTextField(
            value = server.privateKey,
            onValueChange = { server = server.copy(privateKey = it) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 10,
            label = { Text("SSH 私鑰，建議使用") },
            placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") }
        )
        SecretTextField("私鑰密碼，可留空", server.privateKeyPassphrase, { server = server.copy(privateKeyPassphrase = it) }, showPassword) { showPassword = !showPassword }
        OutlinedTextField(server.projectPath, { server = server.copy(projectPath = it) }, modifier = Modifier.fillMaxWidth(), label = { Text("專案資料夾，可留空") })
        OutlinedTextField(server.serviceName, { server = server.copy(serviceName = it) }, modifier = Modifier.fillMaxWidth(), label = { Text("systemd 服務名稱，可留空") })
        OutlinedTextField(server.dockerContainer, { server = server.copy(dockerContainer = it) }, modifier = Modifier.fillMaxWidth(), label = { Text("Docker 容器名稱，可留空") })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(server.strictHostKeyChecking, { server = server.copy(strictHostKeyChecking = it) })
            Text("啟用嚴格 Host Key 檢查，進階使用者才建議開啟")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(server.allowSudoCommands, { server = server.copy(allowSudoCommands = it) })
            Text("允許手動輸入 sudo 指令，仍需逐次確認")
        }
        Button(onClick = {
            val favoritesToSave = if (favoriteModels.isEmpty() && model.modelName.isNotBlank()) {
                listOf(ModelOption(model.modelName, model.modelName, "手動輸入並儲存的模型"))
            } else favoriteModels
            onSave(model, server, modelOptions, favoritesToSave)
            favoriteModels = favoritesToSave
            saved = true
        }, modifier = Modifier.fillMaxWidth()) {
            Text("儲存設定到手機加密儲存區")
        }
        if (saved) Text("已儲存。之後開啟 App 會自動帶入，不需要重填。", color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun HistoryScreen(key: Int, store: SecureStore, onCleared: () -> Unit) {
    var entries by remember(key) { mutableStateOf(store.loadHistory()) }
    Column(modifier = Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("維修紀錄")
            OutlinedButton(onClick = {
                store.clearHistory()
                entries = emptyList()
                onCleared()
            }) { Text("清除") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(entries) { entry ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(entry.title, fontWeight = FontWeight.Bold)
                        Text(entry.timestamp, style = MaterialTheme.typography.labelSmall)
                        SelectionContainer { Text(entry.content.take(4000)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == "user"
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Card(
            modifier = Modifier.fillMaxWidth(0.92f),
            colors = CardDefaults.cardColors(
                containerColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (isUser) "你" else "AI", fontWeight = FontWeight.Bold)
                SelectionContainer { Text(message.content) }
            }
        }
    }
}

@Composable
private fun OutputBox(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC))
    ) {
        SelectionContainer {
            Text(
                text = text,
                modifier = Modifier.padding(12.dp),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFE4E6))) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, modifier = Modifier.weight(1f), color = Color(0xFF9F1239))
            IconButton(onClick = onDismiss) { Text("×") }
        }
    }
}

@Composable
private fun WarningCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED))) {
        Text(message, modifier = Modifier.padding(12.dp), color = Color(0xFF9A3412))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
private fun SecretTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    visible: Boolean,
    onToggle: () -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = onToggle) { Text(if (visible) "隱藏" else "顯示") } }
    )
}




private fun modelForProvider(current: ModelSettings, provider: String): ModelSettings = when (provider) {
    "gemini" -> current.copy(
        provider = provider,
        baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        modelName = "gemini-2.5-flash"
    )
    "nvidia" -> current.copy(
        provider = provider,
        baseUrl = "https://integrate.api.nvidia.com/v1",
        modelName = "meta/llama-3.3-70b-instruct"
    )
    else -> current.copy(provider = provider)
}

private fun buildChatContext(messages: List<ChatMessage>, maxCharacters: Int): List<ChatMessage> {
    val system = messages.firstOrNull { it.role == "system" } ?: ChatMessage("system", RepairPrompts.systemPrompt)
    val normalMessages = messages.filter { it.role != "system" }
    val selected = mutableListOf<ChatMessage>()
    var used = 0
    for (message in normalMessages.asReversed()) {
        val cost = message.content.length + 32
        if (selected.isNotEmpty() && used + cost > maxCharacters) break
        selected.add(message)
        used += cost
    }
    return listOf(system) + selected.asReversed()
}

@Composable
private fun FavoriteModelChooser(
    allModels: List<ModelOption>,
    favorites: List<ModelOption>,
    filter: String,
    onFilterChange: (String) -> Unit,
    onToggle: (ModelOption, Boolean) -> Unit,
    onUseNow: (ModelOption) -> Unit
) {
    SectionTitle("常用模型")
    Card(
        modifier = Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("先按「取得模型」，再勾選你常用的模型。聊天頁與設定頁的下拉選單會優先只顯示勾選項目。")
            if (favorites.isNotEmpty()) {
                Text("已勾選：${favorites.size} 個", fontWeight = FontWeight.Bold)
                favorites.take(12).forEach { option ->
                    Text("• ${option.displayName.ifBlank { option.id }}", style = MaterialTheme.typography.labelSmall)
                }
                if (favorites.size > 12) Text("還有 ${favorites.size - 12} 個未顯示", style = MaterialTheme.typography.labelSmall)
            } else {
                Text("尚未勾選常用模型。若直接儲存，App 會把目前 Model Name 當成常用模型保存。", style = MaterialTheme.typography.labelSmall)
            }

            if (allModels.isNotEmpty()) {
                OutlinedTextField(
                    value = filter,
                    onValueChange = onFilterChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("搜尋模型") },
                    placeholder = { Text("例如：llama、gemini、qwen、70b") }
                )
                val filtered = allModels.filter { option ->
                    filter.isBlank() || option.id.contains(filter, ignoreCase = true) ||
                        option.displayName.contains(filter, ignoreCase = true) ||
                        option.description.contains(filter, ignoreCase = true)
                }.take(120)
                Text("顯示 ${filtered.size} 個模型；大量平台會先顯示前 120 個，可用搜尋縮小。", style = MaterialTheme.typography.labelSmall)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    filtered.forEach { option ->
                        val checked = favorites.any { it.id == option.id }
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                            Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(checked = checked, onCheckedChange = { onToggle(option, it) })
                                    Column(Modifier.weight(1f)) {
                                        Text(option.displayName.ifBlank { option.id }, fontWeight = FontWeight.Bold)
                                        Text(option.id, style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(onClick = { onUseNow(option) }) { Text("使用") }
                                }
                                if (option.description.isNotBlank()) {
                                    Text(option.description.take(180), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            } else {
                Text("尚未取得模型清單；可以先按「取得模型」，或手動輸入 Model Name 後儲存。", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ModelSelector(
    selectedModel: String,
    options: List<ModelOption>,
    onSelect: (ModelOption) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.id == selectedModel }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(selected?.displayName ?: selectedModel.ifBlank { "選擇模型" }, fontWeight = FontWeight.Bold)
                Text(selected?.id ?: "從官方 API 回傳清單選擇", style = MaterialTheme.typography.labelSmall)
            }
            Text("選擇")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.take(80).forEach { option ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(option.displayName.ifBlank { option.id }, fontWeight = FontWeight.Bold)
                            Text(option.id, style = MaterialTheme.typography.labelSmall)
                            if (option.description.isNotBlank()) {
                                Text(option.description.take(160), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun ProviderSelector(provider: String, onProvider: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (provider) {
        "gemini" -> "Google Gemini，OpenAI 相容端點"
        "nvidia" -> "NVIDIA NIM，OpenAI 相容端點"
        else -> "自訂 OpenAI-compatible API"
    }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f))
            Text("切換")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Google Gemini") }, onClick = { onProvider("gemini"); expanded = false })
            DropdownMenuItem(text = { Text("NVIDIA NIM") }, onClick = { onProvider("nvidia"); expanded = false })
            DropdownMenuItem(text = { Text("自訂 OpenAI-compatible API") }, onClick = { onProvider("custom"); expanded = false })
        }
    }
}

private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.TAIWAN).format(Date())
