package dev.wearclaude.watch.ui

import dev.wearclaude.watch.data.AgentCommands
import dev.wearclaude.watch.data.BridgeException
import dev.wearclaude.watch.data.ProjectInfo
import java.io.IOException

const val PROJECTS_RETRY_COPY = "Can't load projects — tap to retry"
const val NO_PROJECTS_COPY = "No projects — add one on your PC with `wearclaude-bridge add-project`"

/** Index of the default project: lastProjectId if present, else 0 (or -1 when empty). */
fun defaultProjectIndex(projects: List<ProjectInfo>, lastProjectId: String?): Int {
    if (projects.isEmpty()) return -1
    val i = projects.indexOfFirst { it.id == lastProjectId }
    return if (i >= 0) i else 0
}

fun nextProjectIndex(current: Int, size: Int): Int = if (size <= 0) -1 else (current + 1) % size

fun sendErrorMessage(e: Throwable): String = when {
    e is BridgeException && e.code == 409 -> "Agent is still working"
    e is BridgeException -> "Couldn't send (${e.code})"
    e is IOException -> "Can't reach your PC"
    else -> "Couldn't send (${e::class.simpleName ?: "error"})"
}

const val CHAT_SENDING_COPY = "Sending to your PC…"
const val CHAT_REPLY_FOOTER = "Replies go to the open desktop chat as a message from your watch"

fun chatSendErrorMessage(t: Throwable): String = when {
    t is BridgeException && t.code == 409 && t.message == "not_live" -> "Open this chat in the Claude desktop app, then try again"
    t is BridgeException && t.code == 409 && t.message == "ambiguous" -> "Several open chats have this title — rename one on your PC"
    t is BridgeException && t.code == 409 && t.message == "untitled" -> "This chat has no title yet"
    t is BridgeException && t.code == 429 -> "Still sending your last message"
    t is BridgeException -> "Couldn't deliver (${t.code})"
    t is IOException -> "Can't reach your PC"
    else -> "Couldn't deliver (${t::class.simpleName ?: "error"})"
}

/** Relays a dictated reply into the live desktop chat [chatId]. */
suspend fun submitChatReply(commands: AgentCommands, chatId: String, prompt: String): SendResult =
    try {
        commands.sendToChat(chatId, prompt)
        SendResult.Ok(chatId)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        SendResult.Failed(chatSendErrorMessage(e))
    }

sealed interface SendResult {
    data class Ok(val agentId: String) : SendResult
    data class Failed(val message: String) : SendResult
}

/** replyTo == null creates a new agent in [projectId]; otherwise sends a follow-up. */
suspend fun submitPrompt(commands: AgentCommands, replyTo: String?, projectId: String?, prompt: String): SendResult =
    try {
        if (replyTo != null) {
            commands.followUp(replyTo, prompt)
            SendResult.Ok(replyTo)
        } else {
            SendResult.Ok(commands.createAgent(requireNotNull(projectId), prompt))
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        SendResult.Failed(sendErrorMessage(e))
    }
