#!/usr/bin/env python3
"""PermissionRequest hook for Claude Code: a permission prompt in any of the human's sessions
also reaches the phone. The request is written to asks/RID.json and sent as a message of kind
`ask` (Speek shows an Allow / Deny card), and this hook waits for the first answer. The
machine's own dialog stays usable the whole time: answered there, the session stops waiting and
this hook steps aside.

Silent in dontAsk, bypassPermissions and plan mode: those never ask the human.

Install: in ~/.claude/settings.json, hooks.PermissionRequest runs
`python3 /ABSOLUTE/PATH/TO/speek/server/asks.py` with a timeout of 3600.

  (no arguments)                  the hook (reads the request on stdin)
  answer RID allow|deny|always    record an answer
"""
import json
import signal
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import store  # noqa: E402

QUIET_MODES = ("dontAsk", "bypassPermissions", "plan")
POLL_S = 1.0
CHECK_S = 5.0  # how often to look whether the machine's dialog answered first
WAIT_S = 3500  # just under the hook's timeout in settings (3600)


def answer(rid, decision):
    if decision not in ("allow", "deny", "always"):
        raise ValueError("decision is allow, deny or always")
    store.checked(rid)
    req = store.ASKS / f"{rid}.json"
    if not req.exists():
        raise ValueError(f"no request {rid}")
    r = json.loads(req.read_text())
    if r["status"] != "pending":
        return f"Already {r['status']}."
    (store.ASKS / f"{rid}.decision").write_text(decision)
    return {"allow": "Allowed.", "always": "Allowed, and from now on.", "deny": "Denied."}[decision]


def still_waiting(session_id):
    """False once the session is no longer at a permission prompt (answered on the machine)."""
    import chats
    s = next((x for x in chats.agents() if x["sessionId"] == session_id), None)
    if s is None or s.get("kind") == "interactive":
        return True  # a terminal session does not report it; its own dialog still works
    return s.get("state") == "blocked"


def detail(tool, inp):
    if tool == "Bash":
        return inp.get("command", "") + (f"\n\n{inp['description']}" if inp.get("description") else "")
    if tool == "Edit":
        return f"{inp.get('file_path')}\n\n- {str(inp.get('old_string'))[:600]}\n+ {str(inp.get('new_string'))[:600]}"
    if tool == "Write":
        return f"{inp.get('file_path')}\n\n{str(inp.get('content'))[:600]}"
    return json.dumps(inp, ensure_ascii=False, indent=1)[:1200]


def hook():
    import chats
    req = json.load(sys.stdin)
    if req.get("permission_mode") in QUIET_MODES:
        return  # no output: Claude Code decides as it would without this hook
    rid = uuid.uuid4().hex[:8]
    sid = req["session_id"]
    s = next((x for x in chats.agents() if x["sessionId"] == sid), None)
    name, short = (s.get("name") or s["id"], s["id"]) if s else (Path(req.get("cwd", "")).name, sid[:8])
    tool, inp = req.get("tool_name", ""), req.get("tool_input") or {}
    line = chats.summary(tool, inp)
    path = store.ASKS / f"{rid}.json"
    record = {"rid": rid, "session_id": sid, "id": short, "name": name, "tool": tool, "summary": line,
              "detail": detail(tool, inp), "suggestions": req.get("permission_suggestions") or [],
              "status": "pending", "at": time.strftime("%Y-%m-%d %H:%M:%S")}
    store.write_json(path, record)

    def done(status):
        record["status"] = status
        store.write_json(path, record)
        (store.ASKS / f"{rid}.decision").unlink(missing_ok=True)

    signal.signal(signal.SIGTERM, lambda *_: (done("gone"), sys.exit(0)))
    buttons = [[["✅ Allow", f"perm:{rid}:allow"], ["❌ Deny", f"perm:{rid}:deny"]],
               [["Always allow", f"perm:{rid}:always"]]]
    store.send("ask", f"[ask {rid} {short}] {name} wants to use {tool}:\n{line[:400]}",
               importance="urgent", buttons=buttons, origin={"claude_session": sid, "agent": name})
    deadline, next_check = time.monotonic() + WAIT_S, time.monotonic() + CHECK_S
    decision_file = store.ASKS / f"{rid}.decision"
    while time.monotonic() < deadline:
        if decision_file.exists():
            d = decision_file.read_text().strip()
            done({"allow": "allowed", "always": "allowed", "deny": "denied"}[d])
            out = {"behavior": "deny", "message": "Denied from the phone. Do not retry it; say what you "
                   "would do instead, or ask."} if d == "deny" else {"behavior": "allow"}
            if d == "always" and record["suggestions"]:
                out["updatedPermissions"] = record["suggestions"]
            print(json.dumps({"hookSpecificOutput": {"hookEventName": "PermissionRequest", "decision": out}}))
            return
        if time.monotonic() >= next_check:
            if not still_waiting(sid):
                return done("answered on the machine")
            next_check = time.monotonic() + CHECK_S
        time.sleep(POLL_S)
    done("timed out")  # no output: the machine's dialog decides


def pending(session_id=None):
    """Requests still waiting (optionally for one session), oldest first."""
    rows = [json.loads(p.read_text()) for p in store.ASKS.glob("*.json")] if store.ASKS.exists() else []
    return sorted((r for r in rows if r["status"] == "pending" and (session_id is None or r["session_id"] == session_id)),
                  key=lambda r: r["at"])


def recent(session_id):
    """A session's requests, pending and answered, oldest first (the last 30)."""
    rows = [json.loads(p.read_text()) for p in store.ASKS.glob("*.json")] if store.ASKS.exists() else []
    rows = sorted((r for r in rows if r["session_id"] == session_id), key=lambda r: r["at"])[-30:]
    return [{k: r[k] for k in ("rid", "tool", "summary", "detail", "status", "at")} for r in rows]


if __name__ == "__main__":
    a = sys.argv[1:]
    if not a:
        hook()
    elif len(a) == 3 and a[0] == "answer":
        print(answer(a[1], a[2]))
    else:
        sys.exit(__doc__)
