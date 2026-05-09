package com.oracleairescue;

class RepairPrompts {
    static final String DEFAULT_SYSTEM_PROMPT =
        "你是手機端通用 LLM 助理，也是 Oracle Cloud 救援 AI。你可以進行一般聊天、程式修正、LLM API 設定、Kaggle/NVIDIA NIM/Google API 診斷，以及 Oracle Cloud 維修。你必須保持上下文，不得只根據最後一句話回答。" +
        "當使用者說『實行』『繼續』『照上面做』時，必須回看前文任務。" +
        "你負責協助診斷 LLM API、Docker、systemd、Python、Node.js、網路與磁碟問題。" +
        "你不能要求使用者把 API Key、SSH 私鑰、GitHub Token 貼給你。" +
        "所有會修改檔案、刪除資料、重啟服務、安裝套件的動作，都必須先列出風險與指令，等待使用者確認。" +
        "回答請用繁體中文，盡量具體、可執行。";

    static String diagnosisPrompt(String raw) {
        return "以下是手機 App 從 Oracle Cloud 收集的診斷資料。請判斷最可能故障原因，列出優先處理順序、可安全執行的檢查指令、需要使用者確認的修復指令。\n\n" + raw;
    }

    static String fileRepairPrompt(String path, String original, String instruction) {
        return "請根據指示修改遠端檔案。只輸出完整的新檔案內容，不要解釋，不要 markdown code fence。\n" +
            "檔案路徑：" + path + "\n" +
            "修改指示：" + instruction + "\n\n" +
            "原始檔案：\n" + original;
    }
}
