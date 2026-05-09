"""
Kaggle 端發布目前 Qwen API 隧道網址到 GitHub 設定檔的範例腳本。
用途：讓手機 App 不需要手動輸入 ngrok/cloudflared URL。

需要環境變數：
GITHUB_TOKEN：有權限更新 dinosonicgo3/Mobile-LLM 的 token
KAGGLE_QWEN_BASE_URL：目前隧道網址，例如 https://xxxx.ngrok-free.app/v1
KAGGLE_QWEN_MODEL：模型名稱，例如 Qwen/Qwen3.6-27B

在 Kaggle Notebook 中，當你的隧道建立完成後執行此檔，或把相同邏輯整合到原本啟動程式。
"""
import base64, json, os, urllib.request

OWNER = "dinosonicgo3"
REPO = "Mobile-LLM"
PATH = "oracle-ai-rescue-config.json"
BRANCH = "main"

token = os.environ.get("GITHUB_TOKEN", "").strip()
base_url = os.environ.get("KAGGLE_QWEN_BASE_URL", "").strip().rstrip("/")
model = os.environ.get("KAGGLE_QWEN_MODEL", "Qwen/Qwen3.6-27B").strip()

if not token:
    raise SystemExit("缺少 GITHUB_TOKEN")
if not base_url.startswith("https://"):
    raise SystemExit("KAGGLE_QWEN_BASE_URL 必須是 https://.../v1")

api = f"https://api.github.com/repos/{OWNER}/{REPO}/contents/{PATH}"
req = urllib.request.Request(api + f"?ref={BRANCH}", headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"})
try:
    with urllib.request.urlopen(req, timeout=30) as r:
        current = json.loads(r.read().decode())
        sha = current.get("sha")
        content = base64.b64decode(current["content"]).decode()
        data = json.loads(content)
except Exception:
    sha = None
    data = {"version":"kaggle-auto", "systemPrompt":"", "extraDiagnosticCommands":[]}

data.setdefault("kaggle", {})
data["kaggle"]["baseUrl"] = base_url
data["kaggle"].setdefault("apiKey", "")
data["kaggle"]["defaultModel"] = model
data["kaggle"]["models"] = [model, "Qwen/Qwen3.6-27B", "Qwen/Qwen3.6-35B", "qwen3.6-27b", "qwen3.6-35b"]
data["version"] = "kaggle-endpoint-updated"

payload = {
    "message": f"Update Kaggle Qwen endpoint {base_url}",
    "content": base64.b64encode(json.dumps(data, ensure_ascii=False, indent=2).encode()).decode(),
    "branch": BRANCH,
}
if sha:
    payload["sha"] = sha

req = urllib.request.Request(api, data=json.dumps(payload).encode(), method="PUT", headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json", "Content-Type": "application/json"})
with urllib.request.urlopen(req, timeout=30) as r:
    print(r.read().decode())
print("已發布 Kaggle Qwen endpoint：", base_url)
