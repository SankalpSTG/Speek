# Speek

Android app: voice human-in-the-loop for agents over MCP. README.md says what it does,
PROTOCOL.md what it needs from a server. `server/` is Speek's own MCP server (Python stdlib):
store.py (config, data, outbox), chats.py (Claude Code sessions), asks.py (permission hook),
threads.py (reply threads), send.py (Claude -> human), server.py (MCP + extensions). Extensions
add private tools without touching it; never put anything private in `server/`.

- Native Kotlin + Compose, package `com.latencot.speek`, minSdk 29, target 37 (AGP 9.2.1,
  Kotlin 2.2.10). Needs a full JDK 17+; point Gradle at one in `~/.gradle/gradle.properties`.
- `mcp/McpClient.kt` MCP over Streamable HTTP (JSON or SSE answers). `data/Parse.kt` turns the
  server's `feed` / `wait` text into rows; if the server's text format changes, change Parse
  too. `data/Chats.kt` builds
  the chat list. `voice/` wraps SpeechRecognizer and TextToSpeech; each voice feature shows only
  when the phone has it (`Listener.available`, `Speaker.available`). `data/Files.kt` keeps
  copies of sent files (`filesDir/sent`), caches fetched ones (`cacheDir/received`), camera
  shots, and opens files through the FileProvider `${applicationId}.files`.
  `ui/SpeekViewModel.kt` holds all state and calls; screens are dumb.
- `calls/`: `ListenService` (specialUse FGS, `wait` long poll), `Calls` (ring now / at a time /
  missed; every ring goes through `AlarmManager.setAlarmClock`, the reliable background-start
  exemption), `RingService` (mediaPlayback FGS: `res/raw/call_melody.wav` looped at alarm
  volume, volume keys via a remote MediaSession, power via SCREEN_OFF), `CallActivity`
  (over the lock screen), boot/alarm receivers. `CallBook` dedupes call keys and keeps alarms.
- Build: `./gradlew assembleRelease` (signing: README). CI (.github/workflows/ci.yml) builds and
  lints the app, runs the app's unit tests (app/src/test) and the server's (server/tests,
  unittest), checks PROTOCOL.md against `server.py protocol`, and starts the server once.
- Styling: no margins, symmetric padding only; insets are sized spacers
  (`windowInsetsTopHeight` / `windowInsetsBottomHeight`).
- Nothing personal or secret in this repo. Personal notes go in CLAUDE.local.md (not published).

