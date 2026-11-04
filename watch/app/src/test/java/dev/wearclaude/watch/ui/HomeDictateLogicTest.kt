package dev.wearclaude.watch.ui

import dev.wearclaude.watch.data.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class HomeDictateLogicTest {
    private fun agent(id: String, t: Long, status: String = "running") =
        AgentInfo(id, "T$id", "p", status, "step$id", null, 0, t)

    @Test fun statusRoles() {
        assertEquals(StatusColorRole.Primary, statusColorRole("running"))
        assertEquals(StatusColorRole.Amber, statusColorRole("waiting_approval"))
        assertEquals(StatusColorRole.Green, statusColorRole("done"))
        assertEquals(StatusColorRole.Error, statusColorRole("failed"))
        assertEquals(StatusColorRole.Muted, statusColorRole("stopped"))
        assertEquals(StatusColorRole.Muted, statusColorRole("interrupted"))
        assertEquals(StatusColorRole.Outline, statusColorRole("idle"))
        assertEquals(StatusColorRole.Outline, statusColorRole("weird"))
    }

    @Test fun countsApprovals() {
        val s = AgentsState(
            agents = listOf(agent("a", 1), agent("b", 3), agent("c", 2)).associateBy { it.id },
            approvals = mapOf(
                "x" to PendingApproval("x", "b", "Bash", "s", 20),
                "y" to PendingApproval("y", "c", "Bash", "s", 10),
            ),
        )
        val ui = homeItems(s)
        assertEquals(2, ui.pendingApprovals)
        assertEquals("y", ui.firstApprovalId)
        assertEquals("c", ui.firstApprovalAgentId)
        assertFalse(ui.reconnecting)
    }

    @Test fun emptyAndError() {
        val ui = homeItems(AgentsState(error = "boom"))
        assertEquals(0, ui.pendingApprovals)
        assertNull(ui.firstApprovalId)
        assertTrue(ui.reconnecting)
        assertEquals("No active chats on your PC", EMPTY_ACTIVE_COPY)
    }

    private val ps = listOf(ProjectInfo("a", "A"), ProjectInfo("b", "B"), ProjectInfo("c", "C"))

    @Test fun projectDefaultAndCycle() {
        assertEquals(1, defaultProjectIndex(ps, "b"))
        assertEquals(0, defaultProjectIndex(ps, "zzz"))
        assertEquals(0, defaultProjectIndex(ps, null))
        assertEquals(-1, defaultProjectIndex(emptyList(), "b"))
        assertEquals(2, nextProjectIndex(1, 3))
        assertEquals(0, nextProjectIndex(2, 3))
        assertEquals(-1, nextProjectIndex(0, 0))
    }

    @Test fun errorMessages() {
        assertEquals("Agent is still working", sendErrorMessage(BridgeException(409, "x")))
        assertEquals("Can't reach your PC", sendErrorMessage(IOException("x")))
        assertEquals("Couldn't send (500)", sendErrorMessage(BridgeException(500, "x")))
    }

    private class Fake(val fail: Throwable? = null) : AgentCommands {
        val calls = mutableListOf<String>()
        override suspend fun projects() = emptyList<ProjectInfo>()
        override suspend fun createAgent(projectId: String, prompt: String): String {
            fail?.let { throw it }; calls += "create $projectId $prompt"; return "new1"
        }
        override suspend fun followUp(id: String, prompt: String) {
            fail?.let { throw it }; calls += "follow $id $prompt"
        }
        override suspend fun stop(id: String) {}
        override suspend fun decide(approvalId: String, allow: Boolean) {}
        override suspend fun sendToChat(id: String, prompt: String) {}
    }

    @Test fun submitCreateAndReply() = runTest {
        val f = Fake()
        assertEquals(SendResult.Ok("new1"), submitPrompt(f, null, "p", "hi"))
        assertEquals(SendResult.Ok("ag"), submitPrompt(f, "ag", "p", "more"))
        assertEquals(listOf("create p hi", "follow ag more"), f.calls)
    }

    @Test fun submitFailure() = runTest {
        assertEquals(SendResult.Failed("Agent is still working"),
            submitPrompt(Fake(BridgeException(409, "x")), "ag", null, "m"))
        assertEquals(SendResult.Failed("Can't reach your PC"),
            submitPrompt(Fake(IOException()), null, "p", "m"))
    }
}
