package com.latencot.speek.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.latencot.speek.calls.Call
import com.latencot.speek.calls.Calls
import com.latencot.speek.calls.ListenService
import com.latencot.speek.data.Chat
import com.latencot.speek.data.Chats
import com.latencot.speek.data.Files
import com.latencot.speek.data.Media
import com.latencot.speek.data.Agent
import com.latencot.speek.data.AnonSession
import com.latencot.speek.data.Ask
import com.latencot.speek.data.Line
import com.latencot.speek.data.Markdown
import com.latencot.speek.data.LiveEvent
import com.latencot.speek.data.Dashboard
import com.latencot.speek.data.LiveParse
import com.latencot.speek.data.LiveSession
import com.latencot.speek.data.Parse
import com.latencot.speek.data.Store
import com.latencot.speek.mcp.McpClient
import com.latencot.speek.notes.NotificationsApi
import com.latencot.speek.voice.Listener
import com.latencot.speek.voice.Speaker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Base64
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

data class UiState(
    val connected: Boolean,
    /** The server's Claude Code sessions, and those waiting on your OK. */
    val live: List<LiveSession> = emptyList(),
    val waitingOn: Set<String> = emptySet(),
    /** Background sessions the server runs (an extension): the anonymous chats. */
    val anon: List<AnonSession> = emptyList(),
    /** The chat list's tab: false the Claude Code sessions, true the anonymous chats. */
    val anonTab: Boolean = false,
    val liveSeen: Map<String, String> = emptyMap(),
    /** The open session's transcript events (oldest first), its permission requests, its status. */
    val liveEvents: List<LiveEvent> = emptyList(),
    val liveAsks: List<Ask> = emptyList(),
    val liveStatus: String = "idle",
    /** Every notification from the feed, oldest first. */
    val lines: List<Line> = emptyList(),
    val read: Set<String> = emptySet(),
    /** The open chat: "cc:ID" (a Claude Code session) or "fp:N" (an anonymous chat); null shows the chat list. */
    val open: String? = null,
    /** The status sheet is open. */
    val statusOpen: Boolean = false,
    /** The bell (notifications list) is open. */
    val bell: Boolean = false,
    /** The open notification's id (its thread); [noteFocus]: opened to reply (from a call). */
    val note: String? = null,
    val noteFocus: Boolean = false,
    /** Its data; null while loading. */
    val dashboard: Dashboard? = null,
    /** The server's scheduled agents with their switches (loaded with the status sheet). */
    val agents: List<Agent> = emptyList(),
    val draft: String = "",
    /** Files picked for the next message. */
    val pending: List<Files.Pending> = emptyList(),
    /** Agent files on the phone (fetched), and being fetched now, by their remote path. */
    val fetched: Set<String> = emptySet(),
    val fetching: Set<String> = emptySet(),
    val sending: Boolean = false,
    val notice: String? = null,
    val sendWhenDone: Boolean,
    val readAloud: Boolean,
    val listen: Boolean,
    val endpoint: String,
    /** The server's tool names. The status sheet (`dashboard`) and the Anonymous tab
     * (`anon_chats`) show only on a server that has them; empty until first loaded. */
    val tools: Set<String> = emptySet(),
) {
    val hasStatus: Boolean get() = "dashboard" in tools
    val hasAnon: Boolean get() = "anon_chats" in tools

    val chats: List<Chat> get() = Chats.of(live, liveSeen, waitingOn)
    val anonChats: List<Chat> get() = Chats.anon(anon, liveSeen)
    /** Notifications not seen yet: the agent's messages outside any session chat. */
    val bellUnread: Int get() = lines.count { !it.fromMe && it.session == null && it.thread == null && it.key !in read }

    val openLive: LiveSession? get() = live.firstOrNull { it.ref == open }
    val openId: String? get() = open?.takeIf { it.startsWith("cc:") }?.removePrefix("cc:")
    val openN: Int? get() = open?.takeIf { it.startsWith(AnonSession.PREFIX) }?.removePrefix(AnonSession.PREFIX)?.toIntOrNull()
    val openAnon: AnonSession? get() = anon.firstOrNull { it.ref == open }
}

class SpeekViewModel(app: Application) : AndroidViewModel(app) {

    private val store = Store(app)
    val files = Files(app)
    private var client: McpClient? = store.connection?.let { McpClient(it.endpoint, it.token) }
    private val polling = Mutex()

    /** The server's name was read since the app started (see [Store.loadServerName]). */
    private var named = false

    /** Keys of the agent messages already shown; null until the first feed read. */
    private var seen: MutableSet<String>? = null

    private val _ui = MutableStateFlow(
        UiState(
            connected = client != null,
            read = store.read.orEmpty(),
            liveSeen = store.liveSeen,
            sendWhenDone = store.sendWhenDone,
            readAloud = store.readAloud,
            listen = store.listen,
            endpoint = store.connection?.endpoint.orEmpty(),
        ),
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val speaker = Speaker(app)
    val listener = Listener(app, onFinal = ::heard, onError = ::tell)

    fun setDraft(text: String) = _ui.update { it.copy(draft = text) }

    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    fun listen() {
        speaker.hush()
        listener.start()
    }

    private fun heard(text: String) {
        val draft = listOf(_ui.value.draft.trim(), text).filter { it.isNotEmpty() }.joinToString(" ")
        _ui.update { it.copy(draft = draft) }
        if (store.sendWhenDone) send()
    }

    private var follow: Job? = null

    /** Opens a chat; a live chat follows its session's events while it is open. */
    fun open(ref: String) {
        listener.cancel()
        follow?.cancel()
        _ui.update { it.copy(open = ref, draft = "", pending = emptyList(), liveEvents = emptyList(), liveAsks = emptyList(), liveStatus = "idle") }
        _ui.value.openId?.let { id -> follow = viewModelScope.launch { follow(ref, "chat_events", { put("id", id) }, LiveParse::events) } }
        _ui.value.openN?.let { n -> follow = viewModelScope.launch { follow(ref, "anon_events", { put("n", n) }, LiveParse::anonEvents) } }
    }

    /** The server's notification tools, while connected. */
    fun notes(): NotificationsApi? = client?.let { NotificationsApi(it) }

    /** The bell: every notification; opening it marks them seen. */
    fun openBell() {
        val s = _ui.value
        val read = s.read + s.lines.filter { !it.fromMe && it.session == null }.map { it.key }
        store.read = read
        _ui.update { it.copy(bell = true, note = null, open = null, read = read) }
    }

    fun openNote(id: String, focus: Boolean = false) = _ui.update { it.copy(note = id, noteFocus = focus, open = null) }

    /** Back from a notification: to the bell list it was opened from (or home). */
    fun closeNote() = _ui.update { it.copy(note = null, noteFocus = false) }

    fun closeBell() = _ui.update { it.copy(bell = false, note = null) }

    fun close() {
        listener.cancel()
        follow?.cancel()
        follow = null
        _ui.update { it.copy(open = null, draft = "", pending = emptyList(), liveEvents = emptyList(), liveAsks = emptyList()) }
        loadLive()
    }

    /**
     * Long-polls the open chat's transcript ([tool]: chat_events or anon_events) while [ref] is
     * open; new replies are read aloud.
     */
    private suspend fun follow(
        ref: String,
        tool: String,
        which: JSONObject.() -> Unit,
        parse: (String) -> Triple<String, List<LiveEvent>, List<Ask>>,
    ) {
        val c = client ?: return
        var after = 0
        var first = true
        // A Claude Code session is marked seen by its short id, an anonymous chat by its ref.
        val seenKey = ref.removePrefix("cc:")
        while (viewModelScope.isActive && _ui.value.open == ref) {
            try {
                val args = JSONObject().apply(which).put("after", after).put("timeout", if (first) 0 else 25).put("limit", 600)
                val (status, evs, asks) = parse(c.call(tool, args))
                if (_ui.value.open != ref) return
                if (!first && store.readAloud) evs.filter { it.type == "text" }.forEach { speaker.say(Markdown.plain(it.text)) }
                after = evs.lastOrNull()?.seq ?: after
                val seen = _ui.value.liveSeen + (seenKey to now())
                store.liveSeen = seen
                _ui.update { it.copy(liveEvents = it.liveEvents + evs, liveAsks = asks, liveStatus = status, liveSeen = seen) }
                first = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                tell("Lost the session: ${e.message}")
                delay(3_000)
            }
        }
    }

    /** Starts a Claude Code session on the server's machine with your first message. */
    fun newChat(name: String, text: String) {
        if (text.isBlank()) return tell("Say what the new chat is about.")
        tool("chat_new", JSONObject().put("name", name.trim()).put("text", text.trim())) { json ->
            val id = LiveParse.created(json)
            loadLive()
            open("cc:$id")
        }
    }

    /** Starts an anonymous chat: a background session the server runs. */
    fun newAnon(label: String, text: String) {
        if (text.isBlank()) return tell("Say what the new chat is about.")
        tool("anon_new", JSONObject().put("label", label.trim()).put("text", text.trim())) { json ->
            val n = LiveParse.anonCreated(json)
            loadLive()
            open("${AnonSession.PREFIX}$n")
        }
    }

    /** Both chat lists: the Claude Code sessions and the anonymous chats. */
    fun loadLive() {
        tool("chats") { json ->
            val (rows, waiting) = LiveParse.chats(json)
            _ui.update { it.copy(live = rows, waitingOn = waiting) }
        }
        if (_ui.value.tools.isEmpty()) {
            val c = client ?: return
            viewModelScope.launch {
                runCatching { c.toolNames() }.onSuccess { names ->
                    _ui.update { it.copy(tools = names, anonTab = it.anonTab && "anon_chats" in names) }
                    if ("anon_chats" in names) loadLive()
                }
            }
            return
        }
        if (_ui.value.hasAnon) tool("anon_chats") { json -> _ui.update { it.copy(anon = LiveParse.anon(json)) } }
    }

    fun showAnon(on: Boolean) = _ui.update { it.copy(anonTab = on) }

    /** Renames a chat: a Claude Code session (its /rename) or an anonymous chat (its label). */
    fun rename(ref: String, name: String) {
        if (name.isBlank()) return tell("The new name is empty.")
        val (tool, args) = when {
            ref.startsWith("cc:") -> "chat_rename" to JSONObject().put("id", ref.removePrefix("cc:")).put("name", name.trim())
            ref.startsWith(AnonSession.PREFIX) -> "anon_rename" to JSONObject().put("n", ref.removePrefix(AnonSession.PREFIX).toInt()).put("label", name.trim())
            else -> return
        }
        tell("Renaming...")
        tool(tool, args) {
            tell("Renamed.")
            loadLive()
        }
    }

    /** Deletes a chat on the server's machine: a Claude Code session or an anonymous chat. */
    fun delete(ref: String) {
        val (tool, args) = when {
            ref.startsWith("cc:") -> "chat_delete" to JSONObject().put("id", ref.removePrefix("cc:"))
            ref.startsWith(AnonSession.PREFIX) -> "anon_delete" to JSONObject().put("n", ref.removePrefix(AnonSession.PREFIX).toInt())
            else -> return
        }
        tool(tool, args) {
            if (_ui.value.open == ref) close()
            _ui.update { s -> s.copy(live = s.live.filter { it.ref != ref }, anon = s.anon.filter { it.ref != ref }) }
            tell("Deleted.")
            loadLive()
        }
    }

    /** Your answer to a permission request: allow, deny or always. */
    fun approve(rid: String, decision: String) = tool("chat_answer", JSONObject().put("rid", rid).put("decision", decision)) {}

    /** Stops the open session (claude stop); your next message resumes it. */
    fun interrupt() {
        val id = _ui.value.openId ?: return
        tool("chat_stop", JSONObject().put("id", id)) { loadLive() }
    }

    /** Adds picked files (gallery, documents, camera) to the next message. */
    fun attach(uris: List<Uri>) {
        try {
            val picked = uris.map(files::pending)
            _ui.update { it.copy(pending = (it.pending + picked).distinctBy { p -> p.uri }) }
        } catch (e: Exception) {
            tell("Could not attach: ${e.message}")
        }
    }

    fun unattach(p: Files.Pending) = _ui.update { it.copy(pending = it.pending - p) }

    /** Sends the draft and the picked files to the open chat's session. */
    fun send() {
        val text = _ui.value.draft.trim()
        val pending = _ui.value.pending
        val ref = _ui.value.open
        val c = client ?: return
        if ((text.isEmpty() && pending.isEmpty()) || _ui.value.sending || ref == null) return
        val id = _ui.value.openId
        val n = _ui.value.openN
        if (id == null && n == null) return
        val known = pending.sumOf { maxOf(it.size, 0) }
        if (known > UPLOAD_MAX) return tell("Files over ${UPLOAD_MAX / MB} MB together; the server takes at most that.")
        _ui.update { it.copy(draft = "", pending = emptyList(), sending = true) }
        viewModelScope.launch {
            try {
                val tool = if (id != null) "chat_send" else "anon_send"
                if (pending.isNotEmpty() && !c.accepts(tool, "files")) {
                    throw IllegalStateException("this server's $tool tool takes no files")
                }
                val keep = UUID.randomUUID().toString()
                val kept = withContext(Dispatchers.IO) { pending.map { files.keep(it, keep) } }
                if (kept.sumOf { it.second.size.toLong() } > UPLOAD_MAX) {
                    throw IllegalStateException("files over ${UPLOAD_MAX / MB} MB together")
                }
                val payload = JSONArray()
                kept.forEach { (f, bytes) ->
                    payload.put(JSONObject().put("name", f.name).put("data", Base64.getEncoder().encodeToString(bytes)))
                }
                val args = JSONObject().put("text", text)
                if (payload.length() > 0) args.put("files", payload)
                // It lands in the session's transcript; the open chat shows it from there.
                c.call(tool, if (id != null) args.put("id", id) else args.put("n", n))
                _ui.update { it.copy(sending = false) }
            } catch (e: Exception) {
                _ui.update { it.copy(draft = text, pending = pending, sending = false) }
                tell("Not sent: ${e.message}")
            }
        }
    }

    /** Where an agent file is on the phone, once fetched. */
    fun local(remote: String): File = files.local(remote)

    /** Fetches an agent's file with the `file` tool (once; it stays cached). */
    fun fetch(remote: String) {
        val c = client ?: return
        val s = _ui.value
        if (remote in s.fetching || remote in s.fetched) return
        if (files.local(remote).exists()) return _ui.update { it.copy(fetched = it.fetched + remote) }
        _ui.update { it.copy(fetching = it.fetching + remote) }
        viewModelScope.launch {
            try {
                val bytes = c.resource("file", JSONObject().put("path", remote))
                withContext(Dispatchers.IO) { files.save(remote, bytes) }
                _ui.update { it.copy(fetched = it.fetched + remote, fetching = it.fetching - remote) }
            } catch (e: Exception) {
                _ui.update { it.copy(fetching = it.fetching - remote) }
                tell("Could not fetch ${Media.name(remote)}: ${e.message}")
            }
        }
    }

    /** Opens one attachment: yours or a fetched one from its local copy, a URL in the browser. */
    fun openFile(line: Line, path: String) {
        when {
            line.fromMe || Media.isUrl(path) -> files.open(path)?.let(::tell)
            files.local(path).exists() -> files.open(files.local(path).path)?.let(::tell)
            else -> fetch(path)
        }
    }

    /** One feed read. New agent messages are read aloud, except on the first read. */
    fun refresh() {
        val c = client ?: return
        if (!polling.tryLock()) return
        viewModelScope.launch {
            try {
                if (!named) {
                    store.loadServerName(c)
                    named = true
                }
                // JSON: every line has its id, so a call rung from here opens its notification.
                val feed = Parse.feedJson(c.call("feed", JSONObject().put("limit", FEED).put("format", "json")))
                val known = seen
                if (known == null) {
                    Calls.catchUp(getApplication(), feed)
                } else {
                    // Calls ring here too when the background listener is off; each rings once.
                    feed.filter { it.key !in known && it.kind == "call" }.forEach { Calls.handle(getApplication(), it) }
                    // An open anonymous chat reads its own answers aloud (from its transcript).
                    val openN = _ui.value.openN?.toString()
                    if (store.readAloud) feed.filter { it.key !in known && it.kind != "call" && !(it.kind == "session" && it.session == openN) }
                        .forEach { speaker.say(Markdown.plain(it.summary)) }
                }
                seen = (known ?: mutableSetOf()).apply { addAll(feed.map { it.key }) }
                val keys = feed.map { it.key }.toSet()
                // First read ever: everything already sent counts as read. After that, keep only
                // keys still in the feed so the set stays small.
                val read = store.read?.intersect(keys) ?: keys
                store.read = read
                _ui.update { it.copy(lines = merge(feed), read = read) }
            } catch (e: Exception) {
                tell("Could not reach the agent: ${e.message}")
            } finally {
                polling.unlock()
            }
        }
    }

    fun loadStatus() {
        _ui.update { it.copy(statusOpen = true, dashboard = null) }
        loadDashboard()
    }

    /** Re-reads the status sheet while it is open (it follows what runs). */
    fun refreshStatus() {
        if (_ui.value.statusOpen) loadDashboard()
    }

    /** Flips every agent of a workspace at once (on starts them now, off stops them). */
    fun setWorkspace(name: String, on: Boolean) {
        _ui.update { s ->
            s.copy(
                dashboard = s.dashboard?.let { d -> d.copy(workspaces = d.workspaces.map { if (it.name == name) it.copy(enabled = on) else it }) },
                agents = s.agents.map { if (it.workspace == name) it.copy(enabled = on) else it },
            )
        }
        tool("workspace_set", JSONObject().put("name", name).put("enabled", on)) { json ->
            val d = LiveParse.dashboard(json)
            _ui.update { it.copy(dashboard = d, agents = d.agents) }
        }
    }

    private fun loadDashboard() = tool("dashboard") { json ->
        val d = LiveParse.dashboard(json)
        _ui.update { it.copy(dashboard = d, agents = d.agents) }
    }

    /** Flips a scheduled agent's switch on the server: on starts it now, off stops it. */
    fun setAgent(id: String, on: Boolean) {
        _ui.update { s -> s.copy(agents = s.agents.map { if (it.id == id) it.copy(enabled = on) else it }) }
        tool("agent_set", JSONObject().put("id", id).put("enabled", on)) { json ->
            _ui.update { it.copy(agents = LiveParse.agents(json)) }
            loadDashboard()
        }
    }

    fun closeStatus() = _ui.update { it.copy(statusOpen = false) }

    /** Checks the endpoint and token with the MCP handshake before keeping them. */
    fun connect(endpoint: String, token: String, done: () -> Unit) {
        val url = endpoint.trim()
        val key = token.trim()
        if (!url.startsWith("https://")) return tell("The endpoint must be https://")
        if (key.isEmpty()) return tell("The token is required.")
        val candidate = McpClient(url, key)
        viewModelScope.launch {
            try {
                val server = candidate.connect()
                store.connection = Store.Connection(url, key)
                client = candidate
                named = false
                seen = null
                _ui.update { it.copy(connected = true, endpoint = url, tools = emptySet()) }
                if (store.listen) ListenService.start(getApplication())
                tell("Connected to $server.")
                refresh()
                loadLive()
                done()
            } catch (e: Exception) {
                tell("Could not connect: ${e.message}")
            }
        }
    }

    fun setSendWhenDone(on: Boolean) {
        store.sendWhenDone = on
        _ui.update { it.copy(sendWhenDone = on) }
    }

    fun setListen(on: Boolean) {
        store.listen = on
        if (on && client != null) ListenService.start(getApplication()) else ListenService.stop(getApplication())
        _ui.update { it.copy(listen = on) }
    }

    /** Starts the background listener if it should run (Speek opened, connected, switch on). */
    fun ensureListening() {
        if (client != null && store.listen) ListenService.start(getApplication())
    }

    /** A call from Speek itself, in 10 s: time to lock the phone and see it ring. */
    fun testCall() {
        Calls.ringIn(getApplication(), Call("test-${UUID.randomUUID()}", "This is a test call from Speek.", null, null), 10_000)
        tell("Test call in 10 seconds. Lock the phone if you like.")
    }

    fun setReadAloud(on: Boolean) {
        store.readAloud = on
        if (!on) speaker.hush()
        _ui.update { it.copy(readAloud = on) }
    }

    private fun tool(name: String, args: JSONObject = JSONObject(), then: (String) -> Unit) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                then(c.call(name, args))
            } catch (e: Exception) {
                tell("$name failed: ${e.message}")
            }
        }
    }

    private fun tell(text: String) = _ui.update { it.copy(notice = text) }

    override fun onCleared() {
        listener.destroy()
        speaker.shutdown()
    }

    companion object {
        /** The server's feed cap: how much history is read. */
        const val FEED = 100
        private const val MB = 1L shl 20
        /** The server's upload cap. */
        const val UPLOAD_MAX = 25 * MB
        private val second = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        private fun now(): String = LocalDateTime.now().format(second)

        /** Oldest first; within one minute the arrival order is kept (stable sort). */
        private fun merge(lines: List<Line>): List<Line> = lines.distinctBy { it.key }.sortedBy { it.at }
    }
}
