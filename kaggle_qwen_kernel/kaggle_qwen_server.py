import base64
import json
import os
import re
import shutil
import signal
import subprocess
import tarfile
import zipfile
import sys
import threading
import time
import urllib.request
import urllib.error
from datetime import datetime, timedelta, timezone
from pathlib import Path

GH_TOKEN = "__GH_TOKEN__"
GH_OWNER = "__GH_OWNER__"
GH_REPO = "__GH_REPO__"
GH_BRANCH = "__GH_BRANCH__"
CONFIG_PATH = "__CONFIG_PATH__"
MODEL_NAME = "__MODEL_NAME__"
MODEL_SOURCE = "__MODEL_SOURCE__"
IDLE_MINUTES = int("__IDLE_MINUTES__")
WEEKLY_QUOTA_HOURS = int("__WEEKLY_QUOTA_HOURS__")
API_KEY = os.environ.get("KAGGLE_QWEN_API_KEY", "")
LLAMA_PORT = 8001
PROXY_PORT = 8000
DEFAULT_CTX_SIZE = int(os.environ.get("LLAMA_CTX_SIZE", "4096"))
DEFAULT_BATCH_SIZE = int(os.environ.get("LLAMA_BATCH_SIZE", "512"))
DEFAULT_THREADS = int(os.environ.get("LLAMA_THREADS", str(max(2, min(8, os.cpu_count() or 4)))))
start_monotonic = time.time()
state_lock = threading.Lock()
children = []
TOOL_CACHE_DIR = Path("/kaggle/working/tool_cache")

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
    # 發布狀態到 GitHub 只是「同步用」，不能讓同步失敗導致 Kaggle API 直接死掉。
    # 若 GH_CONFIG_PAT 權限錯誤或 GitHub API 短暫失敗，仍繼續啟動 server，並把端點印在 Kaggle / GitHub Actions log 讓使用者可手動填入。
    try:
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
                kaggle["publicUrl"] = base_url.rstrip("/")
            kaggle.update({
                "state": state,
                "status": state,
                "apiKey": API_KEY,
                "defaultModel": MODEL_NAME,
                "models": [MODEL_NAME, "qwen36-27b-q4-gguf", "qwen3.6-27b-q4", "qwen3.6-27b"],
                "backend": "llama.cpp llama-server GGUF + qwen36-tunnel-tools",
                "lastHeartbeatUtc8": now_utc8(),
                "idleShutdownMinutes": IDLE_MINUTES,
                "weeklyQuotaHours": WEEKLY_QUOTA_HOURS,
                "estimatedUsedMinutes": used,
                "estimatedRemainingMinutes": remaining,
                "weekResetAtUtc8": week_reset_utc8().strftime("%Y-%m-%d %H:%M:%S UTC+8"),
                "message": message,
                "workerVersion": "v2.4.8",
                "datasetSlug": "dinosonicgo/qwen36-27b-q4-gguf-cache",
                "toolsDatasetSlug": "dinosonicgo/qwen36-tunnel-tools",
                "modelSource": MODEL_SOURCE,
            })
            if state == "starting":
                kaggle["startedAtUtc8"] = now_utc8()
                kaggle["stoppedAtUtc8"] = ""
            if state in ("stopped", "error"):
                kaggle["stoppedAtUtc8"] = now_utc8()
            cfg["kaggle"] = kaggle
            cfg["version"] = "v2.4.8-kaggle-preflight-autowait"
            cfg.setdefault("systemPrompt", "你是手機端通用 LLM 助理，也是 Oracle Cloud 救援 AI。")
            cfg.setdefault("extraDiagnosticCommands", [])
            raw = json.dumps(cfg, ensure_ascii=False, indent=2).encode("utf-8")
            payload = {
                "message": f"Update Kaggle GGUF endpoint {state}",
                "content": base64.b64encode(raw).decode("ascii"),
                "branch": GH_BRANCH,
            }
            if sha:
                payload["sha"] = sha
            gh_api("PUT", f"/repos/{GH_OWNER}/{GH_REPO}/contents/{CONFIG_PATH}", payload)
            print(f"PUBLISHED_STATUS state={state} baseUrl={(base_url.rstrip('/') + '/v1') if base_url else ''} message={message}", flush=True)
    except Exception as e:
        print("WARNING: publish_status failed; server will keep running:", repr(e), flush=True)
        if base_url:
            print("MANUAL_KAGGLE_PUBLIC_URL=" + base_url.rstrip("/"), flush=True)
            print("MANUAL_KAGGLE_BASE_URL=" + base_url.rstrip("/") + "/v1", flush=True)


def sh(cmd, check=True, env=None):
    print("$", cmd, flush=True)
    return subprocess.run(cmd, shell=True, check=check, env=env)


def safe_chmod_exec(path):
    try:
        p = Path(path)
        p.chmod(p.stat().st_mode | 0o111)
    except Exception:
        pass


def maybe_extract_tool_archives():
    """Extract common archives from qwen36-tunnel-tools if the dataset ships binaries packed in one file."""
    marker = TOOL_CACHE_DIR / ".extracted"
    if marker.exists():
        return
    TOOL_CACHE_DIR.mkdir(parents=True, exist_ok=True)
    roots = [Path("/kaggle/input")]
    suffixes = (".zip", ".tar", ".tar.gz", ".tgz", ".tar.xz", ".txz")
    for root in roots:
        if not root.exists():
            continue
        for f in root.rglob("*"):
            if not f.is_file():
                continue
            name = f.name.lower()
            # Avoid touching large GGUF model files.
            if name.endswith(".gguf"):
                continue
            if not name.endswith(suffixes):
                continue
            try:
                print("Extracting tool archive:", f, flush=True)
                if name.endswith(".zip"):
                    with zipfile.ZipFile(f) as z:
                        z.extractall(TOOL_CACHE_DIR)
                else:
                    with tarfile.open(f) as t:
                        t.extractall(TOOL_CACHE_DIR)
            except Exception as e:
                print("Tool archive extract skipped:", f, repr(e), flush=True)
    marker.write_text("ok", encoding="utf-8")


def find_tool_binary(exact_names=None, contains=None):
    exact_names = set(exact_names or [])
    contains = [x.lower() for x in (contains or [])]
    for name in exact_names:
        hit = shutil.which(name)
        if hit:
            safe_chmod_exec(hit)
            return hit
    search_roots = [TOOL_CACHE_DIR, Path("/kaggle/input"), Path("/kaggle/working"), Path("/tmp")]
    candidates = []
    for root in search_roots:
        if not root.exists():
            continue
        for f in root.rglob("*"):
            if not f.is_file():
                continue
            lname = f.name.lower()
            if f.name in exact_names or any(x in lname for x in contains):
                # Avoid selecting source files or archives.
                if lname.endswith((".py", ".txt", ".json", ".md", ".zip", ".tar", ".gz", ".xz", ".gguf")):
                    continue
                candidates.append(f)
    # Prefer files from extracted tool cache, then mounted tools dataset, then /tmp.
    candidates.sort(key=lambda x: (0 if str(x).startswith(str(TOOL_CACHE_DIR)) else 1, len(str(x))))
    for f in candidates:
        try:
            safe_chmod_exec(f)
            return str(f)
        except Exception:
            continue
    return ""


def prepare_cloudflared():
    maybe_extract_tool_archives()
    hit = find_tool_binary(exact_names=["cloudflared"], contains=["cloudflared"])
    if hit:
        dst = Path("/tmp/cloudflared")
        try:
            if Path(hit).resolve() != dst:
                shutil.copy2(hit, dst)
            safe_chmod_exec(dst)
            print("Using mounted cloudflared:", hit, flush=True)
            return str(dst)
        except Exception as e:
            print("Mounted cloudflared copy failed; will download:", repr(e), flush=True)
    sh("wget -q -O /tmp/cloudflared https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-amd64 && chmod +x /tmp/cloudflared")
    return "/tmp/cloudflared"


def install_deps():
    sh("python -m pip install -q --upgrade pip")
    sh("python -m pip install -q fastapi uvicorn httpx")
    prepare_cloudflared()


def find_gguf_model():
    roots = [Path("/kaggle/input"), Path("/kaggle/working")]
    files = []
    for root in roots:
        if not root.exists():
            continue
        files.extend(root.rglob("*.gguf"))
    if not files:
        raise RuntimeError("No .gguf model found under /kaggle/input. Please attach dataset dinosonicgo/qwen36-27b-q4-gguf-cache or model source dinosonicgo/qwen36-gguf-hermes/gguf/qwen36-gguf-hermes/1 to the Kaggle kernel.")

    def score(p: Path):
        s = str(p).lower()
        val = 0
        for kw, pts in [("qwen", 10), ("27", 8), ("q4", 6), ("k_m", 4), ("q4_k_m", 8), ("36", 3)]:
            if kw in s:
                val += pts
        try:
            val += min(10, p.stat().st_size // (1024 ** 3))
        except Exception:
            pass
        return val

    files.sort(key=score, reverse=True)
    chosen = files[0]
    print("GGUF candidates:", flush=True)
    for p in files[:10]:
        print(" -", p, flush=True)
    print("Selected GGUF:", chosen, flush=True)
    return str(chosen)


def find_existing_llama_server():
    maybe_extract_tool_archives()
    hit = find_tool_binary(exact_names=["llama-server", "server"], contains=["llama-server"])
    if hit:
        print("Found mounted llama-server candidate:", hit, flush=True)
        return hit
    return ""


def build_llama_cpp():
    src = Path("/tmp/llama.cpp")
    if not src.exists():
        sh("git clone --depth 1 https://github.com/ggml-org/llama.cpp /tmp/llama.cpp")
    build = src / "build"
    # 優先 CUDA；若 Kaggle 環境或目前 llama.cpp 選項不支援，退回 CPU build，至少保證 API 能啟動。
    cuda_cmd = "cmake -S /tmp/llama.cpp -B /tmp/llama.cpp/build -DGGML_CUDA=ON -DLLAMA_CURL=OFF -DCMAKE_BUILD_TYPE=Release && cmake --build /tmp/llama.cpp/build --target llama-server -j2"
    cpu_cmd = "cmake -S /tmp/llama.cpp -B /tmp/llama.cpp/build -DLLAMA_CURL=OFF -DCMAKE_BUILD_TYPE=Release && cmake --build /tmp/llama.cpp/build --target llama-server -j2"
    try:
        sh(cuda_cmd)
    except Exception:
        print("CUDA build failed; retrying CPU build so the endpoint can still start.", flush=True)
        sh("rm -rf /tmp/llama.cpp/build", check=False)
        sh(cpu_cmd)
    candidates = [
        build / "bin" / "llama-server",
        build / "bin" / "server",
        build / "examples" / "server" / "llama-server",
        build / "examples" / "server" / "server",
    ]
    for p in candidates:
        if p.exists():
            return str(p)
    raise RuntimeError("llama.cpp built, but llama-server binary was not found")


def ensure_llama_server():
    hit = find_existing_llama_server()
    if hit:
        print("Using llama-server:", hit, flush=True)
        return hit
    print("No llama-server binary found in mounted datasets; building llama.cpp from source.", flush=True)
    return build_llama_cpp()


def llama_cmd(binary, model_path, gpu_layers):
    cmd = [
        binary,
        "-m", model_path,
        "--host", "127.0.0.1",
        "--port", str(LLAMA_PORT),
        "--ctx-size", str(DEFAULT_CTX_SIZE),
        "--batch-size", str(DEFAULT_BATCH_SIZE),
        "--threads", str(DEFAULT_THREADS),
        "--alias", MODEL_NAME,
        "--parallel", "1",
        "-ngl", str(gpu_layers),
    ]
    return cmd


def start_llama_server(binary, model_path):
    forced = os.environ.get("LLAMA_N_GPU_LAYERS", "").strip()
    attempts = [int(forced)] if forced else [999, 80, 64, 48, 40, 32, 24, 16, 8, 0]
    last_rc = None
    for gl in attempts:
        cmd = llama_cmd(binary, model_path, gl)
        print("Starting llama-server:", " ".join(cmd), flush=True)
        p = subprocess.Popen(cmd)
        children.append(p)
        if wait_server(90, p):
            print(f"llama-server ready with n_gpu_layers={gl}", flush=True)
            return p, gl
        last_rc = p.poll()
        try:
            p.terminate()
            p.wait(timeout=15)
        except Exception:
            try:
                p.kill()
            except Exception:
                pass
        print(f"llama-server was not ready with n_gpu_layers={gl}; returncode={last_rc}; trying lower GPU offload.", flush=True)
    raise RuntimeError(f"llama-server did not become ready; last returncode={last_rc}")


def wait_server(timeout=900, proc=None):
    deadline = time.time() + timeout
    while time.time() < deadline:
        if proc is not None and proc.poll() is not None:
            return False
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{LLAMA_PORT}/v1/models", timeout=5) as r:
                if r.status == 200:
                    return True
        except Exception:
            time.sleep(5)
    return False


def write_proxy_app():
    code = f'''
import os, time, threading
import httpx
from fastapi import FastAPI, Request, Response
import uvicorn

BACKEND = "http://127.0.0.1:{LLAMA_PORT}"
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
        if not auth_ok(request):
            return Response('{{"error":"unauthorized"}}', status_code=401, media_type="application/json")
        def later():
            time.sleep(1)
            os._exit(0)
        threading.Thread(target=later, daemon=True).start()
        return Response('{{"ok":true,"message":"shutdown scheduled"}}', media_type="application/json")
    if not auth_ok(request):
        return Response('{{"error":"unauthorized"}}', status_code=401, media_type="application/json")
    touch()
    body = await request.body()
    url = BACKEND + request.url.path
    if request.url.query:
        url += "?" + request.url.query
    headers = {{k:v for k,v in request.headers.items() if k.lower() not in ["host", "content-length", "authorization"]}}
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
    p = subprocess.Popen([sys.executable, "/tmp/kaggle_proxy.py"])
    children.append(p)
    return p


def start_tunnel():
    log = "/tmp/cloudflared.log"
    p = subprocess.Popen(["/tmp/cloudflared", "tunnel", "--url", f"http://127.0.0.1:{PROXY_PORT}", "--logfile", log], stdout=subprocess.DEVNULL, stderr=subprocess.STDOUT)
    children.append(p)
    url = ""
    deadline = time.time() + 180
    while time.time() < deadline:
        if os.path.exists(log):
            txt = open(log, "r", encoding="utf-8", errors="ignore").read()
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
        publish_status("running", base_url, "Kaggle GGUF Qwen API running via llama.cpp")
        time.sleep(60)


def cleanup():
    for p in list(children):
        try:
            if p.poll() is None:
                p.terminate()
        except Exception:
            pass
    time.sleep(2)
    for p in list(children):
        try:
            if p.poll() is None:
                p.kill()
        except Exception:
            pass


def main():
    public_url = ""
    publish_status("starting", "", "Kaggle kernel starting; installing dependencies")
    try:
        install_deps()
        publish_status("starting", "", "Searching GGUF model and preparing llama.cpp")
        model_path = find_gguf_model()
        llama_bin = ensure_llama_server()
        publish_status("starting", "", "Starting llama.cpp GGUF model server")
        llama_proc, gpu_layers = start_llama_server(llama_bin, model_path)
        proxy = start_proxy()
        tunnel, public_url = start_tunnel()
        print("KAGGLE_PUBLIC_URL=" + public_url.rstrip("/"), flush=True)
        print("KAGGLE_BASE_URL=" + public_url.rstrip("/") + "/v1", flush=True)
        publish_status("running", public_url, f"Kaggle GGUF Qwen API running via llama.cpp; n_gpu_layers={gpu_layers}; ctx={DEFAULT_CTX_SIZE}")
        threading.Thread(target=heartbeat_loop, args=(public_url,), daemon=True).start()
        while True:
            time.sleep(5)
            if proxy.poll() is not None:
                publish_status("stopped", public_url, f"proxy exited with code {proxy.returncode}")
                break
            if llama_proc.poll() is not None:
                publish_status("error", public_url, f"llama-server exited with code {llama_proc.returncode}")
                break
    except Exception as e:
        publish_status("error", public_url, repr(e))
        raise
    finally:
        cleanup()


if __name__ == "__main__":
    main()
