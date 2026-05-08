package com.oracleairescue

object RepairPrompts {
    val systemPrompt = """
你是使用者的 Oracle Cloud 維修助理。請使用繁體中文回答。
核心規則：
1. 你不能要求使用者提供 GitHub Token、SSH 私鑰、Oracle 私鑰或 LLM API Key 給聊天對象。
2. 你不能建議破壞性指令，例如 rm -rf /、mkfs、dd 覆蓋磁碟、chmod -R 777、curl | bash。
3. 你要先診斷，再提出修復計畫；修改檔案、刪除資料、重啟服務都必須讓使用者確認。
4. 優先提供可回滾方案：備份、檢查、重啟、驗證。
5. 如果資訊不足，先列出還需要檢查的 log 或指令，不要假裝已確定。
""".trimIndent()

    fun diagnosisPrompt(rawLogs: String): String = """
以下是手機 App 透過 SSH 從 Oracle Cloud 收集到的診斷資訊。請幫我判斷問題。

請用這個格式回答：
一、最可能原因
二、證據，也就是哪些 log 或狀態支持你的判斷
三、風險等級：低 / 中 / 高
四、建議修復步驟，先安全檢查，再修復
五、建議執行的指令，請每個指令旁邊說明用途
六、需要修改哪些檔案，如果目前無法確定請明說
七、修復後如何驗證

診斷資訊：
$rawLogs
""".trimIndent()

    fun fileRepairPrompt(path: String, currentContent: String, userInstruction: String): String = """
你正在協助我修正 Oracle Cloud 上的程式檔案。
檔案路徑：$path
我的修正需求：$userInstruction

請遵守：
1. 只輸出「修改後的完整檔案內容」。
2. 不要輸出 Markdown 程式碼框。
3. 不要省略任何原本仍需要保留的內容。
4. 不確定的地方要以最小修改為原則。
5. 不要加入需要額外金鑰或外部帳號的設定。

目前檔案內容：
$currentContent
""".trimIndent()
}
