package dev.wearclaude.watch.ui

import android.app.Activity
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper

private const val KEY_INPUT = "wearclaude_input"

/** Opens the system keyboard (RemoteInput) and returns the text through [onResult]. */
@Composable
internal fun rememberTextInput(title: String, onResult: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            val data = r.data ?: return@rememberLauncherForActivityResult
            val bundle: Bundle? = RemoteInput.getResultsFromIntent(data)
            bundle?.getCharSequence(KEY_INPUT)?.toString()?.let(onResult)
        }
    }
    return {
        val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        val input = RemoteInput.Builder(KEY_INPUT).setLabel(title).build()
        RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(input))
        RemoteInputIntentHelper.putTitleExtra(intent, title)
        launcher.launch(intent)
    }
}

@Composable
fun PairScreen(
    state: PairUiState,
    onPair: (url: String, code: String) -> Unit,
    initialUrl: String = "",
    initialCode: String = "",
) {
    var url by rememberSaveable { mutableStateOf(initialUrl) }
    var code by rememberSaveable { mutableStateOf(initialCode) }
    val openUrl = rememberTextInput("Bridge address") { url = it.trim() }
    val openCode = rememberTextInput("6-digit code") { code = it.filter(Char::isDigit).take(6) }
    val loading = state is PairUiState.Loading
    val listState = rememberTransformingLazyColumnState()

    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Top),
        ) {
            item {
                Text("Pair with your PC", style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
            }
            item {
                Button(
                    onClick = openUrl,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (url.isBlank()) "Enter address" else url, maxLines = 2) },
                )
            }
            item {
                Text(
                    "Use your PC's address or Tailscale URL",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    textAlign = TextAlign.Center,
                )
            }
            item {
                Button(
                    onClick = openCode,
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (code.isBlank()) "Enter 6-digit code" else code) },
                )
            }
            item {
                Button(
                    onClick = { onPair(url, code) },
                    enabled = !loading && url.isNotBlank() && code.length == 6,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Pair") },
                )
            }
            if (loading) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
            }
            if (state is PairUiState.Error) {
                item {
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
