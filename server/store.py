"""Speek server storage: config, data folders, the message outbox, calls answered.

Nothing lives next to the code. Config and the token: ~/.config/speek (server.json, server.env).
Data: ~/.local/share/speek (outbox/ with sent/, threads/, asks/, media/, calls_answered.json,
speek.log).

A message is one JSON file in outbox/ (or outbox/sent/ once an extension delivered it
elsewhere), named by its id. Ids sort by time, so the newest file is the newest message:
  {id, created, kind, text, files, session, ring_at, importance, origin, thread, buttons}
kind: message (default), reply, call (rings the phone), ask (a permission request), app (a
Speek build to install), or any kind an extension adds.
"""
import datetime as dt
import json
import re
import threading
import uuid
from pathlib import Path

CONFIG = Path.home() / ".config" / "speek"
DATA = Path.home() / ".local" / "share" / "speek"
OUTBOX = DATA / "outbox"
SENT = OUTBOX / "sent"
THREADS = DATA / "threads"
ASKS = DATA / "asks"
MEDIA = DATA / "media"
ANSWERED = DATA / "calls_answered.json"
LOG = DATA / "speek.log"
IMPORTANCE = ("urgent", "normal", "quiet", "unrated")
ID = re.compile(r"[A-Za-z0-9_-]{1,64}")
VERSION = "0.15.1"
ANSWERED_KEEP_S = 3600  # longer than any phone keeps ringing or a call stays late
_answered_lock = threading.Lock()


def config():
    """server.json: port, name, chat_dir, mcp_feed_max, mcp_upload_max_mb, mcp_file_max_mb,
    mcp_wait_max_s, extensions. Every key is required."""
    return json.loads((CONFIG / "server.json").read_text())


def token():
    env = dict(l.split("=", 1) for l in (CONFIG / "server.env").read_text().splitlines() if "=" in l)
    return env["MCP_TOKEN"].strip()


def log(msg):
    LOG.parent.mkdir(parents=True, exist_ok=True)
    with LOG.open("a") as f:
        f.write(f"{dt.datetime.now().isoformat(timespec='seconds')} {msg}\n")


def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(obj, indent=2, ensure_ascii=False) + "\n")
    tmp.replace(path)


def checked(ident):
    """An id that arrived from a client, safe to use in a file name (no `/`, no `..`)."""
    if not isinstance(ident, str) or not ID.fullmatch(ident):
        raise ValueError(f"not a valid id: {ident!r}")
    return ident


def new_id():
    return f"{dt.datetime.now():%Y%m%d-%H%M%S}-{uuid.uuid4().hex[:6]}"


def send(kind, text, files=(), ring_at=None, importance="unrated", origin=None, thread=None,
         buttons=None, session=None, extra=None):
    """Queue one message for the phone; returns its id. files: absolute paths or http(s) URLs.
    ring_at (kind call): 'YYYY-MM-DD HH:MM', when the phone rings; None rings now."""
    text, files = text.strip(), list(files)
    if not text and not files:
        raise ValueError("empty message")
    for f in files:
        if not f.startswith(("http://", "https://")) and not (Path(f).is_absolute() and Path(f).is_file()):
            raise ValueError(f"{f} is not a URL or an absolute path to a file")
    if ring_at is not None:
        if kind != "call":
            raise ValueError("a ring time goes with kind call")
        if dt.datetime.strptime(ring_at, "%Y-%m-%d %H:%M") <= dt.datetime.now():
            raise ValueError(f"{ring_at} is not in the future")
    if importance not in IMPORTANCE:
        raise ValueError(f"importance is one of {', '.join(IMPORTANCE)}")
    mid = new_id()
    msg = {"id": mid, "created": dt.datetime.now().isoformat(timespec="seconds"), "kind": kind,
           "text": text, "files": files, "session": session, "ring_at": ring_at,
           "importance": importance, "origin": origin, "thread": thread, "buttons": buttons or [],
           "deliveries": {}, **(extra or {})}
    write_json(OUTBOX / f"{mid}.json", msg)
    log(f"send {kind} {mid}")
    return mid


def outbox():
    """Every message file, queued or sent, oldest first."""
    return sorted(list(OUTBOX.glob("*.json")) + list(SENT.glob("*.json")), key=lambda p: p.name)


def find(mid):
    checked(mid)
    for p in (OUTBOX / f"{mid}.json", SENT / f"{mid}.json"):
        if p.exists():
            return json.loads(p.read_text())
    raise ValueError(f"no message {mid}")


def answered_calls():
    """Call ids a phone stopped within the last ANSWERED_KEEP_S: id -> ISO time."""
    if not ANSWERED.exists():
        return {}
    cut = (dt.datetime.now() - dt.timedelta(seconds=ANSWERED_KEEP_S)).isoformat(timespec="seconds")
    return {k: v for k, v in json.loads(ANSWERED.read_text()).items() if v >= cut}


def call_answered(mid):
    """One phone stopped call `mid`: every other phone's wait wakes and stops ringing."""
    if find(mid)["kind"] != "call":
        raise ValueError(f"{mid} is not a call")
    with _answered_lock:
        rows = answered_calls()
        rows[mid] = dt.datetime.now().isoformat(timespec="seconds")
        write_json(ANSWERED, rows)
    return "Stopped on every phone."


def answered_stamp():
    return ANSWERED.stat().st_mtime if ANSWERED.exists() else 0
