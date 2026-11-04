package dev.wearclaude.watch.service

import dev.wearclaude.watch.data.AgentCommands

/** Calls [onStarted] after createAgent/followUp/sendToChat succeed, so the watch service can start. */
class StartingCommands(private val d: AgentCommands, private val onStarted: () -> Unit) : AgentCommands by d {
    override suspend fun createAgent(projectId: String, prompt: String): String =
        d.createAgent(projectId, prompt).also { onStarted() }

    override suspend fun followUp(id: String, prompt: String) {
        d.followUp(id, prompt)
        onStarted()
    }

    override suspend fun sendToChat(id: String, prompt: String) {
        d.sendToChat(id, prompt)
        onStarted()
    }
}
