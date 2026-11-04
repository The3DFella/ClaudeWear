package dev.wearclaude.watch.data

/** The slice of the bridge API that screens use to send prompts. */
interface AgentCommands {
    suspend fun projects(): List<ProjectInfo>
    suspend fun createAgent(projectId: String, prompt: String): String
    suspend fun followUp(id: String, prompt: String)
    suspend fun stop(id: String)
    suspend fun decide(approvalId: String, allow: Boolean)
    /** Relays [prompt] into the live desktop chat [id]; can take up to ~90 s. */
    suspend fun sendToChat(id: String, prompt: String)
}
