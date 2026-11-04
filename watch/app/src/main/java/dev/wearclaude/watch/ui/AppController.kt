package dev.wearclaude.watch.ui

import dev.wearclaude.watch.data.AgentCommands
import dev.wearclaude.watch.data.AgentRepository
import dev.wearclaude.watch.data.BridgeException
import dev.wearclaude.watch.data.ProjectInfo
import dev.wearclaude.watch.data.SettingsStore
import dev.wearclaude.watch.data.normalizeBridgeUrl
import dev.wearclaude.watch.data.pairErrorMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface PairUiState {
    data object Idle : PairUiState
    data object Loading : PairUiState
    data class Error(val message: String) : PairUiState
}

const val REMOVED_MESSAGE = "This watch was removed — pair again"

/** Plain-Kotlin core of MainViewModel so it can be unit-tested on the JVM. */
class AppController(
    private val store: SettingsStore,
    private val pairer: suspend (baseUrl: String, code: String, deviceName: String) -> String,
    private val repoFactory: (baseUrl: String, token: String) -> AgentRepository,
    private val scope: CoroutineScope,
    private val deviceName: String,
    private val commandsFactory: (baseUrl: String, token: String) -> AgentCommands? = { _, _ -> null },
) {
    private val _paired = MutableStateFlow<Boolean?>(null)
    val paired: StateFlow<Boolean?> = _paired.asStateFlow()
    private val _repo = MutableStateFlow<AgentRepository?>(null)
    val repo: StateFlow<AgentRepository?> = _repo.asStateFlow()
    private val _commands = MutableStateFlow<AgentCommands?>(null)
    val commands: StateFlow<AgentCommands?> = _commands.asStateFlow()
    private val _pairing = MutableStateFlow<PairUiState>(PairUiState.Idle)
    val pairing: StateFlow<PairUiState> = _pairing.asStateFlow()
    private var authJob: Job? = null
    private var pairJob: Job? = null
    private var clearJob: Job? = null

    init {
        scope.launch {
            val creds = try {
                store.bridgeUrl.first() to store.token()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                null to null
            }
            val (url, token) = creds
            if (!url.isNullOrBlank() && !token.isNullOrBlank()) activate(url, token) else _paired.value = false
        }
    }

    private fun activate(url: String, token: String) {
        _repo.value?.stop()
        val r = repoFactory(url, token)
        _repo.value = r
        _commands.value = commandsFactory(url, token)?.let { AuthGuardedCommands(it, r::markAuthFailed) }
        authJob?.cancel()
        authJob = scope.launch {
            r.state.first { it.authFailed }
            unpair()
            _pairing.value = PairUiState.Error(REMOVED_MESSAGE)
        }
        _paired.value = true
        r.start()
    }

    fun pair(url: String, code: String) {
        if (_pairing.value == PairUiState.Loading) return
        _pairing.value = PairUiState.Loading
        pairJob = scope.launch {
            try {
                clearJob?.join()
                val base = normalizeBridgeUrl(url)
                val token = pairer(base, code.trim(), deviceName)
                store.setPaired(base, token)
                activate(base, token)
                _pairing.value = PairUiState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                _pairing.value = PairUiState.Error(pairErrorMessage(e))
            }
        }
    }

    suspend fun lastProjectId(): String? = try { store.lastProjectId.first() } catch (e: CancellationException) { throw e } catch (e: Throwable) { null }

    fun saveLastProject(id: String) {
        scope.launch { try { store.setLastProjectId(id) } catch (e: CancellationException) { throw e } catch (_: Throwable) {} }
    }

    fun unpair() {
        pairJob?.cancel()
        pairJob = null
        authJob?.cancel()
        authJob = null
        _repo.value?.stop()
        _repo.value = null
        _commands.value = null
        _paired.value = false
        _pairing.value = PairUiState.Idle
        val prev = clearJob
        clearJob = scope.launch { prev?.join(); store.clear() }
    }
}

/** Reports a 401 from any command to [onAuthFailed] (which unpairs) before rethrowing it. */
private class AuthGuardedCommands(private val d: AgentCommands, private val onAuthFailed: () -> Unit) : AgentCommands {
    private suspend fun <T> guard(block: suspend () -> T): T = try {
        block()
    } catch (e: BridgeException) {
        if (e.code == 401) onAuthFailed()
        throw e
    }

    override suspend fun projects(): List<ProjectInfo> = guard { d.projects() }
    override suspend fun createAgent(projectId: String, prompt: String): String = guard { d.createAgent(projectId, prompt) }
    override suspend fun followUp(id: String, prompt: String) = guard { d.followUp(id, prompt) }
    override suspend fun stop(id: String) = guard { d.stop(id) }
    override suspend fun decide(approvalId: String, allow: Boolean) = guard { d.decide(approvalId, allow) }
    override suspend fun sendToChat(id: String, prompt: String) = guard { d.sendToChat(id, prompt) }
}
