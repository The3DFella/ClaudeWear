package dev.wearclaude.watch.ui

import dev.wearclaude.watch.data.AgentInfo
import dev.wearclaude.watch.data.AgentsState
import dev.wearclaude.watch.data.PendingApproval
import org.junit.Assert.*
import org.junit.Test

class DetailApprovalLogicTest {
    private fun state(status: String, steps: List<String> = emptyList(), depths: List<Int> = emptyList(),
                       summary: String? = null, reason: String? = null, approvals: List<PendingApproval> = emptyList()) =
        AgentsState(
            agents = mapOf("a" to AgentInfo("a", "Title", "p", status, null, summary, 1, 2)),
            steps = mapOf("a" to steps), stepDepths = mapOf("a" to depths),
            failReasons = if (reason != null) mapOf("a" to reason) else emptyMap(),
            approvals = approvals.associateBy { it.approvalId },
        )

    @Test fun unknownAgentIsNull() = assertNull(detailUi(AgentsState(), "x"))

    @Test fun replyAndStopEnablement() {
        val cases = mapOf(
            "running" to (false to true), "waiting_approval" to (false to true),
            "done" to (true to false), "failed" to (true to false),
            "stopped" to (true to false), "interrupted" to (true to false),
        )
        for ((st, exp) in cases) {
            val ui = detailUi(state(st), "a")!!
            assertEquals(st, exp.first, ui.canReply)
            assertEquals(st, exp.second, ui.canStop)
        }
    }

    @Test fun stepsPrefixedAndIndented() {
        val ui = detailUi(state("running", listOf("read", "grep"), listOf(0, 1)), "a")!!
        assertEquals(DetailStep("read", false), ui.steps[0])
        assertEquals(DetailStep("↳ grep", true), ui.steps[1])
        assertEquals("Title", ui.title)
        assertEquals("running", ui.status)
    }

    @Test fun missingDepthsDefaultToZero() {
        val ui = detailUi(state("running", listOf("x")), "a")!!
        assertFalse(ui.steps[0].indented)
    }

    @Test fun summaryAndReason() {
        val ui = detailUi(state("failed", summary = "sum", reason = "oops"), "a")!!
        assertEquals("sum", ui.summary)
        assertEquals("oops", ui.reason)
        assertNull(detailUi(state("done", reason = "oops"), "a")!!.reason)
    }

    @Test fun pendingApprovalLink() {
        val ap = PendingApproval("ap1", "a", "Bash", "ls", 100)
        val other = PendingApproval("ap2", "b", "Bash", "ls", 50)
        assertEquals("ap1", detailUi(state("waiting_approval", approvals = listOf(other, ap)), "a")!!.pendingApprovalId)
        assertNull(detailUi(state("running"), "a")!!.pendingApprovalId)
    }

    @Test fun countdown() {
        assertEquals("Auto-deny in 1:05", countdownText(65_000, 0))
        assertEquals("Auto-deny in 0:09", countdownText(9_500, 0))
        assertEquals("Expired", countdownText(1000, 1000))
        assertEquals("Expired", countdownText(1000, 5000))
    }

    @Test fun newApprovalIds() {
        assertEquals(setOf("c"), newApprovalIds(setOf("a", "b"), setOf("b", "c")))
        assertEquals(emptySet<String>(), newApprovalIds(setOf("a"), setOf("a")))
        assertEquals(emptySet<String>(), newApprovalIds(setOf("a"), emptySet()))
    }

    @Test fun approvalModes() {
        val ap = PendingApproval("ap1", "a", "Bash", "ls", 100)
        assertEquals(ApprovalMode.Loading, approvalScreenMode(false, null, null, 0))
        assertEquals(ApprovalMode.Loading, approvalScreenMode(false, null, null, 9_999))
        assertEquals(ApprovalMode.Resolved, approvalScreenMode(false, null, null, 10_000))
        assertEquals(ApprovalMode.Show, approvalScreenMode(false, null, ap, 0))
        assertEquals(ApprovalMode.Show, approvalScreenMode(true, ap, ap, 0))
        assertEquals(ApprovalMode.Resolved, approvalScreenMode(false, ap, null, 0))
        assertEquals(ApprovalMode.Resolved, approvalScreenMode(true, null, null, 0))
    }

    @Test fun autoOpenSeedsOnlyFromFirstLoadedState() {
        val before = autoOpenIds(null, false, setOf("x"))
        assertNull(before.seeded); assertTrue(before.fresh.isEmpty())
        val seeded = autoOpenIds(before.seeded, true, setOf("x"))
        assertEquals(setOf("x"), seeded.seeded); assertTrue(seeded.fresh.isEmpty())
        val next = autoOpenIds(seeded.seeded, true, setOf("x", "y"))
        assertEquals(setOf("y"), next.fresh)
        assertEquals(setOf("x", "y"), next.seeded)
    }
}
