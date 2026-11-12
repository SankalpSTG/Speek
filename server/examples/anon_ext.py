"""A Speek server extension: the app's Anonymous tab, as headless `claude -p` conversations.

Enable it by listing this file in ~/.config/speek/server.json:

    "extensions": ["/ABSOLUTE/PATH/TO/server/examples/anon_ext.py"]

Each anonymous chat is one Claude conversation run with `claude -p` in the chat folder
(server.json `chat_dir`), resumed by its session id for every message. Unlike Chats, these runs
never show in `claude agents`. Its tool calls get Claude Code's permission rules for `-p` runs:
anything not allowed in your settings is refused (no prompt). The JSON shapes are in PROTOCOL.md,
"Optional tools".
"""
import json
import os
import re
import subprocess
import time
import uuid
from pathlib import Path

import chats
import store

INDEX = store.DATA / "anon.json"  # n -> {label, session_id, pid, updated}
RUNS = {}  # n -> the running claude process

NONE = {"type": "object", "properties": {}}
FILES = {"type": "array", "items": {"type": "object", "required": ["name", "data"], "properties": {
    "name": {"type": "string"}, "data": {"type": "string", "contentEncoding": "base64"}}}}
N, S, I = {"type": "integer"}, {"type": "string"}, {"type": "integer"}
TOOLS = [
    {"name": "anon_chats", "description": "The background conversations, as JSON {sessions}.", "inputSchema": NONE},
    {"name": "anon_new", "description": "Start a background conversation with a first message. JSON {n}.",
     "inputSchema": {"type": "object", "required": ["label", "text"], "properties": {"label": S, "text": S, "files": FILES}}},
    {"name": "anon_send", "description": "A message into background conversation `n`.",
     "inputSchema": {"type": "object", "required": ["n", "text"], "properties": {"n": N, "text": S, "files": FILES}}},
    {"name": "anon_events", "description": "Long poll conversation `n` as chat_events does. JSON {status, events}.",
     "inputSchema": {"type": "object", "required": ["n", "after", "timeout", "limit"],
                     "properties": {"n": N, "after": I, "timeout": I, "limit": I}}},
    {"name": "anon_rename", "description": "A new label for conversation `n`.",
     "inputSchema": {"type": "object", "required": ["n", "label"], "properties": {"n": N, "label": S}}},
    {"name": "anon_delete", "description": "Forget conversation `n` (refused while it works).",
     "inputSchema": {"type": "object", "required": ["n"], "properties": {"n": N}}},
]


def index():
    return {int(k): v for k, v in json.loads(INDEX.read_text()).items()} if INDEX.exists() else {}


def save(rows):
    store.write_json(INDEX, {str(k): v for k, v in rows.items()})


def transcript(row):
    slug = re.sub(r"[^A-Za-z0-9]", "-", str(chats.chat_dir()))
    return chats.PROJECTS / slug / f"{row['session_id']}.jsonl"


def working(n):
    p = RUNS.get(n)
    return p is not None and p.poll() is None


def run(n, row, text, first):
    """`claude -p TEXT` in the chat folder: a new conversation with this id, or its next turn."""
    args = ["--session-id" if first else "--resume", row["session_id"]]
    RUNS[n] = subprocess.Popen([chats.CLAUDE, "-p", text, *args], cwd=chats.chat_dir(),
                               stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                               start_new_session=True, env=dict(os.environ))


def call(name, args):
    import server  # the running server: its upload() saves files the human attached
    rows = index()
    if name == "anon_chats":
        return json.dumps({"sessions": [
            {"n": n, "label": r["label"], "status": "working" if working(n) else "idle", "updated": r["updated"],
             "last": chats.last_message({"cwd": str(chats.chat_dir()), "sessionId": r["session_id"]}),
             "current": False} for n, r in sorted(rows.items(), reverse=True)]})
    if name == "anon_new":
        n = max(rows, default=0) + 1
        rows[n] = {"label": str(args["label"]).strip(), "session_id": str(uuid.uuid4()),
                   "updated": time.strftime("%Y-%m-%d %H:%M:%S")}
        save(rows)
        run(n, rows[n], server.upload(str(args["text"]).strip(), args.get("files") or []), first=True)
        return json.dumps({"n": n})
    n = int(args["n"])
    if n not in rows:
        raise ValueError(f"no conversation {n}")
    row = rows[n]
    if name == "anon_send":
        if working(n):
            raise ValueError("it is still answering; send again when it is done")
        row["updated"] = time.strftime("%Y-%m-%d %H:%M:%S")
        save(rows)
        run(n, row, server.upload(str(args["text"]).strip(), args.get("files") or []), first=False)
        return "Sent."
    if name == "anon_events":
        deadline = time.monotonic() + min(int(args["timeout"]), store.config()["mcp_wait_max_s"])
        while True:
            evs = chats.events_in(transcript(row), int(args["after"]))[-int(args["limit"]):]
            if evs or time.monotonic() >= deadline:
                return json.dumps({"status": "working" if working(n) else "idle", "events": evs})
            time.sleep(0.5)
    if name == "anon_rename":
        row["label"] = str(args["label"]).strip()
        save(rows)
        return "Renamed."
    if name == "anon_delete":
        if working(n):
            raise ValueError("it is still answering")
        del rows[n]
        save(rows)
        return "Deleted."
    raise KeyError(name)
