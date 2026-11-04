package dev.wearclaude.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text

@Composable
fun HomeScreen(
    ui: HomeUi,
    onOpenApproval: (String) -> Unit,
    groups: List<ProjectGroup> = emptyList(),
    archivedCount: Int = 0,
    onOpenChat: (String) -> Unit = {},
    onOpenArchive: () -> Unit = {},
) {
    val listState = rememberTransformingLazyColumnState()
    val nowMs by produceState(System.currentTimeMillis()) {
        while (true) { delay(30_000); value = System.currentTimeMillis() }
    }
    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Top),
        ) {
            if (ui.reconnecting) {
                item {
                    Text(
                        RECONNECTING_COPY,
                        style = MaterialTheme.typography.bodyExtraSmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            val approvalId = ui.firstApprovalId
            if (ui.pendingApprovals > 0 && approvalId != null) {
                item {
                    FilledTonalButton(
                        onClick = { onOpenApproval(approvalId) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("${ui.pendingApprovals} approval${if (ui.pendingApprovals == 1) "" else "s"} waiting") },
                    )
                }
            }
            if (groups.isEmpty()) {
                item {
                    Text(
                        EMPTY_ACTIVE_COPY,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            } else {
                chatGroupItems(groups, nowMs, onOpenChat)
            }
            if (archivedCount > 0) {
                item(key = "archived-chip") {
                    FilledTonalButton(
                        onClick = onOpenArchive,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Archived ($archivedCount)", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        }
    }
}

/** Project headers (centered, inset for the round screen) each followed by that project's chat chips. */
fun TransformingLazyColumnScope.chatGroupItems(
    groups: List<ProjectGroup>,
    nowMs: Long,
    onOpenChat: (String) -> Unit,
) {
    groups.forEach { g ->
        item(key = "ph:${g.projectName}") {
            Text(
                g.projectName,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            )
        }
        items(g.chats, key = { "chat:${it.id}" }) { c ->
            FilledTonalButton(
                onClick = { onOpenChat(c.id) },
                modifier = Modifier.fillMaxWidth(),
                icon = { StatusDot(if (c.status == "working") "running" else "idle") },
                label = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = { Text(if (c.status == "working") "working…" else relativeTime(nowMs, c.lastActivity), maxLines = 1) },
            )
        }
    }
}
