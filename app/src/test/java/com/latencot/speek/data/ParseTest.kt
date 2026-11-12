package com.latencot.speek.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParseTest {

    private val feed = """
        {"cursor": "20261113-040629-78d37f", "messages": [
          {"id": "20261113-040629-78d37f", "kind": "reply", "text": "Done, see the log", "importance": "normal",
           "ring_at": null, "session": null, "thread": "20261113-035132-1db510", "files": ["/tmp/run.log"]},
          {"id": "20261113-035132-1db510", "kind": "message", "text": "[#3 build] Build finished", "importance": "quiet",
           "ring_at": null, "session": null, "thread": null, "files": []}
        ]}
    """.trimIndent()

    @Test
    fun feedJsonComesOldestFirstWithTimeFromTheId() {
        val lines = Parse.feedJson(feed)
        assertEquals(listOf("20261113-035132-1db510", "20261113-040629-78d37f"), lines.map { it.id })
        assertEquals("2026-11-13 03:51:32", lines[0].at)
    }

    @Test
    fun sessionTagBecomesTheSessionAndLeavesTheText() {
        val first = Parse.feedJson(feed)[0]
        assertEquals("3", first.session)
        assertEquals("Build finished", first.text)
    }

    @Test
    fun threadFilesAndImportanceAreKept() {
        val reply = Parse.feedJson(feed)[1]
        assertEquals("20261113-035132-1db510", reply.thread)
        assertEquals(listOf("/tmp/run.log"), reply.files)
        assertEquals("normal", reply.importance)
        assertNull(reply.ringAt)
    }

    @Test
    fun waitJsonCarriesCursorAndAnsweredCalls() {
        val w = Parse.waitJson("""{"cursor": "20261113-050000-aaaaaa", "messages": [
            {"id": "20261113-050000-aaaaaa", "kind": "call", "text": "Wake up", "importance": "urgent",
             "ring_at": "2026-11-13 07:00", "session": null, "thread": null, "files": []}],
            "answered": ["20261113-040000-bbbbbb"]}""")
        assertEquals("20261113-050000-aaaaaa", w.cursor)
        assertEquals("2026-11-13 07:00", w.lines.single().ringAt)
        assertEquals(listOf("20261113-040000-bbbbbb"), w.answered)
    }

    @Test
    fun waitTextGivesTheCursorAndTheMessages() {
        val (cursor, lines) = Parse.wait("cursor: 20261113-050000-aaaaaa\n\n[2026-11-13 05:00:00] call:\nWake up\nring: 2026-11-13 07:00")
        assertEquals("20261113-050000-aaaaaa", cursor)
        assertEquals("call", lines.single().kind)
        assertEquals("2026-11-13 07:00", lines.single().ringAt)
    }
}
