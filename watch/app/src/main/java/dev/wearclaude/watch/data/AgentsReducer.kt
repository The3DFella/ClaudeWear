package dev.wearclaude.watch.data

const val MAX_STEPS_PER_AGENT = 50
const val MAX_CHAT_ITEMS = 60
/** A chat_item matching one of this many latest items (same kind and text) is a replay of a loaded item. */
const val CHAT_DEDUP_WINDOW = 5

data class AgentsState(
    val agents: Map<String, AgentInfo> = emptyMap(),
    val steps: Map<String, List<String>> = emptyMap(),
    /** Nesting depth per step, parallel to [steps]. */
    val stepDepths: Map<String, List<Int>> = emptyMap(),
    /** Failure reason per agent, set when a failed status carries one. */
    val failReasons: Map<String, String> = emptyMap(),
    val approvals: Map<String, PendingApproval> = emptyMap(),
    /** PC chats keyed by session id (the "pc:" prefix stripped). */
    val chats: Map<String, ChatInfo> = emptyMap(),
    val chatItems: Map<String, List<ChatItem>> = emptyMap(),
    /** Seq the state is current up to; stream events with seq <= this are already applied. */
    val lastSeq: Long = 0,
    /** Boot id of the bridge the state was loaded from (null until the first reload). */
    val bootId: String? = null,
    /** True while a reload triggered by a hello/reset is pending. */
    val needsReload: Boolean = false,
    /** Last applied step seq per agent; guards against duplicate steps on backlog replay. */
    val stepSeq: Map<String, Long> = emptyMap(),
    val error: String? = null,
    val authFailed: Boolean = false,
    /** True once the repository has completed its first successful reload. */
    val loaded: Boolean = false,
)

object AgentsReducer {
    /** Applies one stream event. Control messages (hello/reset) and anything at or below lastSeq are ignored. */
    fun reduce(s: AgentsState, e: WatchEvent): AgentsState {
        if (e is WatchEvent.Reset || e is WatchEvent.Hello) return s
        if (e.seq <= s.lastSeq) return s
        return apply(s, e).copy(lastSeq = e.seq)
    }

    private fun chatId(agentId: String) = agentId.removePrefix("pc:")

    private fun touch(s: AgentsState, e: WatchEvent, f: (AgentInfo) -> AgentInfo): AgentsState {
        val a = s.agents[e.agentId] ?: return s
        return s.copy(agents = s.agents + (a.id to f(a).copy(updatedAt = e.ts)))
    }

    private fun apply(s: AgentsState, e: WatchEvent): AgentsState = when (e) {
        is WatchEvent.AgentCreated ->
            if (s.agents.containsKey(e.agentId)) s
            else s.copy(agents = s.agents + (e.agentId to AgentInfo(
                id = e.agentId, title = e.title, projectId = e.projectId, status = "running",
                createdAt = e.ts, updatedAt = e.ts)))
        is WatchEvent.Status -> touch(s, e) { it.copy(status = e.status) }.let { n ->
            val reason = e.reason
            n.copy(failReasons = if (e.status == "failed" && reason != null) n.failReasons + (e.agentId to reason) else n.failReasons - e.agentId)
        }
        is WatchEvent.Summary -> touch(s, e) { it.copy(summary = e.text) }
        is WatchEvent.Step -> if (e.seq <= (s.stepSeq[e.agentId] ?: 0L)) s else {
            val steps = (s.steps[e.agentId].orEmpty() + e.text).takeLast(MAX_STEPS_PER_AGENT)
            val depths = (s.stepDepths[e.agentId].orEmpty() + e.depth).takeLast(MAX_STEPS_PER_AGENT)
            touch(s.copy(steps = s.steps + (e.agentId to steps), stepDepths = s.stepDepths + (e.agentId to depths), stepSeq = s.stepSeq + (e.agentId to e.seq)), e) { it.copy(lastStep = e.text) }
        }
        is WatchEvent.ApprovalRequested -> s.copy(approvals = s.approvals + (e.approvalId to
            PendingApproval(e.approvalId, e.agentId, e.tool, e.summary, e.expiresAt)))
        is WatchEvent.ApprovalResolved -> s.copy(approvals = s.approvals - e.approvalId)
        is WatchEvent.ChatStatusEvent -> {
            val id = chatId(e.agentId)
            val old = s.chats[id]
            s.copy(chats = s.chats + (id to ChatInfo(
                id = id, title = e.title, projectName = e.projectName,
                lastActivity = maxOf(old?.lastActivity ?: 0L, e.ts), status = e.status, ended = e.ended,
                titled = old?.titled ?: false, archived = e.archived)))
        }
        is WatchEvent.ChatItemEvent -> {
            val id = chatId(e.agentId)
            val existing = s.chatItems[id].orEmpty()
            // Opening a chat loads its items over REST; the stream may then replay the same lines.
            if (existing.takeLast(CHAT_DEDUP_WINDOW).any { it.kind == e.kind && it.text == e.text }) s
            else {
                val items = (existing + ChatItem(e.kind, e.text, e.ts)).takeLast(MAX_CHAT_ITEMS)
                val old = s.chats[id]
                s.copy(
                    chatItems = s.chatItems + (id to items),
                    chats = if (old == null) s.chats else s.chats + (id to old.copy(lastActivity = maxOf(old.lastActivity, e.ts))),
                )
            }
        }
        is WatchEvent.Message, is WatchEvent.Unknown, is WatchEvent.Reset, is WatchEvent.Hello -> s
    }
}
