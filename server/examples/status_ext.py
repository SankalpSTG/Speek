"""A minimal Speek server extension: the app's status sheet, for a few pretend agents.

Enable it by listing this file in ~/.config/speek/server.json:

    "extensions": ["/ABSOLUTE/PATH/TO/server/examples/status_ext.py"]

Restart the server and the app shows its status sheet (the chart icon), with an on/off switch
per agent. A real extension would report and switch real work (cron jobs, CI, agents); the
JSON shapes are in PROTOCOL.md, "Optional tools".
"""
import json

AGENTS = {"docs.review": False, "docs.publish": True}  # id (workspace.name) -> switched on

NONE = {"type": "object", "properties": {}}
TOOLS = [
    {"name": "dashboard", "description": "The status sheet.", "inputSchema": NONE},
    {"name": "agents", "description": "Every agent with its switch.", "inputSchema": NONE},
    {"name": "agent_set", "description": "Switch agent `id` on or off.", "inputSchema": {
        "type": "object", "required": ["id", "enabled"],
        "properties": {"id": {"type": "string"}, "enabled": {"type": "boolean"}}}},
    {"name": "workspace_set", "description": "Switch every agent of workspace `name`.", "inputSchema": {
        "type": "object", "required": ["name", "enabled"],
        "properties": {"name": {"type": "string"}, "enabled": {"type": "boolean"}}}},
]


def agents():
    return [{"id": aid, "workspace": aid.split(".", 1)[0], "enabled": on, "state": "idle",
             "summary": "waiting for work" if on else "switched off", "last": "", "next": ""}
            for aid, on in AGENTS.items()]


def dashboard():
    spaces = sorted({aid.split(".", 1)[0] for aid in AGENTS})
    return {"needs_you": [], "now": [], "agents": agents(), "tasks": [], "tasks_hours": 24,
            "workspaces": [{"name": ws, "enabled": any(on for a, on in AGENTS.items() if a.startswith(ws + ".")),
                            "numbers": [{"heading": "Agents", "tiles": [
                                {"label": "on", "value": sum(on for a, on in AGENTS.items() if a.startswith(ws + "."))}]}]}
                           for ws in spaces]}


def call(name, args):
    if name == "dashboard":
        return json.dumps(dashboard())
    if name == "agents":
        return json.dumps(agents())
    if name == "agent_set":
        if args["id"] not in AGENTS:
            raise ValueError(f"no agent {args['id']}")
        AGENTS[args["id"]] = bool(args["enabled"])
        return json.dumps(agents())
    if name == "workspace_set":
        for aid in AGENTS:
            if aid.startswith(args["name"] + "."):
                AGENTS[aid] = bool(args["enabled"])
        return json.dumps(dashboard())
    raise KeyError(name)


def capabilities():
    return {"status_sheet": True}
