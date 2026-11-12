"""Speek server tests: standard library only. Run from the repo root:

    python -m unittest discover -s server/tests

Each test gets its own empty HOME, so nothing touches real config or data.
"""
import datetime as dt
import importlib
import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

SERVER = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SERVER))

CONFIG = {"name": "Spartacus", "port": 0, "chat_dir": "~", "mcp_feed_max": 100, "mcp_upload_max_mb": 1,
          "mcp_file_max_mb": 1, "mcp_wait_max_s": 1, "extensions": []}


class SpeekTest(unittest.TestCase):

    def setUp(self):
        self.home = tempfile.TemporaryDirectory()
        os.environ["HOME"] = self.home.name
        (Path(self.home.name) / ".config" / "speek").mkdir(parents=True)
        (Path(self.home.name) / ".config" / "speek" / "server.json").write_text(json.dumps(CONFIG))
        import store, threads, chats, asks, server  # noqa: E401
        self.store = importlib.reload(store)  # paths are computed from HOME at import
        self.threads = importlib.reload(threads)
        self.chats = importlib.reload(chats)
        self.asks = importlib.reload(asks)
        self.server = importlib.reload(server)

    def tearDown(self):
        self.home.cleanup()

    def call(self, name, **args):
        r = self.server.rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                             "params": {"name": name, "arguments": args}})
        return r["result"]


class Ids(SpeekTest):

    def test_ids_from_clients_cannot_leave_their_folder(self):
        self.assertEqual(self.store.checked("20261113-035132-1db510"), "20261113-035132-1db510")
        for bad in ("../../etc/passwd", "a/b", "", "x" * 65, None):
            with self.assertRaises(ValueError):
                self.store.checked(bad)

    def test_find_and_answer_refuse_bad_ids(self):
        with self.assertRaises(ValueError):
            self.store.find("../outbox/x")
        with self.assertRaises(ValueError):
            self.asks.answer("../x", "allow")


class Sending(SpeekTest):

    def test_send_then_find(self):
        mid = self.store.send("message", "Build finished")
        m = self.store.find(mid)
        self.assertEqual((m["kind"], m["text"], m["importance"]), ("message", "Build finished", "unrated"))

    def test_outbox_is_oldest_first(self):
        ids = [self.store.send("message", f"m{i}") for i in range(3)]
        self.assertEqual([p.stem for p in self.store.outbox()], sorted(ids))

    def test_send_refuses_bad_messages(self):
        later = (dt.datetime.now() + dt.timedelta(hours=1)).strftime("%Y-%m-%d %H:%M")
        earlier = (dt.datetime.now() - dt.timedelta(hours=1)).strftime("%Y-%m-%d %H:%M")
        for kwargs in ({"text": "  "}, {"text": "x", "files": ["relative.txt"]},
                       {"text": "x", "ring_at": later}, {"text": "x", "importance": "loud"}):
            with self.assertRaises(ValueError):
                self.store.send("message", **kwargs)
        with self.assertRaises(ValueError):
            self.store.send("call", "wake up", ring_at=earlier)
        self.store.send("call", "wake up", ring_at=later)

    def test_a_call_answered_on_one_phone(self):
        mid = self.store.send("call", "wake up")
        self.store.call_answered(mid)
        self.assertIn(mid, self.store.answered_calls())
        with self.assertRaises(ValueError):
            self.store.call_answered(self.store.send("message", "not a call"))


class Threads(SpeekTest):

    def test_reply_without_a_listening_sender_stays_in_the_thread(self):
        mid = self.store.send("message", "Deploy?")
        said = self.threads.reply(mid, "yes", [])
        self.assertIn("thread", said)
        self.assertEqual([e["from"] for e in self.threads.load(mid)["entries"]], ["owner"])

    def test_the_senders_answer_joins_the_thread(self):
        mid = self.store.send("message", "Deploy?")
        self.threads.reply(mid, "yes", [])
        self.threads.answered(mid, "Deploying now")
        self.assertEqual(self.threads.summary(mid)["replies"], 2)

    def test_an_extension_can_take_the_reply(self):
        mid = self.store.send("message", "Deploy?")

        class Ext:
            @staticmethod
            def reply(m, text, msg):
                return f"ext got {text}"

        self.assertEqual(self.threads.reply(mid, "yes", [Ext]), "ext got yes")


class Transcripts(SpeekTest):

    def test_events_show_the_conversation_not_the_noise(self):
        p = Path(self.home.name) / "t.jsonl"
        rows = [
            {"type": "user", "timestamp": "2026-11-13T03:00:00Z", "message": {"content": "run the tests"}},
            {"type": "user", "timestamp": "2026-11-13T03:00:00Z", "message": {"content": "<system-reminder>x</system-reminder>"}},
            {"type": "assistant", "timestamp": "2026-11-13T03:00:01Z", "message": {"content": [
                {"type": "text", "text": "Running them."},
                {"type": "tool_use", "id": "t1", "name": "Bash", "input": {"command": "npm test"}}]}},
            {"type": "user", "timestamp": "2026-11-13T03:00:02Z", "message": {"content": [
                {"type": "tool_result", "tool_use_id": "t1", "content": "ok"}]}},
            {"type": "assistant", "isSidechain": True, "message": {"content": "subagent chatter"}},
        ]
        p.write_text("\n".join(json.dumps(r) for r in rows) + "\n{not json")
        evs = self.chats.events_in(p)
        self.assertEqual([e["type"] for e in evs], ["user", "text", "tool", "result"])
        self.assertEqual(evs[2]["summary"], "npm test")
        self.assertEqual([e["type"] for e in self.chats.events_in(p, after=evs[1]["seq"])], ["tool", "result"])


class Mcp(SpeekTest):

    def test_tools_list_has_the_core_tools(self):
        r = self.server.rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
        names = {t["name"] for t in r["result"]["tools"]}
        self.assertTrue({"chats", "chat_events", "send", "notifications", "wait", "file"} <= names)

    def test_capabilities_name_the_server(self):
        self.assertEqual(json.loads(self.call("capabilities")["content"][0]["text"])["name"], "Spartacus")

    def test_unknown_tool_is_an_error(self):
        r = self.server.rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "nope"}})
        self.assertEqual(r["error"]["code"], -32602)

    def test_send_reaches_notifications_and_file_only_serves_named_files(self):
        f = Path(self.home.name) / "report.txt"
        f.write_text("hi")
        self.call("send", text="Report ready", files=[str(f)])
        rows = json.loads(self.call("notifications", limit=5)["content"][0]["text"])
        self.assertEqual((rows[0]["text"], rows[0]["from"]), ("Report ready", "Spartacus"))
        self.assertFalse(self.call("file", path=str(f))["isError"])
        self.assertTrue(self.call("file", path="/etc/hostname")["isError"])


class Extensions(SpeekTest):

    def test_the_example_extension_adds_the_status_sheet(self):
        cfg = dict(CONFIG, extensions=[str(SERVER / "examples" / "status_ext.py")])
        (Path(self.home.name) / ".config" / "speek" / "server.json").write_text(json.dumps(cfg))
        self.server.EXTENSIONS.clear()
        self.server.load_extensions()
        r = self.server.rpc({"jsonrpc": "2.0", "id": 1, "method": "tools/list"})
        self.assertIn("dashboard", {t["name"] for t in r["result"]["tools"]})
        d = json.loads(self.call("dashboard")["content"][0]["text"])
        self.assertEqual(set(d), {"needs_you", "now", "agents", "tasks", "tasks_hours", "workspaces"})
        rows = json.loads(self.call("agent_set", id="docs.review", enabled=True)["content"][0]["text"])
        self.assertTrue(next(a for a in rows if a["id"] == "docs.review")["enabled"])
        self.assertTrue(json.loads(self.call("capabilities")["content"][0]["text"])["status_sheet"])
        self.server.EXTENSIONS.clear()


    def test_the_anonymous_example_keeps_its_conversations(self):
        cfg = dict(CONFIG, extensions=[str(SERVER / "examples" / "anon_ext.py")])
        (Path(self.home.name) / ".config" / "speek" / "server.json").write_text(json.dumps(cfg))
        self.server.EXTENSIONS.clear()
        self.server.load_extensions()
        ext = self.server.EXTENSIONS[0]
        started = []
        ext.run = lambda n, row, text, first: started.append((n, text, first))  # no real claude in tests
        n = json.loads(self.call("anon_new", label="research", text="look into X")["content"][0]["text"])["n"]
        self.assertEqual(started, [(n, "look into X", True)])
        row = ext.index()[n]
        p = ext.transcript(row)
        p.parent.mkdir(parents=True)
        p.write_text(json.dumps({"type": "assistant", "timestamp": "2026-11-13T03:00:00Z",
                                 "message": {"content": [{"type": "text", "text": "On it."}]}}) + "\n")
        ev = json.loads(self.call("anon_events", n=n, after=0, timeout=0, limit=10)["content"][0]["text"])
        self.assertEqual([e["text"] for e in ev["events"]], ["On it."])
        self.call("anon_rename", n=n, label="X research")
        rows = json.loads(self.call("anon_chats")["content"][0]["text"])["sessions"]
        self.assertEqual((rows[0]["label"], rows[0]["last"]), ("X research", "On it."))
        self.call("anon_delete", n=n)
        self.assertEqual(json.loads(self.call("anon_chats")["content"][0]["text"])["sessions"], [])
        self.server.EXTENSIONS.clear()


if __name__ == "__main__":
    unittest.main()
