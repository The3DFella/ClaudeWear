package dev.wearclaude.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.wearclaude.watch.data.AgentRepository
import dev.wearclaude.watch.data.AgentsState
import kotlinx.coroutines.CancellationException

@Composable
fun ChatScreen(id: String, state: AgentsState, repo: AgentRepository?, onReply: () -> Unit = {}) {
    val chat = state.chats[id]
    val lines = chatLines(state.chatItems[id].orEmpty())
    var loadError by remember { mutableStateOf(false) }
    val listState = rememberTransformingLazyColumnState()

    LaunchedEffect(id, repo) {
        loadError = false
        try {
            repo?.loadChat(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = true
        }
    }

    // Items before the lines: title, project, plus optional load error.
    val leading = 2 + (if (loadError) 1 else 0)
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(leading + lines.size - 1)
    }

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Top),
        ) {
            item {
                Text(chat?.title ?: "Chat", style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            item {
                Text(chat?.projectName.orEmpty(), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            if (loadError) {
                item { Text("Couldn't load chat", style = MaterialTheme.typography.bodyExtraSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            }
            items(lines.size) { i ->
                val l = lines[i]
                Text(
                    l.text,
                    style = if (l.style == ChatLineStyle.Step) MaterialTheme.typography.bodyExtraSmall else MaterialTheme.typography.bodySmall,
                    color = when (l.style) {
                        ChatLineStyle.Prompt -> MaterialTheme.colorScheme.primary
                        ChatLineStyle.Step -> MaterialTheme.colorScheme.onSurfaceVariant
                        ChatLineStyle.Reply -> MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (canReplyToChat(chat)) {
                item {
                    Button(
                        onClick = onReply,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("🎤 Reply") },
                    )
                }
            }
            item {
                Text(chatFooter(chat), style = MaterialTheme.typography.bodyExtraSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
