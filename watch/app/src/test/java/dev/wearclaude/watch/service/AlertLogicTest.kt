package dev.wearclaude.watch.service

import dev.wearclaude.watch.data.AgentInfo
import dev.wearclaude.watch.data.AgentsState
import dev.wearclaude.watch.data.PendingApproval
import org.junit.Assert.*
import org.junit.Test

class AlertLogicTest {
    private fun agent(id: String, status: String, summary: String? = null) =
        AgentInfo(id, "T-$id", "p", status, summary = summary, createdAt = 0, updatedAt = 0)

    private fun st(
        vararg a: AgentInfo,
        approvals: List<PendingApproval> = emptyList(),
        loaded: Boolean = true,
        reasons: Map<String, String> = emptyMap(),
        needsReload: Boolean = false,
    ) = AgentsState(
        agents = a.associateBy { it.id }, approvals = approvals.associateBy { it.approvalId },
        failReasons = reasons, loaded = loaded, needsReload = needsReload,
    )

    private fun appr(id: String, agent: String = "a") = PendingApproval(id, agent, "Bash", "rm x", 0)

    @Test fun noAlertWhenPrevNotLoaded() {
        assertTrue(alertsFor(st(loaded = false), st(agent("a", "done"))).isEmpty())
    }

    @Test fun noAlertWhenNextNotLoaded() {
        assertTrue(alertsFor(st(agent("a", "running")), st(agent("a", "done"), loaded = false)).isEmpty())
    }

    @Test fun firstLoadedStateDoesNotAlert() {
        val loadedDone = st(agent("a", "done"), approvals = listOf(appr("x")))
        assertTrue(alertsFor(AgentsState(), loadedDone).isEmpty())
    }

    @Test fun runningToDoneAlertsOnceWithSummary() {
        val a = st(agent("a", "running"))
        val b = st(agent("a", "done", "all good"))
        assertEquals(listOf<Alert>(Alert.Done("a", "T-a", "all good")), alertsFor(a, b))
        assertTrue(alertsFor(b, b).isEmpty())
    }

    @Test fun unchangedDoneNoAlert() {
        val s = st(agent("a", "done"))
        assertTrue(alertsFor(s, s.copy(lastSeq = 5)).isEmpty())
    }

    @Test fun failedAlertsWithReason() {
        val r = alertsFor(st(agent("a", "running")), st(agent("a", "failed"), reasons = mapOf("a" to "boom")))
        assertEquals(listOf<Alert>(Alert.Failed("a", "T-a", "boom")), r)
    }

    @Test fun failedWithoutReason() {
        val r = alertsFor(st(agent("a", "running")), st(agent("a", "failed")))
        assertEquals(listOf<Alert>(Alert.Failed("a", "T-a", null)), r)
    }

    @Test fun waitingApprovalToDoneAlerts() {
        assertEquals(1, alertsFor(st(agent("a", "waiting_approval")), st(agent("a", "done"))).size)
    }

    @Test fun doneToRunningNoAlert() {
        assertTrue(alertsFor(st(agent("a", "done")), st(agent("a", "running"))).isEmpty())
    }

    @Test fun alertsForIgnoresApprovals() {
        val prev = st(agent("a", "running"))
        val next = st(agent("a", "running"), approvals = listOf(appr("x")))
        assertTrue(alertsFor(prev, next).isEmpty())
    }

    @Test fun startupApprovalsNotAlerted() {
        val (alerts, set) = approvalAlerts(st(agent("a", "waiting_approval"), approvals = listOf(appr("x"))), null)
        assertTrue(alerts.isEmpty())
        assertEquals(setOf("x"), set)
    }

    @Test fun notLoadedKeepsUnseeded() {
        val (alerts, set) = approvalAlerts(st(loaded = false), null)
        assertTrue(alerts.isEmpty())
        assertNull(set)
    }

    @Test fun newApprovalAlertsOnce() {
        val s1 = st(agent("a", "waiting_approval"), approvals = listOf(appr("x")))
        val (alerts, set) = approvalAlerts(s1, emptySet())
        assertEquals(listOf(Alert.ApprovalNeeded("a", "x", "Bash", "rm x")), alerts)
        assertTrue(approvalAlerts(s1, set).first.isEmpty())
    }

    @Test fun approvalRaisedDuringReloadAlertsOnceAfter() {
        val reset = st(agent("a", "running"), needsReload = true)
        val (a1, s1) = approvalAlerts(reset, emptySet())
        assertTrue(a1.isEmpty())
        val reloaded = st(agent("a", "waiting_approval"), approvals = listOf(appr("x")))
        val (a2, s2) = approvalAlerts(reloaded, s1)
        assertEquals(1, a2.size)
        assertTrue(approvalAlerts(reloaded, s2).first.isEmpty())
    }

    @Test fun alertedApprovalSurvivingResetDoesNotRealert() {
        val live = st(agent("a", "waiting_approval"), approvals = listOf(appr("x")))
        val (_, s1) = approvalAlerts(live, emptySet())
        val (_, s2) = approvalAlerts(st(agent("a", "waiting_approval"), needsReload = true), s1)
        assertEquals(setOf("x"), s2)
        assertTrue(approvalAlerts(live, s2).first.isEmpty())
    }

    @Test fun resolvedApprovalPruned() {
        val live = st(agent("a", "waiting_approval"), approvals = listOf(appr("x")))
        val (_, s1) = approvalAlerts(live, emptySet())
        val (alerts, s2) = approvalAlerts(st(agent("a", "running")), s1)
        assertTrue(alerts.isEmpty())
        assertEquals(emptySet<String>(), s2)
        // a re-raised id alerts again
        assertEquals(1, approvalAlerts(live, s2).first.size)
    }

    private val min = 60_000L

    @Test fun shouldStopLogic() {
        val idle = st(agent("a", "done"))
        val busy = st(agent("a", "running"))
        assertFalse(shouldStop(busy, 0L, 0L, 10_000 * min))
        assertFalse(shouldStop(st(agent("a", "waiting_approval")), 0L, null, 10_000 * min))
        assertFalse(shouldStop(idle, null, null, 10_000 * min))
        // idle 14m59s since last busy: keep; 15 min: stop
        assertFalse(shouldStop(idle, 1_000L, null, 1_000L + 15 * min - 1))
        assertTrue(shouldStop(idle, 1_000L, null, 1_000L + 15 * min))
        // the later of lastBusy and backgroundedAt counts
        assertFalse(shouldStop(idle, 0L, 10 * min, 24 * min + 59_000))
        assertTrue(shouldStop(idle, 0L, 10 * min, 25 * min))
        assertFalse(shouldStop(idle, 20 * min, 5 * min, 34 * min))
        assertTrue(shouldStop(idle, null, 5 * min, 20 * min))
        assertTrue(shouldStop(null, null, null, 0L))
        // visible: keep, however long idle
        assertFalse(shouldStop(idle, 0L, null, 10_000 * min, visible = true))
        assertFalse(shouldStop(idle, 0L, 5 * min, 10_000 * min, visible = true))
    }

    @Test fun nextLastBusy() {
        assertEquals(10L, nextLastBusy(st(agent("a", "running")), 5L, 10L))
        assertEquals(5L, nextLastBusy(st(agent("a", "done")), 5L, 10L))
        assertNull(nextLastBusy(st(agent("a", "done")), null, 10L))
    }

    @Test fun deliveryRouting() {
        val done = Alert.Done("a", "t", null)
        val failed = Alert.Failed("a", "t", null)
        val chat = Alert.ChatFinished("c", "t", "p")
        val appr = Alert.ApprovalNeeded("a", "x", "Bash", "rm")
        for (a in listOf(done, failed, chat)) {
            assertEquals(Delivery.Vibrate, deliveryFor(a, true))
            assertEquals(Delivery.Notify, deliveryFor(a, false))
        }
        assertEquals(Delivery.None, deliveryFor(appr, true))
        assertEquals(Delivery.Notify, deliveryFor(appr, false))
    }

    @Test fun activeCountAndText() {
        val s = st(agent("a", "running"), agent("b", "waiting_approval"), agent("c", "done"))
        assertEquals(2, activeAgentCount(s))
        assertEquals("2 agents running", serviceText(2, 0))
        assertEquals("1 agent running", serviceText(1, 0))
        assertEquals("Watching for finished chats", serviceText(0, 0))
        assertEquals("1 PC chat working", serviceText(0, 1))
        assertEquals("3 PC chats working", serviceText(0, 3))
        assertEquals("2 agents running · 1 PC chat working", serviceText(2, 1))
        assertEquals("1 agent running · 2 PC chats working", serviceText(1, 2))
    }

    @Test fun workingPcChatsKeepServiceAlive() {
        val s = AgentsState(agents = mapOf("a" to agent("a", "done")), chats = mapOf("c" to chat("c", "working")), loaded = true)
        assertEquals(1, workingChatCount(s))
        assertTrue(isBusy(s))
        assertFalse(shouldStop(s, 1_000L, null, 99_999_999L))
        val idle = s.copy(chats = mapOf("c" to chat("c", "idle")))
        assertEquals(0, workingChatCount(idle))
        assertFalse(isBusy(idle))
        assertTrue(shouldStop(idle, 1_000L, null, 1_000L + 15 * min))
        assertTrue(isBusy(st(agent("a", "running"))))
    }

    @Test fun routeFromExtrasValidates() {
        assertEquals("agent/abc", routeFromExtras("agent/abc"))
        assertEquals("approval/x%2F1", routeFromExtras("approval/x%2F1"))
        assertNull(routeFromExtras(null))
        assertNull(routeFromExtras(""))
        assertNull(routeFromExtras("pair"))
        assertNull(routeFromExtras("agent/"))
        assertNull(routeFromExtras("http://evil/agent/x"))
    }

    @Test fun alertContentText() {
        assertEquals("T" to "x".repeat(100), alertContent(Alert.Done("a", "T", "x".repeat(150))))
        assertEquals("T" to "Done", alertContent(Alert.Done("a", "T", null)))
        assertEquals("T" to "boom", alertContent(Alert.Failed("a", "T", "boom")))
        assertEquals("T" to "Failed", alertContent(Alert.Failed("a", "T", null)))
        assertEquals("Approve: Bash" to "rm x", alertContent(Alert.ApprovalNeeded("a", "i", "Bash", "rm x")))
    }

    private fun chat(id: String, status: String, ended: Boolean = status == "idle") =
        dev.wearclaude.watch.data.ChatInfo(id, "C$id", "p", 0, status, ended)
    private fun cst(vararg c: dev.wearclaude.watch.data.ChatInfo, loaded: Boolean = true, needsReload: Boolean = false) =
        AgentsState(chats = c.associateBy { it.id }, loaded = loaded, needsReload = needsReload)

    @Test fun chatFinishedOnWorkingToIdle() {
        val r = chatAlerts(cst(chat("a", "working")), cst(chat("a", "idle")))
        assertEquals(listOf(Alert.ChatFinished("a", "Ca", "p")), r)
    }

    @Test fun chatNoAlertAtStartupOrOtherCases() {
        assertTrue(chatAlerts(cst(loaded = false), cst(chat("a", "idle"))).isEmpty())
        assertTrue(chatAlerts(cst(), cst(chat("a", "idle"))).isEmpty())
        assertTrue(chatAlerts(cst(chat("a", "idle")), cst(chat("a", "working"))).isEmpty())
        assertTrue(chatAlerts(cst(chat("a", "working"), needsReload = true), cst(chat("a", "idle"))).isEmpty())
        assertTrue(chatAlerts(cst(chat("a", "working")), cst(chat("a", "idle"), needsReload = true)).isEmpty())
        assertTrue(chatAlerts(cst(chat("a", "working")), cst(chat("a", "idle"), loaded = false)).isEmpty())
    }

    @Test fun chatTimeoutIdleIsNotAFinish() {
        assertTrue(chatAlerts(cst(chat("a", "working")), cst(chat("a", "idle", ended = false))).isEmpty())
    }

    @Test fun routeFromExtrasAcceptsChat() {
        assertEquals("chat/abc", routeFromExtras("chat/abc"))
        assertNull(routeFromExtras("chat/"))
    }
}
