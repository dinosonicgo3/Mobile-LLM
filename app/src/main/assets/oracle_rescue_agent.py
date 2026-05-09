#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Oracle AI Rescue Agent v2.0.8
Runs on the Oracle host. The Android app is only a remote controller/bridge.
Main diagnosis/repair uses ONLY the selected main model; no main-model fallback.
Fallback is allowed only for 31B verifier: Google Gemma 4 31B -> NVIDIA NIM Gemma 4 31B.
Full authority mode: the LLM may execute shell commands, remove projects, stop services, kill processes, and use sudo -n.
No password prompt is used or requested. If sudo -n is unavailable, the command will fail and report the real permission error.
"""
import argparse, base64, difflib, json, os, re, shlex, subprocess, sys, time, urllib.request, urllib.error
from pathlib import Path

MAX_OBS = 12000


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
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            body = resp.read().decode("utf-8", "replace")
    except TimeoutError as e:
        raise TimeoutError(f"LLM API read timed out after {timeout}s; provider={provider}; model={model}; base={base}; note=NVIDIA_NIM_large_models_may_need_up_to_300s") from e
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace") if e.fp else ""
        raise RuntimeError(f"LLM API HTTP {e.code}; provider={provider}; model={model}; body={limit(body, 3000)}") from e
    root = json.loads(body)
    return root["choices"][0]["message"].get("content", "")


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
        has_shell = transcript_has_tool(transcript, "ssh_exec") or transcript_has_tool(transcript, "shell_exec") or transcript_has_tool(transcript, "remove_project") or transcript_has_tool(transcript, "repair_file") or transcript_has_tool(transcript, "read_file") or transcript_has_tool(transcript, "list_dir")
        # Pure command-like tests may use ssh_exec directly, but project/file/service maintenance must not finish without tools.
        if not (has_discovery or has_shell):
            return False, "最終回答被阻擋：這是程式/雲端維護任務，但尚未使用任何工具查證真實環境。請先用 maintenance_plan 或 discover_projects/ssh_exec。"
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
    if tool in ("ssh_exec", "shell_exec"):
        return run_full(args.get("command") or "", timeout=300)
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--request", required=True)
    ns = ap.parse_args()
    req = json.loads(Path(ns.request).read_text(encoding="utf-8"))
    question = req.get("question") or ""
    main_model = req.get("main_model") or {}
    models = [main_model] if main_model and (main_model.get("api_key") or "").strip() else []
    # v2.0.8：主模型嚴禁備援；NVIDIA NIM / 大模型每次 API 回覆等待最多 300 秒，超過才報錯。
    # 加入自主規劃、Project Discovery、工具一致性檢查，不增加主模型備援。
    # 一般檢修、工具判斷、讀檔分析、產生修正版，都只允許使用 main_model。
    # req.fallback_models 即使存在也會被忽略；備援只允許在 verify_with_31b() 後段驗證內使用。
    if not models:
        print("# Oracle Rescue Agent 失敗\n\n主模型沒有可用 LLM API Key。主模型不使用備援；請修正目前選定模型的 API Key / Base URL / 模型名稱。")
        return 2
    agent_constitution = """
你正在 Oracle 主機本機的 Rescue Agent 內。App 只是遙控器，不會代你搜尋或判斷。你必須自己選工具。所有工具以 JSON 呼叫，不要用 Markdown code fence。

【完整權限模式】
你可以要求執行完整 shell 指令，包括 rm/rm -rf、pkill、killall、systemctl stop/disable、docker rm、crontab 清理、mv、cp、chmod、chown、sudo -n。不要要求使用者輸入 sudo 密碼；需要 root 權限時使用 sudo -n，若 sudo -n 失敗就回報權限錯誤。

【編程維護 Agent 最高規範】
你不是一般聊天助手。你的基本要求是：編程穩定、動手前先規劃、正確遵循使用者指令、提出基於證據的優化方案、無幻覺。這些規範是硬性流程，不是建議。
1. 不可只照字面搜尋；遇到專案/檔案/服務/流程查找、維修、刪除、更新、重啟、log 檢查，必須優先使用 discover_projects，再用 resolve_project_identity 確認身份。
2. 使用者輸入可能是中文代號、暱稱、縮寫、舊名稱；你必須自行查找目前雲端最新正確專案，不可要求使用者提供精準英文資料夾名。
3. 工具結果高於模型推測；若工具找到候選路徑、服務、進程、crontab，你不得說沒有找到，只能列為候選並繼續查證。
4. 動手前必須規劃：目標、候選專案、要改檔案/服務、備份點、測試方式、成功標準、回滾方式。可用 maintenance_plan 建立規劃。
5. 修改程式必須使用 repair_file，保留 backup → write → test → 31B verify → rollback。
6. 高風險操作如 rm -rf、remove_project、systemctl stop、pkill 前必須先備份或 quarantine。
7. 主模型不得備援；後段 31B 驗證可以備援。NVIDIA NIM 為公共平台，大模型回覆較慢；每次 LLM API 呼叫等待上限為 300 秒，超過才視為 timeout。
8. 需要 root 權限只用 sudo -n；失敗就回報權限錯誤，不要求密碼。
9. 最終回答不得宣稱未執行的測試、未確認的刪除、未讀取的檔案或不存在的結果。
10. 任何編程維護任務若尚未使用工具查證，不可直接 final；必須先使用 maintenance_plan、discover_projects、resolve_project_identity、ssh_exec/shell_exec、read_file 或 repair_file。
11. 如果你的結論與工具結果衝突，必須修正結論，不可要求使用者相信模型推測。

可用工具：
{"tool":"maintenance_plan","args":{"question":"任務目標","discovery":"可選，已取得的 discover_projects 結果摘要"}}
{"tool":"discover_projects","args":{"query":"使用者輸入/專案代號/任務關鍵字","include_all":false}}
{"tool":"resolve_project_identity","args":{"query":"使用者輸入/專案代號","paths":["候選路徑，可省略"]}}
{"tool":"ssh_exec","args":{"command":"任意 shell 指令，可含 sudo -n"}}
{"tool":"shell_exec","args":{"command":"任意 shell 指令，可含 sudo -n"}}
{"tool":"remove_project","args":{"target":"專案關鍵字","paths":["/path/to/remove"]}}
{"tool":"read_file","args":{"path":"/任意/絕對路徑"}}
{"tool":"list_dir","args":{"path":"/任意/絕對路徑"}}
{"tool":"repair_file","args":{"path":"/任意/絕對路徑","instruction":"修正要求"}}
{"tool":"final","answer":"最終回答"}

修程式仍應使用 repair_file，因為它會自動備份、測試、31B 驗證、失敗回滾。移除專案可使用 remove_project 或直接 ssh_exec 完成。
"""
    system = (req.get("system_prompt") or "") + "\n\n" + agent_constitution
    messages = [{"role":"system", "content":system}, {"role":"user", "content":"使用者問題：" + question + "\n請你直接選第一個最小工具。"}]
    transcript = []
    used_models = []
    for step in range(1, int(req.get("max_steps", 10))+1):
        try:
            text, used, errs = chat_chain(models, messages, timeout=int(req.get("tool_model_timeout_seconds", 300)))
            used_models.append(used)
        except Exception as e:
            print("# Oracle Rescue Agent 模型失敗\n")
            print(f"步驟 {step} 呼叫主模型失敗：{type(e).__name__}: {e}\n")
            print("主模型不使用備援；這是刻意設計，方便正確定位 API / 模型 / 工具提示錯誤。\n")
            if transcript:
                print("## 已取得工具結果\n")
                print("```text")
                print(limit("\n\n".join(transcript), 30000))
                print("```")
            return 1
        obj = parse_json_tool(text)
        if not obj:
            messages.append({"role":"assistant", "content":text})
            messages.append({"role":"user", "content":"你沒有輸出可解析 JSON。請只輸出 JSON 工具呼叫。"})
            continue
        if obj.get("tool") == "final":
            answer = obj.get("answer") or (obj.get("args") or {}).get("answer") or ""
            static_ok, static_reason = final_static_guard(question, transcript, answer)
            if not static_ok:
                messages.append({"role":"assistant", "content":json.dumps(obj, ensure_ascii=False)})
                messages.append({"role":"user", "content":"你的 final 被程式化無幻覺規則阻擋：" + static_reason + "\n請根據規範選擇下一個必要工具，不要直接 final。"})
                transcript.append(f"STEP {step} STATIC_GUARD ok=False\n{static_reason}")
                continue
            final_check = verify_final_with_31b(req, question, "\n\n".join(transcript), answer)
            if not final_verifier_passed(final_check):
                print("# Oracle Rescue Agent 最終一致性驗證失敗\n")
                print("31B 後段驗證判定最終回答可能忽略工具結果、存在幻覺或未遵循指令，因此已阻擋輸出。\n")
                print("## 31B 驗證\n")
                print("```text")
                print(limit(final_check, 12000))
                print("```\n")
                if transcript:
                    print("## 已取得工具結果\n")
                    print("```text")
                    print(limit("\n\n".join(transcript), 30000))
                    print("```")
                return 1
            print(answer)
            print("\n---\n31B 最終一致性驗證：通過")
            if final_check:
                print("```text")
                print(limit(final_check, 4000))
                print("```")
            if used_models:
                print("\n---\n使用模型：" + ", ".join(dict.fromkeys(used_models)))
            return 0
        res = execute_tool(req, models, obj)
        obs = limit(res.get("output"), MAX_OBS)
        transcript.append(f"STEP {step} TOOL {obj.get('tool')} ok={res.get('ok')}\n{obs}")
        messages.append({"role":"assistant", "content":json.dumps(obj, ensure_ascii=False)})
        messages.append({"role":"user", "content":"工具執行結果如下。請判斷下一步；若已足夠，輸出 final JSON。\n\n" + obs})
    print("# Oracle Rescue Agent 達到步驟上限\n")
    print("```text")
    print(limit("\n\n".join(transcript), 30000))
    print("```")
    return 0

if __name__ == "__main__":
    sys.exit(main())
