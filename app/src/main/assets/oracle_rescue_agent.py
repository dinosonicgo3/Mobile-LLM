#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Oracle AI Rescue Agent v2.0.4
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


def chat_once(cfg, messages, timeout=120):
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
    }
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(url, data=data, method="POST")
    req.add_header("Content-Type", "application/json")
    req.add_header("Authorization", "Bearer " + key)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        body = resp.read().decode("utf-8", "replace")
    root = json.loads(body)
    return root["choices"][0]["message"].get("content", "")


def chat_chain(models, messages, timeout=120):
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
    report.append(f"quarantine={quarantine}")
    report.append("==== DISCOVERED PATHS ====")
    report.extend(discovered or ["(none)"])

    # Process/service cleanup by target only.
    if target:
        report.append("==== KILL PROCESSES ====")
        for cmd in [
            "pkill -f " + shell_quote(target),
            "sudo -n pkill -f " + shell_quote(target),
            "killall " + shell_quote(target) + " 2>/dev/null",
            "sudo -n killall " + shell_quote(target) + " 2>/dev/null",
        ]:
            res = run_full(cmd, timeout=60)
            report.append(res["output"])

        report.append("==== SYSTEMD STOP/DISABLE MATCHING UNITS ====")
        list_units = subprocess.run(
            "systemctl list-units --type=service --all --no-legend 2>/dev/null | awk '{print $1}'",
            shell=True, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60
        )
        units = [u.strip() for u in list_units.stdout.splitlines() if target.lower() in u.lower()]
        if not units:
            report.append("(no matching services)")
        for u in units[:50]:
            for cmd in [
                "sudo -n systemctl stop " + shell_quote(u),
                "sudo -n systemctl disable " + shell_quote(u),
            ]:
                report.append(run_full(cmd, timeout=60)["output"])

        report.append("==== CRONTAB CLEANUP CURRENT USER ====")
        cleanup = (
            "TMP=$(mktemp); "
            "crontab -l 2>/dev/null | grep -vi " + shell_quote(target) + " > $TMP; "
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
    if target:
        verify_cmd = (
            "echo '[process]'; ps aux | grep -i " + shell_quote(target) + " | grep -v grep || true; "
            "echo '[paths]'; find /home /opt /srv /var/www /etc/systemd/system -maxdepth 7 -iname " + shell_quote(f"*{target}*") + " 2>/dev/null | head -n 100 || true; "
            "echo '[services]'; systemctl list-units --type=service --all 2>/dev/null | grep -i " + shell_quote(target) + " || true; "
            "echo '[crontab]'; crontab -l 2>/dev/null | grep -i " + shell_quote(target) + " || true"
        )
        report.append(run_full(verify_cmd, timeout=120)["output"])

    return {"ok": True, "output": limit("\n".join(report), 30000)}

def read_file(path):
    p = safe_path(path)
    if not p: return {"ok": False, "output": "read_file 被拒絕：路徑需在 /home、/opt、/srv、/var/www 內，且不能含危險字元。"}
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
            out = chat_once(cfg, msgs, timeout=120)
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
        proposed, used, errs = chat_chain(models, gen_msgs, timeout=180)
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
    # v2.0.2：主模型嚴禁備援。
    # 一般檢修、工具判斷、讀檔分析、產生修正版，都只允許使用 main_model。
    # req.fallback_models 即使存在也會被忽略；備援只允許在 verify_with_31b() 後段驗證內使用。
    if not models:
        print("# Oracle Rescue Agent 失敗\n\n主模型沒有可用 LLM API Key。主模型不使用備援；請修正目前選定模型的 API Key / Base URL / 模型名稱。")
        return 2
    system = (req.get("system_prompt") or "") + "\n\n你正在 Oracle 主機本機的 Rescue Agent 內。App 只是遙控器，不會代你搜尋或判斷。你必須自己選工具。所有工具以 JSON 呼叫，不要用 Markdown code fence。\n\n【完整權限模式】\n你可以要求執行完整 shell 指令，包括 rm/rm -rf、pkill、killall、systemctl stop/disable、docker rm、crontab 清理、mv、cp、chmod、chown、sudo -n。不要要求使用者輸入 sudo 密碼；需要 root 權限時使用 sudo -n，若 sudo -n 失敗就回報權限錯誤。\n\n可用工具：\n{\"tool\":\"ssh_exec\",\"args\":{\"command\":\"任意 shell 指令，可含 sudo -n\"}}\n{\"tool\":\"shell_exec\",\"args\":{\"command\":\"任意 shell 指令，可含 sudo -n\"}}\n{\"tool\":\"remove_project\",\"args\":{\"target\":\"專案關鍵字\",\"paths\":[\"/path/to/remove\"]}}\n{\"tool\":\"read_file\",\"args\":{\"path\":\"/任意/絕對路徑\"}}\n{\"tool\":\"list_dir\",\"args\":{\"path\":\"/任意/絕對路徑\"}}\n{\"tool\":\"repair_file\",\"args\":{\"path\":\"/任意/絕對路徑\",\"instruction\":\"修正要求\"}}\n{\"tool\":\"final\",\"answer\":\"最終回答\"}\n\n修程式仍應使用 repair_file，因為它會自動備份、測試、31B 驗證、失敗回滾。移除專案可使用 remove_project 或直接 ssh_exec 完成。"
    messages = [{"role":"system", "content":system}, {"role":"user", "content":"使用者問題：" + question + "\n請你直接選第一個最小工具。"}]
    transcript = []
    used_models = []
    for step in range(1, int(req.get("max_steps", 10))+1):
        try:
            text, used, errs = chat_chain(models, messages, timeout=150)
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
            print(answer)
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
