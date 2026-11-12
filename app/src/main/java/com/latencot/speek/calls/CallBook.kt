package com.latencot.speek.calls

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One call: who (the session), why (the text), its message key, and its notification id. */
data class Call(val key: String, val text: String, val session: String?, val ringAt: String?, val id: String? = null)

/** What the call machinery keeps on the phone: the wait cursor, calls already handled, alarms set. */
class CallBook(context: Context) {

    private val prefs = context.getSharedPreferences("speek-calls", Context.MODE_PRIVATE)

    /** The last outbox id seen by `wait`; empty before the first one. */
    var cursor: String
        get() = prefs.getString("cursor", "")!!
        set(value) = prefs.edit().putString("cursor", value).apply()

    /** True the first time a call key is claimed: every call rings, schedules or is missed once. */
    @Synchronized
    fun claim(key: String): Boolean {
        val handled = list("handled")
        if (key in handled) return false
        prefs.edit().putString("handled", JSONArray((handled + key).takeLast(KEEP)).toString()).commit()
        return true
    }

    /** Alarms set, each a call and when it is due (epoch ms): the alarm manager forgets them on reboot. */
    val scheduled: List<Pair<Call, Long>>
        get() {
            val arr = JSONArray(prefs.getString("scheduled", "[]"))
            return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                Call(it.getString("key"), it.getString("text"), it.optString("session").ifEmpty { null },
                    it.optString("ringAt").ifEmpty { null }, it.optString("id").ifEmpty { null }) to it.getLong("due")
            }
        }

    @Synchronized
    fun schedule(call: Call, due: Long) = save(scheduled.filter { it.first.key != call.key } + (call to due))

    @Synchronized
    fun unschedule(key: String) = save(scheduled.filter { it.first.key != key })

    private fun save(calls: List<Pair<Call, Long>>) {
        val arr = JSONArray()
        calls.forEach { (c, due) ->
            arr.put(JSONObject().put("key", c.key).put("text", c.text).put("session", c.session ?: "")
                .put("ringAt", c.ringAt ?: "").put("id", c.id ?: "").put("due", due))
        }
        prefs.edit().putString("scheduled", arr.toString()).commit()
    }

    private fun list(name: String): List<String> =
        JSONArray(prefs.getString(name, "[]")).let { a -> (0 until a.length()).map(a::getString) }

    companion object {
        const val KEEP = 500
    }
}
