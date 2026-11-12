# Speek

Human-in-the-loop, remotely. Your agents work around the clock; you are the one external
dependency they need to reach. Speek puts you in the loop from your phone: talk to your Claude
Code sessions, approve what they want to do, answer their messages, and get rung like an alarm
when something truly needs you.

This repo has both halves:

- **`app/`**: the Android app (Kotlin, Jetpack Compose, no third-party libraries).
- **`server/`**: Speek's server, a small MCP server (Python 3.12, standard library only) that
  runs on your machine next to Claude Code. The phone talks to it, and so does your Claude: it
  can message you, call you, or send you a file, and your reply goes back into the session that
  asked.

The protocol between them is in [PROTOCOL.md](PROTOCOL.md).

## What you get

- **Chats** with your Claude Code sessions (what `claude agents` lists): read them as a chat,
  send messages and files, start new ones, rename, stop or delete them.
- **Permission prompts** from those sessions as Allow / Deny / Always cards on the phone. The
  prompt on your machine keeps working; whichever answers first wins.
- **Notifications** with threads: everything sent to you is in the bell list, and your reply
  goes back to whatever sent it.
- **Calls**: a message of kind `call` rings the phone like an alarm, over the lock screen, until
  you stop it. A call set for a time rings even if the phone is offline by then.
- **Voice**: speak instead of typing and have answers read aloud, with the phone's own speech
  recognition and text to speech (each shows only if the phone supports it).
- **App updates**: a message of kind `app` with an APK attached shows as "New Speek build";
  tapping it opens Android's installer (you confirm the install there).

## Requirements

- A Linux or macOS machine that stays on, with [Claude Code](https://claude.com/claude-code)
  installed and signed in (built against Claude Code 2.x). Chats use its background sessions (`claude --bg`, `claude agents`,
  `claude attach`) and read its session transcripts under `~/.claude/projects`.
- Python 3.12.
- A way to reach the machine over HTTPS from the phone: any tunnel or reverse proxy to
  `127.0.0.1:<port>`, for example a Cloudflare Tunnel or Tailscale.
- An Android phone, Android 10 or newer.

## Set it up with Claude

There is no download: you build the app yourself, signed with your own key. The easiest way is to
let Claude Code do it. Clone this repo, open Claude Code in it, and paste:

> Set up Speek from this repo for me, following README.md. Install what is missing to build the
> Android app (a JDK 17+ and the Android SDK), create a release keystore for me and point
> `~/.gradle/gradle.properties` at it, and build the release APK. Set up the server as a user
> service, add the permission hook, and add the server to Claude Code as an MCP server. Ask me
> how I want to reach this machine over HTTPS (for example a Cloudflare Tunnel or Tailscale)
> before setting that up. At the end, tell me where the APK is and the endpoint and token to
> enter in the app.

The steps it follows are below, if you would rather do them yourself.

## Set up the server

1. Config lives outside the code:

   ```sh
   mkdir -p ~/.config/speek
   cp server/config.example/server.json ~/.config/speek/
   python3 server/server.py token        # writes ~/.config/speek/server.env (MCP_TOKEN)
   ```

   In `server.json`, set `chat_dir` (where chats started from the phone run) and `port`.
2. Run it with `python3 server/server.py serve`, or as a user service: copy
   `server/deploy/speek-server.service` to `~/.config/systemd/user/`, set its
   `WorkingDirectory`, then `systemctl --user enable --now speek-server`. Publish
   `127.0.0.1:<port>` over HTTPS and check that `https://<your host>/health` answers `ok`.
3. Permission prompts on the phone: add `python3 /ABSOLUTE/PATH/TO/server/asks.py` as a
   `PermissionRequest` hook (timeout 3600) in `~/.claude/settings.json`.
4. Let your Claude reach you. Either add the server to Claude Code as an MCP server
   (`https://<your host>/mcp`, header `Authorization: Bearer <MCP_TOKEN>`) so it can use `send`,
   or tell it about `python3 /ABSOLUTE/PATH/TO/server/send.py "text"` (`--kind call` rings you,
   `--file PATH` attaches a file). Data lives in `~/.local/share/speek`.

## Install the app

Build a signed APK (below), install it, and enter `https://<your host>/mcp` and the `MCP_TOKEN` from
`~/.config/speek/server.env`. For calls, turn on "Listen for calls" in Settings and allow what
it lists (notifications, full screen over the lock screen, no battery limits); the test call
checks it all.

## Security

- **The token is full access to your machine.** Whoever has it can start Claude Code sessions in
  `chat_dir`, send them anything, answer their permission prompts (including "always allow"),
  and read the files messages name. Treat it like an SSH key: keep it on your phone only, and
  make a new one (delete `server.env`, run `server.py token`) if a phone is lost.
- The server listens on `127.0.0.1` only; reach it over HTTPS. The app refuses `http://`.
- The `file` tool only serves files that a message names. That keeps the phone to what was sent
  to it; it is not a boundary against someone holding the token.
- IDs from clients are checked before they are used in file names.
- A token holder can also send you an APK (kind `app`). Android installs it only if you confirm,
  and only over an app signed with the same key.

## Build the app

```sh
./gradlew assembleRelease
```

Needs a full JDK 17 or newer and the Android SDK (`local.properties` or `ANDROID_HOME`). To sign
release builds, name your keystore in `~/.gradle/gradle.properties`:

```properties
speek.storeFile=/path/to/your.keystore
speek.storePassword=...
speek.keyAlias=...
speek.keyPassword=...
```

Without these, `assembleRelease` builds an unsigned APK. Keep using the same key: Android only
updates an app signed with the key it was installed with. CI (`.github/workflows/ci.yml`) builds
and lints the app and starts the server on every push.

## Extending the server

`server.json` `extensions` lists Python files that add MCP tools and hooks without changing
`server/`; the hooks an extension can define are listed at the top of `server/server.py`. The
app has two screens that only extensions light up:

- a **status sheet** of your own agents or jobs with on/off switches, when the server offers
  `dashboard`, `agents`, `agent_set` and `workspace_set`;
- an **Anonymous tab** of background conversations the server runs itself, when it offers
  `anon_chats` (and the other `anon_*` tools).

Their exact arguments and JSON are in [PROTOCOL.md](PROTOCOL.md) ("Optional tools").
Two small working extensions show how: `server/examples/status_ext.py` lights up the status sheet
(the chart icon), and `server/examples/anon_ext.py` the Anonymous tab, as headless `claude -p`
conversations. List one in `extensions` and restart the server.

## License

[Apache License 2.0](LICENSE).
