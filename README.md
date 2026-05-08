# Oracle AI Rescue

這是一個 Android 原生救援 App 專案，用途是：在 Oracle Cloud 上的 AI 助理故障時，讓手機直接使用你的 Google Gemini API 或 NVIDIA NIM API 進行診斷、讀取 log、分析錯誤、修正遠端檔案並備份。

## 重要安全原則

請不要把 GitHub Token、Google API Key、NVIDIA API Key、Oracle SSH 私鑰貼給任何聊天 AI。

本 App 的設計是：

1. 你第一次安裝 App 後，在手機內輸入 API Key 與 SSH 資訊。
2. 按「儲存設定到手機加密儲存區」。
3. App 使用 Android Keystore 加密保存設定。
4. 之後重新開啟 App 會自動帶入，不需要每次重填。
5. 原始碼裡不寫死任何金鑰，因此可以安全上傳到 GitHub 打包。

## 支援平台

- Google Gemini API，預設 OpenAI-compatible Base URL：
  `https://generativelanguage.googleapis.com/v1beta/openai`
- NVIDIA NIM，預設 Base URL：
  `https://integrate.api.nvidia.com/v1`
- 自訂 OpenAI-compatible API

## 自動取得模型清單

設定頁有「取得模型」按鈕。

- Google Gemini：App 會呼叫 Google 官方 `models` API，取得可用模型，並自動把 `models/gemini-...` 正規化為 `gemini-...`，方便 OpenAI-compatible chat endpoint 使用。
- NVIDIA NIM：App 會呼叫 OpenAI-compatible `/models` 端點取得模型清單。
- 自訂 API：App 會嘗試呼叫 `{Base URL}/models`。

取得後可以在 App 內直接選模型，不必自己猜模型名稱。

## 常用模型勾選與下拉選單

設定頁新增「常用模型」區塊：

1. 選擇平台：Google Gemini 或 NVIDIA NIM。
2. 輸入 API Key。
3. 按「取得模型」。
4. 在模型清單中勾選你常用的模型。
5. 按「儲存設定到手機加密儲存區」。

之後聊天頁與設定頁會優先顯示你勾選的常用模型，下拉選單不會被 NVIDIA NIM 大量模型塞滿。模型清單與常用模型都會加密保存在手機本機，不需要每次重新取得。

## 對話上下文與記憶

聊天頁會把對話記錄保存在手機端，下一次開啟 App 仍可沿用前面的對話。送出訊息時，App 會帶入最近的上下文，避免你剛說「規劃任務藍圖」，下一句說「實行」時模型不知道你在指哪件事。

設定頁可調整「聊天上下文保留字元數」，預設 60000。數字越大越不容易忘記前文，但 API 成本與失敗機率也可能提高。

## 已包含功能

- 模型平台選擇：Google / NVIDIA / 自訂
- API Key 本機加密儲存
- 官方模型清單取得與選擇
- 常用模型勾選、保存與快速下拉切換
- 聊天上下文保存與最近上下文自動帶入
- Chat 頁面
- Oracle SSH 設定
- 測試 SSH 連線
- 一鍵診斷
- 收集 systemd / Docker / journal / disk / memory 狀態
- 把診斷結果交給 LLM 分析
- 手動確認後執行單一修復指令
- 高風險指令阻擋
- 遠端讀取檔案
- AI 產生修正後完整檔案
- 顯示 unified diff
- 寫回前自動備份
- 維修紀錄保存
- GitHub Actions 自動打包 Debug APK

## 如何用 GitHub Actions 打包 APK

1. 到 GitHub 建立新的 Repository，例如 `OracleAIRescue`。
2. 把本專案全部檔案上傳到 Repository 根目錄。
3. 進入 GitHub Repository 的 `Actions`。
4. 選擇 `Build Debug APK`。
5. 點 `Run workflow`。
6. 完成後下載 Artifact：`OracleAIRescue-debug-apk`。
7. 解壓縮後會看到 `app-debug.apk`。
8. 把 APK 傳到 Android 手機安裝。

## 安裝後第一次設定

1. 開啟 App。
2. 到「設定」。
3. 選平台：Google Gemini 或 NVIDIA NIM。
4. 貼上你自己的 API Key。
5. 按「取得模型」。
6. 選一個模型。
7. 填 Oracle SSH 資料：IP、Port、使用者、SSH 私鑰。
8. 填 systemd 服務名稱或 Docker 容器名稱，可留空。
9. 按「儲存設定到手機加密儲存區」。
10. 到「診斷」按「測試 SSH」。
11. 成功後按「一鍵診斷並交給 AI 分析」。

## 注意

Debug APK 主要用於自用測試，不建議公開散布。若要正式發布，請改用 release 簽署流程。
