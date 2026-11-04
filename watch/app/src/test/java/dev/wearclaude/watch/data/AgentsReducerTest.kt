package dev.wearclaude.watch.data

import org.junit.Assert.*
import org.junit.Test

class AgentsReducerTest {
    private fun fold(vararg events: WatchEvent, from: AgentsState = AgentsState()) =
        events.fold(from) { s, e -> AgentsReducer.reduce(s, e) }

    private fun created(seq: Long, id: String = "a1") =
        WatchEvent.AgentCreated(seq, 1000 + seq, id, "Fix bug", "p1")

    @Test fun lifecycle() {
        val s = fold(
            created(1),
            WatchEvent.Step(2, 2000, "a1", "reading", 0),
            WatchEvent.Step(3, 3000, "a1", "editing", 1),
            WatchEvent.Summary(4, 4000, "a1", "all done", 0.12),
            WatchEvent.Status(5, 5000, "a1", "done", null),
        )
        val a = s.agents.getValue("a1")
        assertEquals("done", a.status)
        assertEquals("Fix bug", a.title)
        assertEquals("p1", a.projectId)
        assertEquals("editing", a.lastStep)
        assertEquals("all done", a.summary)
        assertEquals(1001L, a.createdAt)
        assertEquals(5000L, a.updatedAt)
        assertEquals(listOf("reading", "editing"), s.steps["a1"])
        assertEquals(5L, s.lastSeq)
        assertFalse(s.needsReload)
    }

    @Test fun chatStatusKeepsTitled() {
        val from = AgentsState(chats = mapOf("abc" to ChatInfo("abc", "T", "P", 1, "idle", titled = true)))
        val s = fold(WatchEvent.ChatStatusEvent(1, 100, "pc:abc", "working", "T", "P"), from = from)
        assertTrue(s.chats.getValue("abc").titled)
    }

    @Test fun chatStatusCopiesArchived() {
        val s = fold(WatchEvent.ChatStatusEvent(1, 100, "pc:abc", "idle", "T", "P", archived = true))
        assertTrue(s.chats.getValue("abc").archived)
        val s2 = fold(WatchEvent.ChatStatusEvent(2, 200, "pc:abc", "idle", "T", "P"), from = s)
        assertFalse(s2.chats.getValue("abc").archived)
    }

    @Test fun chatStatusUpsertsAndStripsPrefix() {
        val s = fold(WatchEvent.ChatStatusEvent(1, 100, "pc:abc", "working", "Title", "Proj"))
        assertEquals(setOf("abc"), s.chats.keys)
        assertEquals(ChatInfo("abc", "Title", "Proj", 100, "working"), s.chats["abc"])
        val s2 = fold(WatchEvent.ChatStatusEvent(2, 200, "pc:abc", "idle", "New", "Proj"), from = s)
        assertEquals(ChatInfo("abc", "New", "Proj", 200, "idle"), s2.chats["abc"])
        assertTrue(s2.agents.isEmpty())
    }

    @Test fun chatStatusCarriesEnded() {
        val s = fold(
            WatchEvent.ChatStatusEvent(1, 100, "pc:abc", "working", "T", "P"),
            WatchEvent.ChatStatusEvent(2, 200, "pc:abc", "idle", "T", "P", ended = true),
        )
        assertTrue(s.chats["abc"]!!.ended)
        val s2 = fold(WatchEvent.ChatStatusEvent(3, 300, "pc:abc", "working", "T", "P"), from = s)
        assertFalse(s2.chats["abc"]!!.ended)
    }

    @Test fun chatItemSkippedWhenSameKindAndTextInLastFive() {
        val loaded = AgentsState(chatItems = mapOf("abc" to listOf(
            ChatItem("prompt", "go", 1), ChatItem("reply", "a", 2), ChatItem("step", "s1", 3),
            ChatItem("step", "s2", 4), ChatItem("reply", "b", 5), ChatItem("reply", "c", 6),
        )))
        // "go" is 6th from the end: outside the window, so it is appended
        val s = fold(
            WatchEvent.ChatItemEvent(1, 10, "pc:abc", "reply", "c"),
            WatchEvent.ChatItemEvent(2, 10, "pc:abc", "step", "s1"),
            WatchEvent.ChatItemEvent(3, 10, "pc:abc", "prompt", "a"),
            WatchEvent.ChatItemEvent(4, 10, "pc:abc", "prompt", "go"),
            from = loaded,
        )
        assertEquals(listOf("go", "a", "s1", "s2", "b", "c", "a", "go"), s.chatItems["abc"]!!.map { it.text })
        assertEquals(4L, s.lastSeq)
    }

    @Test fun chatStatusOlderTsKeepsLastActivity() {
        val s = fold(
            WatchEvent.ChatStatusEvent(1, 500, "pc:abc", "working", "T", "P"),
            WatchEvent.ChatStatusEvent(2, 300, "pc:abc", "idle", "T", "P"),
        )
        assertEquals(500L, s.chats["abc"]!!.lastActivity)
    }

    @Test fun chatItemAppendsAndTouchesLastActivity() {
        val s = fold(
            WatchEvent.ChatStatusEvent(1, 100, "pc:abc", "idle", "T", "P"),
            WatchEvent.ChatItemEvent(2, 250, "pc:abc", "prompt", "hello"),
            WatchEvent.ChatItemEvent(3, 260, "pc:abc", "reply", "hi"),
        )
        assertEquals(listOf(ChatItem("prompt", "hello", 250), ChatItem("reply", "hi", 260)), s.chatItems["abc"])
        assertEquals(260L, s.chats["abc"]!!.lastActivity)
    }

    @Test fun chatItemsCappedAt60DropOldest() {
        var s = AgentsState()
        for (i in 1..61) s = AgentsReducer.reduce(s, WatchEvent.ChatItemEvent(i.toLong(), 10, "pc:abc", "step", "s$i"))
        val items = s.chatItems.getValue("abc")
        assertEquals(60, items.size)
        assertEquals("s2", items.first().text)
        assertEquals("s61", items.last().text)
    }

    @Test fun chatEventsDedupBySeq() {
        val s = fold(
            WatchEvent.ChatItemEvent(5, 10, "pc:abc", "reply", "a"),
            WatchEvent.ChatItemEvent(5, 10, "pc:abc", "reply", "a"),
            WatchEvent.ChatItemEvent(4, 10, "pc:abc", "reply", "old"),
        )
        assertEquals(1, s.chatItems["abc"]!!.size)
        val s2 = fold(WatchEvent.ChatItemEvent(3, 10, "pc:abc", "reply", "x"), from = AgentsState(lastSeq = 3))
        assertTrue(s2.chatItems.isEmpty())
    }

    @Test fun unknownEventStillIgnored() {
        val s = fold(WatchEvent.Unknown(9, 1, "pc:abc"))
        assertTrue(s.chats.isEmpty() && s.chatItems.isEmpty())
    }

    @Test fun createdDefaultsToRunning() {
        assertEquals("running", fold(created(1)).agents.getValue("a1").status)
    }

    @Test fun stepsCappedAt50DropOldest() {
        var s = fold(created(1))
        for (i in 1..51) s = AgentsReducer.reduce(s, WatchEvent.Step(1L + i, 10, "a1", "s$i", 0))
        val steps = s.steps.getValue("a1")
        assertEquals(50, steps.size)
        assertEquals("s2", steps.first())
        assertEquals("s51", steps.last())
    }

    @Test fun approvalAddedThenRemoved() {
        val s1 = fold(created(1), WatchEvent.ApprovalRequested(2, 5, "a1", "ap1", "Bash", "rm x", 99))
        assertEquals(PendingApproval("ap1", "a1", "Bash", "rm x", 99), s1.approvals["ap1"])
        val s2 = AgentsReducer.reduce(s1, WatchEvent.ApprovalResolved(3, 6, "a1", "ap1", "allow"))
        assertTrue(s2.approvals.isEmpty())
    }

    @Test fun controlMessagesDoNotChangeState() {
        val base = fold(created(7))
        assertEquals(base, AgentsReducer.reduce(base, WatchEvent.Reset(seq = 99, bootId = "b")))
        assertEquals(base, AgentsReducer.reduce(base, WatchEvent.Hello(bootId = "b", seq = 99)))
    }

    @Test fun eventsAtOrBelowLastSeqAreDroppedForAllTypes() {
        val base = fold(created(1), WatchEvent.Status(5, 5000, "a1", "done", null))
        val s = fold(
            WatchEvent.Status(2, 2000, "a1", "running", null),
            WatchEvent.Status(5, 5000, "a1", "failed", "x"),
            WatchEvent.ApprovalRequested(3, 3000, "a1", "ap", "Bash", "rm", 9),
            WatchEvent.Summary(4, 4000, "a1", "old"),
            from = base,
        )
        assertEquals(base, s)
    }

    @Test fun unknownOnlyBumpsLastSeq() {
        val base = fold(created(1))
        val s = AgentsReducer.reduce(base, WatchEvent.Unknown(9, 1, "a1"))
        assertEquals(base.copy(lastSeq = 9), s)
    }

    @Test fun lastSeqIsMax() {
        val s = fold(created(10), WatchEvent.Step(4, 1, "a1", "old", 0))
        assertEquals(10L, s.lastSeq)
    }

    @Test fun statusForUnknownAgentIgnored() {
        val s = fold(WatchEvent.Status(1, 1, "zz", "failed", "x"))
        assertTrue(s.agents.isEmpty())
        assertEquals(1L, s.lastSeq)
    }

    @Test fun stepDepthsTrackedAlongsideSteps() {
        val s = fold(
            created(1),
            WatchEvent.Step(2, 2000, "a1", "a", 0),
            WatchEvent.Step(3, 3000, "a1", "b", 1),
        )
        assertEquals(listOf(0, 1), s.stepDepths["a1"])
    }

    @Test fun failReasonSetOnFailedAndClearedOnLaterStatus() {
        val failed = fold(created(1), WatchEvent.Status(2, 2000, "a1", "failed", "boom"))
        assertEquals("boom", failed.failReasons["a1"])
        val again = fold(WatchEvent.Status(3, 3000, "a1", "running", null), from = failed)
        assertNull(again.failReasons["a1"])
    }

}
