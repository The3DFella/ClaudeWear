package dev.wearclaude.watch.ui

import dev.wearclaude.watch.data.AgentsState

enum class StatusColorRole { Primary, Amber, Green, Error, Muted, Outline }

fun statusColorRole(status: String): StatusColorRole = when (status) {
    "running" -> StatusColorRole.Primary
    "waiting_approval" -> StatusColorRole.Amber
    "done" -> StatusColorRole.Green
    "failed" -> StatusColorRole.Error
    "stopped", "interrupted" -> StatusColorRole.Muted
    else -> StatusColorRole.Outline
}

data class HomeUi(
    val pendingApprovals: Int,
    val firstApprovalAgentId: String?,
    val firstApprovalId: String?,
    val reconnecting: Boolean,
)

data class ProjectGroup(val projectName: String, val chats: List<dev.wearclaude.watch.data.ChatInfo>)

private fun groupChats(chats: List<dev.wearclaude.watch.data.ChatInfo>): List<ProjectGroup> =
    chats.groupBy { it.projectName }
        .map { (name, cs) -> ProjectGroup(name, cs.sortedByDescending { it.lastActivity }) }
        .sortedByDescending { it.chats.first().lastActivity }

/** Non-archived PC chats by project; chats newest first, groups ordered by their newest chat. */
fun activeChatGroups(state: AgentsState): List<ProjectGroup> =
    groupChats(state.chats.values.filter { !it.archived })

fun archivedCount(state: AgentsState): Int = state.chats.values.count { it.archived }

/** Archived PC chats grouped the same way as [activeChatGroups]. */
fun archivedChatGroups(state: AgentsState): List<ProjectGroup> =
    groupChats(state.chats.values.filter { it.archived })

fun relativeTime(nowMs: Long, thenMs: Long): String {
    val mins = (nowMs - thenMs) / 60_000
    return when {
        mins < 1 -> "now"
        mins < 60 -> "${mins}m ago"
        mins < 24 * 60 -> "${mins / 60}h ago"
        else -> "${mins / (24 * 60)}d ago"
    }
}

const val EMPTY_ACTIVE_COPY = "No active chats on your PC"
const val RECONNECTING_COPY = "Reconnecting…"

fun homeItems(state: AgentsState): HomeUi {
    val first = state.approvals.values.minByOrNull { it.expiresAt }
    return HomeUi(
        pendingApprovals = state.approvals.size,
        firstApprovalAgentId = first?.agentId,
        firstApprovalId = first?.approvalId,
        reconnecting = state.error != null,
    )
}
