"""Reply threads: every message the human gets is a thread they can answer in Speek.

A reply goes back to whatever sent the message (its `origin`):
  - a Claude Code session (origin claude_session, set by send.py from CLAUDE_CODE_SESSION_ID):
    the reply is typed into that session with the message quoted, and it answers in its chat;
  - an extension that claims the message (server.json `extensions`, its `reply` hook);
  - anything else: the reply stays in the thread, where the sender reads it (`notification`).
An answer inside a thread is a message with `thread` set (send.py --thread); it is added to the
thread as the sender's entry.
"""
import datetime as dt
import fcntl
import json

import store


def now():
    return dt.datetime.now().isoformat(timespec="seconds")


def path(mid):
    store.checked(mid)
    return store.THREADS / f"{mid}.json"


def load(mid):
    p = path(mid)
    if p.exists():
        return json.loads(p.read_text())
    m = store.find(mid)
    return {"mid": mid, "origin": m.get("origin"), "entries": [], "status": "idle", "error": ""}


def save(t):
    store.write_json(path(t["mid"]), t)


def locked(mid):
    store.THREADS.mkdir(parents=True, exist_ok=True)
    f = (store.THREADS / f"{store.checked(mid)}.lock").open("a")
    fcntl.flock(f, fcntl.LOCK_EX)
    return f


def reply(mid, text, extensions):
    """The human's reply to message `mid`; returns what to tell them."""
    text = text.strip()
    if not text:
        raise ValueError("empty reply")
    msg = store.find(mid)
    for ext in extensions:
        hook = getattr(ext, "reply", None)
        said = hook(mid, text, msg) if hook else None
        if said is not None:
            return said  # the extension keeps the thread itself
    with locked(mid):
        t = load(mid)
        t["entries"].append({"at": now(), "from": "owner", "text": text})
        save(t)
    o = t["origin"] or {}
    if "claude_session" in o:
        import chats
        chats.send(o["claude_session"], f"The human replied to a message you sent them:\n"
                                        f"----- your message -----\n{msg['text'][:1500]}\n----- end -----\n"
                                        f"Their reply: {text}")
        with locked(mid):
            t = load(mid)
            t["entries"].append({"at": now(), "from": "run",
                                 "text": f"Sent to {o.get('agent') or 'the session'}; it answers in its chat."})
            save(t)
        store.log(f"thread {mid}: reply sent into session {o['claude_session'][:8]}")
        return f"Sent to {o.get('agent') or 'the session'}."
    return "Saved in the thread; the sender reads it there."


def answered(mid, text):
    """The sender's answer inside thread `mid` (a message sent with --thread)."""
    with locked(mid):
        t = load(mid)
        t["entries"].append({"at": now(), "from": "run", "text": text})
        t["status"] = "idle"
        save(t)


def summary(mid):
    """{replies, status, last} for a notification row; zeros when nobody replied yet."""
    p = path(mid)
    if not p.exists():
        return {"replies": 0, "status": "none", "last": None}
    t = json.loads(p.read_text())
    return {"replies": len(t["entries"]), "status": t["status"],
            "last": t["entries"][-1]["at"] if t["entries"] else None}
