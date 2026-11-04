package dev.wearclaude.watch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import dev.wearclaude.watch.data.AgentCommands
import dev.wearclaude.watch.data.AgentsState
import dev.wearclaude.watch.data.BridgeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val ALREADY_ANSWERED = "Already answered"

@Composable
fun ApprovalScreen(
    approvalId: String,
    state: AgentsState,
    commands: AgentCommands?,
    onClose: () -> Unit,
) {
    val approval = state.approvals[approvalId]
    var seen by remember { mutableStateOf(approval) }
    if (approval != null) seen = approval
    var answeredByServer by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val startedAt = remember { System.currentTimeMillis() }
    var now by remember { mutableLongStateOf(startedAt) }
    val scope = rememberCoroutineScope()
    var closed by remember { mutableStateOf(false) }
    val close: () -> Unit = { if (!closed) { closed = true; onClose() } }

    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val mode = if (answeredByServer) ApprovalMode.Resolved
    else approvalScreenMode(state.loaded, seen, approval, now - startedAt)
    // Resolved elsewhere / timed out / never appeared: show notice, then pop. Cancelled if the approval shows up.
    LaunchedEffect(mode, busy) {
        if (mode == ApprovalMode.Resolved && !busy) {
            delay(1500)
            close()
        }
    }

    if (mode == ApprovalMode.Loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val a = seen
    if (mode == ApprovalMode.Resolved || a == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(ALREADY_ANSWERED, textAlign = TextAlign.Center)
        }
        return
    }
    val expired = a.expiresAt - now <= 0
    val listState = rememberTransformingLazyColumnState()
    fun decide(allow: Boolean) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                commands?.decide(approvalId, allow)
                close()
            } catch (e: CancellationException) {
                throw e
            } catch (e: BridgeException) {
                if (e.code == 404) {
                    busy = false
                    answeredByServer = true
                } else {
                    error = "Couldn't send"
                    busy = false
                }
            } catch (e: Exception) {
                error = "Couldn't send"
                busy = false
            }
        }
    }
    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Top),
        ) {
            item { Text(a.tool, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            item { Text(a.summary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.fillMaxWidth()) }
            item { Text(countdownText(a.expiresAt, now), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }
            error?.let { e -> item { Text(e, style = MaterialTheme.typography.bodyExtraSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()) } }
            item { Button(onClick = { decide(true) }, enabled = !busy && !expired, modifier = Modifier.fillMaxWidth(), label = { Text("Allow") }) }
            item { FilledTonalButton(onClick = { decide(false) }, enabled = !busy && !expired, modifier = Modifier.fillMaxWidth(), label = { Text("Deny") }) }
        }
    }
}
