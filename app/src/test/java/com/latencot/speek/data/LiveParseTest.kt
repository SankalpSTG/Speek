package com.latencot.speek.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveParseTest {

    @Test
    fun chatsListsSessionsAndWhichOnesWait() {
        val (sessions, waiting) = LiveParse.chats("""{"sessions": [
            {"id": "c932baaf", "session_id": "c932baaf-1", "name": "fix login", "cwd": "/srv/app", "kind": "bg",
             "status": "waiting", "waiting_for": "Bash", "updated": "2026-11-13 03:00:00", "last": "Running tests"}],
            "asks": [{"rid": "1a2b3c4d", "session_id": "c932baaf-1", "id": "c932baaf", "name": "fix login",
             "tool": "Bash", "summary": "npm test", "detail": "npm test", "status": "pending", "at": "2026-11-13 03:00:01"}]}""")
        assertEquals("cc:c932baaf", sessions.single().ref)
        assertEquals(setOf("c932baaf"), waiting)
    }

    @Test
    fun eventsKeepOrderToolsAndAsks() {
        val (status, events, asks) = LiveParse.events("""{"status": "waiting", "waiting_for": "Bash", "events": [
            {"seq": 100, "at": "2026-11-13 03:00:00", "type": "user", "text": "run the tests"},
            {"seq": 201, "at": "2026-11-13 03:00:02", "type": "tool", "tool_id": "t1", "name": "Bash", "summary": "npm test"}],
            "asks": [{"rid": "1a2b3c4d", "tool": "Bash", "summary": "npm test", "detail": "npm test",
             "status": "pending", "at": "2026-11-13 03:00:03"}]}""")
        assertEquals("waiting", status)
        assertEquals(listOf("user", "tool"), events.map { it.type })
        assertEquals("npm test", events[1].summary)
        assertEquals("pending", asks.single().status)
    }

    @Test
    fun anonymousEventsHaveNoAsks() {
        val (_, events, asks) = LiveParse.anonEvents("""{"status": "idle", "events": [
            {"seq": 100, "at": "2026-11-13 03:00:00", "type": "text", "text": "Done."}]}""")
        assertEquals("Done.", events.single().text)
        assertTrue(asks.isEmpty())
    }

    @Test
    fun attachedFilesShowAsTheirNames() {
        assertEquals("look\n📎 a.png", LiveParse.shown("look\nAttached photo: /data/media/a.png"))
    }
}
