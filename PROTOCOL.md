# The Speek protocol

Speek talks to one server over [MCP](https://modelcontextprotocol.io) (Streamable HTTP, JSON
responses, stateless) with a bearer token: `POST <endpoint>` with
`Authorization: Bearer <token>`, one JSON-RPC message per request. `server/` in this repo
implements it; a Claude reaches the human through the same endpoint (`send`).

## What the app relies on

- **Chats.** `chats` lists the human's Claude Code sessions with any permission requests
  waiting; `chat_events` streams one as events (text, tool calls, results, permission
  requests); `chat_send`, `chat_new`, `chat_answer`, `chat_stop`, `chat_rename`, `chat_delete`
  act on them.
- **Messages.** Everything sent to the human is a message with an `id` (newer ids sort later),
  `kind` (`message`, `reply`, `call`, `ask`, `app`, or one an extension adds), `text`,
  `importance` (`urgent`, `normal`, `quiet`, `unrated`), optional `ring_at` (a call's alarm
  time), `files` (paths the `file` tool serves) and `thread`. A Claude sends one with `send`.
- **Notifications and threads.** `notifications` lists them; `notification` opens one with its
  thread (entries `{at, from: owner|run, text}`: `owner` is the human, `run` is whatever sent the
  message); `notification_reply` answers it and the
  answer goes back to whatever sent it (a Claude Code session gets it typed in);
  `notification_button` presses a button (`perm:RID:allow|deny|always` answers a permission
  request).
- **Waiting.** `wait {cursor, timeout, format: "json"}` is a long poll: it returns as soon as a
  message newer than `cursor` exists (or a call was answered on another phone), else after
  `timeout` seconds, as `{"cursor", "messages", "answered"}`. A `call` rings the phone like an
  alarm; `call_answered {id}` stops it on the human's other phones.
- **Files.** Uploads go inline as base64 `files: [{name, data}]`; `file {path}` returns one file
  a message names, as an embedded resource blob.
- **Optional screens.** The app shows a status sheet only when the server has a `dashboard`
  tool and an Anonymous tab only when it has `anon_chats` (a server extension can add them).

## Optional tools (server extensions)

A server may add these through an extension (see `server/server.py` and the working example
`server/examples/status_ext.py`). The app looks at `tools/list` and shows each screen only when
its tools are there. Every answer is one text content item holding the JSON below.

**Status sheet** (shown when `dashboard` exists):

| Tool | Arguments | Answer |
|---|---|---|
| `dashboard` | - | `{needs_you: [Item], now: [Item], agents: [Agent], tasks: [Task], tasks_hours: int, workspaces: [Space]}` |
| `agents` | - | `[Agent]` |
| `agent_set` | `id`, `enabled` (bool) | `[Agent]` after the switch |
| `workspace_set` | `name`, `enabled` (bool) | the `dashboard` JSON after the switch |

- `Item`: `{title, detail}`: things that need the human (`needs_you`) or are happening (`now`).
- `Agent`: `{id, workspace, enabled, state, summary, last, next}`; `id` is `workspace.name`;
  `state` is one of `running`, `idle`, `failed`, `crashed`, `limited`, `never run`; `last` is
  ISO time or `""`; `next` is free text or `""`.
- `Task`: `{name, workspace, state, when, task, result}`: one-off jobs of the last `tasks_hours`.
- `Space`: `{name, enabled, numbers: [{heading, tiles: [{label, value: int}]}]}`: a workspace
  with its switch and number tiles; its agents and tasks are matched by `workspace`.

**Anonymous tab** (shown when `anon_chats` exists): background sessions the server runs itself
(for example `claude -p`), which never appear in `claude agents`.

| Tool | Arguments | Answer |
|---|---|---|
| `anon_chats` | - | `{sessions: [{n: int, label, status, updated, last, current: bool}]}` |
| `anon_new` | `label`, `text`, `files`? | `{n}` |
| `anon_send` | `n`, `text`, `files`? | any text |
| `anon_events` | `n`, `after`, `timeout`, `limit` | `{status, events}`: events as in `chat_events`, plus type `note` for anything else that went into the session |
| `anon_rename` | `n`, `label` | any text |
| `anon_delete` | `n` | any text |

A message of kind `session` whose text starts with `[#N label]` is the answer of anonymous
session `N`; the app shows it in that chat.

## Tool reference

Generated from `server/server.py protocol`; `?` marks an optional argument.

<!-- tools:begin -->
| Tool | Arguments | What it does |
|---|---|---|
| `capabilities` | - | What this server offers, as JSON: name, extensions, and whatever each extension reports. |
| `chats` | - | The human's real Claude Code sessions (what `claude agents` lists) and the permission requests waiting, as JSON {sessions, asks}. |
| `chat_events` | `id`, `after`, `limit`, `timeout` | Long poll session `id`: its transcript as events after `after` (seq), at most `limit`, waiting up to `timeout` s for new ones. JSON {status, waiting_for, events, asks}. |
| `chat_send` | `id`, `text`, `files`? | The human's message (and files) into session `id`. |
| `chat_new` | `name`, `text`, `files`? | Start a Claude Code session (claude --bg, in the configured chat folder) named `name`, with the human's first message. JSON {id}. |
| `chat_answer` | `rid`, `decision` | The human's answer to permission request `rid`: allow, deny or always. |
| `chat_stop` | `id` | Stop session `id` (claude stop; the conversation is kept). |
| `chat_rename` | `id`, `name` | Rename session `id` (as /rename does). |
| `chat_delete` | `id` | Delete session `id` (claude rm). |
| `send` | `text`, `kind`?, `files`?, `ring_at`?, `importance`?, `thread`? | For a Claude: message the human on their phone. kind message (default), call (rings like an alarm until stopped; ring_at 'YYYY-MM-DD HH:MM' for later) or app (a Speek build). files: absolute paths on this machine or URLs. thread: answer inside the thread of a message they replied to. Returns the message id; read their replies with `notification`. |
| `notifications` | `limit` | The human's notifications, newest first (answers inside a thread are left out), as JSON rows {id, created, kind, importance, text, files, ring_at, from, buttons, thread {replies, status, last}}. |
| `notification` | `id` | One notification and its thread as JSON: notification, entries [{at, from: owner\|run, text}], status. |
| `notification_reply` | `id`, `text`, `files`? | The human's reply to notification `id` (files as in chat_send): it goes to whatever sent it. |
| `notification_button` | `data` | Press one of a notification's buttons by its data. |
| `feed` | `limit`, `format`? | The latest messages, newest first. format json: {cursor, messages}; otherwise text blocks `[YYYY-MM-DD HH:MM:SS] kind:` + text + importance / ring / file lines. |
| `wait` | `cursor`, `timeout`, `format`? | Long poll: returns when a message newer than `cursor` (a message id) exists or a call was answered on another phone, else after `timeout` s. format json: {cursor, messages, answered}. An empty cursor returns the newest id at once. |
| `call_answered` | `id` | One of the human's phones stopped ringing for call `id`: the other phones' wait returns it in `answered` and they stop. |
| `file` | `path` | One file a message names (a path from its `files`), as an embedded resource blob. Paths no message names are refused. |
<!-- tools:end -->
