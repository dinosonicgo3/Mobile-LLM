import base64
import json
import os
import signal
import subprocess
import sys
import threading
import time
import urllib.request
import urllib.error
from datetime import datetime, timedelta, timezone

GH_TOKEN = "__GH_TOKEN__"
GH_OWNER = "__GH_OWNER__"
GH_REPO = "__GH_REPO__"
GH_BRANCH = "__GH_BRANCH__"
CONFIG_PATH = "__CONFIG_PATH__"
MODEL_NAME = "__MODEL_NAME__"
IDLE_MINUTES = int("__IDLE_MINUTES__")
WEEKLY_QUOTA_HOURS = int("__WEEKLY_QUOTA_HOURS__")
API_KEY = os.environ.get("KAGGLE_QWEN_API_KEY", "")
VLLM_PORT = 8001
PROXY_PORT = 8000
last_activity = time.time()
start_monotonic = time.time()
state_lock = threading.Lock()

UTC8 = timezone(timedelta(hours=8))

def now_utc8():
    return datetime.now(UTC8).strftime("%Y-%m-%d %H:%M:%S UTC+8")

def week_reset_utc8(dt=None):
    # Kaggle 社群長期說法：週六 00:00 UTC 重置，等於 UTC+8 週六 08:00。
    dt = dt or datetime.now(UTC8)
    reset = dt.replace(hour=8, minute=0, second=0, microsecond=0)
    days = (5 - reset.weekday()) % 7  # Monday=0, Saturday=5
    if days == 0 and dt >= reset:
        days = 7
    return reset + timedelta(days=days)

def current_week_key(dt=None):
    r = week_reset_utc8(dt)
    prev = r - timedelta(days=7)
    return prev.strftime("%Y-%m-%d")

def gh_api(method, path, data=None):
    url = f"https://api.github.com{path}"
    body = None if data is None else json.dumps(data).encode("utf-8")
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("Authorization", f"Bearer {GH_TOKEN}")
    if body is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8")
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as e:
        raise RuntimeError(f"GitHub API HTTP {e.code}: {e.read().decode('utf-8', 'ignore')}")

def load_config():
    path = f"/repos/{GH_OWNER}/{GH_REPO}/contents/{CONFIG_PATH}?ref={GH_BRANCH}"
    try:
        obj = gh_api("GET", path)
        content = base64.b64decode(obj.get("content", "")).decode("utf-8")
        return json.loads(content), obj.get("sha")
    except Exception:
        return {}, None

def publish_status(state, base_url="", message=""):
    with state_lock:
        cfg, sha = load_config()
        kaggle = cfg.get("kaggle") or {}
        week_key = current_week_key()
        if kaggle.get("weekKey") != week_key:
            kaggle["estimatedUsedMinutes"] = 0
            kaggle["weekKey"] = week_key
        elapsed_min = int((time.time() - start_monotonic) / 60)
        previous = int(kaggle.get("baseUsedBeforeThisSession", kaggle.get("estimatedUsedMinutes", 0)) or 0)
        if "baseUsedBeforeThisSession" not in kaggle:
            kaggle["baseUsedBeforeThisSession"] = previous
        used = min(WEEKLY_QUOTA_HOURS * 60, previous + elapsed_min)
        remaining = max(0, WEEKLY_QUOTA_HOURS * 60 - used)
        if base_url:
            kaggle["baseUrl"] = base_url.rstrip("/") + "/v1"
        kaggle.update({
            "state": state,
            "status": state,
            "apiKey": API_KEY,
            "defaultModel": MODEL_NAME,
            "models": [MODEL_NAME, "Qwen/Qwen3.6-27B", "Qwen/Qwen3.6-35B", "qwen3.6-27b", "qwen3.6-35b"],
            "lastHeartbeatUtc8": now_utc8(),
            "idleShutdownMinutes": IDLE_MINUTES,
            "weeklyQuotaHours": WEEKLY_QUOTA_HOURS,
            "estimatedUsedMinutes": used,
            "estimatedRemainingMinutes": remaining,
            "weekResetAtUtc8": week_reset_utc8().strftime("%Y-%m-%d %H:%M:%S UTC+8"),
            "message": message,
        })
        if state == "starting":
            kaggle["startedAtUtc8"] = now_utc8()
            kaggle["stoppedAtUtc8"] = ""
        if state in ("stopped", "error"):
            kaggle["stoppedAtUtc8"] = now_utc8()
        cfg["kaggle"] = kaggle
        cfg.setdefault("version", "kaggle-live")
        cfg.setdefault("systemPrompt", "你是手機端通用 LLM 助理，也是 Oracle Cloud 救援 AI。")
        cfg.setdefault("extraDiagnosticCommands", [])
        raw = json.dumps(cfg, ensure_ascii=False, indent=2).encode("utf-8")
        payload = {
            "message": f"Update Kaggle endpoint {state}",
            "content": base64.b64encode(raw).decode("ascii"),
            "branch": GH_BRANCH,
        }
        if sha:
            payload["sha"] = sha
        gh_api("PUT", f"/repos/{GH_OWNER}/{GH_REPO}/contents/{CONFIG_PATH}", payload)

def sh(cmd, check=True):
    print("$", cmd, flush=True)
    return subprocess.run(cmd, shell=True, check=check)

def install_deps():
    sh("python -m pip install -q --upgrade pip")
    sh("python -m pip install -q fastapi uvicorn httpx")
    # vLLM 很大，Kaggle 可能已快取；若失敗會寫入 error 狀態。
    sh("python -m pip install -q vllm", check=False)
    if not os.path.exists("/tmp/cloudflared"):
        sh("wget -q -O /tmp/cloudflared https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 && chmod +x /tmp/cloudflared")

def start_vllm():
    cmd = [
        sys.executable, "-m", "vllm.entrypoints.openai.api_server",
        "--model", MODEL_NAME,
        "--host", "127.0.0.1",
        "--port", str(VLLM_PORT),
    ]
    # 雙 T4 時可嘗試 tensor parallel；若模型不支援或只有單 GPU，可自行調整此檔。
    if os.environ.get("CUDA_VISIBLE_DEVICES", "").count(",") >= 1:
        cmd += ["--tensor-parallel-size", "2"]
    print("Starting vLLM:", " ".join(cmd), flush=True)
    return subprocess.Popen(cmd)

def wait_vllm(timeout=900):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{VLLM_PORT}/v1/models", timeout=5) as r:
                if r.status == 200:
                    return True
        except Exception:
            time.sleep(5)
    return False

def write_proxy_app():
    code = f'''
import os, time, threading, signal
import httpx
from fastapi import FastAPI, Request, Response
import uvicorn

VLLM = "http://127.0.0.1:{VLLM_PORT}"
IDLE = {IDLE_MINUTES} * 60
API_KEY = {API_KEY!r}
last_activity = time.time()
app = FastAPI()

def touch():
    global last_activity
    last_activity = time.time()

def auth_ok(req: Request):
    if not API_KEY:
        return True
    h = req.headers.get("authorization", "")
    return h == "Bearer " + API_KEY

@app.middleware("http")
async def proxy(request: Request, call_next):
    if request.url.path in ["/health", "/v1/health"]:
        touch()
        return Response("OK", media_type="text/plain")
    if request.url.path in ["/shutdown", "/v1/shutdown"]:
        def later():
            time.sleep(1)
            os._exit(0)
        threading.Thread(target=later, daemon=True).start()
        return Response('{{"ok":true,"message":"shutdown scheduled"}}', media_type="application/json")
    if not auth_ok(request):
        return Response('{{"error":"unauthorized"}}', status_code=401, media_type="application/json")
    touch()
    body = await request.body()
    url = VLLM + request.url.path
    if request.url.query:
        url += "?" + request.url.query
    headers = {{k:v for k,v in request.headers.items() if k.lower() not in ["host", "content-length"]}}
    async with httpx.AsyncClient(timeout=None) as client:
        r = await client.request(request.method, url, content=body, headers=headers)
        return Response(r.content, status_code=r.status_code, media_type=r.headers.get("content-type"))

def watchdog():
    while True:
        time.sleep(30)
        if time.time() - last_activity > IDLE:
            os._exit(0)

threading.Thread(target=watchdog, daemon=True).start()
uvicorn.run(app, host="0.0.0.0", port={PROXY_PORT})
'''
    open("/tmp/kaggle_proxy.py", "w", encoding="utf-8").write(code)

def start_proxy():
    write_proxy_app()
    return subprocess.Popen([sys.executable, "/tmp/kaggle_proxy.py"])

def start_tunnel():
    log = "/tmp/cloudflared.log"
    p = subprocess.Popen(["/tmp/cloudflared", "tunnel", "--url", f"http://127.0.0.1:{PROXY_PORT}", "--logfile", log], stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    url = ""
    deadline = time.time() + 180
    while time.time() < deadline:
        if os.path.exists(log):
            txt = open(log, "r", encoding="utf-8", errors="ignore").read()
            import re
            m = re.search(r"https://[-a-zA-Z0-9.]+trycloudflare.com", txt)
            if m:
                url = m.group(0)
                break
        time.sleep(2)
    if not url:
        raise RuntimeError("cloudflared did not produce a public URL")
    return p, url

def heartbeat_loop(base_url):
    while True:
        publish_status("running", base_url, "Kaggle Qwen API running")
        time.sleep(60)

def main():
    publish_status("starting", "", "Kaggle kernel starting; installing dependencies")
    try:
        install_deps()
        publish_status("starting", "", "Starting vLLM model server")
        vllm = start_vllm()
        if not wait_vllm():
            raise RuntimeError("vLLM did not become ready in time")
        proxy = start_proxy()
        tunnel, public_url = start_tunnel()
        publish_status("running", public_url, "Kaggle Qwen API running")
        threading.Thread(target=heartbeat_loop, args=(public_url,), daemon=True).start()
        while True:
            time.sleep(5)
            if proxy.poll() is not None:
                publish_status("stopped", public_url, f"proxy exited with code {proxy.returncode}")
                break
            if vllm.poll() is not None:
                publish_status("error", public_url, f"vLLM exited with code {vllm.returncode}")
                break
    except Exception as e:
        publish_status("error", "", repr(e))
        raise

if __name__ == "__main__":
    main()
