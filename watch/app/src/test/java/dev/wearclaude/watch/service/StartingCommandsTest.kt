package dev.wearclaude.watch.service

import dev.wearclaude.watch.data.AgentCommands
import dev.wearclaude.watch.data.ProjectInfo
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class StartingCommandsTest {
    private class Fake(val fail: Boolean = false) : AgentCommands {
        override suspend fun projects() = emptyList<ProjectInfo>()
        override suspend fun createAgent(projectId: String, prompt: String): String =
            if (fail) throw RuntimeException("x") else "id1"
        override suspend fun followUp(id: String, prompt: String) { if (fail) throw RuntimeException("x") }
        override suspend fun stop(id: String) {}
        override suspend fun decide(approvalId: String, allow: Boolean) {}
        override suspend fun sendToChat(id: String, prompt: String) {}
    }

    @Test fun firesAfterSuccess() = runTest {
        var n = 0
        val c = StartingCommands(Fake()) { n++ }
        assertEquals("id1", c.createAgent("p", "hi"))
        c.followUp("id1", "more")
        c.stop("id1")
        assertEquals(2, n)
    }

    @Test fun doesNotFireOnFailure() = runTest {
        var n = 0
        val c = StartingCommands(Fake(fail = true)) { n++ }
        try { c.createAgent("p", "hi"); fail() } catch (_: RuntimeException) {}
        try { c.followUp("a", "hi"); fail() } catch (_: RuntimeException) {}
        assertEquals(0, n)
    }
}
