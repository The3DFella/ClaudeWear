package dev.wearclaude.watch.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns [AgentsState]. Resume protocol: reload (GET /agents) first, which sets lastSeq/bootId from the
 * snapshot, then stream from that seq. Stream events at or below lastSeq are dropped (already in the
 * snapshot). A hello with a different boot id (bridge restarted) or a reset the snapshot does not cover
 * triggers a reload. Reloads and event application are serialized by [mutex], and the stream is not
 * consumed while a reload runs, so events that arrive meanwhile are applied after the snapshot.
 */
class AgentRepository(
    private val api: BridgeApiContract,
    private val stream: EventSource,
    scope: CoroutineScope,
    private val retryDelaysMs: List<Long> = listOf(2_000, 5_000, 10_000, 30_000),
) {
    private val scope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val _state = MutableStateFlow(AgentsState())
    val state: StateFlow<AgentsState> = _state.asStateFlow()
    private val mutex = Mutex()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                if (!reloadWithRetry()) return@launch
                stream.events { _state.value.lastSeq }.collect { e -> onStreamEvent(e) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: AuthFailedException) {
                markAuthFailed()
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
    }

    /** Called when any bridge call answers 401: the device was revoked. */
    fun markAuthFailed() {
        _state.update { it.copy(authFailed = true) }
    }

    private suspend fun onStreamEvent(e: WatchEvent) {
        when (e) {
            is WatchEvent.Hello -> if (e.bootId == null || e.bootId != _state.value.bootId) reloadNow()
            is WatchEvent.Reset -> {
                val s = _state.value
                val covered = e.bootId != null && e.bootId == s.bootId && e.seq <= s.lastSeq
                if (!covered) reloadNow()
            }
            else -> mutex.withLock {
                _state.update { AgentsReducer.reduce(it, e).let { n -> if (n.error != null) n.copy(error = null) else n } }
            }
        }
    }

    private suspend fun reloadNow() {
        _state.update { it.copy(needsReload = true) }
        reloadWithRetry()
    }

    /** Retries with backoff until a reload succeeds (true) or the bridge answers 401 (false). */
    private suspend fun reloadWithRetry(): Boolean {
        var attempt = 0
        while (true) {
            try {
                reload()
                return true
            } catch (e: CancellationException) {
                throw e
            } catch (e: BridgeException) {
                if (e.code == 401) { markAuthFailed(); return false }
                _state.update { it.copy(error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.javaClass.simpleName) }
            }
            delay(retryDelaysMs[minOf(attempt, retryDelaysMs.size - 1)])
            attempt++
        }
    }

    private suspend fun reload() = mutex.withLock {
        val snap = api.agents()
        val details = snap.agents.filter { it.status == "waiting_approval" }.map { api.agent(it.id) }
        val chatList: List<ChatInfo>? = try {
            api.chats()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BridgeException) {
            if (e.code == 401) throw e
            null
        } catch (e: Exception) {
            null
        }
        _state.update { s ->
            var n = s.copy(
                agents = snap.agents.associateBy { it.id },
                steps = emptyMap(), stepDepths = emptyMap(), stepSeq = emptyMap(), failReasons = emptyMap(), approvals = emptyMap(),
                lastSeq = snap.seq, bootId = snap.bootId,
                needsReload = false, loaded = true, error = null,
            )
            if (chatList != null) {
                val byId = chatList.associateBy { it.id }
                n = n.copy(chats = byId, chatItems = n.chatItems.filterKeys { it in byId })
            }
            for (d in details) n = merge(n, d)
            n
        }
    }

    suspend fun loadDetail(id: String) {
        val d = try {
            api.agent(id)
        } catch (e: BridgeException) {
            if (e.code == 401) markAuthFailed()
            throw e
        }
        mutex.withLock { _state.update { merge(it, d) } }
    }

    suspend fun loadChat(id: String) {
        val d = try {
            api.chat(id)
        } catch (e: BridgeException) {
            if (e.code == 401) markAuthFailed()
            throw e
        }
        mutex.withLock {
            _state.update {
                it.copy(
                    chats = it.chats + (d.chat.id to d.chat),
                    chatItems = it.chatItems + (d.chat.id to d.items.takeLast(MAX_CHAT_ITEMS)),
                )
            }
        }
    }

    private fun merge(s: AgentsState, d: AgentDetail): AgentsState {
        val id = d.agent.id
        val stepEvents = d.events.filterIsInstance<WatchEvent.Step>()
        val restSeq = stepEvents.maxOfOrNull { it.seq } ?: 0L
        val useRest = restSeq >= (s.stepSeq[id] ?: 0L)
        val restReason = d.events.filterIsInstance<WatchEvent.Status>().lastOrNull { it.status == "failed" }?.reason
        val reasons = when {
            d.agent.status != "failed" -> s.failReasons - id
            restReason != null -> s.failReasons + (id to restReason)
            else -> s.failReasons
        }
        return s.copy(
            failReasons = reasons,
            agents = s.agents + (id to d.agent),
            steps = if (useRest) s.steps + (id to stepEvents.map { it.text }.takeLast(MAX_STEPS_PER_AGENT)) else s.steps,
            stepDepths = if (useRest) s.stepDepths + (id to stepEvents.map { it.depth }.takeLast(MAX_STEPS_PER_AGENT)) else s.stepDepths,
            stepSeq = if (useRest) s.stepSeq + (id to restSeq) else s.stepSeq,
            approvals = s.approvals.filterValues { it.agentId != id } + d.approvals.associateBy { it.approvalId },
        )
    }
}
