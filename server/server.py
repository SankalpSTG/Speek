#!/usr/bin/env python3
"""Speek's server: the MCP endpoint the Speek app talks to (and any Claude, to reach its human).

Listens on 127.0.0.1 (server.json `port`); publish it over HTTPS with any tunnel or reverse proxy.

  POST /mcp      MCP (Streamable HTTP, JSON responses, stateless), `Authorization: Bearer <token>`
  GET  /health   "ok"

  serve      run the server
  token      create ~/.config/speek/server.env (MCP_TOKEN) once
  protocol   print the MCP tool reference as Markdown (PROTOCOL.md)

Extensions (server.json `extensions`: paths of Python files) add tools and hooks without
touching this code. An extension module may define:
  TOOLS             MCP tool definitions it serves; call(name, args) answers them
  BUTTONS           button prefixes (`prefix:...`) the app should show; press(data) handles them
  reply(mid, text, msg) -> str | None          take the human's reply (and keep its thread)
  upload(path, kind) -> [str]                   extra lines about an uploaded file
  activity(text)                                the human did something in the app
  route(handler) -> bool                        serve an extra HTTP path (handler.path)
  capabilities() -> dict                        merged into `capabilities`
  start()                                       called once when the server starts
examples/status_ext.py is a working extension; PROTOCOL.md lists the optional tools the app uses.
"""
import base64
import datetime as dt
import hmac
import http.server
import importlib.util
import json
import mimetypes
import re
import secrets
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import asks  # noqa: E402
import chats  # noqa: E402
import store  # noqa: E402
import threads  # noqa: E402

PROTOCOLS = ("2025-06-18", "2025-03-26", "2024-11-05")
EXTENSIONS = []

FILES = {"type": "array", "items": {"type": "object", "required": ["name", "data"], "properties": {
    "name": {"type": "string"}, "data": {"type": "string", "contentEncoding": "base64"}}}}
NONE = {"type": "object", "properties": {}}


def obj(required, **props):
    return {"type": "object", "required": list(required), "properties": props}


S, I, B = {"type": "string"}, {"type": "integer"}, {"type": "boolean"}
CORE_TOOLS = [
    {"name": "capabilities", "description": "What this server offers, as JSON: name, extensions, and "
     "whatever each extension reports.", "inputSchema": NONE},
    {"name": "chats", "description": "The human's real Claude Code sessions (what `claude agents` lists) "
     "and the permission requests waiting, as JSON {sessions, asks}.", "inputSchema": NONE},
    {"name": "chat_events", "description": "Long poll session `id`: its transcript as events after `after` "
     "(seq), at most `limit`, waiting up to `timeout` s for new ones. JSON {status, waiting_for, events, "
     "asks}.", "inputSchema": obj(["id", "after", "limit", "timeout"], id=S, after=I, limit=I, timeout=I)},
    {"name": "chat_send", "description": "The human's message (and files) into session `id`.",
     "inputSchema": obj(["id", "text"], id=S, text=S, files=FILES)},
    {"name": "chat_new", "description": "Start a Claude Code session (claude --bg, in the configured chat "
     "folder) named `name`, with the human's first message. JSON {id}.",
     "inputSchema": obj(["name", "text"], name=S, text=S, files=FILES)},
    {"name": "chat_answer", "description": "The human's answer to permission request `rid`: allow, deny "
     "or always.", "inputSchema": obj(["rid", "decision"], rid=S, decision=S)},
    {"name": "chat_stop", "description": "Stop session `id` (claude stop; the conversation is kept).",
     "inputSchema": obj(["id"], id=S)},
    {"name": "chat_rename", "description": "Rename session `id` (as /rename does).",
     "inputSchema": obj(["id", "name"], id=S, name=S)},
    {"name": "chat_delete", "description": "Delete session `id` (claude rm).", "inputSchema": obj(["id"], id=S)},
    {"name": "send", "description": "For a Claude: message the human on their phone. kind message "
     "(default), call (rings like an alarm until stopped; ring_at 'YYYY-MM-DD HH:MM' for later) or app (a "
     "Speek build). files: absolute paths on this machine or URLs. thread: answer inside the thread of a "
     "message they replied to. Returns the message id; read their replies with `notification`.",
     "inputSchema": obj(["text"], text=S, kind=S, files={"type": "array", "items": S}, ring_at=S,
                        importance=S, thread=S)},
    {"name": "notifications", "description": "The human's notifications, newest first (answers inside a "
     "thread are left out), as JSON rows {id, created, kind, importance, text, files, ring_at, from, "
     "buttons, thread {replies, status, last}}.", "inputSchema": obj(["limit"], limit=I)},
    {"name": "notification", "description": "One notification and its thread as JSON: notification, "
     "entries [{at, from: owner|run, text}], status.", "inputSchema": obj(["id"], id=S)},
    {"name": "notification_reply", "description": "The human's reply to notification `id` (files as in "
     "chat_send): it goes to whatever sent it.", "inputSchema": obj(["id", "text"], id=S, text=S, files=FILES)},
    {"name": "notification_button", "description": "Press one of a notification's buttons by its data.",
     "inputSchema": obj(["data"], data=S)},
    {"name": "feed", "description": "The latest messages, newest first. format json: {cursor, messages}; "
     "otherwise text blocks `[YYYY-MM-DD HH:MM:SS] kind:` + text + importance / ring / file lines.",
     "inputSchema": obj(["limit"], limit=I, format=S)},
    {"name": "wait", "description": "Long poll: returns when a message newer than `cursor` (a message id) "
     "exists or a call was answered on another phone, else after `timeout` s. format json: {cursor, "
     "messages, answered}. An empty cursor returns the newest id at once.",
     "inputSchema": obj(["cursor", "timeout"], cursor=S, timeout=I, format=S)},
    {"name": "call_answered", "description": "One of the human's phones stopped ringing for call `id`: the "
     "other phones' wait returns it in `answered` and they stop.", "inputSchema": obj(["id"], id=S)},
    {"name": "file", "description": "One file a message names (a path from its `files`), as an embedded "
     "resource blob. Paths no message names are refused.", "inputSchema": obj(["path"], path=S)},
]


def tools():
    return CORE_TOOLS + [t for ext in EXTENSIONS for t in getattr(ext, "TOOLS", [])]


def load_extensions():
    for p in store.config()["extensions"]:
        p = Path(p).expanduser()
        sys.path.insert(0, str(p.parent))
        spec = importlib.util.spec_from_file_location(p.stem, p)
        mod = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(mod)
        EXTENSIONS.append(mod)


def hook(name, *args):
    for ext in EXTENSIONS:
        if hasattr(ext, name):
            getattr(ext, name)(*args)


# ---- messages ----

def kind_of(name):
    mime = mimetypes.guess_type(name)[0] or ""
    return {"image": "photo", "video": "video", "audio": "audio"}.get(mime.split("/")[0], "document")


def upload(text, files):
    """The human's words plus their files, saved under media/, as the text a session gets."""
    cfg = store.config()
    blobs = [(Path(str(f["name"])).name or "file", base64.b64decode(f["data"], validate=True)) for f in files]
    if sum(len(b) for _, b in blobs) > cfg["mcp_upload_max_mb"] * 2**20:
        raise ValueError(f"files over {cfg['mcp_upload_max_mb']} MB together")
    lines = [text] if text else []
    stamp = dt.datetime.now().strftime("%H%M%S")
    for i, (fname, data) in enumerate(blobs):
        dest = store.MEDIA / f"{dt.date.today():%Y-%m-%d}" / f"app-{stamp}-{i}-{fname}"
        dest.parent.mkdir(parents=True, exist_ok=True)
        dest.write_bytes(data)
        what = kind_of(fname)
        lines.append(f"Attached {what}: {dest}")
        for ext in EXTENSIONS:
            if hasattr(ext, "upload"):
                lines += ext.upload(dest, what)
    if not lines:
        raise ValueError("empty message")
    return "\n".join(lines)


def buttons_shown(m):
    prefixes = {"perm"} | {p for ext in EXTENSIONS for p in getattr(ext, "BUTTONS", ())}
    return [b for row in (m.get("buttons") or []) for b in row if b[1].split(":", 1)[0] in prefixes]


def row(m):
    t = threads.path(m["id"])
    o = (json.loads(t.read_text()).get("origin") if t.exists() else None) or m.get("origin")
    return {"id": m["id"], "created": m["created"], "kind": m["kind"], "importance": m.get("importance", "unrated"),
            "text": m["text"], "files": m.get("files", []), "ring_at": m.get("ring_at"),
            "from": o["agent"] if o else store.config()["name"], "buttons": buttons_shown(m),
            "thread": threads.summary(m["id"])}


def notification_list(limit):
    out = []
    for p in reversed(store.outbox()):
        m = json.loads(p.read_text())
        if m["kind"] == "session" or m.get("thread"):
            continue
        out.append(row(m))
        if len(out) >= limit:
            break
    return out


def json_msg(m):
    return {"id": m["id"], "kind": m["kind"], "text": m["text"], "importance": m.get("importance", "unrated"),
            "ring_at": m.get("ring_at"), "session": m.get("session"), "thread": m.get("thread"),
            "files": m.get("files", [])}


def blocks(msgs):
    return "\n\n".join(f"[{m['created'][:19].replace('T', ' ')}] {m['kind']}:\n{m['text']}"
                       + (f"\nimportance: {m['importance']}" if "importance" in m else "")
                       + (f"\nring: {m['ring_at']}" if m.get("ring_at") else "")
                       + "".join(f"\nfile: {f}" for f in m.get("files", [])) for m in msgs)


def feed(limit, as_json):
    files = list(reversed(store.outbox()))[:min(limit, store.config()["mcp_feed_max"])]
    msgs = [json.loads(p.read_text()) for p in files]
    if as_json:
        return json.dumps({"cursor": files[0].stem if files else "", "messages": [json_msg(m) for m in msgs]},
                          ensure_ascii=False)
    return blocks(msgs) or "Nothing sent yet."


def wait(cursor, timeout, as_json):
    deadline = time.monotonic() + min(timeout, store.config()["mcp_wait_max_s"])
    stamp = store.answered_stamp()
    while True:
        files = store.outbox()
        newest = files[-1].stem if files else ""
        new = [p for p in files if p.stem > cursor] if cursor else []
        woke = as_json and store.answered_stamp() != stamp
        if new or woke or not cursor or time.monotonic() >= deadline:
            msgs = [json.loads(p.read_text()) for p in reversed(new)]
            if as_json:
                return json.dumps({"cursor": newest or cursor, "messages": [json_msg(m) for m in msgs],
                                   "answered": sorted(store.answered_calls())}, ensure_ascii=False)
            return f"cursor: {newest or cursor}" + (f"\n\n{blocks(msgs)}" if msgs else "")
        time.sleep(1)


def sent_file(path):
    """A file some message names. This keeps the phone to what was sent to it; it is not a security
    boundary: the token already gives full use of this machine (see README, Security)."""
    sent = {f for p in store.outbox() for f in json.loads(p.read_text()).get("files", [])}
    if path not in sent:
        raise ValueError(f"{path} is not a file any message names")
    f = Path(path)
    if not f.is_file():
        raise ValueError(f"{f.name} is no longer on the machine")
    if f.stat().st_size > store.config()["mcp_file_max_mb"] * 2**20:
        raise ValueError(f"{f.name} is over {store.config()['mcp_file_max_mb']} MB")
    return [{"type": "resource", "resource": {
        "uri": f.as_uri(), "mimeType": mimetypes.guess_type(f.name)[0] or "application/octet-stream",
        "blob": base64.b64encode(f.read_bytes()).decode()}}]


def press(data):
    how, _, ref = data.partition(":")
    if how == "perm":
        rid, _, decision = ref.partition(":")
        return asks.answer(rid, decision)
    for ext in EXTENSIONS:
        if how in getattr(ext, "BUTTONS", ()):
            return ext.press(data)
    raise ValueError(f"no button {how}")


# ---- tools ----

def chat_tool(name, args):
    out = lambda o: json.dumps(o, ensure_ascii=False)
    if name == "chats":
        return out({"sessions": chats.listing(), "asks": asks.pending()})
    if name == "chat_new":
        return out({"id": chats.new(str(args["name"]).strip() or "speek",
                                    upload(str(args["text"]).strip(), args.get("files") or []))})
    if name == "chat_answer":
        return out({"result": asks.answer(str(args["rid"]), str(args["decision"]))})
    s = chats.find(str(args["id"]))
    if name == "chat_send":
        chats.send(s["id"], upload(str(args["text"]).strip(), args.get("files") or []))
        hook("activity", f"talking to Claude Code session {s.get('name') or s['id']} from Speek")
        return out({"sent": True})
    if name == "chat_rename":
        chats.rename(s["id"], str(args["name"]))
        return out({"renamed": True})
    if name == "chat_delete":
        chats.delete(s["id"])
        return out({"deleted": True})
    if name == "chat_stop":
        chats.stop(s["id"])
        return out({"stopped": True})
    if name == "chat_events":
        after, limit = int(args["after"]), min(int(args["limit"]), 1000)
        deadline = time.monotonic() + min(int(args["timeout"]), store.config()["mcp_wait_max_s"])
        asks_then = json.dumps(asks.pending(s["sessionId"]))
        while True:
            evs = chats.events(s, after)[-limit:]
            if evs or time.monotonic() >= deadline or json.dumps(asks.pending(s["sessionId"])) != asks_then:
                break
            time.sleep(0.5)
        s = chats.find(s["id"])
        return out({"status": chats.status(s), "waiting_for": s.get("waitingFor") or "",
                    "events": evs, "asks": asks.recent(s["sessionId"])})
    raise KeyError(name)


def call_tool(name, args):
    if name == "capabilities":
        caps = {"name": store.config()["name"], "extensions": [e.__name__ for e in EXTENSIONS]}
        for ext in EXTENSIONS:
            if hasattr(ext, "capabilities"):
                caps.update(ext.capabilities())
        return json.dumps(caps)
    if name == "chats" or name.startswith("chat_"):
        return chat_tool(name, args)
    if name == "send":
        kind = str(args.get("kind") or "message")
        if kind not in ("message", "call", "app"):
            raise ValueError("kind is message, call or app")
        thread = args.get("thread")
        mid = store.send("reply" if thread else kind, str(args["text"]), args.get("files") or [],
                         args.get("ring_at"), "urgent" if kind == "call" else str(args.get("importance") or "normal"),
                         None, thread)
        if thread:
            threads.answered(thread, str(args["text"]))
        return json.dumps({"id": mid})
    if name == "notifications":
        return json.dumps(notification_list(min(int(args["limit"]), store.config()["mcp_feed_max"])), ensure_ascii=False)
    if name == "notification":
        m = store.find(str(args["id"]))
        t = threads.load(m["id"])
        return json.dumps({"notification": row(m), "entries": t["entries"], "status": t["status"]}, ensure_ascii=False)
    if name == "notification_reply":
        store.find(str(args["id"]))
        text = upload(str(args["text"]).strip(), args.get("files") or [])
        hook("activity", f"replied to a notification in Speek: {text[:200]}")
        return threads.reply(str(args["id"]), text, EXTENSIONS)
    if name == "notification_button":
        return press(str(args["data"]))
    if name == "feed":
        return feed(int(args["limit"]), args.get("format") == "json")
    if name == "wait":
        return wait(str(args["cursor"]), int(args["timeout"]), args.get("format") == "json")
    if name == "call_answered":
        return store.call_answered(str(args["id"]))
    if name == "file":
        return sent_file(str(args["path"]))
    for ext in EXTENSIONS:
        if name in {t["name"] for t in getattr(ext, "TOOLS", [])}:
            return ext.call(name, args)
    raise KeyError(f"unknown tool {name}")


def rpc(req):
    rid, method = req.get("id"), req.get("method", "")
    if rid is None:
        return None  # a notification (initialized, cancelled): nothing to answer
    ok = lambda result: {"jsonrpc": "2.0", "id": rid, "result": result}
    if method == "initialize":
        asked = req.get("params", {}).get("protocolVersion")
        return ok({"protocolVersion": asked if asked in PROTOCOLS else PROTOCOLS[0],
                   "capabilities": {"tools": {}}, "serverInfo": {"name": "speek", "version": store.VERSION},
                   "instructions": "Speek reaches the human on their phone. `send` messages them or rings "
                                   "them (kind call); `notification` shows their replies to a message."})
    if method == "ping":
        return ok({})
    if method == "tools/list":
        return ok({"tools": tools()})
    if method == "tools/call":
        p = req.get("params", {})
        try:
            out, bad = call_tool(p.get("name"), p.get("arguments") or {}), False
        except KeyError as e:
            if p.get("name") not in {t["name"] for t in tools()}:
                return {"jsonrpc": "2.0", "id": rid, "error": {"code": -32602, "message": str(e)}}
            out, bad = f"missing argument {e}", True
        except Exception as e:
            out, bad = f"{type(e).__name__}: {e}", True
        return ok({"content": out if isinstance(out, list) else [{"type": "text", "text": out}], "isError": bad})
    return {"jsonrpc": "2.0", "id": rid, "error": {"code": -32601, "message": f"method not found: {method}"}}


class Handler(http.server.BaseHTTPRequestHandler):
    server_version = "speek"

    def log_message(self, fmt, *args):
        pass

    def reply(self, code, body=b"", ctype="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def body(self):
        return json.loads(self.rfile.read(int(self.headers.get("Content-Length", 0))) or b"null")

    def extension_route(self):
        return any(hasattr(ext, "route") and ext.route(self) for ext in EXTENSIONS)

    def do_GET(self):
        if self.path == "/health":
            return self.reply(200, b"ok", "text/plain")
        if self.path == "/mcp":
            return self.reply(405, b"", "text/plain")
        if not self.extension_route():
            self.reply(404, b"", "text/plain")

    def do_POST(self):
        try:
            if self.path == "/mcp":
                return self.mcp()
            if not self.extension_route():
                self.reply(404, b"", "text/plain")
        except Exception as e:
            store.log(f"server: {self.path} failed: {type(e).__name__}: {e}")
            self.reply(500, b"", "text/plain")

    def mcp(self):
        if not hmac.compare_digest(self.headers.get("Authorization", ""), f"Bearer {store.token()}"):
            self.send_response(401)
            self.send_header("WWW-Authenticate", "Bearer")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if int(self.headers.get("Content-Length", 0)) > store.config()["mcp_upload_max_mb"] * 2**20 * 4 // 3 + 2**20:
            return self.reply(413, b"", "text/plain")
        req = self.body()
        if not isinstance(req, dict):
            return self.reply(400, json.dumps({"jsonrpc": "2.0", "id": None, "error": {
                "code": -32600, "message": "one JSON-RPC message per request"}}).encode())
        res = rpc(req)
        if res is None:
            return self.reply(202, b"", "text/plain")
        if req.get("method") == "tools/call":
            store.log(f"server: mcp {req['params'].get('name')}")
        self.reply(200, json.dumps(res).encode())


def serve():
    cfg = store.config()
    store.token()  # fail at start, not on the first request
    load_extensions()
    hook("start")
    srv = http.server.ThreadingHTTPServer(("127.0.0.1", cfg["port"]), Handler)
    store.log(f"server: listening on 127.0.0.1:{cfg['port']} ({len(EXTENSIONS)} extensions)")
    srv.serve_forever()


def make_token():
    env = store.CONFIG / "server.env"
    if env.exists():
        return f"{env} already exists; delete it to make a new token."
    env.parent.mkdir(parents=True, exist_ok=True)
    env.touch(0o600)
    env.write_text(f"MCP_TOKEN={secrets.token_urlsafe(32)}\n")
    return f"Wrote {env}."


def protocol():
    """The core MCP tool reference as Markdown (PROTOCOL.md embeds it)."""
    out = ["| Tool | Arguments | What it does |", "|---|---|---|"]
    for tool in CORE_TOOLS:
        schema = tool["inputSchema"]
        need = set(schema.get("required", []))
        args = ", ".join(f"`{k}`" + ("" if k in need else "?") for k in schema.get("properties", {}))
        desc = " ".join(tool["description"].split()).replace("|", "\\|")
        out.append(f"| `{tool['name']}` | {args or '-'} | {desc} |")
    return "\n".join(out)


if __name__ == "__main__":
    a = sys.argv[1:]
    if a == ["serve"]:
        serve()
    elif a == ["token"]:
        print(make_token())
    elif a == ["protocol"]:
        print(protocol())
    else:
        sys.exit(__doc__)
