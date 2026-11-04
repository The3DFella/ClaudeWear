package dev.wearclaude.watch.service

import dev.wearclaude.watch.data.AgentsState

sealed interface Alert {
    data class Done(val agentId: String, val title: String, val summary: String?) : Alert
    data class Failed(val agentId: String, val title: String, val reason: String?) : Alert
    data class ChatFinished(val chatId: String, val title: String, val projectName: String) : Alert
    data class ApprovalNeeded(val agentId: String, val approvalId: String, val tool: String, val summary: String) : Alert
}

const val IDLE_STOP_MS = 60_000L
const val ALERT_SUMMARY_MAX = 100

private fun isActive(status: String) = status == "running" || status == "waiting_approval"

fun activeAgentCount(s: AgentsState): Int = s.agents.values.count { isActive(it.status) }

fun workingChatCount(s: AgentsState): Int = s.chats.values.count { it.status == "working" }

/** True while a watch agent is active or a PC chat is working; keeps the service (and its finish buzzes) alive. */
fun isBusy(s: AgentsState): Boolean = activeAgentCount(s) > 0 || workingChatCount(s) > 0

/** Ongoing notification text, e.g. "2 agents running · 1 PC chat working"; zero parts are omitted. */
fun serviceText(agents: Int, chats: Int): String {
    val parts = buildList {
        if (agents > 0) add(if (agents == 1) "1 agent running" else "$agents agents running")
        if (chats > 0) add(if (chats == 1) "1 PC chat working" else "$chats PC chats working")
    }
    return if (parts.isEmpty()) "Connected" else parts.joinToString(" · ")
}

/** Done/failed alerts from a state transition; nothing until both sides are loaded. Approvals: see [approvalAlerts]. */
fun alertsFor(prev: AgentsState, next: AgentsState): List<Alert> {
    if (!prev.loaded || !next.loaded) return emptyList()
    val out = mutableListOf<Alert>()
    for (a in next.agents.values) {
        val before = prev.agents[a.id]?.status
        if (before == a.status) continue
        when (a.status) {
            "done" -> out += Alert.Done(a.id, a.title, a.summary)
            "failed" -> out += Alert.Failed(a.id, a.title, next.failReasons[a.id])
        }
    }
    return out
}

/**
 * PC chat finished alerts: working -> idle with the turn ended, between two loaded, settled states.
 * An idle from a no-output timeout (ended false) never fires; nor does a chat absent in [prev].
 */
fun chatAlerts(prev: AgentsState, next: AgentsState): List<Alert.ChatFinished> {
    if (!prev.loaded || !next.loaded || prev.needsReload || next.needsReload) return emptyList()
    return next.chats.values.filter { prev.chats[it.id]?.status == "working" && it.status == "idle" && it.ended }
        .map { Alert.ChatFinished(it.id, it.title, it.projectName) }
}

/** When the idle period began: null while busy, otherwise the earliest idle timestamp seen. */
fun nextIdleSince(state: AgentsState, idleSinceMs: Long?, nowMs: Long): Long? =
    if (isBusy(state)) null else idleSinceMs ?: nowMs

/** [state] null means unpaired. */
fun shouldStop(state: AgentsState?, idleSinceMs: Long?, nowMs: Long): Boolean {
    if (state == null) return true
    if (isBusy(state)) return false
    return idleSinceMs != null && nowMs - idleSinceMs >= IDLE_STOP_MS
}

fun alertContent(a: Alert): Pair<String, String> = when (a) {
    is Alert.Done -> a.title to (a.summary?.take(ALERT_SUMMARY_MAX)?.takeIf { it.isNotBlank() } ?: "Done")
    is Alert.Failed -> a.title to (a.reason?.takeIf { it.isNotBlank() } ?: "Failed")
    is Alert.ChatFinished -> "${a.title} finished" to a.projectName
    is Alert.ApprovalNeeded -> "Approve: ${a.tool}" to a.summary
}

/** Accepts only in-app deep links; anything else is ignored. */
fun routeFromExtras(route: String?): String? {
    if (route == null) return null
    val ok = (route.startsWith("agent/") && route.length > 6) || (route.startsWith("approval/") && route.length > 9) ||
        (route.startsWith("chat/") && route.length > 5)
    return if (ok) route else null
}

/**
 * Approval alerts. [alerted] is the set of approval ids already handled (null before the first loaded state,
 * which seeds it silently). Nothing changes while not loaded or mid-reload. Returns the new alerts and the
 * updated set, pruned to ids still pending.
 */
fun approvalAlerts(next: AgentsState, alerted: Set<String>?): Pair<List<Alert.ApprovalNeeded>, Set<String>?> {
    if (!next.loaded || next.needsReload) return emptyList<Alert.ApprovalNeeded>() to alerted
    val ids = next.approvals.keys
    if (alerted == null) return emptyList<Alert.ApprovalNeeded>() to ids.toSet()
    val fresh = next.approvals.values.filter { it.approvalId !in alerted }
    val alerts = fresh.map { Alert.ApprovalNeeded(it.agentId, it.approvalId, it.tool, it.summary) }
    return alerts to ((alerted + fresh.map { it.approvalId }) intersect ids)
}
