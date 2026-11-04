package dev.wearclaude.watch.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import dev.wearclaude.watch.service.alertsFor
import dev.wearclaude.watch.service.approvalAlerts
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentRepositoryTest {
    private fun info(id: String, status: String = "idle") = AgentInfo(id, "t$id", "p", status, null, null, 1, 1, "s")

    private class FakeApi(var agents: List<AgentInfo>, var detail: AgentDetail? = null) : BridgeApiContract {
        var agentsCalls = 0
        var failures = 0
        var failWith: Exception = java.io.IOException("down")
        var bootId: String? = "b1"
        var seq = 0L
        var gate: CompletableDeferred<Unit>? = null
        var detailFailWith: Exception? = null
        val details = mutableMapOf<String, AgentDetail>()
        override suspend fun agents(): AgentsSnapshot {
            agentsCalls++
            if (failures > 0) { failures--; throw failWith }
            gate?.await()
            return AgentsSnapshot(agents, bootId, seq)
        }
        override suspend fun agent(id: String): AgentDetail {
            detailFailWith?.let { throw it }
            return details[id] ?: detail!!
        }
        var chatList: List<ChatInfo> = emptyList()
        var chatsFailWith: Exception? = null
        val chatDetails = mutableMapOf<String, ChatDetail>()
        override suspend fun chats(): List<ChatInfo> {
            chatsFailWith?.let { throw it }
            return chatList
        }
        override suspend fun chat(id: String): ChatDetail = chatDetails[id] ?: throw BridgeException(404, "nf")
    }

    @Test fun loadsThenReducesAndReloadsOnReset() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(listOf(info("a")))
        val events = MutableSharedFlow<WatchEvent>()
        val repo = AgentRepository(api, EventSource { events }, backgroundScope)
        repo.start()
        assertEquals(setOf("a"), repo.state.value.agents.keys)

        events.emit(WatchEvent.Step(3, 1, "a", "x", 0))
        events.emit(WatchEvent.Step(4, 1, "gone", "y", 0))
        assertEquals(4L, repo.state.value.lastSeq)

        api.agents = listOf(info("b")); api.seq = 4
        events.emit(WatchEvent.Reset())
        assertEquals(2, api.agentsCalls)
        assertEquals(setOf("b"), repo.state.value.agents.keys)
        assertTrue(repo.state.value.steps.isEmpty())
        assertFalse(repo.state.value.needsReload)
        repo.stop()
    }

    private fun chatInfo(id: String, la: Long = 1) = ChatInfo(id, "t$id", "proj", la, "idle")

    @Test fun reloadPopulatesAndReplacesChats() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(emptyList()).apply { chatList = listOf(chatInfo("c1"), chatInfo("c2")) }
        val events = MutableSharedFlow<WatchEvent>()
        val repo = AgentRepository(api, EventSource { events }, backgroundScope)
        repo.start()
        assertEquals(setOf("c1", "c2"), repo.state.value.chats.keys)
        api.chatDetails["c1"] = ChatDetail(chatInfo("c1"), listOf(ChatItem("prompt", "hi", 1)))
        api.chatDetails["c2"] = ChatDetail(chatInfo("c2"), listOf(ChatItem("reply", "yo", 2)))
        repo.loadChat("c1"); repo.loadChat("c2")
        api.chatList = listOf(chatInfo("c2")); api.seq = 5
        events.emit(WatchEvent.Reset())
        assertEquals(setOf("c2"), repo.state.value.chats.keys)
        assertEquals(setOf("c2"), repo.state.value.chatItems.keys)
        repo.stop()
    }

    @Test fun chatsFailureKeepsPreviousChats() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(emptyList()).apply { chatList = listOf(chatInfo("c1")) }
        val events = MutableSharedFlow<WatchEvent>()
        val repo = AgentRepository(api, EventSource { events }, backgroundScope)
        repo.start()
        api.chatsFailWith = BridgeException(500, "boom"); api.seq = 3
        events.emit(WatchEvent.Reset())
        assertEquals(setOf("c1"), repo.state.value.chats.keys)
        assertTrue(repo.state.value.loaded)
        assertFalse(repo.state.value.authFailed)
        repo.stop()
    }

    @Test fun chats401MarksAuthFailed() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(emptyList()).apply { chatsFailWith = BridgeException(401, "no") }
        val repo = AgentRepository(api, EventSource { emptyFlow() }, backgroundScope)
        repo.start()
        assertTrue(repo.state.value.authFailed)
        repo.stop()
    }

    @Test fun loadChatReplacesItems() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(emptyList())
        val repo = AgentRepository(api, EventSource { emptyFlow() }, backgroundScope)
        api.chatDetails["c1"] = ChatDetail(chatInfo("c1", 5), listOf(ChatItem("prompt", "a", 1)))
        repo.loadChat("c1")
        api.chatDetails["c1"] = ChatDetail(chatInfo("c1", 9), listOf(ChatItem("reply", "b", 2), ChatItem("step", "c", 3)))
        repo.loadChat("c1")
        assertEquals(listOf("b", "c"), repo.state.value.chatItems["c1"]!!.map { it.text })
        assertEquals(9L, repo.state.value.chats["c1"]!!.lastActivity)
    }

    @Test fun loadChatErrorPropagatesWithout401Flag() = runTest(UnconfinedTestDispatcher()) {
        val repo = AgentRepository(FakeApi(emptyList()), EventSource { emptyFlow() }, backgroundScope)
        val ex = try { repo.loadChat("x"); null } catch (e: BridgeException) { e }
        assertEquals(404, ex!!.code)
        assertFalse(repo.state.value.authFailed)
    }

    @Test fun loadDetailMerges() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(emptyList(), AgentDetail(
            info("a", "running"),
            listOf(WatchEvent.Step(1, 1, "a", "s1", 0), WatchEvent.Message(2, 1, "a", "hi")),
            listOf(PendingApproval("ap", "a", "Bash", "x", 9))))
        val repo = AgentRepository(api, EventSource { emptyFlow() }, backgroundScope)
        repo.loadDetail("a")
        val s = repo.state.value
        assertEquals("running", s.agents["a"]!!.status)
        assertEquals(listOf("s1"), s.steps["a"])
        assertEquals(setOf("ap"), s.approvals.keys)
    }

    @Test fun initialReloadRetriesUntilSuccess() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(listOf(info("a"))).apply { failures = 2 }
        val repo = AgentRepository(api, EventSource { MutableSharedFlow() }, backgroundScope, listOf(10L))
        repo.start()
        assertTrue(repo.state.value.agents.isEmpty())
        assertNotNull(repo.state.value.error)
        testScheduler.advanceTimeBy(11); testScheduler.runCurrent()
        assertEquals(2, api.agentsCalls)
        testScheduler.advanceTimeBy(11); testScheduler.runCurrent()
        assertEquals(3, api.agentsCalls)
        assertEquals(setOf("a"), repo.state.value.agents.keys)
        assertNull(repo.state.value.error)
        repo.stop()
    }

    @Test fun resetClearsApprovalsAndRepopulatesWaitingAgents() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(listOf(info("a", "waiting_approval"), info("b")))
        api.details["a"] = AgentDetail(info("a", "waiting_approval"),
            listOf(WatchEvent.Step(40, 1, "a", "s", 0)),
            listOf(PendingApproval("new", "a", "Bash", "x", 9)))
        val events = MutableSharedFlow<WatchEvent>()
        val repo = AgentRepository(api, EventSource { events }, backgroundScope)
        repo.start()
        events.emit(WatchEvent.ApprovalRequested(1, 1, "b", "stale", "Bash", "x", 9))
        assertTrue("stale" in repo.state.value.approvals)
        api.seq = 40
        events.emit(WatchEvent.Reset(seq = 40, bootId = "b1"))
        assertEquals(setOf("new"), repo.state.value.approvals.keys)
        assertEquals(listOf("s"), repo.state.value.steps["a"])
        assertEquals(40L, repo.state.value.stepSeq["a"])
        repo.stop()
    }

    @Test fun duplicateStepSeqIgnored() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(listOf(info("a")))
        val events = MutableSharedFlow<WatchEvent>()
        val repo = AgentRepository(api, EventSource { events }, backgroundScope)
        repo.start()
        events.emit(WatchEvent.Step(5, 1, "a", "x", 0))
        events.emit(WatchEvent.Step(5, 1, "a", "x", 0))
        events.emit(WatchEvent.Step(4, 1, "a", "y", 0))
        assertEquals(listOf("x"), repo.state.value.steps["a"])
        repo.stop()
    }

    @Test fun rest401SetsAuthFailed() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(emptyList()).apply { failures = 1; failWith = BridgeException(401, "unauthorized") }
        val repo = AgentRepository(api, EventSource { emptyFlow() }, backgroundScope)
        repo.start()
        assertTrue(repo.state.value.authFailed)
        assertEquals(1, api.agentsCalls)
    }

    @Test fun streamAuthFailureSetsAuthFailed() = runTest(UnconfinedTestDispatcher()) {
        val repo = AgentRepository(FakeApi(emptyList()),
            EventSource { kotlinx.coroutines.flow.flow { throw AuthFailedException() } }, backgroundScope)
        repo.start()
        assertTrue(repo.state.value.authFailed)
    }

    @Test fun loadedSetAfterFirstReload() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(listOf(info("a")))
        val repo = AgentRepository(api, EventSource { emptyFlow() }, backgroundScope)
        assertFalse(repo.state.value.loaded)
        repo.start()
        assertTrue(repo.state.value.loaded)
        repo.stop()
    }

    @Test fun failReasonFromRestDetailAndClearedOnReload() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(listOf(info("a", "failed")), AgentDetail(
            info("a", "failed"), listOf(WatchEvent.Status(1, 1, "a", "failed", "boom"))))
        val repo = AgentRepository(api, EventSource { emptyFlow() }, backgroundScope)
        repo.loadDetail("a")
        assertEquals("boom", repo.state.value.failReasons["a"])
        repo.start()
        assertTrue(repo.state.value.failReasons.isEmpty())
        repo.stop()
    }

    @Test fun replayedOldEventsAreIgnoredAfterSnapshot() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(listOf(info("a", "done"))).apply { seq = 10 }
        val events = MutableSharedFlow<WatchEvent>()
        val repo = AgentRepository(api, EventSource { events }, backgroundScope)
        val seen = mutableListOf<AgentsState>()
        backgroundScope.launch { repo.state.collect { seen += it } }
        repo.start()
        events.emit(WatchEvent.Hello(bootId = "b1", seq = 10))
        // Bridge replays its backlog (e.g. reconnect with a stale since): all already in the snapshot.
        events.emit(WatchEvent.Status(2, 2, "a", "running"))
        events.emit(WatchEvent.ApprovalRequested(3, 3, "a", "old", "Bash", "x", 9))
        events.emit(WatchEvent.Status(5, 5, "a", "done"))
        events.emit(WatchEvent.Status(10, 10, "a", "failed", "boom"))
        val s = repo.state.value
        assertEquals("done", s.agents["a"]!!.status)
        assertTrue(s.approvals.isEmpty())
        assertEquals(10L, s.lastSeq)
        assertEquals(1, api.agentsCalls)
        val loaded = seen.filter { it.loaded }
        assertTrue(loaded.zipWithNext().all { (p, n) -> alertsFor(p, n).isEmpty() })
        var alerted: Set<String>? = null
        for (st in loaded) { val (a, next) = approvalAlerts(st, alerted); assertTrue(a.isEmpty()); alerted = next }
        events.emit(WatchEvent.Status(11, 11, "a", "running"))
        assertEquals("running", repo.state.value.agents["a"]!!.status)
        assertEquals(11L, repo.state.value.lastSeq)
        repo.stop()
    }

    @Test fun eventArrivingDuringReloadIsAppliedAfterSnapshot() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(listOf(info("a", "running"))).apply { seq = 10 }
        val ch = Channel<WatchEvent>(Channel.UNLIMITED)
        val repo = AgentRepository(api, EventSource { ch.consumeAsFlow() }, backgroundScope)
        repo.start()
        assertEquals("b1", repo.state.value.bootId)
        // Bridge restarted: the new boot id triggers a reload that is still in flight...
        val gate = CompletableDeferred<Unit>().also { api.gate = it }
        api.bootId = "b2"; api.seq = 20
        ch.send(WatchEvent.Hello(bootId = "b2", seq = 20))
        assertEquals(2, api.agentsCalls)
        assertTrue(repo.state.value.needsReload)
        // ...while already-snapshotted and new events arrive.
        ch.send(WatchEvent.Status(20, 20, "a", "failed", "old"))
        ch.send(WatchEvent.Status(21, 21, "a", "done"))
        assertEquals("running", repo.state.value.agents["a"]!!.status)
        gate.complete(Unit)
        val s = repo.state.value
        assertEquals("done", s.agents["a"]!!.status)
        assertTrue(s.failReasons.isEmpty())
        assertEquals(21L, s.lastSeq)
        assertEquals("b2", s.bootId)
        assertFalse(s.needsReload)
        repo.stop()
    }

    @Test fun helloWithSameBootIdDoesNotReloadButNewBootIdDoes() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(listOf(info("a"))).apply { seq = 3 }
        val events = MutableSharedFlow<WatchEvent>()
        val repo = AgentRepository(api, EventSource { events }, backgroundScope)
        repo.start()
        events.emit(WatchEvent.Hello(bootId = "b1", seq = 3))
        assertEquals(1, api.agentsCalls)
        api.bootId = "b2"; api.seq = 1; api.agents = listOf(info("z"))
        events.emit(WatchEvent.Hello(bootId = "b2", seq = 1))
        assertEquals(2, api.agentsCalls)
        assertEquals(setOf("z"), repo.state.value.agents.keys)
        assertEquals(1L, repo.state.value.lastSeq)
        // The reset that follows hello on the same connection is already covered by the snapshot.
        events.emit(WatchEvent.Reset(seq = 1, bootId = "b2"))
        assertEquals(2, api.agentsCalls)
        // A reset reporting newer events than we hold forces a reload.
        events.emit(WatchEvent.Reset(seq = 5, bootId = "b2"))
        assertEquals(3, api.agentsCalls)
        repo.stop()
    }

    @Test fun streamConnectsAndReconnectsWithRepositoryLastSeq() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(listOf(info("a"))).apply { seq = 10 }
        val sinces = mutableListOf<Long>()
        val repo = AgentRepository(api, EventSource { since ->
            flow {
                sinces += since()
                emit(WatchEvent.Hello(bootId = "b1", seq = 10))
                emit(WatchEvent.Step(11, 1, "a", "x", 0))
                emit(WatchEvent.Step(12, 1, "a", "y", 0))
                sinces += since() // what a reconnect would ask for
            }
        }, backgroundScope)
        repo.start()
        assertEquals(listOf(10L, 12L), sinces)
        repo.stop()
    }

    @Test fun loadDetail401SetsAuthFailed() = runTest(UnconfinedTestDispatcher()) {
        val api = FakeApi(emptyList()).apply { detailFailWith = BridgeException(401, "unauthorized") }
        val repo = AgentRepository(api, EventSource { emptyFlow() }, backgroundScope)
        val ex = try { repo.loadDetail("a"); null } catch (e: BridgeException) { e }
        assertEquals(401, ex?.code)
        assertTrue(repo.state.value.authFailed)
    }
}
