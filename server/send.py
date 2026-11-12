#!/usr/bin/env python3
"""Message the human through Speek. For Claude (in any session) and for scripts.

  send.py [--kind message|call|app] [--file PATH]... [--at "YYYY-MM-DD HH:MM"]
          [--importance urgent|normal|quiet] [--thread ID] TEXT...      (prints `id: ID`)

  --kind call     rings the phone like an alarm until they stop it (now, or --at a time)
  --kind app      a Speek build (with --file the .apk): tapping it installs
  --file          a local file (absolute path) or an http(s) URL, any number of times
  --thread ID     an answer inside the thread of message ID (after they replied to it)
  TEXT "-"        reads the text from stdin

Run from a Claude Code session, the message remembers that session (CLAUDE_CODE_SESSION_ID):
the human's reply to it is typed into that same session.
"""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import store  # noqa: E402
import threads  # noqa: E402

OPTS = ("--kind", "--file", "--at", "--importance", "--thread")


def main(argv):
    kind, files, ring_at, importance, thread = "message", [], None, "normal", None
    while argv[:1] and argv[0] in OPTS:
        if len(argv) < 2:
            sys.exit(__doc__)
        opt, val, argv = argv[0], argv[1], argv[2:]
        if opt == "--kind":
            kind = val
        elif opt == "--file":
            files.append(val if val.startswith(("http://", "https://")) else str(Path(val).expanduser().resolve()))
        elif opt == "--at":
            ring_at = val
        elif opt == "--importance":
            importance = val
        else:
            thread = val
    if kind not in ("message", "call", "app"):
        sys.exit("kind is message, call or app")
    text = sys.stdin.read() if argv == ["-"] else " ".join(argv)
    sid = os.environ.get("CLAUDE_CODE_SESSION_ID")
    origin = {"claude_session": sid, "agent": f"Claude Code session {sid[:8]}"} if sid else None
    try:
        mid = store.send("reply" if thread else kind, text, files, ring_at,
                         "urgent" if kind == "call" else importance, origin, thread)
    except ValueError as e:
        sys.exit(f"REFUSED: {e}")
    if thread:
        threads.answered(thread, text)
    print(f"id: {mid}")


if __name__ == "__main__":
    main(sys.argv[1:])
