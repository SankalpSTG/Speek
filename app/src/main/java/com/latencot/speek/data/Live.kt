package com.latencot.speek.data

import org.json.JSONArray
import org.json.JSONObject

/** One of the server's Claude Code sessions: what `claude agents` lists on its machine. */
data class LiveSession(
    /** The short id `claude attach` takes. */
    val id: String,
    val name: String,
    val cwd: String,
    /** background (Speek can talk to it) or interactive (a terminal on the server's machine: read only). */
    val kind: String,
    /** working, waiting (a permission prompt or a question), idle, or stopped. */
    val status: String,
    val updated: String,
    val last: String,
) {
    val ref: String get() = "cc:$id"
    val inTerminal: Boolean get() = kind == "interactive"
}

/**
 * A background session the server runs (an extension feature) that never shows in
 * `claude agents`. Speek lists them apart, as anonymous chats.
 */
data class AnonSession(
    val n: Int,
    val label: String,
    /** working (a turn running or queued), failed (the last turn failed), or idle. */
    val status: String,
    val updated: String,
    val last: String,
    /** The server's current background session. */
    val current: Boolean,
) {
    val ref: String get() = "${PREFIX}$n"
    val name: String get() = label.ifEmpty { "#$n" }

    companion object {
        const val PREFIX = "fp:"
    }
}

/** A permission request in one of the sessions (from the server's permission hook). */
data class Ask(
    val rid: String,
    val tool: String,
    val summary: String,
    val detail: String,
    /** pending, allowed, denied, answered on the server's machine, timed out, gone. */
    val status: String,
    val at: String,
)

/** One step of a session's transcript. */
data class LiveEvent(
    val seq: Int,
    val at: String,
    /** user, text, tool, result; note (an anonymous chat: something else that went into its turn). */
    val type: String,
    val text: String = "",
    val toolId: String = "",
    val name: String = "",
    val summary: String = "",
    val preview: String = "",
    val error: Boolean = false,
)

/** A scheduled agent the server runs, and its on/off switch. */
data class Agent(
    val id: String,
    /** The workspace it works in (the id's first part). */
    val workspace: String,
    /** Its own switch; it also needs its workspace on to run. */
    val enabled: Boolean,
    /** running, idle, failed, crashed, limited, never run. */
    val state: String,
    val summary: String,
    /** When it last started (ISO), or "". */
    val last: String,
    val next: String,
) {
    val detail: String get() = listOf(state, summary, next.takeIf { it.isNotEmpty() }?.let { "next $it" }).filterNotNull().filter { it.isNotEmpty() }.joinToString("; ")
}

/** The status sheet: the server's `dashboard` tool. */
data class Dashboard(
    val needsYou: List<Item>,
    val now: List<Item>,
    val agents: List<Agent>,
    val tasks: List<Task>,
    val tasksHours: Int,
    val workspaces: List<Space>,
) {
    data class Item(val title: String, val detail: String)
    data class Task(val name: String, val workspace: String, val state: String, val `when`: String, val task: String, val result: String)
    /** A workspace: its switch and its number tiles; its agents and tasks are matched by name. */
    data class Space(val name: String, val enabled: Boolean, val numbers: List<Group>)
    data class Tile(val label: String, val value: Int)
    data class Group(val heading: String, val tiles: List<Tile>)
}

object LiveParse {

    private fun agent(o: JSONObject) = Agent(o.getString("id"), o.getString("workspace"), o.getBoolean("enabled"), o.getString("state"),
        o.getString("summary"), o.getString("last"), o.getString("next"))

    private fun <T> JSONArray.objects(f: (JSONObject) -> T): List<T> = (0 until length()).map { f(getJSONObject(it)) }

    fun dashboard(json: String): Dashboard {
        val o = JSONObject(json)
        val item = { i: JSONObject -> Dashboard.Item(i.getString("title"), i.getString("detail")) }
        return Dashboard(
            needsYou = o.getJSONArray("needs_you").objects(item),
            now = o.getJSONArray("now").objects(item),
            agents = o.getJSONArray("agents").objects(::agent),
            tasks = o.getJSONArray("tasks").objects {
                Dashboard.Task(it.getString("name"), it.getString("workspace"), it.getString("state"), it.getString("when"),
                    it.getString("task"), it.getString("result"))
            },
            tasksHours = o.getInt("tasks_hours"),
            workspaces = o.getJSONArray("workspaces").objects { w ->
                Dashboard.Space(w.getString("name"), w.getBoolean("enabled"), w.getJSONArray("numbers").objects { g ->
                    Dashboard.Group(g.getString("heading"), g.getJSONArray("tiles").objects { Dashboard.Tile(it.getString("label"), it.getInt("value")) })
                })
            },
        )
    }

    /** agents / agent_set: every scheduled agent with its switch. */
    fun agents(json: String): List<Agent> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            agent(it)
        }
    }


    /** chats: the sessions, and the permission requests waiting for you (by session id). */
    fun chats(json: String): Pair<List<LiveSession>, Set<String>> {
        val o = JSONObject(json)
        val arr = o.getJSONArray("sessions")
        val asks = o.getJSONArray("asks")
        val waitingOn = (0 until asks.length()).map { asks.getJSONObject(it).getString("id") }.toSet()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            LiveSession(it.getString("id"), it.getString("name"), it.getString("cwd"), it.getString("kind"),
                it.getString("status"), it.getString("updated"), it.getString("last"))
        } to waitingOn
    }

    /** chat_events: the session's status, its new transcript events, and its permission requests. */
    fun events(json: String): Triple<String, List<LiveEvent>, List<Ask>> {
        val o = JSONObject(json)
        val evs = o.getJSONArray("events")
        val asks = o.getJSONArray("asks")
        return Triple(
            o.getString("status"),
            (0 until evs.length()).map { evs.getJSONObject(it) }.map { e ->
                LiveEvent(
                    seq = e.getInt("seq"), at = e.getString("at"), type = e.getString("type"),
                    text = e.optString("text"), toolId = e.optString("tool_id"), name = e.optString("name"),
                    summary = e.optString("summary"), preview = e.optString("preview"), error = e.optBoolean("error"),
                )
            },
            (0 until asks.length()).map { asks.getJSONObject(it) }.map {
                Ask(it.getString("rid"), it.getString("tool"), it.getString("summary"), it.getString("detail"),
                    it.getString("status"), it.getString("at"))
            },
        )
    }

    fun created(json: String): String = JSONObject(json).getString("id")

    /** anon_chats: the server's background sessions. */
    fun anon(json: String): List<AnonSession> {
        val arr = JSONObject(json).getJSONArray("sessions")
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            AnonSession(it.getInt("n"), it.getString("label"), it.getString("status"), it.getString("updated"),
                it.getString("last"), it.getBoolean("current"))
        }
    }

    fun anonCreated(json: String): Int = JSONObject(json).getInt("n")

    /** anon_events: like chat_events, without permission requests (background sessions never ask). */
    fun anonEvents(json: String): Triple<String, List<LiveEvent>, List<Ask>> =
        events(JSONObject(json).put("asks", JSONArray()).toString())

    private val attached = Regex("""^Attached (\w+(?: \w+)*): (.+)$""")

    /** Your message as you wrote it: "Attached photo: /long/path" lines become "📎 name". */
    fun shown(text: String): String = text.lines().joinToString("\n") { line ->
        attached.matchEntire(line)?.let { "📎 ${it.groupValues[2].substringAfterLast('/')}" } ?: line
    }

    /** The session a permission notification is about: "[ask RID SHORTID] ...". */
    private val askTag = Regex("""^\[ask ([0-9a-f]+) ([0-9a-f]{8})]\s*""")

    fun askSession(text: String): String? = askTag.find(text)?.groupValues?.get(2)

    fun askText(text: String): String = text.replace(askTag, "")
}
