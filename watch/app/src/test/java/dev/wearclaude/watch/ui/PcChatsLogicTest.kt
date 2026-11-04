package dev.wearclaude.watch.ui

import dev.wearclaude.watch.data.*
import org.junit.Assert.*
import org.junit.Test

class PcChatsLogicTest {
    private fun chat(id: String, project: String, t: Long, status: String = "idle", archived: Boolean = false) =
        ChatInfo(id, "T$id", project, t, status, archived = archived)

    private val mixed = AgentsState(chats = listOf(
        chat("1", "A", 100), chat("2", "B", 300), chat("3", "A", 200), chat("4", "B", 50),
        chat("5", "C", 900, archived = true), chat("6", "A", 10, archived = true),
    ).associateBy { it.id })

    @Test fun activeGroupsOrderedByNewestChatAndChatsByActivity() {
        val g = activeChatGroups(mixed)
        assertEquals(listOf("B", "A"), g.map { it.projectName })
        assertEquals(listOf("2", "4"), g[0].chats.map { it.id })
        assertEquals(listOf("3", "1"), g[1].chats.map { it.id })
    }

    @Test fun archivedGroupsAndCount() {
        assertEquals(2, archivedCount(mixed))
        val g = archivedChatGroups(mixed)
        assertEquals(listOf("C", "A"), g.map { it.projectName })
        assertEquals(listOf("6"), g[1].chats.map { it.id })
    }

    @Test fun noChatsNoGroups() {
        assertTrue(activeChatGroups(AgentsState()).isEmpty())
        assertTrue(archivedChatGroups(AgentsState()).isEmpty())
        assertEquals(0, archivedCount(AgentsState()))
    }

    @Test fun onlyArchivedLeavesActiveEmpty() {
        val s = AgentsState(chats = mapOf("x" to chat("x", "A", 1, archived = true)))
        assertTrue(activeChatGroups(s).isEmpty())
        assertEquals(1, archivedCount(s))
    }

    @Test fun relativeTimeBuckets() {
        val now = 10_000_000_000L
        assertEquals("now", relativeTime(now, now - 59_000))
        assertEquals("now", relativeTime(now, now + 5_000))
        assertEquals("1m ago", relativeTime(now, now - 60_000))
        assertEquals("59m ago", relativeTime(now, now - 59 * 60_000L))
        assertEquals("1h ago", relativeTime(now, now - 60 * 60_000L))
        assertEquals("23h ago", relativeTime(now, now - 23 * 3_600_000L))
        assertEquals("1d ago", relativeTime(now, now - 24 * 3_600_000L))
        assertEquals("3d ago", relativeTime(now, now - 3 * 86_400_000L))
    }

    @Test fun chatLinesPrefixes() {
        val lines = chatLines(listOf(
            ChatItem("prompt", "hi", 1), ChatItem("step", "Bash", 2), ChatItem("reply", "done", 3),
        ))
        assertEquals(listOf("You: hi", "↳ Bash", "done"), lines.map { it.text })
        assertEquals(listOf(ChatLineStyle.Prompt, ChatLineStyle.Step, ChatLineStyle.Reply), lines.map { it.style })
    }

    @Test fun replyOnlyWhenTitled() {
        val c = ChatInfo("a", "T", "P", 1, "idle")
        assertFalse(canReplyToChat(null))
        assertFalse(canReplyToChat(c))
        assertTrue(canReplyToChat(c.copy(titled = true)))
        assertEquals(CHAT_FOOTER, chatFooter(c))
        assertEquals("Replies go to the open desktop chat as a message from your watch", chatFooter(c.copy(titled = true)))
    }

    @Test fun chatSendErrors() {
        fun m(code: Int, e: String) = chatSendErrorMessage(dev.wearclaude.watch.data.BridgeException(code, e))
        assertEquals("Open this chat in the Claude desktop app, then try again", m(409, "not_live"))
        assertEquals("Several open chats have this title — rename one on your PC", m(409, "ambiguous"))
        assertEquals("This chat has no title yet", m(409, "untitled"))
        assertEquals("Still sending your last message", m(429, "busy"))
        assertEquals("Couldn't deliver (502)", m(502, "relay_failed"))
        assertEquals("Can't reach your PC", chatSendErrorMessage(java.io.IOException("x")))
    }
}
