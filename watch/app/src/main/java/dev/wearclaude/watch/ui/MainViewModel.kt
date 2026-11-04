package dev.wearclaude.watch.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.wearclaude.watch.WearClaudeApp
import dev.wearclaude.watch.data.AgentCommands
import dev.wearclaude.watch.data.AgentRepository
import kotlinx.coroutines.flow.StateFlow

/** Thin facade over the application-scoped [AppController]. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val controller = (app as WearClaudeApp).controller

    val paired: StateFlow<Boolean?> = controller.paired
    val repo: StateFlow<AgentRepository?> = controller.repo
    val pairing: StateFlow<PairUiState> = controller.pairing
    val commands: StateFlow<AgentCommands?> = controller.commands
    suspend fun lastProjectId(): String? = controller.lastProjectId()
    fun saveLastProject(id: String) = controller.saveLastProject(id)
    fun pair(url: String, code: String) = controller.pair(url, code)
    fun unpair() = controller.unpair()
}
