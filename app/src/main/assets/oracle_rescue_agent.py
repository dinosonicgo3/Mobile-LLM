#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Oracle AI Rescue Agent v2.3.2
Runs on the Oracle host. The Android app is only a remote controller/bridge.
Main diagnosis/repair uses ONLY the selected main model; no main-model fallback.
Fallback is allowed only for 31B verifier: Google Gemma 4 31B -> NVIDIA NIM Gemma 4 31B.
Full authority mode: the LLM may execute shell commands, remove projects, stop services, kill processes, and use sudo -n.
No password prompt is used or requested. If sudo -n is unavailable, the command will fail and report the real permission error.
"""
import argparse, base64, difflib, json, os, re, shlex, subprocess, sys, time, traceback, urllib.request, urllib.error
from pathlib import Path

MAX_OBS = 4000
TOOL_CONTEXT_LIMIT = 2000
SESSION_LOG_PATH = None


def log_event(message):
    line = f"[oracle_rescue_agent] {now()} {message}"
    try:
        print(line, flush=True)
    except Exception:
        pass
    try:
        if SESSION_LOG_PATH:
            Path(SESSION_LOG_PATH).parent.mkdir(parents=True, exist_ok=True)
            with open(SESSION_LOG_PATH, "a", encoding="utf-8") as f:
                f.write(line + "\n")
    except Exception:
        pass


def limit(text, n):
    text = "" if text is None else str(text)
    return text if len(text) <= n else text[:n] + f"\n...（已截斷，原長度 {len(text)}）"


def now():
    return time.strftime("%Y-%m-%d %H:%M:%S %Z")


def default_base(provider):
    if provider == "nim": return "https://integrate.api.nvidia.com/v1"
    if provider == "gemini": return "https://generativelanguage.googleapis.com/v1beta/openai"
    return ""


def chat_once(cfg, messages, timeout=300):
    if not cfg: raise RuntimeError("model config empty")
    key = (cfg.get("api_key") or "").strip()
    if not key: raise RuntimeError(f"missing api_key for {cfg.get('label') or cfg.get('provider')}")
    model = (cfg.get("model") or cfg.get("modelName") or "").strip()
    if not model: raise RuntimeError("missing model name")
    provider = (cfg.get("provider") or "custom").strip()
    base = (cfg.get("base_url") or cfg.get("baseUrl") or default_base(provider)).rstrip("/")
    if not base: raise RuntimeError("missing base_url")
    url = base + "/chat/completions"
    payload = {
        "model": model,
        "messages": messages,
        "temperature": float(cfg.get("temperature", 0.0)),
        "max_tokens": int(cfg.get("max_tokens", 1024)),
    }
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", "Bearer " + key)
    t0 = time.time()
    log_event(f"LLM_CALL_START provider={provider} model={model} timeout={timeout}s messages={len(messages)}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", "replace")
    except TimeoutError as e:
        log_event(f"LLM_CALL_TIMEOUT provider={provider} model={model} elapsed={time.time()-t0:.1f}s")
        raise TimeoutError(f"LLM API read timed out after {timeout}s; provider={provider}; model={model}; base={base}; note=NVIDIA_NIM_large_models_may_need_up_to_300s") from e
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace") if e.fp else ""
        log_event(f"LLM_CALL_HTTP_ERROR provider={provider} model={model} code={e.code} elapsed={time.time()-t0:.1f}s body_head={limit(body, 500)}")
        raise RuntimeError(f"LLM API HTTP {e.code}; provider={provider}; model={model}; body={limit(body, 3000)}") from e
    except Exception as e:
        log_event(f"LLM_CALL_ERROR provider={provider} model={model} error={type(e).__name__}: {e} elapsed={time.time()-t0:.1f}s")
        raise
    root = json.loads(body)
    content = root["choices"][0]["message"].get("content", "")
    log_event(f"LLM_CALL_OK provider={provider} model={model} elapsed={time.time()-t0:.1f}s content_length={len(content or '')}")
    return content


def chat_chain(models, messages, timeout=300):
    errors = []
    for cfg in models:
        if not cfg or not (cfg.get("api_key") or "").strip():
            continue
        label = cfg.get("label") or cfg.get("provider") or cfg.get("model") or "model"
        try:
            text = chat_once(cfg, messages, timeout=timeout)
            if text and text.strip():
                return text, label, errors
            errors.append(f"{label}: empty response")
        except Exception as e:
            errors.append(f"{label}: {type(e).__name__}: {e}")
    raise RuntimeError("main model failed; fallback is disabled for diagnosis/repair: " + " | ".join(errors))


def safe_path(path):
    """Full authority path check.

    This is not a policy restriction; it only prevents shell/control-character injection.
    Any absolute path is allowed, including /home, /opt, /srv, /var/www, /etc, /root, etc.
    """
    p = (path or "").strip()
    if not p or not p.startswith("/"):
        return ""
    if any(x in p for x in ["\0", "\n", "\r"]):
        return ""
    return p


def is_safe_read_command(cmd):
    if not cmd: return False
    c = cmd.strip()
    if len(c) > 1000: return False
    low = " " + c.lower() + " "
    blocked = [" sudo ", " rm ", " mv ", " cp ", " chmod ", " chown ", " kill ", " reboot", " shutdown",
               " restart", " start ", " stop ", " enable ", " disable ", " install", " apt ", " yum ",
               " dnf ", " apk ", " pip install", " npm install", " tee ", " >", ">>", "| sh", "| bash"]
    if any(b in low for b in blocked): return False
    # allow compound read-only commands with && and pipes when each command starts with common read-only binary
    allowed_starts = (
        "pwd", "ls", "cat", "head", "tail", "grep", "find", "du", "df", "free", "uptime", "date", "whoami", "hostname",
        "ss", "netstat", "docker ps", "docker logs", "docker inspect", "docker compose ls", "docker compose ps",
        "systemctl status", "systemctl list-units", "systemctl --failed", "journalctl", "git status", "git log", "git branch", "git remote",
        "python3 -m py_compile", "python3 -m json.tool", "node --check", "bash -n"
    )
    parts = re.split(r"\s*(?:&&|\|)\s*", c)
    for part in parts:
        p = part.strip()
        if not p: continue
        if not p.startswith(allowed_starts):
            return False
    return True


def run(cmd, timeout=90):
    if not is_safe_read_command(cmd):
        return {"ok": False, "output": "指令被安全策略拒絕：只允許讀取/檢查型指令。"}
    try:
        p = subprocess.run(cmd, shell=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        out = f"$ {cmd}\nexitCode={p.returncode}\n"
        if p.stdout.strip(): out += "--- stdout ---\n" + p.stdout.strip() + "\n"
        if p.stderr.strip(): out += "--- stderr ---\n" + p.stderr.strip() + "\n"
        return {"ok": p.returncode == 0, "output": limit(out, MAX_OBS)}
    except subprocess.TimeoutExpired:
        return {"ok": False, "output": f"$ {cmd}\nTIMEOUT after {timeout}s"}
    except Exception as e:
        return {"ok": False, "output": f"command failed: {type(e).__name__}: {e}"}



def run_full(cmd, timeout=180):
    """Full authority shell bridge.

    No read-only policy is applied here. This intentionally allows rm, pkill,
    systemctl, docker rm, crontab edits, sudo -n, etc.
    The agent never prompts for sudo password; commands that require password
    must use sudo -n or will fail and report the error.
    """
    if not cmd or not str(cmd).strip():
        return {"ok": False, "output": "空指令。"}
    c = str(cmd)
    try:
        p = subprocess.run(c, shell=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        out = f"$ {c}\nexitCode={p.returncode}\n"
        if p.stdout.strip(): out += "--- stdout ---\n" + p.stdout.strip() + "\n"
        if p.stderr.strip(): out += "--- stderr ---\n" + p.stderr.strip() + "\n"
        return {"ok": p.returncode == 0, "output": limit(out, MAX_OBS)}
    except subprocess.TimeoutExpired:
        return {"ok": False, "output": f"$ {c}\nTIMEOUT after {timeout}s"}
    except Exception as e:
        return {"ok": False, "output": f"command failed: {type(e).__name__}: {e}"}


def shell_quote(x):
    return shlex.quote(str(x))


def expand_target_terms(target):
    """Expand common Chinese project names to folder/process aliases.

    This is not model fallback. It is deterministic discovery so remove_project
    can remove projects whose display name and directory slug differ.
    """
    raw = (target or "").strip()
    terms = []
    def add(x):
        x = (x or "").strip()
        if x and x not in terms:
            terms.append(x)
    add(raw)
    aliases = {
        "海參": ["haishen", "hermes-haishen", "haishen-hermes"],
        "海参": ["haishen", "hermes-haishen", "haishen-hermes"],
        "潤天蟹": ["runtianxie", "hermes-runtianxie", "runtianxie-hermes"],
        "润天蟹": ["runtianxie", "hermes-runtianxie", "runtianxie-hermes"],
    }
    low = raw.lower()
    for k, vals in aliases.items():
        if k in raw:
            for v in vals: add(v)
    if "haishen" in low:
        for v in ["海參", "海参", "hermes-haishen", "haishen-hermes"]: add(v)
    if "runtianxie" in low:
        for v in ["潤天蟹", "润天蟹", "hermes-runtianxie", "runtianxie-hermes"]: add(v)
    return terms



def compact_lines(text, max_lines=80, max_chars=12000):
    text = "" if text is None else str(text)
    lines = text.splitlines()
    if len(lines) > max_lines:
        text = "\n".join(lines[:max_lines]) + f"\n...（已截斷，原行數 {len(lines)}）"
    return limit(text, max_chars)


def path_mtime(path):
    try:
        return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(Path(path).stat().st_mtime))
    except Exception:
        return "unknown"


def looks_like_project_dir(path):
    p = Path(path)
    markers = [".git", "README.md", "readme.md", "package.json", "pyproject.toml", "requirements.txt", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts", "AndroidManifest.xml", "docker-compose.yml", "compose.yml", "Dockerfile", "AGENTS.md", "AI_ARCHITECTURE.md", "config.yaml", "config.yml", ".env", ".env.example"]
    return any((p / m).exists() for m in markers)


def shell_lines(cmd, timeout=90, max_lines=300):
    try:
        p = subprocess.run(cmd, shell=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        lines = [x.strip() for x in p.stdout.splitlines() if x.strip()]
        return lines[:max_lines]
    except Exception:
        return []


def scan_candidate_dirs():
    roots = ["/home/ubuntu", "/home/ubuntu/ai-agents", "/home/ubuntu/.config", "/home/ubuntu/_runtime", "/home/ubuntu/projects", "/home/ubuntu/apps", "/opt", "/srv", "/var/www", "/usr/local"]
    dirs = []
    seen = set()
    for root in roots:
        if not Path(root).exists():
            continue
        cmd = (
            "find " + shell_quote(root) + " -maxdepth 4 -type d "
            "\\( -name .git -o -name node_modules -o -name .venv -o -name venv -o -name __pycache__ \\) -prune -o "
            "-type f \\( -name README.md -o -name readme.md -o -name package.json -o -name pyproject.toml -o -name requirements.txt "
            "-o -name build.gradle -o -name build.gradle.kts -o -name settings.gradle -o -name settings.gradle.kts "
            "-o -name AndroidManifest.xml -o -name docker-compose.yml -o -name compose.yml -o -name Dockerfile "
            "-o -name AGENTS.md -o -name AI_ARCHITECTURE.md -o -name config.yaml -o -name config.yml \\) "
            "-printf '%h\\n' 2>/dev/null | sort -u | head -n 500"
        )
        for d in shell_lines(cmd, timeout=120, max_lines=500):
            sp = safe_path(d)
            if sp and sp not in seen:
                seen.add(sp)
                dirs.append(sp)
    for root in ["/home/ubuntu/ai-agents", "/home/ubuntu/.config", "/home/ubuntu/_runtime", "/home/ubuntu"]:
        if Path(root).exists():
            cmd = "find " + shell_quote(root) + " -maxdepth 2 -type d 2>/dev/null | head -n 300"
            for d in shell_lines(cmd, timeout=60, max_lines=300):
                sp = safe_path(d)
                if sp and sp not in seen and (looks_like_project_dir(sp) or any(x in Path(sp).name.lower() for x in ["hermes", "openclaw", "agent", "rescue", "ai", "llm", "runtime"])):
                    seen.add(sp)
                    dirs.append(sp)
    return dirs[:600]


def read_small_file(path, max_chars=3000):
    try:
        return Path(path).read_text(encoding="utf-8", errors="replace")[:max_chars]
    except Exception:
        return ""


def project_signals(path):
    p = Path(path)
    info = {"path": str(p), "name": p.name, "mtime": path_mtime(str(p)), "markers": [], "git_remote": "", "identity_text": ""}
    marker_files = ["README.md", "readme.md", "AGENTS.md", "AI_ARCHITECTURE.md", "package.json", "pyproject.toml", "requirements.txt", "build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle", "docker-compose.yml", "compose.yml", "Dockerfile", "config.yaml", "config.yml", ".env.example"]
    for m in [".git"] + marker_files:
        if (p / m).exists():
            info["markers"].append(m)
    if (p / ".git" / "config").exists():
        cfg = read_small_file(p / ".git" / "config", 2000)
        m = re.search(r"url\s*=\s*(.+)", cfg)
        if m:
            info["git_remote"] = m.group(1).strip()
    chunks = []
    for m in ["README.md", "readme.md", "AGENTS.md", "AI_ARCHITECTURE.md", "package.json", "pyproject.toml", "config.yaml", "config.yml", ".env.example"]:
        fp = p / m
        if fp.exists() and fp.is_file():
            chunks.append(f"--- {m} ---\n" + read_small_file(fp, 1800))
        if sum(len(c) for c in chunks) > 4500:
            break
    info["identity_text"] = limit("\n".join(chunks), 5000)
    return info


def score_candidate(query, info, terms):
    q = (query or "").lower()
    hay = "\n".join([info.get("path",""), info.get("name",""), info.get("git_remote",""), " ".join(info.get("markers",[])), info.get("identity_text","")]).lower()
    score = 0
    reasons = []
    for t in terms:
        tl = t.lower()
        if not tl:
            continue
        if tl in hay:
            score += 25
            reasons.append("term:" + t)
        for part in re.split(r"[^a-zA-Z0-9]+", tl):
            if len(part) >= 4 and part in hay:
                score += 8
                reasons.append("fragment:" + part)
    if q and q in hay:
        score += 15
        reasons.append("raw-query")
    if ".git" in info.get("markers", []):
        score += 5
        reasons.append("git")
    if "README.md" in info.get("markers", []) or "readme.md" in info.get("markers", []):
        score += 3
        reasons.append("readme")
    name = info.get("name","").lower()
    if any(x in name for x in ["hermes", "openclaw", "agent", "rescue", "llm", "ai"]):
        score += 4
        reasons.append("agent-like-name")
    return score, reasons[:10]


def discover_projects(query="", include_all=False):
    """Autonomous live project discovery for programming maintenance.

    This does not rely on fixed aliases only. It scans current Oracle directories,
    git/project markers, configs, runtime dirs, services, crontab, processes and Docker.
    """
    query = (query or "").strip()
    terms = expand_target_terms(query)
    dirs = scan_candidate_dirs()
    candidates = []
    for d in dirs:
        info = project_signals(d)
        score, reasons = score_candidate(query, info, terms)
        info["score"] = score
        info["reasons"] = reasons
        if include_all or score > 0 or any(x in info["name"].lower() for x in ["hermes", "openclaw", "agent", "rescue", "llm", "ai"]):
            candidates.append(info)
    candidates.sort(key=lambda x: (x.get("score",0), x.get("mtime","")), reverse=True)
    pattern_terms = terms or ([query] if query else [])
    if not pattern_terms:
        pattern_terms = ["hermes", "openclaw", "agent", "rescue", "llm", "ai"]
    pat = "|".join(re.escape(t) for t in pattern_terms if t) or "hermes|openclaw|agent|rescue|llm|ai"
    processes = shell_lines("ps aux | grep -Ei " + shell_quote(pat) + " | grep -v grep | head -n 80 || true", timeout=60, max_lines=80)
    services = shell_lines("systemctl list-units --type=service --all --no-pager 2>/dev/null | grep -Ei " + shell_quote(pat) + " | head -n 80 || true", timeout=60, max_lines=80)
    cron = shell_lines("crontab -l 2>/dev/null | grep -Ei " + shell_quote(pat) + " | head -n 80 || true", timeout=60, max_lines=80)
    docker = shell_lines("docker ps -a --format '{{.Names}} {{.Image}} {{.Status}}' 2>/dev/null | grep -Ei " + shell_quote(pat) + " | head -n 80 || true", timeout=60, max_lines=80)
    out = {
        "query": query,
        "expanded_terms": terms,
        "candidate_count": len(candidates),
        "candidates": candidates[:40],
        "runtime_evidence": {"processes": processes, "services": services, "crontab": cron, "docker": docker},
        "rule": "工具結果高於模型猜測；若 candidates 或 runtime_evidence 非空，不可回答未找到，只能列候選、繼續 resolve_project_identity 或說明不確定。"
    }
    return {"ok": True, "output": json.dumps(out, ensure_ascii=False, indent=2)[:60000]}


def resolve_project_identity(query="", paths=None):
    """Read identity evidence from candidate paths."""
    query = (query or "").strip()
    if isinstance(paths, str):
        paths = [paths]
    if not paths:
        disc = discover_projects(query, include_all=False)
        try:
            root = json.loads(disc["output"])
            paths = [c["path"] for c in root.get("candidates", [])[:8]]
        except Exception:
            paths = []
    resolved = []
    for pth in (paths or [])[:12]:
        sp = safe_path(pth)
        if not sp or not Path(sp).exists():
            continue
        info = project_signals(sp)
        extra = {}
        extra["tree"] = compact_lines("\n".join(shell_lines("find " + shell_quote(sp) + " -maxdepth 2 -type f 2>/dev/null | sed 's#^#- #' | head -n 120", timeout=60, max_lines=120)), 120, 10000)
        extra["recent_files"] = compact_lines("\n".join(shell_lines("find " + shell_quote(sp) + " -type f -printf '%TY-%Tm-%Td %TH:%TM %p\\n' 2>/dev/null | sort -r | head -n 80", timeout=60, max_lines=80)), 80, 10000)
        info["extra"] = extra
        resolved.append(info)
    out = {"query": query, "resolved_count": len(resolved), "projects": resolved, "instruction": "請根據 path/name/git_remote/README/config/recent_files 判斷哪個是最新且正確的目標專案；不確定時列出候選與理由，不可幻覺。"}
    return {"ok": True, "output": json.dumps(out, ensure_ascii=False, indent=2)[:60000]}


def create_maintenance_plan(question="", discovery=""):
    plan = {
        "goal": question,
        "must_follow": [
            "主模型不備援；主模型失敗直接報錯。",
            "動手前先規劃，先調查真實環境，不靠猜。",
            "專案/檔案/服務查找必須先 discover_projects，再 resolve_project_identity。",
            "工具結果高於模型推測；不得忽略候選路徑或與工具結果矛盾。",
            "修改/刪除/覆寫/停服務/殺進程前必須先備份或 quarantine。",
            "修程式使用 repair_file，以便自動 backup → write → test → 31B verify → rollback。",
            "需要 root 權限只能 sudo -n；失敗就回報權限錯誤，不要求密碼。",
            "最終回答前必須確認：是否真的執行、是否測試、是否仍有殘留、是否有不確定性。"
        ],
        "recommended_steps": [
            "1. 使用 discover_projects 取得目前雲端最新候選專案與 runtime evidence。",
            "2. 使用 resolve_project_identity 讀取候選 README/config/git/recent files 確認身份。",
            "3. 產生具體維修方案：目標、檔案、服務、備份、測試、成功標準、回滾方式。",
            "4. 執行最小必要工具；修改檔案走 repair_file，高風險移除走 remove_project 或 shell_exec + sudo -n。",
            "5. 執行驗證命令，並由 31B 後段驗證阻擋矛盾或幻覺。"
        ],
        "discovery_snapshot": discovery[:12000] if discovery else "尚未執行 discover_projects。"
    }
    return {"ok": True, "output": json.dumps(plan, ensure_ascii=False, indent=2)}


def verify_final_with_31b(req, question, transcript, answer):
    has_verifier = any((cfg or {}).get("api_key") for cfg in [req.get("verifier_google"), req.get("verifier_nim")])
    if not has_verifier:
        return "VERDICT: PASS\nRISK: MEDIUM\nTOOL_CONSISTENCY: NOT_CHECKED\nREASON: 未設定 31B 驗證 API Key；僅能依工具紀錄回覆。"
    msgs = [
        {"role":"system", "content":"你是 31B 無幻覺驗證官。只根據使用者問題、工具紀錄與最終回答判斷是否可送出。重點檢查：是否忽略工具結果、是否工具找到候選卻說沒找到、是否宣稱已執行但工具未執行、是否宣稱測試通過但沒有測試、是否違背主模型不備援/完整權限/sudo -n/備份驗證回滾規則。固定格式：\nVERDICT: PASS 或 FAIL\nRISK: LOW/MEDIUM/HIGH\nTOOL_CONSISTENCY: YES/NO\nHALLUCINATION_RISK: LOW/MEDIUM/HIGH\nREASON: 繁體中文"},
        {"role":"user", "content":"[USER_QUESTION]\n" + limit(question, 4000) + "\n\n[TOOL_TRANSCRIPT]\n" + limit(transcript, 30000) + "\n\n[FINAL_ANSWER]\n" + limit(answer, 12000)}
    ]
    failures = []
    for cfg in [req.get("verifier_google"), req.get("verifier_nim")]:
        if not cfg or not (cfg.get("api_key") or "").strip():
            continue
        try:
            out = chat_once(cfg, msgs, timeout=int(req.get("verifier_timeout_seconds", 300)))
            if out and out.strip():
                label = cfg.get("label") or cfg.get("model") or "verifier"
                return "VERIFIER_PROVIDER: " + label + "\n" + out.strip()
        except Exception as e:
            failures.append((cfg.get("label") or "verifier") + f": {type(e).__name__}: {e}")
    return "VERDICT: FAIL\nRISK: HIGH\nTOOL_CONSISTENCY: NO\nHALLUCINATION_RISK: HIGH\nREASON: 31B 最終一致性驗證不可用；依無幻覺規則阻擋。\n" + "\n".join(failures)


def final_verifier_passed(report):
    r = (report or "").upper()
    return "VERDICT: PASS" in r and "VERDICT: FAIL" not in r and "TOOL_CONSISTENCY: NO" not in r and "HALLUCINATION_RISK: HIGH" not in r


def is_programming_maintenance_task(question):
    q = (question or "").lower()
    keywords = [
        "專案", "檔案", "程式", "服務", "流程", "進程", "容器", "docker", "systemd", "crontab",
        "log", "日誌", "維修", "修正", "修復", "修改", "刪除", "移除", "完全移除", "重啟", "停止", "部署",
        "檢查", "查找", "存在", "目錄", "路徑", "錯誤", "bug", "github", "agent", "hermes", "openclaw",
        "海參", "海参", "潤天蟹", "润天蟹"
    ]
    return any(k in q for k in keywords)


def transcript_has_tool(transcript, tool_name):
    joined = "\n".join(transcript or [])
    return ("TOOL " + tool_name) in joined or ('"tool": "' + tool_name + '"') in joined or ('"tool":"' + tool_name + '"') in joined


def final_static_guard(question, transcript, answer):
    """Deterministic no-hallucination guard before the 31B verifier.

    This is not another model call. It prevents the main model from skipping the
    required programming-maintenance workflow even if it tries to output final.
    """
    q = question or ""
    a = answer or ""
    t = "\n".join(transcript or [])
    if is_programming_maintenance_task(q):
        has_discovery = transcript_has_tool(transcript, "discover_projects") or transcript_has_tool(transcript, "resolve_project_identity")
        has_shell = (transcript_has_tool(transcript, "ssh_exec") or transcript_has_tool(transcript, "shell_exec") or
                     transcript_has_tool(transcript, "run_terminal_command") or transcript_has_tool(transcript, "terminal_exec") or
                     transcript_has_tool(transcript, "remove_project") or transcript_has_tool(transcript, "repair_file") or
                     transcript_has_tool(transcript, "read_file") or transcript_has_tool(transcript, "list_dir"))
        # Pure command-like tests may use ssh_exec directly, but project/file/service maintenance must not finish without tools.
        if not (has_discovery or has_shell):
            return False, "最終回答被阻擋：這是程式/雲端維護任務，但尚未使用任何工具查證真實環境。請先用 run_terminal_command 查證。"
        project_words = ["專案", "查找", "存在", "移除", "刪除", "檢查", "維修", "修正", "服務", "檔案", "海參", "潤天蟹", "hermes", "openclaw"]
        if any(w in q.lower() for w in [x.lower() for x in project_words]) and not has_discovery:
            # Allow if the model used shell_exec/ssh_exec and transcript contains project-marker evidence.
            evidence_markers = [".git", "README", "package.json", "pyproject.toml", "systemctl", "crontab", "ps aux", "find ", "/home/", "/opt/", "/srv/"]
            if not any(m.lower() in t.lower() for m in evidence_markers):
                return False, "最終回答被阻擋：涉及專案/檔案/服務查找，但沒有 discover_projects/resolve_project_identity，也沒有足夠 shell 搜尋證據。"
        negative_claims = ["沒找到", "未找到", "不存在", "無殘留", "完全移除", "已完成", "測試通過", "驗證通過"]
        if any(x in a for x in negative_claims):
            if any(path in t for path in ["/home/", "/opt/", "/srv/", "/var/www", ".config", "_runtime", "ai-agents"]):
                if ("沒找到" in a or "未找到" in a or "不存在" in a) and not ("候選" in a or "不確定" in a):
                    return False, "最終回答被阻擋：工具紀錄已有路徑/候選證據，不能直接宣稱沒找到或不存在。請列出候選並繼續查證。"
            if ("測試通過" in a or "驗證通過" in a) and not ("VALIDATION" in t or "py_compile" in t or "node --check" in t or "docker compose config" in t or "31B" in t):
                return False, "最終回答被阻擋：沒有測試/驗證工具紀錄，不能宣稱測試或驗證通過。"
    return True, "PASS"

def remove_project(target="", paths=None):
    """Remove/quarantine any project target with full authority.

    This tool is intentionally powerful. It:
    - discovers target-related paths/processes/services/crontab lines
    - creates a tar.gz backup when possible
    - moves discovered paths into quarantine, falling back to sudo -n mv
    - kills target processes with pkill -f target using normal user and sudo -n
    - stops/disables matching systemd units with sudo -n when found
    - removes matching crontab lines for the current user
    It does not ask for passwords.
    """
    target = (target or "").strip()
    terms = expand_target_terms(target)
    if not target and not paths:
        return {"ok": False, "output": "remove_project 需要 target 或 paths。"}
    stamp = time.strftime("%Y%m%d_%H%M%S")
    safe_name = re.sub(r"[^A-Za-z0-9_.-]+", "_", target or "manual_paths").strip("_") or "target"
    base = Path.home() / ".oracle_ai_rescue"
    quarantine = base / "quarantine" / f"{safe_name}_{stamp}"
    backups = base / "backups"
    quarantine.mkdir(parents=True, exist_ok=True)
    backups.mkdir(parents=True, exist_ok=True)

    discovered = []
    if paths:
        if isinstance(paths, str):
            paths = [paths]
        for p in paths:
            sp = safe_path(p)
            if sp and sp not in discovered:
                discovered.append(sp)

    if target:
        # Broad discovery: user home, common project roots, config roots and systemd units.
        find_cmd = (
            "find /home /opt /srv /var/www /etc/systemd/system "
            "-maxdepth 7 \\( -iname " + shell_quote(f"*{target}*") + " -o -path " + shell_quote(f"*{target}*") + " \\) "
            "2>/dev/null | head -n 200"
        )
        p = subprocess.run(find_cmd, shell=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
        for line in p.stdout.splitlines():
            sp = safe_path(line.strip())
            if sp and sp not in discovered:
                discovered.append(sp)

    report = []
    report.append(f"target={target}")
    report.append("expanded_terms=" + ", ".join(terms))
    report.append(f"quarantine={quarantine}")
    report.append("==== DISCOVERED PATHS ====")
    report.extend(discovered or ["(none)"])

    # Process/service cleanup by all known display names and slugs.
    if terms:
        report.append("==== KILL PROCESSES ====")
        for term in terms:
            report.append(f"--- term={term} ---")
            for cmd in [
                "pkill -f " + shell_quote(term),
                "sudo -n pkill -f " + shell_quote(term),
                "killall " + shell_quote(term) + " 2>/dev/null",
                "sudo -n killall " + shell_quote(term) + " 2>/dev/null",
            ]:
                res = run_full(cmd, timeout=60)
                report.append(res["output"])

        report.append("==== SYSTEMD STOP/DISABLE MATCHING UNITS ====")
        list_units = subprocess.run(
            "systemctl list-units --type=service --all --no-legend 2>/dev/null | awk '{print $1}'",
            shell=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60
        )
        units = []
        for u in list_units.stdout.splitlines():
            uu = u.strip()
            if any(term.lower() in uu.lower() for term in terms) and uu not in units:
                units.append(uu)
        if not units:
            report.append("(no matching services)")
        for u in units[:50]:
            for cmd in [
                "sudo -n systemctl stop " + shell_quote(u),
                "sudo -n systemctl disable " + shell_quote(u),
            ]:
                report.append(run_full(cmd, timeout=60)["output"])

        report.append("==== CRONTAB CLEANUP CURRENT USER ====")
        pattern = "|".join(re.escape(t) for t in terms)
        cleanup = (
            "TMP=$(mktemp); "
            "crontab -l 2>/dev/null | grep -Evi " + shell_quote(pattern) + " > $TMP; "
            "crontab $TMP; RC=$?; rm -f $TMP; exit $RC"
        )
        report.append(run_full(cleanup, timeout=60)["output"])

    # Backup and quarantine paths.
    report.append("==== BACKUP AND QUARANTINE PATHS ====")
    for sp in discovered:
        name = re.sub(r"[^A-Za-z0-9_.-]+", "_", sp.strip("/")) or "path"
        backup_file = backups / f"{safe_name}_{name}_{stamp}.tar.gz"
        qdest = quarantine / name
        report.append(f"--- path={sp} ---")
        # Backup best effort.
        backup_cmd = "tar -czf " + shell_quote(str(backup_file)) + " -C / " + shell_quote(sp.lstrip("/"))
        backup_res = run_full(backup_cmd, timeout=300)
        if not backup_res["ok"]:
            backup_res = run_full("sudo -n " + backup_cmd, timeout=300)
        report.append("[backup]\n" + backup_res["output"])

        # Move to quarantine; fallback to sudo -n mv.
        mv_cmd = "mkdir -p " + shell_quote(str(quarantine)) + " && mv -- " + shell_quote(sp) + " " + shell_quote(str(qdest))
        mv_res = run_full(mv_cmd, timeout=180)
        if not mv_res["ok"]:
            mv_res = run_full("sudo -n " + mv_cmd, timeout=180)
        report.append("[quarantine]\n" + mv_res["output"])

    # Verify.
    report.append("==== VERIFY REMAINS ====")
    if terms:
        pattern = "|".join(re.escape(t) for t in terms)
        name_tests = " ".join("-iname " + shell_quote(f"*{t}*") + " -o" for t in terms)
        name_tests = name_tests[:-3] if name_tests.endswith(" -o") else name_tests
        verify_cmd = (
            "echo '[process]'; ps aux | grep -Ei " + shell_quote(pattern) + " | grep -v grep || true; "
            "echo '[paths]'; find /home /opt /srv /var/www /etc/systemd/system -maxdepth 7 \\( " + name_tests + " \\) 2>/dev/null | head -n 100 || true; "
            "echo '[services]'; systemctl list-units --type=service --all 2>/dev/null | grep -Ei " + shell_quote(pattern) + " || true; "
            "echo '[crontab]'; crontab -l 2>/dev/null | grep -Ei " + shell_quote(pattern) + " || true"
        )
        report.append(run_full(verify_cmd, timeout=120)["output"])

    return {"ok": True, "output": limit("\n".join(report), 30000)}

def read_file(path):
    p = safe_path(path)
    if not p: return {"ok": False, "output": "read_file 被拒絕：路徑必須是絕對路徑，且不能包含控制字元。"}
    try:
        data = Path(p).read_text(encoding="utf-8", errors="replace")
        return {"ok": True, "output": f"FILE={p}\nSIZE={len(data)}\n\n" + limit(data, 30000)}
    except Exception as e:
        return {"ok": False, "output": f"read_file failed: {type(e).__name__}: {e}"}


def validate_file(path):
    p = safe_path(path)
    if not p: return {"ok": False, "output": "validate_file 路徑被拒絕。"}
    cmds = [f"ls -lh {shlex.quote(p)}"]
    if p.endswith(".py"):
        cmds.append(f"python3 -m py_compile {shlex.quote(p)}")
    elif p.endswith((".js", ".mjs", ".cjs")):
        cmds.append(f"node --check {shlex.quote(p)}")
    elif p.endswith((".sh", ".bash")):
        cmds.append(f"bash -n {shlex.quote(p)}")
    elif p.endswith(".json"):
        cmds.append(f"python3 -m json.tool {shlex.quote(p)}")
    d = str(Path(p).parent)
    if Path(d, "docker-compose.yml").exists() or Path(d, "compose.yml").exists():
        cmds.append(f"cd {shlex.quote(d)} && docker compose config -q")
    outputs = []
    ok = True
    for c in cmds:
        r = run(c, timeout=120)
        outputs.append(r["output"])
        ok = ok and r["ok"]
    return {"ok": ok, "output": limit("\n".join(outputs), 20000)}


def strip_code_fence(text):
    x = (text or "").strip()
    m = re.match(r"^```[a-zA-Z0-9_-]*\s*\n(.*?)\n```\s*$", x, flags=re.S)
    return m.group(1) if m else x


def parse_json_tool(text):
    x = (text or "").strip()
    # code fence tolerant
    x = re.sub(r"^```(?:json)?\s*", "", x).strip()
    x = re.sub(r"```$", "", x).strip()
    try:
        return json.loads(x)
    except Exception:
        pass
    start = x.find("{")
    if start >= 0:
        depth = 0
        in_str = False
        esc = False
        for i in range(start, len(x)):
            ch = x[i]
            if in_str:
                if esc: esc = False
                elif ch == "\\": esc = True
                elif ch == '"': in_str = False
            else:
                if ch == '"': in_str = True
                elif ch == "{": depth += 1
                elif ch == "}":
                    depth -= 1
                    if depth == 0:
                        try:
                            return json.loads(x[start:i+1])
                        except Exception:
                            return None
    return None


def unified_diff(a, b, path):
    return "".join(difflib.unified_diff(a.splitlines(True), b.splitlines(True), fromfile=path+".old", tofile=path+".new"))


def verifier_passed(report):
    r = (report or "").upper()
    return "VERDICT: PASS" in r and "VERDICT: FAIL" not in r and "RISK: HIGH" not in r and "ROLLBACK_REQUIRED: YES" not in r and "TESTS_PASSED: NO" not in r


def verify_with_31b(req, stage, path, instruction, original, proposed, diff, tests):
    msgs = [
        {"role":"system", "content":"你是 Gemma 4 31B 後段驗證模型。只根據 diff 與測試輸出判斷修復是否可保留。請固定格式：\nVERDICT: PASS 或 FAIL\nRISK: LOW/MEDIUM/HIGH\nCHANGED: YES/NO\nTESTS_PASSED: YES/NO/NOT_RUN\nNEW_BUG_RISK: LOW/MEDIUM/HIGH\nROLLBACK_REQUIRED: YES/NO\nREASON: 繁體中文\nUSER_SUMMARY: 繁體中文"},
        {"role":"user", "content": f"STAGE={stage}\nFILE={path}\nREQUEST={instruction}\n\n[DIFF]\n{limit(diff,16000)}\n\n[TESTS]\n{limit(tests,12000)}\n\n[ORIGINAL_HEAD]\n{limit(original,10000)}\n\n[PROPOSED_HEAD]\n{limit(proposed,10000)}"}
    ]
    failures = []
    for cfg in [req.get("verifier_google"), req.get("verifier_nim")]:
        if not cfg or not (cfg.get("api_key") or "").strip():
            failures.append((cfg or {}).get("label", "verifier") + ": missing key")
            continue
        try:
            out = chat_once(cfg, msgs, timeout=int(req.get("verifier_timeout_seconds", 300)))
            if out and out.strip():
                label = cfg.get("label") or cfg.get("model") or "verifier"
                return f"VERIFIER_PROVIDER: {label}\n\n" + out.strip()
            failures.append((cfg.get("label") or "verifier") + ": empty")
        except Exception as e:
            failures.append((cfg.get("label") or "verifier") + f": {type(e).__name__}: {e}")
    return "VERDICT: FAIL\nRISK: HIGH\nCHANGED: UNKNOWN\nTESTS_PASSED: UNKNOWN\nNEW_BUG_RISK: HIGH\nROLLBACK_REQUIRED: YES\nREASON: 31B 驗證不可用。\nUSER_SUMMARY: 後段驗證失敗，不能保留修復。\n" + "\n".join(failures)


def repair_file(req, models, path, instruction):
    p = safe_path(path)
    if not p: return {"ok": False, "output": "repair_file 被拒絕：路徑必須是絕對路徑，且不能包含控制字元。"}
    try:
        original = Path(p).read_text(encoding="utf-8", errors="replace")
    except Exception as e:
        return {"ok": False, "output": f"讀取原檔失敗：{type(e).__name__}: {e}"}
    gen_msgs = [
        {"role":"system", "content":"你是程式修復模型。請只輸出修正後的完整檔案內容，不要 Markdown，不要解釋。必須保留原功能，只做必要修正。"},
        {"role":"user", "content": f"檔案路徑：{p}\n修正要求：{instruction}\n\n原始檔案：\n{limit(original,50000)}"}
    ]
    try:
        proposed, used, errs = chat_chain(models, gen_msgs, timeout=int(req.get("repair_model_timeout_seconds", 300)))
    except Exception as e:
        return {"ok": False, "output": f"主模型產生修正版失敗：{type(e).__name__}: {e}\n主模型不使用備援；請修正目前選定模型錯誤後重試。"}
    proposed = strip_code_fence(proposed)
    if not proposed.strip() or proposed == original:
        return {"ok": False, "output": "主模型沒有產生有效修改，或修正版與原檔相同。"}
    diff = unified_diff(original, proposed, p)
    pre = verify_with_31b(req, "PRE_WRITE_REVIEW", p, instruction, original, proposed, diff, "尚未寫回，尚無測試。")
    if not verifier_passed(pre):
        return {"ok": False, "output": "31B 寫回前預審未通過，已阻擋寫回。\n\n" + pre + "\n\nDIFF:\n" + limit(diff, 12000)}
    backup = p + ".bak_" + time.strftime("%Y%m%d_%H%M%S")
    try:
        Path(backup).write_text(original, encoding="utf-8")
        Path(p).write_text(proposed, encoding="utf-8")
    except Exception as e:
        return {"ok": False, "output": f"備份或寫回失敗：{type(e).__name__}: {e}"}
    val = validate_file(p)
    final = verify_with_31b(req, "FINAL_AFTER_TESTS", p, instruction, original, proposed, diff, val["output"])
    if not val["ok"] or not verifier_passed(final):
        try:
            Path(p).write_text(original, encoding="utf-8")
            rb = "已回滾原檔。"
        except Exception as e:
            rb = f"回滾失敗：{type(e).__name__}: {e}"
        return {"ok": False, "output": "修復驗證失敗。" + rb + "\n\n備份：" + backup + "\n\n[VALIDATION]\n" + val["output"] + "\n\n[31B FINAL]\n" + final}
    return {"ok": True, "output": "安全修復完成並通過 31B 驗證。\n檔案：" + p + "\n備份：" + backup + "\n主模型：" + used + "\n\n[VALIDATION]\n" + val["output"] + "\n\n[31B FINAL]\n" + final}


def execute_tool(req, models, tool_obj):
    tool = (tool_obj.get("tool") or "").strip()
    args = tool_obj.get("args") or {}
    if tool == "maintenance_plan":
        return create_maintenance_plan(args.get("question") or req.get("question") or "", args.get("discovery") or "")
    if tool == "discover_projects":
        return discover_projects(args.get("query") or req.get("question") or "", bool(args.get("include_all", False)))
    if tool == "resolve_project_identity":
        return resolve_project_identity(args.get("query") or req.get("question") or "", args.get("paths"))
    if tool in ("ssh_exec", "shell_exec", "run_ssh_command", "run_terminal_command", "terminal_exec"):
        try:
            t = int(args.get("timeout_seconds") or 30)
        except Exception:
            t = 30
        t = max(5, min(300, t))
        return run_full(args.get("command") or "", timeout=t)
    if tool == "remove_project":
        return remove_project(args.get("target") or "", args.get("paths"))
    if tool == "read_file":
        return read_file(args.get("path") or "")
    if tool == "list_dir":
        p = safe_path(args.get("path") or "")
        if not p: return {"ok": False, "output":"list_dir path rejected"}
        return run_full(f"ls -la {shlex.quote(p)} && find {shlex.quote(p)} -maxdepth 2 -type f | head -n 200", timeout=120)
    if tool == "repair_file":
        return repair_file(req, models, args.get("path") or "", args.get("instruction") or req.get("question") or "")
    if tool == "final":
        return {"ok": True, "final": True, "output": tool_obj.get("answer") or args.get("answer") or ""}
    return {"ok": False, "output": "未知工具：" + tool}



# =========================
# v2.3.3 cloud-local terminal runtime tool loop
# =========================

def tool_schemas():
    """Exactly one Gemini-style terminal tool.

    Do not expose project-specific tools.  The model decides shell commands;
    this runtime executes them locally on the Oracle VM.  Output returned to
    the model is intentionally capped, matching the working Gemini version's
    small observation style.
    """
    return [{
        "type": "function",
        "function": {
            "name": "run_terminal_command",
            "description": "Execute one shell command locally on the current Oracle Cloud Ubuntu host. Use this for live environment inspection, project discovery, reading logs/files, backups, edits, tests and service control. Keep commands focused and bounded.",
            "parameters": {
                "type": "object",
                "properties": {
                    "command": {"type": "string", "description": "Shell command to execute on this Oracle host"},
                    "timeout_seconds": {"type": "integer", "description": "Optional command timeout, default 30, max 300"}
                },
                "required": ["command"]
            }
        }
    }]

def build_runtime_system(req):
    # Gemini-style, but cloud-local: short, operational, no fixed project list.
    return (
        "你是在 Oracle Cloud Ubuntu 主機本機執行的維護 Agent。"
        "你不知道目前有哪些專案，因為環境會變動；必須用 run_terminal_command 查證當下環境。"
        "你只有一個工具 run_terminal_command；用它執行必要且短小的 shell 指令。"
        "每次只做最小檢查，不要一次列大量資料；工具結果足夠時立刻給最終答案。"
        "一般檢查任務最多 1 到 2 次工具；不要反覆問候、不要反覆環境檢查。"
        "需要更深入維修時才繼續查檔、備份、修改、測試。"
        "修改、刪除、停止服務或殺進程前必須先確認目標並備份/隔離；root 權限只用 sudo -n。"
        "最終回答只能根據工具結果；沒查證不可說已確認，沒測試不可說測試通過。"
    )


def normalize_message_for_native(msg):
    """Keep OpenAI-compatible messages compact and valid."""
    role = msg.get("role")
    if role == "assistant" and msg.get("tool_calls"):
        return {"role":"assistant", "content": msg.get("content"), "tool_calls": msg.get("tool_calls")}
    if role == "tool":
        return {"role":"tool", "tool_call_id": msg.get("tool_call_id"), "content": limit(msg.get("content") or "", TOOL_CONTEXT_LIMIT)}
    return {"role": role, "content": limit(msg.get("content") or "", 6000)}


def openai_chat_completion(cfg, messages, timeout=300, tools=None, tool_choice="auto", stream=True, max_tokens=None):
    if not cfg: raise RuntimeError("model config empty")
    key = (cfg.get("api_key") or "").strip()
    if not key: raise RuntimeError(f"missing api_key for {cfg.get('label') or cfg.get('provider')}")
    model = (cfg.get("model") or cfg.get("modelName") or "").strip()
    if not model: raise RuntimeError("missing model name")
    provider = (cfg.get("provider") or "custom").strip()
    base = (cfg.get("base_url") or cfg.get("baseUrl") or default_base(provider)).rstrip("/")
    if not base: raise RuntimeError("missing base_url")
    url = base + "/chat/completions"
    payload = {
        "model": model,
        "messages": [normalize_message_for_native(m) for m in messages],
        "temperature": float(cfg.get("temperature", 0.0)),
        "max_tokens": int(max_tokens if max_tokens is not None else cfg.get("max_tokens", 1024)),
    }
    if tools:
        payload["tools"] = tools
        payload["tool_choice"] = tool_choice or "auto"
    if stream:
        payload["stream"] = True
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", "Bearer " + key)
    t0 = time.time()
    log_event(f"NATIVE_LLM_CALL_START provider={provider} model={model} timeout={timeout}s stream={stream} tools={bool(tools)} messages={len(messages)}")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if stream:
                return read_streaming_chat(resp, provider, model, t0)
            body = resp.read().decode("utf-8", "replace")
    except TimeoutError as e:
        log_event(f"NATIVE_LLM_CALL_TIMEOUT provider={provider} model={model} elapsed={time.time()-t0:.1f}s")
        raise TimeoutError(f"LLM API read timed out after {timeout}s; provider={provider}; model={model}; base={base}") from e
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace") if e.fp else ""
        log_event(f"NATIVE_LLM_CALL_HTTP_ERROR provider={provider} model={model} code={e.code} elapsed={time.time()-t0:.1f}s body_head={limit(body, 700)}")
        raise RuntimeError(f"LLM API HTTP {e.code}; provider={provider}; model={model}; body={limit(body, 3000)}") from e
    except Exception as e:
        log_event(f"NATIVE_LLM_CALL_ERROR provider={provider} model={model} error={type(e).__name__}: {e} elapsed={time.time()-t0:.1f}s")
        raise
    root = json.loads(body)
    msg = root["choices"][0].get("message") or {}
    content = msg.get("content") or ""
    tool_calls = msg.get("tool_calls") or []
    log_event(f"NATIVE_LLM_CALL_OK provider={provider} model={model} elapsed={time.time()-t0:.1f}s content_length={len(content)} tool_calls={len(tool_calls)}")
    return {"content": content, "tool_calls": tool_calls, "raw": root}


def read_streaming_chat(resp, provider, model, t0):
    content_parts = []
    tool_acc = {}
    raw_events = 0
    for raw in resp:
        line = raw.decode("utf-8", "replace").strip()
        if not line:
            continue
        if line.startswith("data:"):
            line = line[5:].strip()
        if not line or line == "[DONE]":
            continue
        try:
            ev = json.loads(line)
        except Exception:
            continue
        raw_events += 1
        choices = ev.get("choices") or []
        if not choices:
            continue
        delta = choices[0].get("delta") or {}
        if delta.get("content"):
            content_parts.append(delta.get("content") or "")
        for tc in delta.get("tool_calls") or []:
            idx = int(tc.get("index", 0))
            cur = tool_acc.setdefault(idx, {"id":"", "type":"function", "function":{"name":"", "arguments":""}})
            if tc.get("id"):
                cur["id"] += tc.get("id") if not cur["id"] else ""
            if tc.get("type"):
                cur["type"] = tc.get("type")
            fn = tc.get("function") or {}
            if fn.get("name"):
                cur["function"]["name"] += fn.get("name")
            if fn.get("arguments"):
                cur["function"]["arguments"] += fn.get("arguments")
    content = "".join(content_parts)
    tool_calls = [tool_acc[i] for i in sorted(tool_acc.keys())]
    # Some providers omit id in streaming chunks; synthesize a stable id.
    for i, tc in enumerate(tool_calls):
        if not tc.get("id"):
            tc["id"] = f"call_{int(time.time()*1000)}_{i}"
    log_event(f"NATIVE_LLM_STREAM_DONE provider={provider} model={model} elapsed={time.time()-t0:.1f}s content_length={len(content)} tool_calls={len(tool_calls)} events={raw_events}")
    return {"content": content, "tool_calls": tool_calls, "raw": None}



def parse_loose_tool_protocol(text):
    """Parse Gemma/Gemini-style textual tool blocks robustly.

    Supports pure JSON, markdown fenced JSON, [TOOL_CALL] blocks,
    tool => / args => notation, and TOOL:/ARGS: text.
    """
    raw = text or ""
    obj = parse_json_tool(raw)
    if obj:
        return obj
    m = re.search(r"\[TOOL_CALL\]([\s\S]*?)\[/TOOL_CALL\]", raw, re.I)
    block = m.group(1).strip() if m else raw
    if re.search(r"tool\s*=>", block) or re.search(r"args\s*=>", block):
        tool_m = re.search(r"tool\s*=>\s*['\"]?([A-Za-z0-9_\-]+)['\"]?", block)
        cmd_m = (re.search(r"--command\s+(['\"])(.*?)\1", block, re.S) or
                 re.search(r"command\s*[:=]>?\s*(['\"])(.*?)\1", block, re.S) or
                 re.search(r"command\s*[:=]\s*([^,}\n]+)", block, re.S))
        timeout_m = re.search(r"(?:timeout_seconds|timeout)\s*[:=]>?\s*([0-9]+)", block)
        if tool_m:
            args = {}
            if cmd_m:
                if cmd_m.lastindex and cmd_m.lastindex >= 2:
                    args["command"] = cmd_m.group(2).strip()
                else:
                    args["command"] = cmd_m.group(1).strip()
            if timeout_m:
                try: args["timeout_seconds"] = int(timeout_m.group(1))
                except Exception: pass
            return {"tool": tool_m.group(1), "args": args}
    tool_m = re.search(r"(?im)^\s*TOOL\s*:\s*([A-Za-z0-9_\-]+)\s*$", block)
    if tool_m:
        args = {}
        cmd_m = re.search(r"(?im)^\s*ARGS\s*:\s*(?:command\s*=\s*)?(.+)$", block)
        if cmd_m:
            args["command"] = cmd_m.group(1).strip().strip('"\'')
        return {"tool": tool_m.group(1), "args": args}
    return None

def legacy_json_tool_call(cfg, messages, timeout=60):
    """Compatibility fallback for endpoints/models that reject native tools.

    Same main model, no fallback.  It uses a short terminal protocol instead of
    OpenAI tools when a provider rejects stream+tools.
    """
    short_tools = """
只輸出一個工具呼叫或 final，不要長篇解釋。
優先格式：
{"tool":"run_terminal_command","args":{"command":"...","timeout_seconds":30}}
或 {"tool":"final","answer":"..."}
也接受 [TOOL_CALL] 區塊，但只能呼叫 run_terminal_command 或 final。
所有查專案、讀檔、查 log、修程式、跑測試、清理、刪除、服務控制，都用 run_terminal_command 轉成雲端本機 shell 指令。
工具結果足夠時立刻 final，不要反覆檢查。
"""
    compact = []
    for m in messages[-5:]:
        if m.get("role") == "tool":
            compact.append({"role":"user", "content":"工具結果：\n" + limit(m.get("content") or "", 2500)})
        elif m.get("role") == "assistant" and m.get("tool_calls"):
            compact.append({"role":"assistant", "content":"已呼叫工具。"})
        else:
            compact.append({"role":m.get("role"), "content":limit(m.get("content") or "", 4000)})
    compact.insert(0, {"role":"system", "content":"你是 Oracle 雲端本機終端工具路由器。" + short_tools})
    text = chat_once(dict(cfg, max_tokens=256), compact, timeout=timeout)
    obj = parse_loose_tool_protocol(text)
    if not obj:
        if (text or "").strip() and any(m.get("role") == "tool" for m in messages):
            return {"content": text.strip(), "tool_calls": []}
        raise RuntimeError("legacy JSON tool call returned unparsable output: " + limit(text, 1000))
    if obj.get("tool") == "final":
        return {"content": obj.get("answer") or (obj.get("args") or {}).get("answer") or "", "tool_calls": []}
    name = obj.get("tool") or ""
    if name == "run_ssh_command": name = "run_terminal_command"
    args = obj.get("args") or {}
    return {"content":"", "tool_calls":[{"id":f"legacy_{int(time.time()*1000)}", "type":"function", "function":{"name":name, "arguments":json.dumps(args, ensure_ascii=False)}}]}


def call_main_for_tools(req, cfg, messages):
    """Route the main model to the single terminal tool.

    v2.3.3 fixes:
    - Google/Gemini: do NOT use native stream+tools because the uploaded log
      showed HTTP 500 with gemma-4-31b-it. Use the same model via a short,
      tolerant textual terminal protocol.
    - NIM/OpenAI-compatible: use native streamed tools, bounded router timeout.
    - Never chain stream -> non-stream -> legacy slow calls in the same step.
    """
    provider = (cfg.get("provider") or "custom").strip().lower()
    route_timeout = int(req.get("tool_route_timeout_seconds", 90))
    route_timeout = max(20, min(120, route_timeout))
    if provider == "gemini":
        legacy_timeout = int(req.get("legacy_tool_router_timeout_seconds", 45))
        legacy_timeout = max(20, min(90, legacy_timeout))
        return legacy_json_tool_call(cfg, messages, timeout=legacy_timeout), "gemini-json-terminal"
    return openai_chat_completion(
        cfg, messages, timeout=route_timeout, tools=tool_schemas(), tool_choice="auto",
        stream=True, max_tokens=int(req.get("tool_router_max_tokens", 256))
    ), "native-single-terminal-stream"


def extract_textual_terminal_tool(content):
    """Convert textual tool blocks into a native-looking terminal call."""
    obj = parse_loose_tool_protocol(content or "")
    if not obj:
        return []
    name = obj.get("tool") or ""
    if name == "run_ssh_command":
        name = "run_terminal_command"
    if name != "run_terminal_command":
        return []
    args = obj.get("args") or {}
    command = args.get("command") or ""
    if not command:
        return []
    try:
        timeout_seconds = int(args.get("timeout_seconds") or args.get("timeout") or 30)
    except Exception:
        timeout_seconds = 30
    return [{
        "id": f"textual_{int(time.time()*1000)}",
        "type": "function",
        "function": {"name": "run_terminal_command", "arguments": json.dumps({"command": command, "timeout_seconds": timeout_seconds}, ensure_ascii=False)}
    }]


def parse_tool_call(tc):
    fn = tc.get("function") or {}
    name = (fn.get("name") or "").strip()
    arg_text = fn.get("arguments") or "{}"
    try:
        args = json.loads(arg_text) if isinstance(arg_text, str) else (arg_text or {})
    except Exception:
        args = {}
    return {"tool": name, "args": args}


def is_mutating_tool(tool_name, args):
    name = (tool_name or "").strip()
    if name in ("repair_file", "remove_project"):
        return True
    if name in ("shell_exec", "ssh_exec", "run_ssh_command", "run_terminal_command", "terminal_exec"):
        cmd = str((args or {}).get("command") or "").lower()
        risky = ["rm ", "rm -rf", "mv ", "cp ", "tee ", ">", ">>", "pkill", "killall", "systemctl stop", "systemctl disable", "docker rm", "crontab", "chmod", "chown", "apt ", "pip install", "npm install"]
        return any(x in cmd for x in risky)
    return False


def final_needs_31b(req, transcript, mutating_used):
    if req.get("always_verify_final") is True:
        return True
    if mutating_used:
        return True
    text = "\n".join(transcript or "")
    return "repair_file" in text or "remove_project" in text or "VALIDATION" in text


def is_deep_or_mutating_request(question):
    q = (question or "").lower()
    markers = ["修", "修改", "修復", "刪除", "移除", "停止", "重啟", "kill", "rm ", "部署", "更新", "寫入", "覆寫", "安裝", "編譯", "github actions", "apk", "錯誤"]
    return any(m in q for m in markers)


def render_evidence_answer(question, transcript, reason=""):
    print("# Oracle Rescue Agent 已取得工具證據\n")
    if reason:
        print(reason + "\n")
    print("以下內容只包含已執行工具的結果；未額外推測。\n")
    print("```text")
    print(limit("\n\n".join(transcript or []), 24000))
    print("```")
    return 0


def run_native_tool_loop(req, models):
    question = req.get("question") or ""
    cfg = models[0]
    messages = [
        {"role":"system", "content": build_runtime_system(req)},
        {"role":"user", "content": question},
    ]
    transcript = []
    used_modes = []
    mutating_used = False
    requested_steps = int(req.get("max_steps", 4))
    deep = is_deep_or_mutating_request(question)
    hard_cap = 6 if deep else 4
    max_steps = max(2, min(requested_steps, hard_cap))
    total_timeout = int(req.get("total_task_timeout_seconds", 900 if deep else 240))
    total_timeout = max(60, min(1200, total_timeout))
    started = time.time()
    static_retry_used = False
    no_tool_turns_after_evidence = 0
    for step in range(1, max_steps + 1):
        if time.time() - started > total_timeout:
            if transcript:
                return render_evidence_answer(question, transcript, "任務達到總時間上限，已停止追加模型輪次。")
            print("# Oracle Rescue Agent 超過總時間上限，且尚未取得工具結果")
            return 1
        print(f"[oracle_rescue_agent] step={step} calling main model tool loop...", flush=True)
        try:
            resp, mode = call_main_for_tools(req, cfg, messages)
            used_modes.append(mode)
        except Exception as e:
            if transcript and not mutating_used:
                return render_evidence_answer(question, transcript, f"模型後續整理失敗：{type(e).__name__}: {e}")
            print("# Oracle Rescue Agent 模型失敗\n")
            print(f"步驟 {step} 呼叫主模型失敗：{type(e).__name__}: {e}\n")
            print("主模型不使用備援；這是刻意設計，方便正確定位 API / 模型 / 工具提示錯誤。\n")
            if transcript:
                print("## 已取得工具結果\n```text")
                print(limit("\n\n".join(transcript), 24000))
                print("```")
            return 1
        tool_calls = resp.get("tool_calls") or []
        content = resp.get("content") or ""
        if not tool_calls:
            tool_calls = extract_textual_terminal_tool(content)
            if tool_calls:
                log_event("TEXTUAL_TOOL_CALL_CONVERTED count=" + str(len(tool_calls)))
        if tool_calls:
            assistant_msg = {"role":"assistant", "content": content or None, "tool_calls": tool_calls}
            messages.append(assistant_msg)
            for tc in tool_calls[:2]:
                obj = parse_tool_call(tc)
                name = obj.get("tool")
                if name == "run_ssh_command": name = "run_terminal_command"
                args = obj.get("args") or {}
                print(f"[oracle_rescue_agent] step={step} executing native tool={name}", flush=True)
                log_event(f"NATIVE_TOOL_START step={step} tool={name}")
                if is_mutating_tool(name, args):
                    mutating_used = True
                res = execute_tool(req, models, {"tool": name, "args": args})
                raw_out = res.get("output")
                out = limit(raw_out, MAX_OBS)
                obs = limit(raw_out, TOOL_CONTEXT_LIMIT)
                log_event(f"NATIVE_TOOL_END step={step} tool={name} ok={res.get('ok')} output_length={len(str(raw_out or ''))} context_length={len(obs)}")
                transcript.append(f"STEP {step} TOOL {name} ok={res.get('ok')}\n{out}")
                messages.append({"role":"tool", "tool_call_id": tc.get("id") or f"call_{step}", "name": name or "run_terminal_command", "content": obs})
            if len(tool_calls) > 2:
                transcript.append(f"STEP {step} TOOL_LIMIT ok=True\n模型一次要求 {len(tool_calls)} 個工具；為避免長時間循環，本輪只執行前 2 個。")
            continue
        answer = content.strip()
        if not answer:
            no_tool_turns_after_evidence += 1
            if transcript:
                return render_evidence_answer(question, transcript, "模型未輸出最終文字；已停止追加輪次。")
            if no_tool_turns_after_evidence >= 1:
                print("# Oracle Rescue Agent 模型沒有輸出工具或答案")
                return 1
            messages.append({"role":"user", "content":"請立刻呼叫 run_terminal_command 查證環境，或回答無法處理的原因。"})
            continue
        static_ok, static_reason = final_static_guard(question, transcript, answer)
        if not static_ok:
            if transcript and static_retry_used:
                return render_evidence_answer(question, transcript, "模型最終回答被守門規則再次阻擋：" + static_reason)
            static_retry_used = True
            messages.append({"role":"user", "content":"你的最終回答被程式化無幻覺規則阻擋：" + static_reason + "\n請只根據已有工具結果給最終答案；不要再重複環境檢查。"})
            transcript.append(f"STEP {step} STATIC_GUARD ok=False\n{static_reason}")
            continue
        if final_needs_31b(req, transcript, mutating_used):
            final_check = verify_final_with_31b(req, question, "\n\n".join(transcript), answer)
            if not final_verifier_passed(final_check):
                print("# Oracle Rescue Agent 最終一致性驗證失敗\n")
                print("31B 後段驗證判定最終回答可能忽略工具結果、存在幻覺或未遵循指令，因此已阻擋輸出。\n")
                print("## 31B 驗證\n```text")
                print(limit(final_check, 12000))
                print("```\n")
                if transcript:
                    print("## 已取得工具結果\n```text")
                    print(limit("\n\n".join(transcript), 24000))
                    print("```")
                return 1
            print(answer)
            print("\n---\n31B 最終一致性驗證：通過")
            print("```text")
            print(limit(final_check, 4000))
            print("```")
        else:
            print(answer)
            print("\n---\n最終回答依據：工具結果 + 程式化無幻覺檢查；未執行破壞性或改檔操作，因此未額外呼叫 31B。")
        if used_modes:
            print("\n---\n工具調用模式：" + ", ".join(dict.fromkeys(used_modes)))
        return 0
    if transcript:
        return render_evidence_answer(question, transcript, "達到步驟上限，已停止追加模型輪次。")
    print("# Oracle Rescue Agent 達到步驟上限，且沒有取得工具結果")
    return 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--request", required=True)
    ns = ap.parse_args()
    req = json.loads(Path(ns.request).read_text(encoding="utf-8"))
    global SESSION_LOG_PATH
    SESSION_LOG_PATH = req.get("session_log_path") or str(Path.home() / ".oracle_ai_rescue" / "sessions" / ((req.get("session_id") or ("session_" + time.strftime("%Y%m%d_%H%M%S"))) + ".log"))
    log_event("SESSION_START version=2.3.3 mode=" + str(req.get("runtime_mode") or "cloud-local-terminal-runtime") + " session_id=" + str(req.get("session_id") or ""))
    main_model = req.get("main_model") or {}
    models = [main_model] if main_model and (main_model.get("api_key") or "").strip() else []
    if not models:
        log_event("MAIN_MODEL_MISSING api_key_or_config_empty")
        print("# Oracle Rescue Agent 失敗\n\n主模型沒有可用 LLM API Key。主模型不使用備援；請修正目前選定模型的 API Key / Base URL / 模型名稱。")
        return 2
    # v2.3.3：統一使用單一 cloud-local run_terminal_command 工具循環，並加入硬性收斂與 Gemini 相容解析；
    # Gemini 使用同主模型 JSON 終端協議以避開 stream+tools 500，相同任務流程不做查專案特例。
    return run_native_tool_loop(req, models)

if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:
        print("# Oracle Rescue Agent 未捕捉例外", flush=True)
        print(f"{type(e).__name__}: {e}", flush=True)
        print(traceback.format_exc(), flush=True)
        try:
            log_event("UNCAUGHT_EXCEPTION " + type(e).__name__ + ": " + str(e))
        except Exception:
            pass
        sys.exit(99)
