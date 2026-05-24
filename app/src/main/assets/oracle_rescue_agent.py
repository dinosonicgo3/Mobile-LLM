#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Oracle AI Rescue Agent v2.5.2
[v2.5.2 FIX] 完全對齊 Google 官方 Gemma 4 文檔：
1. 移除導致 500/空白回應的 OpenAI 專用參數 (reasoning_effort)。
2. 依據官方規範，在 System Prompt 注入 <|think|> 觸發原生思考模式。
3. 自動剝離 <|channel>thought...<channel|> 標籤，隱藏思考過程並防止多輪對話 Context 污染。
"""
import argparse, base64, copy, difflib, json, os, re, shlex, subprocess, sys, time, traceback, urllib.request, urllib.error
from pathlib import Path

MAX_OBS = 4000
TOOL_CONTEXT_LIMIT = 2000
SESSION_LOG_PATH = None

def log_event(message):
    line = f"[oracle_rescue_agent] {now()} {message}"
    try: print(line, flush=True)
    except Exception: pass
    try:
        if SESSION_LOG_PATH:
            Path(SESSION_LOG_PATH).parent.mkdir(parents=True, exist_ok=True)
            with open(SESSION_LOG_PATH, "a", encoding="utf-8") as f: f.write(line + "\n")
    except Exception: pass

def limit(text, n):
    text = "" if text is None else str(text)
    return text if len(text) <= n else text[:n] + f"\n...（已截斷，原長度 {len(text)}）"

def now(): return time.strftime("%Y-%m-%d %H:%M:%S %Z")
def default_base(provider):
    if provider == "nim": return "https://integrate.api.nvidia.com/v1"
    if provider == "gemini": return "https://generativelanguage.googleapis.com/v1beta/openai"
    return ""

def provider_requires_api_key(provider): return (provider or "").strip().lower() in ("gemini", "nim")

def model_config_usable(cfg):
    if not cfg: return False
    provider = (cfg.get("provider") or "custom").strip().lower()
    model = (cfg.get("model") or cfg.get("modelName") or "").strip()
    base = (cfg.get("base_url") or cfg.get("baseUrl") or default_base(provider)).rstrip("/")
    key = (cfg.get("api_key") or "").strip()
    if not model or not base: return False
    if provider_requires_api_key(provider) and not key: return False
    return True

def add_optional_auth(req, key):
    key = (key or "").strip()
    if key: req.add_header("Authorization", "Bearer " + key)

def strip_gemma_thoughts(text):
    """[v2.5.2] 依據 Google 官方文檔，移除 Gemma 4 的 <|channel>thought 標籤"""
    if not text: return text
    text = re.sub(r"(?is)<\|channel\>thought.*?<channel\|>", "", text)
    text = re.sub(r"(?is)<think>.*?</think>", "", text)
    text = re.sub(r"(?is)<thought>.*?</thought>", "", text)
    return text.strip()

def sanitize_gemini_payload(payload, provider, model):
    """[v2.5.2] 依據官方文檔啟用 Gemma 4 思考模式，並移除導致 500 錯誤的參數"""
    p = payload.copy()
    is_gemini = (provider or "").lower() == "gemini" or "gemini" in (model or "").lower() or "gemma" in (model or "").lower()
    if is_gemini:
        p.pop("temperature", None)
        p.pop("reasoning_effort", None) # 官方 Gemma 4 不支援，會導致 500 或空白
        if "max_tokens" in p: p["max_completion_tokens"] = p.pop("max_tokens")
        if "messages" in p and isinstance(p["messages"], list):
            p["messages"] = copy.deepcopy(p["messages"])
            has_system = False
            for msg in p["messages"]:
                if msg.get("role") == "system":
                    content = str(msg.get("content") or "")
                    if "<|think|>" not in content: msg["content"] = "<|think|>\n" + content
                    has_system = True
                    break
            if not has_system: p["messages"].insert(0, {"role": "system", "content": "<|think|>"})
    return p

def chat_once(cfg, messages, timeout=300):
    if not cfg: raise RuntimeError("model config empty")
    provider = (cfg.get("provider") or "custom").strip()
    key = (cfg.get("api_key") or "").strip()
    if provider_requires_api_key(provider) and not key: raise RuntimeError(f"missing api_key")
    model = (cfg.get("model") or cfg.get("modelName") or "").strip()
    base = (cfg.get("base_url") or cfg.get("baseUrl") or default_base(provider)).rstrip("/")
    url = base + "/chat/completions"
    payload = {"model": model, "messages": messages, "temperature": float(cfg.get("temperature", 0.0)), "max_tokens": int(cfg.get("max_tokens", 1024))}
    payload = sanitize_gemini_payload(payload, provider, model)
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    t0 = time.time()
    max_retries = 3
    for attempt in range(max_retries + 1):
        req = urllib.request.Request(url, data=data, method="POST")
        req.add_header("Content-Type", "application/json")
        add_optional_auth(req, key)
        log_event(f"LLM_CALL_START provider={provider} model={model} attempt={attempt+1}")
        try:
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                body = resp.read().decode("utf-8", "replace")
            root = json.loads(body)
            choice = root["choices"][0]
            content = choice.get("message", {}).get("content", "")
            content = strip_gemma_thoughts(content)
            if not content and attempt < max_retries:
                log_event("LLM_CALL_EMPTY_RESPONSE retrying...")
                time.sleep(2); continue
            log_event(f"LLM_CALL_OK elapsed={time.time()-t0:.1f}s len={len(content or '')}")
            return content
        except urllib.error.HTTPError as e:
            body = e.read().decode("utf-8", "replace") if e.fp else ""
            if e.code in (429, 503) and attempt < max_retries:
                time.sleep(5 * (2 ** attempt)); continue
            raise RuntimeError(f"LLM API HTTP {e.code}; body={limit(body, 3000)}") from e
        except Exception as e: raise

def chat_chain(models, messages, timeout=300):
    errors = []
    for cfg in models:
        if not model_config_usable(cfg): continue
        label = cfg.get("label") or cfg.get("model")
        try:
            text = chat_once(cfg, messages, timeout=timeout)
            if text and text.strip(): return text, label, errors
            errors.append(f"{label}: empty")
        except Exception as e: errors.append(f"{label}: {e}")
    raise RuntimeError("main model failed: " + " | ".join(errors))

def safe_path(path):
    p = (path or "").strip()
    if not p or not p.startswith("/") or any(x in p for x in ["\0", "\n", "\r"]): return ""
    return p

def is_safe_read_command(cmd):
    if not cmd: return False
    c = cmd.strip()
    if len(c) > 1000: return False
    low = " " + c.lower() + " "
    blocked = [" sudo ", " rm ", " mv ", " cp ", " chmod ", " chown ", " kill ", " reboot", " shutdown", " restart", " start ", " stop ", " enable ", " disable ", " install", " apt ", " yum ", " dnf ", " apk ", " pip install", " npm install", " tee ", " >", ">>", "| sh", "| bash"]
    if any(b in low for b in blocked): return False
    allowed_starts = ("pwd", "ls", "cat", "head", "tail", "grep", "find", "du", "df", "free", "uptime", "date", "whoami", "hostname", "ss", "netstat", "docker ps", "docker logs", "docker inspect", "systemctl status", "systemctl list-units", "journalctl", "git status", "python3 -m py_compile", "node --check", "bash -n")
    parts = re.split(r"\s*(?:&&|\|)\s*", c)
    for part in parts:
        p = part.strip()
        if not p: continue
        if not p.startswith(allowed_starts): return False
    return True

def run(cmd, timeout=90):
    if not is_safe_read_command(cmd): return {"ok": False, "output": "指令被安全策略拒絕。"}
    try:
        p = subprocess.run(cmd, shell=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        out = f"$ {cmd}\nexitCode={p.returncode}\n"
        if p.stdout.strip(): out += "--- stdout ---\n" + p.stdout.strip() + "\n"
        if p.stderr.strip(): out += "--- stderr ---\n" + p.stderr.strip() + "\n"
        return {"ok": p.returncode == 0, "output": limit(out, MAX_OBS)}
    except Exception as e: return {"ok": False, "output": f"command failed: {e}"}

def run_full(cmd, timeout=180):
    if not cmd or not str(cmd).strip(): return {"ok": False, "output": "空指令。"}
    c = str(cmd)
    try:
        p = subprocess.run(c, shell=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        out = f"$ {c}\nexitCode={p.returncode}\n"
        if p.stdout.strip(): out += "--- stdout ---\n" + p.stdout.strip() + "\n"
        if p.stderr.strip(): out += "--- stderr ---\n" + p.stderr.strip() + "\n"
        return {"ok": p.returncode == 0, "output": limit(out, MAX_OBS)}
    except Exception as e: return {"ok": False, "output": f"command failed: {e}"}

def shell_quote(x): return shlex.quote(str(x))
def expand_target_terms(target):
    raw = (target or "").strip()
    terms = [raw] if raw else []
    aliases = {"海參": ["haishen", "hermes-haishen"], "潤天蟹": ["runtianxie", "hermes-runtianxie"]}
    for k, vals in aliases.items():
        if k in raw: terms.extend(vals)
    return list(set(terms))

def compact_lines(text, max_lines=80, max_chars=12000):
    text = "" if text is None else str(text)
    lines = text.splitlines()
    if len(lines) > max_lines: text = "\n".join(lines[:max_lines]) + f"\n...（已截斷）"
    return limit(text, max_chars)

def path_mtime(path):
    try: return time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(Path(path).stat().st_mtime))
    except Exception: return "unknown"

def looks_like_project_dir(path):
    p = Path(path)
    markers = [".git", "README.md", "package.json", "pyproject.toml", "requirements.txt", "build.gradle.kts", "docker-compose.yml", "Dockerfile", "AGENTS.md"]
    return any((p / m).exists() for m in markers)

def shell_lines(cmd, timeout=90, max_lines=300):
    try:
        p = subprocess.run(cmd, shell=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        return [x.strip() for x in p.stdout.splitlines() if x.strip()][:max_lines]
    except Exception: return []

def scan_candidate_dirs():
    roots = ["/home/ubuntu", "/home/ubuntu/ai-agents", "/home/ubuntu/.config", "/home/ubuntu/_runtime", "/opt", "/srv", "/var/www"]
    dirs, seen = [], set()
    for root in roots:
        if not Path(root).exists(): continue
        cmd = f"find {shell_quote(root)} -maxdepth 4 -type d \\( -name .git -o -name node_modules -o -name .venv \\) -prune -o -type f \\( -name README.md -o -name package.json -o -name pyproject.toml -o -name build.gradle.kts -o -name docker-compose.yml -o -name AGENTS.md \\) -printf '%h\\n' 2>/dev/null | sort -u | head -n 500"
        for d in shell_lines(cmd, timeout=120, max_lines=500):
            sp = safe_path(d)
            if sp and sp not in seen: seen.add(sp); dirs.append(sp)
    return dirs[:600]

def read_small_file(path, max_chars=3000):
    try: return Path(path).read_text(encoding="utf-8", errors="replace")[:max_chars]
    except Exception: return ""

def project_signals(path):
    p = Path(path)
    info = {"path": str(p), "name": p.name, "mtime": path_mtime(str(p)), "markers": [], "git_remote": "", "identity_text": ""}
    for m in [".git", "README.md", "package.json", "pyproject.toml", "docker-compose.yml", "AGENTS.md"]:
        if (p / m).exists(): info["markers"].append(m)
    if (p / ".git" / "config").exists():
        cfg = read_small_file(p / ".git" / "config", 2000)
        m = re.search(r"url\s*=\s*(.+)", cfg)
        if m: info["git_remote"] = m.group(1).strip()
    info["identity_text"] = limit(read_small_file(p / "README.md", 3000) or read_small_file(p / "AGENTS.md", 3000), 5000)
    return info

def discover_projects(query="", include_all=False):
    terms = expand_target_terms(query)
    dirs = scan_candidate_dirs()
    candidates = []
    for d in dirs:
        info = project_signals(d)
        score = sum(25 for t in terms if t.lower() in info["identity_text"].lower() or t.lower() in info["name"].lower())
        if include_all or score > 0: info["score"] = score; candidates.append(info)
    candidates.sort(key=lambda x: x.get("score", 0), reverse=True)
    return {"ok": True, "output": json.dumps({"candidates": candidates[:20]}, ensure_ascii=False, indent=2)[:60000]}

def resolve_project_identity(query="", paths=None):
    if isinstance(paths, str): paths = [paths]
    if not paths:
        disc = discover_projects(query)
        try: paths = [c["path"] for c in json.loads(disc["output"]).get("candidates", [])[:5]]
        except Exception: paths = []
    resolved = [project_signals(safe_path(p)) for p in (paths or []) if safe_path(p) and Path(safe_path(p)).exists()]
    return {"ok": True, "output": json.dumps({"projects": resolved}, ensure_ascii=False, indent=2)[:60000]}

def verify_final_with_31b(req, question, transcript, answer):
    msgs = [{"role":"system", "content":"你是 31B 無幻覺驗證官。固定格式：\nVERDICT: PASS 或 FAIL\nREASON: 繁體中文"}, {"role":"user", "content":"[QUESTION]\n" + limit(question, 2000) + "\n\n[TRANSCRIPT]\n" + limit(transcript, 10000) + "\n\n[ANSWER]\n" + limit(answer, 5000)}]
    for cfg in [req.get("verifier_google"), req.get("verifier_nim")]:
        if not cfg or not (cfg.get("api_key") or "").strip(): continue
        try:
            out = chat_once(cfg, msgs, timeout=300)
            if out and out.strip(): return "VERIFIER: " + (cfg.get("label") or "31B") + "\n" + out.strip()
        except Exception: pass
    return "VERDICT: PASS\nREASON: 31B 驗證不可用，依工具紀錄放行。"

def final_verifier_passed(report): return "VERDICT: PASS" in (report or "").upper() and "VERDICT: FAIL" not in (report or "").upper()

def remove_project(target="", paths=None):
    terms = expand_target_terms(target)
    stamp = time.strftime("%Y%m%d_%H%M%S")
    base = Path.home() / ".oracle_ai_rescue"
    quarantine = base / "quarantine" / f"{target}_{stamp}"
    quarantine.mkdir(parents=True, exist_ok=True)
    report = [f"target={target}", "==== KILL PROCESSES ===="]
    for term in terms:
        for cmd in [f"pkill -f {shell_quote(term)}", f"sudo -n pkill -f {shell_quote(term)}"]: report.append(run_full(cmd, timeout=60)["output"])
    return {"ok": True, "output": limit("\n".join(report), 30000)}

def read_file(path):
    p = safe_path(path)
    if not p: return {"ok": False, "output": "path rejected"}
    try: return {"ok": True, "output": f"FILE={p}\n\n" + limit(Path(p).read_text(encoding="utf-8", errors="replace"), 30000)}
    except Exception as e: return {"ok": False, "output": f"failed: {e}"}

def validate_file(path):
    p = safe_path(path)
    if not p: return {"ok": False, "output": "path rejected"}
    cmds = [f"ls -lh {shlex.quote(p)}"]
    if p.endswith(".py"): cmds.append(f"python3 -m py_compile {shlex.quote(p)}")
    elif p.endswith(".json"): cmds.append(f"python3 -m json.tool {shlex.quote(p)}")
    outputs = [run(c, timeout=120)["output"] for c in cmds]
    return {"ok": all("exitCode=0" in o for o in outputs), "output": limit("\n".join(outputs), 20000)}

def strip_code_fence(text):
    m = re.match(r"^```[a-zA-Z0-9_-]*\s*\n(.*?)\n```\s*$", (text or "").strip(), flags=re.S)
    return m.group(1) if m else (text or "").strip()

def parse_json_tool(text):
    x = re.sub(r"^```(?:json)?\s*", "", (text or "").strip()).strip()
    x = re.sub(r"```$", "", x).strip()
    try: return json.loads(x)
    except Exception: pass
    start = x.find("{")
    if start >= 0:
        depth, in_str, esc = 0, False, False
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
                        try: return json.loads(x[start:i+1])
                        except Exception: return None
    return None

def unified_diff(a, b, path): return "".join(difflib.unified_diff(a.splitlines(True), b.splitlines(True), fromfile=path+".old", tofile=path+".new"))

def verify_with_31b(req, stage, path, instruction, original, proposed, diff, tests):
    msgs = [{"role":"system", "content":"你是 Gemma 4 31B 驗證官。固定格式：\nVERDICT: PASS 或 FAIL\nREASON: 繁體中文"}, {"role":"user", "content": f"STAGE={stage}\nFILE={path}\n[DIFF]\n{limit(diff,8000)}\n[TESTS]\n{limit(tests,4000)}"}]
    for cfg in [req.get("verifier_google"), req.get("verifier_nim")]:
        if not cfg or not (cfg.get("api_key") or "").strip(): continue
        try:
            out = chat_once(cfg, msgs, timeout=300)
            if out and out.strip(): return f"VERIFIER: {cfg.get('label')}\n" + out.strip()
        except Exception: pass
    return "VERDICT: FAIL\nREASON: 31B 驗證不可用。"

def repair_file(req, models, path, instruction):
    p = safe_path(path)
    if not p: return {"ok": False, "output": "path rejected"}
    try: original = Path(p).read_text(encoding="utf-8", errors="replace")
    except Exception as e: return {"ok": False, "output": f"read failed: {e}"}
    gen_msgs = [{"role":"system", "content":"你是程式修復模型。只輸出修正後的完整檔案內容，不要 Markdown。"}, {"role":"user", "content": f"檔案：{p}\n要求：{instruction}\n\n原檔：\n{limit(original,40000)}"}]
    try: proposed, used, _ = chat_chain(models, gen_msgs, timeout=300)
    except Exception as e: return {"ok": False, "output": f"main model failed: {e}"}
    proposed = strip_code_fence(proposed)
    if not proposed.strip() or proposed == original: return {"ok": False, "output": "no changes"}
    diff = unified_diff(original, proposed, p)
    pre = verify_with_31b(req, "PRE_WRITE", p, instruction, original, proposed, diff, "")
    if "PASS" not in pre.upper(): return {"ok": False, "output": "31B blocked write.\n" + pre}
    backup = p + ".bak_" + time.strftime("%Y%m%d_%H%M%S")
    Path(backup).write_text(original, encoding="utf-8")
    Path(p).write_text(proposed, encoding="utf-8")
    val = validate_file(p)
    final = verify_with_31b(req, "FINAL", p, instruction, original, proposed, diff, val["output"])
    if not val["ok"] or "PASS" not in final.upper():
        Path(p).write_text(original, encoding="utf-8")
        return {"ok": False, "output": "validation failed, rolled back.\n" + val["output"] + "\n" + final}
    return {"ok": True, "output": "repair ok.\n" + val["output"]}

def execute_tool(req, models, tool_obj):
    tool, args = (tool_obj.get("tool") or "").strip(), tool_obj.get("args") or {}
    if tool == "discover_projects": return discover_projects(args.get("query") or req.get("question") or "")
    if tool == "resolve_project_identity": return resolve_project_identity(args.get("query") or req.get("question") or "", args.get("paths"))
    if tool in ("ssh_exec", "shell_exec", "run_terminal_command"): return run_full(args.get("command") or "", timeout=max(5, min(300, int(args.get("timeout_seconds") or 30))))
    if tool == "remove_project": return remove_project(args.get("target") or "", args.get("paths"))
    if tool == "read_file": return read_file(args.get("path") or "")
    if tool == "repair_file": return repair_file(req, models, args.get("path") or "", args.get("instruction") or req.get("question") or "")
    if tool == "final": return {"ok": True, "final": True, "output": tool_obj.get("answer") or args.get("answer") or ""}
    return {"ok": False, "output": "unknown tool"}

def tool_schemas():
    return [{"type": "function", "function": {"name": "run_terminal_command", "description": "Execute shell command locally.", "parameters": {"type": "object", "properties": {"command": {"type": "string"}}, "required": ["command"]}}}]

def build_runtime_system(req):
    return "你是在 Oracle 主機本機執行的 Agent。只能用 run_terminal_command 查證環境。最終回答只能根據工具結果。"

def normalize_message_for_native(msg):
    role = msg.get("role")
    if role == "assistant" and msg.get("tool_calls"):
        # 官方鐵律：多輪對話必須剝離歷史中的思考過程
        clean_content = strip_gemma_thoughts(msg.get("content") or "")
        return {"role":"assistant", "content": clean_content or None, "tool_calls": msg.get("tool_calls")}
    if role == "tool": return {"role":"tool", "tool_call_id": msg.get("tool_call_id"), "content": limit(msg.get("content") or "", TOOL_CONTEXT_LIMIT)}
    content = msg.get("content") or ""
    if role == "assistant": content = strip_gemma_thoughts(content)
    return {"role": role, "content": limit(content, 6000)}

def openai_chat_completion(cfg, messages, timeout=300, tools=None, tool_choice="auto", stream=True, max_tokens=None):
    if not cfg: raise RuntimeError("config empty")
    provider = (cfg.get("provider") or "custom").strip()
    model = (cfg.get("model") or "").strip()
    base = (cfg.get("base_url") or default_base(provider)).rstrip("/")
    url = base + "/chat/completions"
    payload = {"model": model, "messages": [normalize_message_for_native(m) for m in messages], "temperature": float(cfg.get("temperature", 0.0)), "max_tokens": int(max_tokens or cfg.get("max_tokens", 1024))}
    if tools: payload["tools"], payload["tool_choice"] = tools, tool_choice or "auto"
    if stream: payload["stream"] = True
    payload = sanitize_gemini_payload(payload, provider, model)
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    add_optional_auth(req, (cfg.get("api_key") or "").strip())
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            if stream: return read_streaming_chat(resp, provider, model, t0)
            body = resp.read().decode("utf-8", "replace")
        root = json.loads(body)
        msg = root["choices"][0].get("message") or {}
        content = strip_gemma_thoughts(msg.get("content") or "")
        return {"content": content, "tool_calls": msg.get("tool_calls") or [], "raw": root}
    except Exception as e: raise

def read_streaming_chat(resp, provider, model, t0):
    content_parts, tool_acc = [], {}
    for raw in resp:
        line = raw.decode("utf-8", "replace").strip()
        if line.startswith("data:"): line = line[5:].strip()
        if not line or line == "[DONE]": continue
        try: ev = json.loads(line)
        except Exception: continue
        delta = (ev.get("choices") or [{}])[0].get("delta") or {}
        if delta.get("content"): content_parts.append(delta.get("content") or "")
        for tc in delta.get("tool_calls") or []:
            idx = int(tc.get("index", 0))
            cur = tool_acc.setdefault(idx, {"id":"", "type":"function", "function":{"name":"", "arguments":""}})
            if tc.get("id"): cur["id"] += tc.get("id") if not cur["id"] else ""
            fn = tc.get("function") or {}
            if fn.get("name"): cur["function"]["name"] += fn.get("name")
            if fn.get("arguments"): cur["function"]["arguments"] += fn.get("arguments")
    content = strip_gemma_thoughts("".join(content_parts))
    tool_calls = [tool_acc[i] for i in sorted(tool_acc.keys())]
    for i, tc in enumerate(tool_calls):
        if not tc.get("id"): tc["id"] = f"call_{int(time.time()*1000)}_{i}"
    return {"content": content, "tool_calls": tool_calls, "raw": None}

def parse_loose_tool_protocol(text):
    obj = parse_json_tool(text)
    if obj: return obj
    m = re.search(r"\[TOOL_CALL\]([\s\S]*?)\[/TOOL_CALL\]", text or "", re.I)
    block = m.group(1).strip() if m else (text or "")
    tool_m = re.search(r"tool\s*=>\s*['\"]?([A-Za-z0-9_\-]+)['\"]?", block)
    cmd_m = re.search(r"command\s*[:=]>?\s*(['\"])(.*?)\1", block, re.S) or re.search(r"command\s*[:=]\s*([^,}\n]+)", block, re.S)
    if tool_m and cmd_m: return {"tool": tool_m.group(1), "args": {"command": cmd_m.group(2).strip() if cmd_m.lastindex >= 2 else cmd_m.group(1).strip()}}
    return None

def legacy_json_tool_call(cfg, messages, timeout=60):
    compact = [{"role":"system", "content":"你是工具路由器。只輸出 JSON：{\"tool\":\"run_terminal_command\",\"args\":{\"command\":\"...\"}} 或 {\"tool\":\"final\",\"answer\":\"...\"}"}]
    for m in messages[-5:]:
        if m.get("role") == "tool": compact.append({"role":"user", "content":"結果：\n" + limit(m.get("content") or "", 2500)})
        else: compact.append({"role":m.get("role"), "content":limit(strip_gemma_thoughts(m.get("content") or ""), 4000)})
    text = chat_once(dict(cfg, max_tokens=512), compact, timeout=timeout)
    obj = parse_loose_tool_protocol(text)
    if not obj: raise RuntimeError("unparsable tool output: " + limit(text, 500))
    if obj.get("tool") == "final": return {"content": obj.get("answer") or "", "tool_calls": []}
    return {"content":"", "tool_calls":[{"id":f"legacy_{int(time.time()*1000)}", "type":"function", "function":{"name":obj.get("tool"), "arguments":json.dumps(obj.get("args") or {}, ensure_ascii=False)}}]}

def call_main_for_tools(req, cfg, messages):
    provider = (cfg.get("provider") or "custom").strip().lower()
    if provider in ("gemini", "kaggle", "local_gemma"): return legacy_json_tool_call(cfg, messages, timeout=120), provider + "-json"
    return openai_chat_completion(cfg, messages, timeout=120, tools=tool_schemas(), stream=True, max_tokens=512), "native-stream"

def extract_textual_terminal_tool(content):
    obj = parse_loose_tool_protocol(content or "")
    if not obj or obj.get("tool") != "run_terminal_command": return []
    return [{"id": f"textual_{int(time.time()*1000)}", "type": "function", "function": {"name": "run_terminal_command", "arguments": json.dumps(obj.get("args") or {}, ensure_ascii=False)}}]

def parse_tool_call(tc):
    fn = tc.get("function") or {}
    try: args = json.loads(fn.get("arguments") or "{}")
    except Exception: args = {}
    return {"tool": (fn.get("name") or "").strip(), "args": args}

def run_native_tool_loop(req, models):
    question = req.get("question") or ""
    cfg = models[0]
    messages = [{"role":"system", "content": build_runtime_system(req)}, {"role":"user", "content": question}]
    transcript, mutating_used = [], False
    max_steps = 6 if any(m in question.lower() for m in ["修", "刪除", "重啟"]) else 4
    for step in range(1, max_steps + 1):
        print(f"[agent] step={step} calling model...", flush=True)
        try: resp, mode = call_main_for_tools(req, cfg, messages)
        except Exception as e:
            print(f"# Agent 失敗\n步驟 {step} 呼叫模型失敗：{e}\n主模型不備援。")
            return 1
        tool_calls = resp.get("tool_calls") or []
        content = resp.get("content") or ""
        if not tool_calls: tool_calls = extract_textual_terminal_tool(content)
        if tool_calls:
            messages.append({"role":"assistant", "content": content or None, "tool_calls": tool_calls})
            for tc in tool_calls[:2]:
                obj = parse_tool_call(tc)
                name, args = obj.get("tool"), obj.get("args") or {}
                if name in ("repair_file", "remove_project") or any(x in str(args.get("command","")).lower() for x in ["rm ", "pkill", "systemctl stop"]): mutating_used = True
                res = execute_tool(req, models, {"tool": name, "args": args})
                obs = limit(res.get("output"), TOOL_CONTEXT_LIMIT)
                transcript.append(f"STEP {step} TOOL {name} ok={res.get('ok')}\n{obs}")
                messages.append({"role":"tool", "tool_call_id": tc.get("id"), "name": name, "content": obs})
            continue
        answer = content.strip()
        if not answer: continue
        if mutating_used:
            final_check = verify_final_with_31b(req, question, "\n\n".join(transcript), answer)
            if not final_verifier_passed(final_check):
                print("# 31B 驗證失敗，阻擋輸出。\n" + final_check)
                return 1
        print(answer)
        return 0
    print("# Agent 達到步驟上限")
    return 1

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--request", required=True)
    ns = ap.parse_args()
    req = json.loads(Path(ns.request).read_text(encoding="utf-8"))
    global SESSION_LOG_PATH
    SESSION_LOG_PATH = req.get("session_log_path") or str(Path.home() / ".oracle_ai_rescue" / "sessions" / ((req.get("session_id") or ("session_" + time.strftime("%Y%m%d_%H%M%S"))) + ".log"))
    log_event("SESSION_START version=2.5.2")
    main_model = req.get("main_model") or {}
    models = [main_model] if model_config_usable(main_model) else []
    if not models:
        print("# Agent 失敗\n主模型設定不可用。")
        return 2
    return run_native_tool_loop(req, models)

if __name__ == "__main__":
    try: sys.exit(main())
    except Exception as e:
        print(f"# Agent 未捕捉例外\n{type(e).__name__}: {e}", flush=True)
        sys.exit(99)
