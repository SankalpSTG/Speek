"""The human's real Claude Code sessions: the ones `claude agents` lists and `claude attach`
opens. Speek's chats are these sessions, nothing parallel: a chat started from Speek is a
`claude --bg` session in the chat folder (server.json `chat_dir`), and one started in a terminal
shows up in Speek. Headless `claude -p` runs (automation) never appear here.

  list             claude agents --json --all, each with a preview of its last message
  events           a session's transcript as chat events (the human's messages, replies, tool steps)
  send             the human's message into a session: attached through a pseudo-terminal while it runs
                   (`claude --resume ID TEXT` needs a terminal), resumed under the same id when
                   it has stopped (`claude --bg --resume ID TEXT`)
  new              `claude --bg --name NAME TEXT` in the chat folder
  stop             `claude stop ID` (the conversation is kept)
  rename           Claude Code's own /rename, sent into the session as a message is
  delete           `claude rm ID`: the background session and (when safe) its worktree

Permission prompts in these sessions go through asks.py (a PermissionRequest hook in the
Claude Code settings), answered from Speek or the machine's own dialog.

Transcripts are Claude Code's own files (~/.claude/projects/<cwd slug>/<session id>.jsonl);
their format is Claude Code's, so all reading of them is in this file.
"""
import datetime as dt
import json
import os
import pty
import re
import select
import signal
import subprocess
import time
from pathlib import Path

import store

CLAUDE = os.environ.get("CLAUDE_BIN", "claude")
PROJECTS = Path.home() / ".claude" / "projects"
REGISTRY = Path.home() / ".claude" / "sessions"  # PID.json for every running claude process
PREVIEW = 600  # characters of a tool result shown in a chat
SEND_WAIT_S = 45  # how long a message may take to land in a session



def chat_dir():
    """Where a new chat (claude --bg) starts: server.json `chat_dir`."""
    return Path(store.config()["chat_dir"]).expanduser()


def run(*args, cwd=None, timeout=60):
    r = subprocess.run([CLAUDE, *args], cwd=cwd, capture_output=True, text=True, timeout=timeout,
                       stdin=subprocess.DEVNULL)
    if r.returncode:
        raise RuntimeError(f"claude {args[0]} failed: {(r.stderr or r.stdout).strip()[:300]}")
    return r.stdout


def print_run(s):
    """A `claude -p` process (headless automation): Claude Code registers those too
    (~/.claude/sessions/PID.json, entrypoint sdk-cli) and `claude agents` lists them as
    interactive. They are background work, never the human's chats."""
    pid = s.get("pid")
    if not pid:
        return False
    try:
        return json.loads((REGISTRY / f"{pid}.json").read_text()).get("entrypoint") == "sdk-cli"
    except (FileNotFoundError, json.JSONDecodeError):
        return False


def agents():
    """The human's sessions `claude agents` knows (active and finished), newest first; -p runs left out."""
    rows = [s for s in json.loads(run("agents", "--json", "--all")) if not print_run(s)]
    for s in rows:
        s.setdefault("id", s["sessionId"][:8])  # terminal sessions have no short id; attach takes this
    return sorted(rows, key=lambda s: s.get("startedAt", 0), reverse=True)


def find(short):
    for s in agents():
        if s["id"] == short or s["sessionId"] == short:
            return s
    raise ValueError(f"no Claude Code session {short}")


def running(s):
    """Its process is alive (a background session the daemon hosts, or a terminal session)."""
    pid = s.get("pid")
    if not pid:
        return False
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False


def status(s):
    """working | waiting (a permission prompt or a question) | idle | stopped."""
    if not running(s):
        return "stopped"
    state = s.get("state")
    if state == "working" or s.get("status") == "busy":
        return "working"
    if state == "blocked" or s.get("status") == "waiting":
        return "waiting"
    return "idle"


def transcript(s):
    slug = re.sub(r"[^A-Za-z0-9]", "-", s["cwd"])
    return PROJECTS / slug / f"{s['sessionId']}.jsonl"


def _text(content):
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        return "\n".join(c.get("text", "") for c in content if isinstance(c, dict) and c.get("type") == "text")
    return ""


def _shown_user(text):
    """The human's own words, not what Claude Code injects as a user turn (reminders, command output,
    task notifications, interrupts)."""
    t = text.strip()
    return bool(t) and not t.startswith("<") and not t.startswith("[Request interrupted")


def summary(name, inp):
    key = {"Bash": "command", "Read": "file_path", "Write": "file_path", "Edit": "file_path",
           "NotebookEdit": "notebook_path", "Glob": "pattern", "Grep": "pattern", "WebFetch": "url",
           "WebSearch": "query", "Agent": "description", "Task": "description", "Skill": "skill"}.get(name)
    if key and inp.get(key):
        return str(inp[key])[:300]
    first = next((v for v in inp.values() if isinstance(v, str) and v.strip()), "")
    return first[:300]


def events(s, after=0):
    """The session as chat events, oldest first; seq = transcript line * 100 + block, so it only
    grows as Claude Code appends. Subagent (sidechain) and meta entries are left out."""
    return events_in(transcript(s), after)


def events_in(p, after=0):
    """events() for any transcript file."""
    out = []
    if not p.exists():
        return out
    with p.open(errors="replace") as f:
        for line_no, line in enumerate(f, 1):
            if line_no * 100 + 99 <= after:
                continue
            try:
                d = json.loads(line)
            except json.JSONDecodeError:
                continue  # a line Claude Code is still writing
            if d.get("isSidechain") or d.get("isMeta") or d.get("type") not in ("user", "assistant"):
                continue
            at = d.get("timestamp", "")
            content = d.get("message", {}).get("content")
            blocks = content if isinstance(content, list) else [{"type": "text", "text": content or ""}]
            for i, b in enumerate(blocks):
                seq = line_no * 100 + i
                if seq <= after or not isinstance(b, dict):
                    continue
                ev = None
                if d["type"] == "user" and b.get("type") == "text" and _shown_user(b.get("text", "")):
                    ev = {"type": "user", "text": b["text"]}
                elif d["type"] == "user" and b.get("type") == "tool_result":
                    ev = {"type": "result", "tool_id": b.get("tool_use_id", ""), "error": bool(b.get("is_error")),
                          "preview": _text(b.get("content")) if not isinstance(b.get("content"), str) else b["content"]}
                    ev["preview"] = ev["preview"][:PREVIEW]
                elif d["type"] == "assistant" and b.get("type") == "text" and b.get("text", "").strip():
                    ev = {"type": "text", "text": b["text"]}
                elif d["type"] == "assistant" and b.get("type") == "tool_use":
                    ev = {"type": "tool", "tool_id": b.get("id", ""), "name": b.get("name", ""),
                          "summary": summary(b.get("name", ""), b.get("input") or {})}
                if ev:
                    out.append({"seq": seq, "at": _local(at), **ev})
    return out


def _local(iso):
    """Transcript timestamps are UTC ISO; the app shows the laptop's local time."""
    if not iso:
        return ""
    t = dt.datetime.fromisoformat(iso.replace("Z", "+00:00")).astimezone()
    return t.strftime("%Y-%m-%d %H:%M:%S")


def last_message(s):
    """A one-line preview: the newest reply or message, from the transcript's tail."""
    p = transcript(s)
    if not p.exists():
        return ""
    size = p.stat().st_size
    with p.open("rb") as f:
        f.seek(max(0, size - 200_000))
        lines = f.read().decode(errors="replace").splitlines()
    for line in reversed(lines):
        try:
            d = json.loads(line)
        except json.JSONDecodeError:
            continue
        if d.get("isSidechain") or d.get("isMeta") or d.get("type") not in ("user", "assistant"):
            continue
        t = _text(d.get("message", {}).get("content")).strip()
        if t and (d["type"] == "assistant" or _shown_user(t)):
            return (("You: " if d["type"] == "user" else "") + " ".join(t.split()))[:200]
    return ""


def listing():
    rows = []
    for s in agents():
        p = transcript(s)
        rows.append({"id": s["id"], "session_id": s["sessionId"], "name": s.get("name") or s["id"],
                     "cwd": s["cwd"], "kind": s.get("kind", ""), "status": status(s),
                     "waiting_for": s.get("waitingFor") or "",
                     "updated": time.strftime("%Y-%m-%d %H:%M:%S", time.localtime(p.stat().st_mtime)) if p.exists() else "",
                     "last": last_message(s)})
    return rows


def _delivered(s, text, since_line):
    p = transcript(s)
    if not p.exists():
        return False
    want = " ".join(text.split())
    with p.open(errors="replace") as f:
        for n, line in enumerate(f, 1):
            if n <= since_line:
                continue
            try:
                d = json.loads(line)
            except json.JSONDecodeError:
                continue
            if d.get("type") == "user" and " ".join(_text(d.get("message", {}).get("content")).split()) == want:
                return True
    return False


def _lines(s):
    p = transcript(s)
    return sum(1 for _ in p.open(errors="replace")) if p.exists() else 0


def send(short, text):
    """The human's message into session `short`. Returns once it is in the session's transcript."""
    s = find(short)
    if s.get("kind") == "interactive":
        raise ValueError(f"{s.get('name') or short} runs in a terminal on the laptop: answer it there")
    since = _lines(s)
    _deliver(s, text, lambda: _delivered(s, text, since))
    store.log(f"chats: message into {s['id']}")


def _end(pid, fd):
    """Stops a claude process started in a pseudo-terminal and closes the terminal."""
    for sig in (signal.SIGTERM, signal.SIGKILL):
        try:
            os.kill(pid, sig)
            time.sleep(0.5)
        except ProcessLookupError:
            break
    try:
        os.waitpid(pid, 0)
    except ChildProcessError:
        pass
    os.close(fd)


def _deliver(s, text, landed):
    """TEXT into session s as the next input (a message or a slash command); returns once
    landed() is true."""
    if not running(s):
        run("--bg", "--resume", s["sessionId"], text, cwd=s["cwd"])
    else:
        # `claude --resume ID TEXT` attaches to the running session and sends TEXT as its next
        # turn, but only with a terminal: give it one, wait for the message to land, detach.
        pid, fd = pty.fork()
        if pid == 0:
            os.chdir(s["cwd"])
            os.execvp(CLAUDE, [CLAUDE, "--resume", s["sessionId"], text])
        try:
            deadline = time.monotonic() + SEND_WAIT_S
            while time.monotonic() < deadline and not landed():
                if select.select([fd], [], [], 0.3)[0]:
                    try:
                        os.read(fd, 65536)  # drain the screen it draws
                    except OSError:
                        break
        finally:
            _end(pid, fd)
    deadline = time.monotonic() + SEND_WAIT_S
    while not landed():
        if time.monotonic() > deadline:
            raise RuntimeError(f"the session did not take {text[:40]!r}")
        time.sleep(0.5)


def _type(s, text, landed):
    """Types TEXT into running session s as the human would: `claude attach` in a pseudo-terminal,
    TEXT + Enter once its prompt is drawn, detached once landed() is true."""
    import fcntl, struct, termios
    pid, fd = pty.fork()
    if pid == 0:
        os.chdir(s["cwd"])
        os.execvp(CLAUDE, [CLAUDE, "attach", s["id"]])
    fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", 50, 160, 0, 0))
    screen, typed = b"", None
    try:
        deadline = time.monotonic() + SEND_WAIT_S
        while time.monotonic() < deadline:
            if typed is None and "❯".encode() in screen:
                time.sleep(1)  # let it finish drawing before typing
                os.write(fd, text.encode())
                time.sleep(0.5)
                os.write(fd, b"\r")
                typed = time.monotonic()
            if typed is not None and landed():
                break
            if select.select([fd], [], [], 0.3)[0]:
                try:
                    screen = (screen + os.read(fd, 65536))[-200_000:]
                except OSError:
                    break
        else:
            raise RuntimeError(f"the session did not take {text[:40]!r}" + ("" if typed else " (attach drew no prompt)"))
    finally:
        _end(pid, fd)


def new(name, text):
    """A new background session in the chat folder (server.json `chat_dir`), named, started with
    the first message."""
    out = run("--bg", "--name", name, text, cwd=chat_dir())
    m = re.search(r"claude attach ([0-9a-f]{8})", out)
    if not m:
        raise RuntimeError(f"claude --bg printed no session id: {out.strip()[:200]}")
    store.log(f"chats: new session {m.group(1)} {name!r}")
    return m.group(1)


def rename(short, name):
    """Renames session `short` with Claude Code's own /rename (what `claude agents` and --resume
    show), typed into it through `claude attach`. Returns once `claude agents` shows the new name."""
    name = " ".join(str(name).split())
    if not name:
        raise ValueError("the new name is empty")
    s = find(short)
    if s.get("kind") == "interactive":
        raise ValueError(f"{s.get('name') or short} runs in a terminal on the laptop: /rename it there")
    was_stopped = not running(s)
    # Typed through `claude attach`: a running session takes no slash prompt via --resume, and a
    # stopped one resumed with `--bg --resume ID /rename` renames its transcript but not its job.
    _type(s, f"/rename {name}", lambda: find(s["id"]).get("name") == name)
    if was_stopped:
        run("stop", s["id"])  # attach woke it; leave it as it was
    store.log(f"chats: renamed {s['id']} to {name!r}")


def delete(short):
    """`claude rm`: the session and its worktree are deleted (Claude Code refuses when the
    worktree holds unpushed work, and says so)."""
    s = find(short)
    if s.get("kind") == "interactive":
        raise ValueError("that session runs in a terminal on the laptop; close it there")
    run("rm", s["id"])
    store.log(f"chats: deleted {s['id']}")


def stop(short):
    s = find(short)
    if s.get("kind") == "interactive":
        raise ValueError("that session runs in a terminal on the laptop; stop it there")
    run("stop", s["id"])
