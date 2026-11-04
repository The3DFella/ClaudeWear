package dev.wearclaude.watch.ui

import dev.wearclaude.watch.data.AgentsState
import dev.wearclaude.watch.data.PendingApproval

data class DetailStep(val text: String, val indented: Boolean)

data class DetailUi(
    val title: String,
    val status: String,
    val steps: List<DetailStep>,
    val summary: String?,
    val reason: String?,
    val canReply: Boolean,
    val canStop: Boolean,
    val pendingApprovalId: String?,
)

private val REPLY_STATUSES = setOf("done", "failed", "stopped", "interrupted")
private val STOP_STATUSES = setOf("running", "waiting_approval")

fun statusLabel(status: String): String = when (status) {
    "waiting_approval" -> "Waiting for approval"
    else -> status.replaceFirstChar { it.uppercase() }
}

fun detailUi(state: AgentsState, id: String): DetailUi? {
    val a = state.agents[id] ?: return null
    val depths = state.stepDepths[id].orEmpty()
    val steps = state.steps[id].orEmpty().mapIndexed { i, t ->
        if ((depths.getOrNull(i) ?: 0) >= 1) DetailStep("↳ $t", true) else DetailStep(t, false)
    }
    return DetailUi(
        title = a.title,
        status = a.status,
        steps = steps,
        summary = a.summary,
        reason = if (a.status == "failed") state.failReasons[id] else null,
        canReply = a.status in REPLY_STATUSES,
        canStop = a.status in STOP_STATUSES,
        pendingApprovalId = state.approvals.values.filter { it.agentId == id }.minByOrNull { it.expiresAt }?.approvalId,
    )
}

fun countdownText(expiresAt: Long, now: Long): String {
    val remaining = expiresAt - now
    if (remaining <= 0) return "Expired"
    val secs = remaining / 1000
    return "Auto-deny in ${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
}

fun newApprovalIds(previousSeen: Set<String>, current: Set<String>): Set<String> = current - previousSeen

const val APPROVAL_LOAD_TIMEOUT_MS = 10_000L

enum class ApprovalMode { Loading, Show, Resolved }

fun approvalScreenMode(loaded: Boolean, seen: PendingApproval?, current: PendingApproval?, elapsedMs: Long): ApprovalMode = when {
    current != null -> ApprovalMode.Show
    seen != null -> ApprovalMode.Resolved
    loaded -> ApprovalMode.Resolved
    elapsedMs >= APPROVAL_LOAD_TIMEOUT_MS -> ApprovalMode.Resolved
    else -> ApprovalMode.Loading
}

data class AutoOpen(val seeded: Set<String>?, val fresh: Set<String>)

/** Seeds only from the first loaded state; afterwards reports ids not seen before. */
fun autoOpenIds(seeded: Set<String>?, loaded: Boolean, current: Set<String>): AutoOpen = when {
    !loaded -> AutoOpen(seeded, emptySet())
    seeded == null -> AutoOpen(current, emptySet())
    else -> AutoOpen(seeded + current, newApprovalIds(seeded, current))
}
