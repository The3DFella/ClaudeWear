package dev.wearclaude.watch.ui

import dev.wearclaude.watch.data.ChatInfo
import dev.wearclaude.watch.data.ChatItem

enum class ChatLineStyle { Prompt, Reply, Step }

data class ChatLine(val text: String, val style: ChatLineStyle)

const val CHAT_FOOTER = "Read-only — reply from your PC or the Claude phone app"

fun canReplyToChat(chat: ChatInfo?): Boolean = chat?.titled == true

fun chatFooter(chat: ChatInfo?): String = if (canReplyToChat(chat)) CHAT_REPLY_FOOTER else CHAT_FOOTER

fun chatLines(items: List<ChatItem>): List<ChatLine> = items.map {
    when (it.kind) {
        "prompt" -> ChatLine("You: ${it.text}", ChatLineStyle.Prompt)
        "step" -> ChatLine("↳ ${it.text}", ChatLineStyle.Step)
        else -> ChatLine(it.text, ChatLineStyle.Reply)
    }
}
