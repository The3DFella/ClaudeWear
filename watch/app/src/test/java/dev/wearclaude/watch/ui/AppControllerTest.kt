package dev.wearclaude.watch.ui

import dev.wearclaude.watch.data.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AppControllerTest {
    private class FakeStore(url: String? = null, token: String? = null) : SettingsStore {
        val url = MutableStateFlow(url)
        var tok = token
        override val bridgeUrl: Flow<String?> = this.url
        override val lastProjectId: Flow<String?> = MutableStateFlow(null)
        override suspend fun token() = tok
        override suspend fun setPaired(url: String, token: String) { this.url.value = url; tok = token }
        override suspend fun setLastProjectId(id: String) {}
        override suspend fun clear() { url.value = null; tok = null }
    }

    private class FakeApi : BridgeApiContract {
        override suspend fun agents() = AgentsSnapshot(emptyList(), "b", 0)
        override suspend fun agent(id: String): AgentDetail = error("unused")
        override suspend fun chats(): List<ChatInfo> = emptyList()
        override suspend fun chat(id: String): ChatDetail = error("unused")
    }

    private fun TestScope.controller(
        store: SettingsStore,
        pairer: suspend (String, String, String) -> String = { _, _, _ -> "tok" },
        events: EventSource = EventSource { emptyFlow() },
        built: MutableList<Pair<String, String>> = mutableListOf(),
    ) = AppController(
        store, pairer,
        { u, t -> built += u to t; AgentRepository(FakeApi(), events, backgroundScope, listOf(1)) },
        backgroundScope, "Watch",
    )

    @Test fun unpairedWhenNothingStored() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeStore())
        assertEquals(false, c.paired.value)
        assertNull(c.repo.value)
    }

    @Test fun pairedFromStore() = runTest(UnconfinedTestDispatcher()) {
        val built = mutableListOf<Pair<String, String>>()
        val c = controller(FakeStore("http://h", "t"), built = built)
        assertEquals(true, c.paired.value)
        assertNotNull(c.repo.value)
        assertEquals(listOf("http://h" to "t"), built)
    }

    @Test fun pairSuccessNormalizesAndStores() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore()
        var seen: Triple<String, String, String>? = null
        val c = controller(store, pairer = { u, code, n -> seen = Triple(u, code, n); "tok" })
        c.pair("192.168.1.5:8787", "123456")
        assertEquals(Triple("http://192.168.1.5:8787", "123456", "Watch"), seen)
        assertEquals(true, c.paired.value)
        assertEquals("tok", store.tok)
        assertEquals(PairUiState.Idle, c.pairing.value)
    }

    @Test fun pairFailureMapsError() = runTest(UnconfinedTestDispatcher()) {
        val c = controller(FakeStore(), pairer = { _, _, _ -> throw BridgeException(429, "locked") })
        c.pair("x.ts.net", "123456")
        assertEquals(false, c.paired.value)
        assertEquals(PairUiState.Error("Too many tries — wait 5 minutes"), c.pairing.value)
    }

    @Test fun authFailureUnpairsWithMessage() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore("http://h", "t")
        val c = controller(store, events = EventSource { flow { throw AuthFailedException() } })
        assertEquals(false, c.paired.value)
        assertNull(c.repo.value)
        assertNull(store.tok)
        assertEquals(PairUiState.Error(REMOVED_MESSAGE), c.pairing.value)
    }

    @Test fun unpairClears() = runTest(UnconfinedTestDispatcher()) {
        val store = FakeStore("http://h", "t")
        val c = controller(store)
        c.unpair()
        assertEquals(false, c.paired.value)
        assertNull(store.tok)
    }

    @Test fun storeFailureTreatedAsUnpaired() = runTest(UnconfinedTestDispatcher()) {
        val store = object : SettingsStore by FakeStore() {
            override suspend fun token(): String? = throw IOException("boom")
        }
        val c = controller(store)
        assertEquals(false, c.paired.value)
    }

    @Test fun unpairThenImmediatePairKeepsNewCredentials() = runTest(UnconfinedTestDispatcher()) {
        val real = FakeStore("http://h", "old")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val store = object : SettingsStore by real {
            override suspend fun clear() { gate.await(); real.clear() }
        }
        val c = controller(store)
        c.unpair()
        c.pair("192.168.1.5:8787", "123456")
        gate.complete(Unit)
        assertEquals("tok", real.tok)
        assertEquals("http://192.168.1.5:8787", real.url.value)
        assertEquals(true, c.paired.value)
    }

    @Test fun unpairCancelsInFlightPair() = runTest(UnconfinedTestDispatcher()) {
        val real = FakeStore()
        val gate = kotlinx.coroutines.CompletableDeferred<String>()
        val c = controller(real, pairer = { _, _, _ -> gate.await() })
        c.pair("x.ts.net", "123456")
        c.unpair()
        gate.complete("late")
        assertEquals(false, c.paired.value)
        assertNull(real.tok)
        assertEquals(PairUiState.Idle, c.pairing.value)
    }

    @Test fun commandsExposedWhilePairedAndClearedOnUnpair() = runTest(UnconfinedTestDispatcher()) {
        val cmds = object : AgentCommands {
            override suspend fun projects() = emptyList<ProjectInfo>()
            override suspend fun createAgent(projectId: String, prompt: String) = "x"
            override suspend fun followUp(id: String, prompt: String) {}
            override suspend fun stop(id: String) {}
            override suspend fun decide(approvalId: String, allow: Boolean) {}
            override suspend fun sendToChat(id: String, prompt: String) {}
        }
        val c = AppController(
            FakeStore("http://h", "t"), { _, _, _ -> "tok" },
            { _, _ -> AgentRepository(FakeApi(), EventSource { emptyFlow() }, backgroundScope, listOf(1)) },
            backgroundScope, "Watch", { _, _ -> cmds },
        )
        assertEquals("x", c.commands.value!!.createAgent("p", "hi"))
        c.unpair()
        assertNull(c.commands.value)
    }

    @Test fun command401UnpairsWithMessage() = runTest(UnconfinedTestDispatcher()) {
        for (call in listOf<suspend (AgentCommands) -> Unit>(
            { it.createAgent("p", "x") }, { it.followUp("a", "x") }, { it.stop("a") }, { it.decide("ap", true) }, { it.sendToChat("c", "x") },
        )) {
            val cmds = object : AgentCommands {
                override suspend fun projects() = emptyList<ProjectInfo>()
                override suspend fun createAgent(projectId: String, prompt: String): String = throw BridgeException(401, "unauthorized")
                override suspend fun followUp(id: String, prompt: String) = throw BridgeException(401, "unauthorized")
                override suspend fun stop(id: String) = throw BridgeException(401, "unauthorized")
                override suspend fun decide(approvalId: String, allow: Boolean) = throw BridgeException(401, "unauthorized")
                override suspend fun sendToChat(id: String, prompt: String) = throw BridgeException(401, "unauthorized")
            }
            val store = FakeStore("http://h", "t")
            val c = AppController(
                store, { _, _, _ -> "tok" },
                { _, _ -> AgentRepository(FakeApi(), EventSource { kotlinx.coroutines.flow.MutableSharedFlow() }, backgroundScope, listOf(1)) },
                backgroundScope, "Watch", { _, _ -> cmds },
            )
            assertEquals(true, c.paired.value)
            val ex = try { call(c.commands.value!!); null } catch (e: BridgeException) { e }
            assertEquals(401, ex?.code)
            assertEquals(false, c.paired.value)
            assertNull(store.tok)
            assertEquals(PairUiState.Error(REMOVED_MESSAGE), c.pairing.value)
        }
    }

    @Test fun commandNon401DoesNotUnpair() = runTest(UnconfinedTestDispatcher()) {
        val cmds = object : AgentCommands {
            override suspend fun projects() = emptyList<ProjectInfo>()
            override suspend fun createAgent(projectId: String, prompt: String): String = throw BridgeException(409, "busy")
            override suspend fun followUp(id: String, prompt: String) {}
            override suspend fun stop(id: String) {}
            override suspend fun decide(approvalId: String, allow: Boolean) {}
            override suspend fun sendToChat(id: String, prompt: String) {}
        }
        val c = AppController(
            FakeStore("http://h", "t"), { _, _, _ -> "tok" },
            { _, _ -> AgentRepository(FakeApi(), EventSource { kotlinx.coroutines.flow.MutableSharedFlow() }, backgroundScope, listOf(1)) },
            backgroundScope, "Watch", { _, _ -> cmds },
        )
        try { c.commands.value!!.createAgent("p", "x") } catch (_: BridgeException) {}
        assertEquals(true, c.paired.value)
    }
}
