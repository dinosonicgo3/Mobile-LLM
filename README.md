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

- `KAGGLE_KEY`：你的 kaggle.json 裡面的 key

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
KAGGLE_KEY
GH_CONFIG_PAT
```

`KAGGLE_KEY` 來自 Kaggle 下載的 `kaggle.json` 裡面的 `key` 欄位。
`GH_CONFIG_PAT` 用於 Kaggle 端把目前 cloudflared / OpenAI-compatible API 端點寫回 `oracle-ai-rescue-config.json`。
