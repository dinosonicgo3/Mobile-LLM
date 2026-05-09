# 甲骨文雲端AI

這是一個手機端 Android 救援 App 專案。它不需要 Oracle Cloud 上的 AI 正常運作；手機 App 會直接呼叫外部 LLM API，並透過 SSH 連到 Oracle Cloud 讀取 log、診斷問題、產生修復建議、讀取/修改檔案。

## 版本

v1.3.5 Java 原生版。此版加入 Kaggle Qwen 動態端點同步：App 可從 GitHub 的 oracle-ai-rescue-config.json 讀取 Kaggle 隧道網址，不需要使用者手動知道 ngrok/cloudflared URL。固定簽章仍保留，之後新版 APK 可直接覆蓋安裝並保留 App 內資料。

此版改成 Java + 原生 Android UI，移除 Kotlin/Compose，目標是降低 GitHub Actions 編譯失敗機率。

## 已包含功能

- Google Gemini OpenAI-compatible API
- NVIDIA NIM OpenAI-compatible API
- Kaggle Qwen / vLLM / FastAPI OpenAI-compatible API
- 自訂 OpenAI-compatible Provider
- API Key 手機本機加密保存
- SSH 私鑰手機本機加密保存
- 各平台可各自保存 API Key、Base URL、模型名稱
- 取得官方或服務端 `/models` 模型清單
- 勾選常用模型
- 聊天頁下拉選擇常用模型
- 聊天上下文保存，避免「上一句剛說完下一句就忘」
- Oracle Cloud SSH 測試連線
- 一鍵診斷 systemd / Docker / journalctl / 專案路徑
- AI 分析診斷輸出
- 安全執行指令，阻擋高風險指令
- 遠端讀取檔案
- AI 產生修正版
- 顯示簡易 diff
- 備份後寫回檔案
- 維修紀錄
- GitHub 設定熱更新
- 熱更新設定一鍵回滾
- GitHub Releases 版本清單，方便下載新版或舊版 APK

## Kaggle Qwen 使用方式

若你的 Kaggle Notebook 已用 vLLM 或 FastAPI 提供 OpenAI 相容 API：

1. App 進入「設定」
2. 平台選「Kaggle Qwen / OpenAI 相容」
3. Base URL 填你的隧道網址加 `/v1`
   - 例：`https://xxxx.ngrok-free.app/v1`
   - 例：`https://xxxx.trycloudflare.com/v1`
4. API Key 若沒有驗證可留空
5. 模型名稱填 Kaggle 伺服器實際模型名
   - 例：`Qwen/Qwen2.5-7B-Instruct`
   - 例：你的 Qwen 27B/35B 啟動名稱
6. 按「把目前模型加入常用」

如果 Kaggle 端有支援 `/v1/models`，也可以按「取得模型清單」。

## GitHub 自動打包 APK

Repository 根目錄應直接看到：

```text
app
.github
build.gradle.kts
settings.gradle.kts
gradle.properties
README.md
oracle-ai-rescue-config.json
```

不要變成：

```text
OracleAIRescue/app
OracleAIRescue/.github
```

如果 `.github` 無法上傳，可以到 GitHub 網頁：

```text
Code → Add file → Create new file
```

檔名輸入：

```text
.github/workflows/build-apk.yml
```

再貼入本專案的 workflow 內容。

## 建置 Debug APK

進 GitHub：

```text
Actions → Build Debug APK → Run workflow
```

成功後在該次 workflow 頁面下方 Artifacts 下載：

```text
OracleAIRescue-debug-apk
```

解壓縮後安裝 `app-debug.apk`。

## 建立可讓 App 讀取的 Release

進 GitHub：

```text
Actions → Build Release APK → Run workflow
```

填 tag，例如：

```text
v1.3.3
```

它會建立 GitHub Release 並附上 APK。之後 App 的「更新」頁可以列出 Releases。

## GitHub 設定熱更新

App 會讀取倉庫中的：

```text
oracle-ai-rescue-config.json
```

可更新：

- 系統提示詞
- 額外診斷指令

這種更新不需要重新下載 APK。若熱更新設定造成問題，可在 App 內按「救援回滾」。

## 關於 APK 自動更新與回滾

一般 Android 不適合讓普通 App 靜默替換自己，尤其救援工具更不能依賴高風險自更新。因此本專案採用：

1. 設定熱更新：直接從 GitHub 讀 JSON，可一鍵回滾。
2. APK 更新：App 列出 GitHub Releases，你確認後用瀏覽器下載安裝。
3. APK 回滾：若新版 App 還能開，從 App 內選舊 Release；若新版完全打不開，直接用瀏覽器進 GitHub Releases 下載舊 APK 安裝。

這比讓 App 自己靜默替換更安全，也更容易救援。

## 安全提醒

- 不要把 GitHub Token、Oracle SSH 私鑰、Google/NVIDIA/Kaggle API Key 貼給任何 AI。
- App 會在手機端保存金鑰，不會要求你寫死到原始碼。
- AI 產生的指令不會自動執行，必須你手動確認。
- 修改檔案前會建立備份。


## v1.3.3

- Oracle SSH 設定頁新增「從檔案匯入 SSH 私鑰」，可直接選擇 `ssh-key-2026-02-26.key`。
- 新增私鑰格式檢查，會顯示 RSA / OpenSSH / 公鑰誤用等狀態。


## v1.3.4 更新重點

- 加入固定 APK 簽章金鑰：安裝 v1.3.4 後，之後 GitHub Actions 打包的新 APK 可直接覆蓋安裝，不必每次刪除 App。
- 注意：若你目前手機上的舊版是用 GitHub 臨時 debug key 安裝，第一次升級到 v1.3.4 仍可能需要解除安裝一次；從 v1.3.4 開始才會穩定保留資料。
- 聊天頁可當一般手機 LLM 使用，不限於 Oracle 維修。
- Kaggle Qwen 預設加入 Qwen 3.6 27B / 35B 常用模型選項。


## v1.3.5 Kaggle 動態端點同步

App 不能憑空知道 Kaggle 每次啟動後的 ngrok/cloudflared 隧道網址；Kaggle 端程式必須把目前 URL 發布到一個手機可讀的位置。此版預設讀取你的 GitHub 倉庫：

```
https://raw.githubusercontent.com/dinosonicgo3/Mobile-LLM/main/oracle-ai-rescue-config.json
```

設定檔支援以下欄位：

```json
{
  "kaggle": {
    "baseUrl": "https://xxxx.ngrok-free.app/v1",
    "apiKey": "",
    "defaultModel": "Qwen/Qwen3.6-27B",
    "models": ["Qwen/Qwen3.6-27B", "Qwen/Qwen3.6-35B"]
  }
}
```

手機端操作：設定 → Kaggle Qwen 自動端點 → 自動同步 Kaggle 端點。若聊天頁選 Kaggle 但 Base URL 仍是空的，App 也會嘗試自動同步一次。

## v1.3.6 Kaggle 自動啟動

這版新增「Kaggle」頁：

- 從手機觸發 GitHub Actions 啟動 Kaggle Qwen Notebook
- Kaggle Notebook 自動建立 cloudflared 隧道
- Kaggle Notebook 自動把目前 API Base URL 寫回 `oracle-ai-rescue-config.json`
- 手機 App 可同步端點、顯示狀態、顯示估算 GPU 額度、呼叫 `/shutdown`
- 閒置 15 分鐘自動退出，以節省 Kaggle GPU 額度

### 你需要在 GitHub Repo 設定 Secrets

到 `Settings → Secrets and variables → Actions → New repository secret` 新增：

- `KAGGLE_API_TOKEN`：你的 kaggle.json 裡面的 key

`KAGGLE_USERNAME` 已在 workflow 內預設為 `dinosonicgo`，不需要再建立這個 Secret。
- `GH_CONFIG_PAT`：細粒度 GitHub Token，只給此 repo `Contents: Read and write`

手機 App 若要直接按鈕觸發 GitHub Actions，還需要在 App 的 Kaggle 頁填入另一個 GitHub Fine-grained token，只給此 repo `Actions: Read and write`。

### 額度顯示

Kaggle 沒有穩定公開 API 可以讓手機讀取帳號實際剩餘 GPU 額度；App 顯示的是 Kaggle 端程式根據本週啟動時間寫回 GitHub 的估算值。重置時間以 UTC+8 顯示，對應週六 08:00。


## v1.3.7 Kaggle 使用者名稱內建

此版已把 Kaggle username 固定為：

```text
dinosonicgo
```

因此 GitHub Secrets 只需要新增：

```text
KAGGLE_API_TOKEN
GH_CONFIG_PAT
```

`KAGGLE_API_TOKEN` 來自 Kaggle 下載的 `kaggle.json` 裡面的 `key` 欄位。
`GH_CONFIG_PAT` 用於 Kaggle 端把目前 cloudflared / OpenAI-compatible API 端點寫回 `oracle-ai-rescue-config.json`。


## v1.3.8 固定簽章檔上傳修正

前一版打包失敗原因是 `app/oracleairescue-update-key.jks` 被 `.gitignore` 擋住，導致 GitHub Actions 找不到固定簽章檔。

此版已修正：
- `.gitignore` 不再忽略 `.jks`
- 專案包內包含 `app/oracleairescue-update-key.jks`
- Build Debug APK / Build Release APK 會先檢查簽章檔是否存在

從固定簽章版開始，後續 APK 才能直接覆蓋安裝並保留 App 內資料。


## v1.3.9 Kaggle 新版 API Token 支援

此版改為優先使用 GitHub Secret：

```text
KAGGLE_API_TOKEN
```

這是 Kaggle 新版 token，通常以 `KGAT_` 開頭。Workflow 會把它寫入：

```text
~/.kaggle/access_token
```

保留舊版 `KAGGLE_KEY` 相容，但建議使用 `KAGGLE_API_TOKEN`。

請勿把真實 GitHub Token 或 Kaggle Token 寫進程式碼、GitHub repo、README 或聊天內容。


## v1.4.0 私人 GitHub 倉庫支援

此版修正私人倉庫下列功能：

- 抓取 `oracle-ai-rescue-config.json` 時會使用 App 內儲存的 GitHub Token。
- 查看 GitHub Releases 時會使用 App 內儲存的 GitHub Token。
- 更新頁新增 GitHub Token 欄位；可與 Kaggle 頁第一欄使用同一把 Token。

請勿把 GitHub Token 或 Kaggle Token 寫進程式碼、README、GitHub repo 或聊天內容。Token 只應在手機 App 或 GitHub Secrets 中保存。


## v1.4.1 編譯修正

修正 `LlmClient.java` 中多出的一個 `}`，此錯誤會導致：

```text
class, interface, enum, or record expected
```

並使 `dispatchWorkflow`、`listReleases` 等方法被 Java 編譯器判定在 class 外。


## v1.4.2 模型清單與平台 KEY 修正

- 模型清單管理改為逐列顯示，可搜尋，不再擠在一起。
- Google Gemini / NVIDIA NIM / Kaggle / 自訂平台的 API Key、Base URL、模型名稱、模型清單、常用模型分開保存。
- 修正舊版共用 `model.apiKey` 導致 Google KEY 在切換到 NVIDIA NIM 時被誤顯示的問題。
- 設定頁新增 `Temperature` 與 `上下文保留字元數` 說明。


## v1.4.3 Release APK 工作流程修正

從此版開始，建議日常更新使用：

```text
Actions → Build Release APK → Run workflow
```

此 workflow 會：

- 執行 `gradle assembleRelease`
- 使用固定簽章產生正式 release APK
- 自動建立 GitHub Release
- 若 tag 留空，會自動從 `app/build.gradle.kts` 的 `versionName` 產生，例如 `v1.4.3`
- App 的「查看 Releases / 下載 APK」會讀取這裡建立的 Release

`Build Debug APK` 仍保留作為快速測試與排錯用途，但穩定使用建議跑 `Build Release APK`。


## v1.4.4 自動 Release

從此版開始，你用上傳腳本 push 新版專案到 GitHub 後，`Build Release APK` 會自動執行，不必每次手動點 `Run workflow`。

標準流程變成：

```text
1. 解壓縮新版專案
2. 用連續上傳腳本上傳
3. 等 GitHub Actions 自動跑 Build Release APK
4. 到 Releases 或 App 內更新頁下載 APK
```

`Build Debug APK` 仍會保留，用於排錯與臨時測試。


## v1.4.5 LOG 回報與聊天按鈕改善

- 新增「匯出LOG回報」功能，可產生 Markdown 或 TXT 報告。
- 報告包含 App 狀態、模型平台、Oracle 設定摘要、Kaggle 狀態、最近 App LOG、維修紀錄、最近聊天內容。
- 匯出時會自動遮蔽常見 GitHub / Kaggle / Google / NVIDIA Token 與私鑰內容。
- 聊天頁的「送出訊息」改成大按鈕，避免在下方很難按。
- 「清空聊天」移到第二排，並保留確認對話框，降低誤觸風險。


## v1.4.6 Release 打包修正

v1.4.5 加入 AndroidX FileProvider 以支援 LOG 報告匯出，但 `gradle.properties` 尚未啟用 AndroidX，導致 Release 打包在 `checkReleaseAarMetadata` 失敗。

此版已加入：

```text
android.useAndroidX=true
android.enableJetifier=true
```

修正 Release APK 自動打包失敗問題。


## v1.4.7 聊天介面修正

- 聊天輸入框改成常見 Web / 手機聊天樣式：
  - 左側為輸入框
  - 右側為小型送出圖示 `➤`
- 移除大型「送出訊息」按鈕。
- `LOG 回報`、`查看 LOG`、`清空聊天`、`清空本機 LOG` 移入聊天頁右上角齒輪 `⚙`。
- 降低誤觸清空聊天的機率，也讓聊天區更乾淨。


## v1.4.8 聊天頁空白修正

- 因 `LOG 回報` 與 `清空聊天` 已移入右上角齒輪，原本底部工具列空間不再保留。
- 聊天紀錄顯示區由 360dp 加高到 470dp。
- 輸入列高度縮小，送出按鈕改為更緊湊的小圖示。
- 修正移除底部工具列後仍看起來留有大片空白的問題。


## v1.4.9 Gemini 思考與 Markdown 顯示

- Google Gemini / Gemma over Gemini API 預設使用 `reasoning_effort=high` 啟用思考。
- App 不請求 thought summaries，並會過濾常見 `<think>...</think>` / thinking code fence，避免思考內容顯示到聊天欄。
- 聊天欄加入 Markdown 顯示支援，可閱讀標題、列表、粗體、程式碼區塊。
- 設定頁可調整 Gemini 思考等級：high / medium / low / none。


## v1.5.2 手機本機 Gemma 4 E2B/E4B

此版調整 Gemma 4 方向：

- Google API / NVIDIA NIM：仍然是直連 Key 使用。
- Kaggle：仍然用 Kaggle Qwen。
- Oracle：保留 SSH 維修，不再用來下載/部署 Gemma 4 模型。
- 新增 `本機` 頁：手機本地 Gemma 4 E2B / E4B 一鍵下載。
- 新增 `本機 Gemma 4` 平台：聊天時不走 API、不走 Oracle，在手機上用 LiteRT-LM 推論。
- E2B / E4B 使用 `litert-community/*-litert-lm` 的 `.litertlm` 模型。
- 啟用 LiteRT-LM speculative decoding / MTP。

注意：E2B 約 2.6GB，E4B 約 3.7GB；手機需要足夠儲存空間與 RAM。第一次本機推論會花時間載入模型。


## v1.5.3 本機 Gemma 編譯修正

修正 v1.5.2 打包失敗：

```text
cannot find symbol
method cleanModelThoughts(String)
```

原因是本機 Gemma 回覆分支需要清理 `<think>...</think>` / thinking code block 等思考輸出，但 `MainActivity` 尚未補上該方法。

此版已補上 `cleanModelThoughts`，並保留：
- 本機 Gemma 4 E2B / E4B LiteRT-LM
- Markdown 顯示
- Google API / NVIDIA NIM / Kaggle / Oracle SSH 維修


## v1.5.4 聊天捲動與導航列修正

- 聊天頁不再使用外層 `ScrollView` 包住整頁，避免聊天紀錄區被父層攔截觸控。
- 聊天紀錄區改成獨立 `ScrollView`，可上下滑動。
- 每次送出或收到新回覆後，聊天紀錄會自動捲到底。
- 上方導航列改為橫向滑動列，所有按鈕固定寬高，避免「大小不一」與擠壓變形。


## v1.5.5 LOG 回報下載修正

- LOG 回報選單新增：
  - 儲存 Markdown 到下載資料夾（推薦）
  - 儲存 TXT 到下載資料夾
- Android 10 以上會儲存到：

```text
Download/OracleCloudAI/
```

- 分享功能保留為備用，但標示為「可能開新話題」。
- 建議流程改為：
  1. App → 齒輪 → LOG 回報 → 儲存 Markdown 到下載資料夾
  2. 回到原本 ChatGPT 對話
  3. 用附件上傳該 `.md` 或 `.txt` 檔案


## v1.5.6 Oracle 自動上下文

修正核心問題：聊天頁原本只是把「檢查甲骨文雲端」直接送給模型，沒有先 SSH 到 Oracle 主機，因此模型會給一般 OCI 教學。

此版新增：

- 偵測聊天內容是否包含：
  - 甲骨文 / Oracle / OCI
  - 主機 / 服務 / 專案 / Docker / Container
  - log / 日誌 / 維修 / 故障 / 部署 / 更新 / 重啟 / 檢查 / 狀態
- 若命中，App 會先透過 SSH 自動收集：
  - OS / hostname / whoami
  - CPU / 記憶體 / 磁碟
  - 常見專案目錄
  - systemd failed services
  - 疑似 AI / LLM / Docker / Python / Node 服務
  - Docker containers
  - Docker logs sample
  - journal warning/error sample
- 再把真實主機資料塞入 LLM 上下文。
- AI 會根據真實 SSH 結果回答，不再只給一般教學。


## v1.5.7 動態 Oracle 專案搜尋

修正方向：

- 不再假設 Oracle 上只有固定專案路徑。
- 使用者的雲端專案可能隨時更改，因此每次診斷/維修都應先掃描最新狀態。

新增：

- 聊天 Oracle 自動上下文加入：
  - 最近修改的專案候選
  - Git remote / git status / 最近 commit
  - Docker container mounts
  - systemd ExecStart / WorkingDirectory
  - 常見程式檔案候選
- 維修頁新增：
  - `自動掃描最新專案 / 服務 / 容器`
- 一鍵診斷會同時跑最新專案掃描。
- AI 修正遠端檔案時會帶入最新掃描資料，但仍只會在使用者指定檔案後產生修正版。
- 寫回檔案仍需使用者確認，並會備份原檔。


## v1.5.8 Oracle 空白回覆防呆

修正問題：

- 使用者要求 AI 檢查 Oracle Cloud 時，若模型回覆空白，聊天頁不再只顯示空白。
- App 會直接顯示已透過 SSH 取得的 Oracle 掃描資料。
- 如果 SSH 掃描本身沒有輸出，也會明確提示可能是權限/指令/輸出截斷問題。

新增：

- 維修頁新增 `只掃描 Oracle 並直接顯示結果（不呼叫模型）`
- 可用來確認 SSH 掃描本身是否正常，不受 LLM 回覆影響。


## v1.6.0 Oracle 輕量掃描

修正：v1.5.7 聊天頁自動掃描 Oracle 時輸出過大，曾達到約 5 萬字元，導致 NVIDIA NIM timeout。

新的分層掃描：

### 聊天頁自動掃描：輕量

只收集：

- 系統摘要
- 最近修改的專案候選 Top 12
- 專案 markers：`.git`、`docker-compose.yml`、`package.json`、`requirements.txt` 等
- Docker containers 摘要
- docker compose ls
- 疑似 systemd services 摘要
- failed services 摘要
- listening ports 摘要

不收集：

- Docker logs
- journalctl 大量紀錄
- 大量檔案清單
- systemd 完整 ExecStart 詳情

### 維修頁深度掃描

需要查錯誤或故障時，才使用深度掃描，包含 Docker mounts、systemd ExecStart、少量 recent errors。


## v1.6.1 有限診斷封包

修正架構問題：即使是深度診斷，也不應該把大量 raw logs 整包塞給 LLM。

新的做法：

- App 先在 SSH 端建立「有限診斷封包」
- 只保留：
  - 系統摘要
  - 專案索引 Top 10
  - Docker 狀態摘要
  - 可疑 Docker 容器
  - failed / suspicious systemd services
  - ports 摘要
  - journal error hints only
- 送給 LLM 的內容限制約 14,000 字元
- 如果資訊不足，LLM 必須提出下一個「最小化目標查詢」
  - 例如查某一個 service
  - 查某一個 container 最近 80 行 log
  - 查某一個專案檔案
- 不再要求整包 logs。


## v1.6.2 Oracle 工具式 Agent

修正架構方向：

- 不再由 App 預先掃描一大包資料。
- Oracle 相關聊天會進入「工具式 Agent」流程。
- LLM 自己判斷下一步需要哪個工具。
- App 只執行安全白名單工具。

可用工具：

```text
list_projects
list_docker
list_services
service_status name=<service>
container_logs name=<container> lines=<20-120>
list_dir path=<path>
read_file path=<path>
run_safe command=<read-only command>
final
```

安全限制：

- 每次只執行一個工具。
- 工具結果會壓縮後再回給 LLM。
- 自動工具只允許讀取型操作。
- 寫檔、刪除、重啟、安裝、修改權限等操作不會自動執行，仍需使用者在維修頁確認。


## v1.6.3 Gemma 4 31B 後段驗證流水線

依使用者要求，修程式的後段驗證固定使用 Gemma 4 31B。

新增安全修復流程：

1. 主模型產生修正版
2. Gemma 4 31B 寫回前預審
   - 是否真的有修改
   - 是否與需求相關
   - 是否有高風險
   - 是否可進入寫回後測試
3. 寫回前自動備份原檔
4. 寫回修正版
5. App 自動執行驗證測試
   - Python: `python3 -m py_compile`
   - Node: `node --check`
   - Shell: `bash -n`
   - JSON: `python3 -m json.tool`
   - Docker Compose: `docker compose config -q`
6. Gemma 4 31B 根據 diff + 測試輸出做最終驗證
7. 測試失敗或 31B 不通過：自動回滾
8. 測試通過且 31B 通過：保留修復

注意：

- 必須在 Google Gemini 平台填入 Google API Key。
- 預設 31B 驗證模型名稱：`gemma-4-31b-it`
- 可以在設定頁修改 31B 驗證模型名稱。


## v1.6.4 31B 後段驗證備援

依需求調整後段驗證策略：

1. 優先使用 Google Gemini API 的 Gemma 4 31B。
2. 如果 Google API 失敗，改用 NVIDIA NIM 的 Gemma 4 31B。
3. 如果 Google 與 NVIDIA NIM 都失敗：
   - 後段驗證結果固定判定為 FAIL。
   - App 會阻擋自動寫回或保留修復。
   - 顯示失敗原因，讓使用者知道是驗證層不可用，而不是主模型修復已通過。
   - 不會自動相信主模型結果。

設定頁新增：

- Google 31B 驗證模型名稱
- NVIDIA NIM 備援 31B 模型名稱
- 測試 Google 31B
- 測試 NIM 31B

注意：NVIDIA NIM 的模型 ID 可能依帳號與平台可用清單不同，請在 App 的 NIM 平台取得模型清單後，把實際 Gemma 4 31B 模型 ID 填到備援欄位。


## v1.6.5 確認 NVIDIA NIM Gemma 4 31B 模型 ID

已搜尋並確認 NVIDIA NIM 官方模型 ID：

```text
google/gemma-4-31b-it
```

調整：

- NVIDIA NIM 備援 31B 驗證模型預設固定為 `google/gemma-4-31b-it`
- 設定頁文字改為明確標示官方 ID
- NVIDIA NIM 常用模型清單新增：
  - `google/gemma-4-31b-it`
- Google 31B 失敗時，App 會用此 NIM 模型做備援後段驗證
- Google / NIM 都失敗時，仍判定後段驗證失敗並阻擋自動寫回或保留修復


## v1.6.6 編譯錯誤修正

修正 Release 打包失敗：

```text
MainActivity.java:2122: error: unclosed string literal
sb.append("- App 版本：v1.6.0
```

原因：LOG 報告中的 App 版本字串被錯誤替換成真實換行，Java 字串沒有關閉。

已修正為：

```java
sb.append("- App 版本：v1.6.6\n");
```


## v1.6.7 編譯錯誤修正

修正 Release 打包失敗：

- `buildOracleDiagnosticPacketCommand()` 重複定義
- `buildOracleBlankReplyFallback(OracleContextPack)` 缺失
- `Pattern` / `Matcher` 未 import

本版不改功能邏輯，只修正 Java 編譯錯誤。


## v1.6.8 強制確認編譯修正版

此版包含 v1.6.7 的三個編譯修正，並加入明確版本標記。

靜態檢查結果應為：

```text
MainActivity.java 裡 buildOracleDiagnosticPacketCommand 定義數量 = 1
已 import java.util.regex.Matcher
已 import java.util.regex.Pattern
已定義 buildOracleBlankReplyFallback(OracleContextPack)
```

如果 GitHub Actions 仍出現同一組錯誤，代表 GitHub 上跑的不是這份 v1.6.8 檔案，而是舊 commit / 舊 zip 沒有被覆蓋。
