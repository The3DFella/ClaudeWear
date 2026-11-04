package dev.wearclaude.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.Card
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.wearclaude.watch.data.AgentCommands
import dev.wearclaude.watch.data.AgentRepository
import dev.wearclaude.watch.data.AgentsState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AgentDetailScreen(
    id: String,
    state: AgentsState,
    repo: AgentRepository?,
    commands: AgentCommands?,
    onReply: () -> Unit,
    onOpenApproval: (String) -> Unit,
) {
    val ui = detailUi(state, id)
    var loadError by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val listState = rememberTransformingLazyColumnState()

    LaunchedEffect(id, repo) {
        loadError = false
        try {
            repo?.loadDetail(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadError = true
        }
    }

    val stepCount = ui?.steps?.size ?: 0
    // Items before the steps: title, status, plus optional load error, reason, approval button.
    val leading = 2 + (if (loadError) 1 else 0) + (if (ui?.reason != null) 1 else 0) + (if (ui?.pendingApprovalId != null) 1 else 0)
    LaunchedEffect(stepCount) {
        if (stepCount > 0) listState.animateScrollToItem(leading + stepCount - 1)
    }

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Top),
        ) {
            if (ui == null) {
                item { Text("Agent not found", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
                return@TransformingLazyColumn
            }
            item {
                Text(ui.title, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            item {
                Text(statusLabel(ui.status), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            if (loadError) {
                item { Text("Couldn't load details", style = MaterialTheme.typography.bodyExtraSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            }
            ui.reason?.let { r ->
                item { Text(r, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()) }
            }
            ui.pendingApprovalId?.let { aid ->
                item {
                    FilledTonalButton(onClick = { onOpenApproval(aid) }, modifier = Modifier.fillMaxWidth(), label = { Text("Needs approval") })
                }
            }
            items(ui.steps.size) { i ->
                val s = ui.steps[i]
                Text(
                    s.text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().padding(start = if (s.indented) 12.dp else 0.dp),
                )
            }
            ui.summary?.let { sum ->
                item {
                    Card(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text(sum, style = MaterialTheme.typography.bodySmall) }
                }
            }
            actionError?.let { e ->
                item { Text(e, style = MaterialTheme.typography.bodyExtraSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()) }
            }
            item {
                Button(onClick = onReply, enabled = ui.canReply, modifier = Modifier.fillMaxWidth(), label = { Text("Reply") })
            }
            if (ui.canStop) {
                item {
                    FilledTonalButton(
                        onClick = {
                            if (!stopping) scope.launch {
                                stopping = true
                                try {
                                    commands?.stop(id)
                                    actionError = null
                                    try { repo?.loadDetail(id) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    actionError = "Couldn't stop agent"
                                } finally {
                                    stopping = false
                                }
                            }
                        },
                        enabled = !stopping,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Stop") },
                    )
                }
            }
        }
    }
}
