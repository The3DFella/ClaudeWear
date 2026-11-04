package dev.wearclaude.watch.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import dev.wearclaude.watch.data.ProjectInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val PROMPT = "What should Claude do?"

/**
 * @param replyTo agent id for a follow-up; null creates a new agent.
 * @param onSent called with the agent id after a successful send.
 */
@Composable
fun DictateScreen(
    commands: AgentCommands?,
    replyTo: String?,
    loadLastProjectId: suspend () -> String?,
    saveLastProjectId: (String) -> Unit,
    onSent: (agentId: String) -> Unit,
    chatId: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var transcript by rememberSaveable { mutableStateOf("") }
    var needsKeyboard by rememberSaveable { mutableStateOf(false) }
    var started by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var projects by remember { mutableStateOf<List<ProjectInfo>?>(null) }
    var projectsError by remember { mutableStateOf(false) }
    var loadTick by remember { mutableIntStateOf(0) }
    var projectIdx by remember { mutableIntStateOf(-1) }

    val keyboard = rememberTextInput(PROMPT) { if (it.isNotBlank()) transcript = it.trim() }

    val speech = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        } else null
        if (!text.isNullOrBlank()) transcript = text
        else if (transcript.isBlank()) needsKeyboard = true
    }
    val launchSpeech: () -> Unit = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, PROMPT)
        }
        if (intent.resolveActivity(context.packageManager) == null) {
            needsKeyboard = true
        } else {
            needsKeyboard = false
            try {
                speech.launch(intent)
            } catch (_: Exception) {
                needsKeyboard = true
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!started) {
            started = true
            launchSpeech()
        }
    }
    LaunchedEffect(commands, loadTick) {
        val c = commands ?: return@LaunchedEffect
        if (replyTo != null || chatId != null || projects != null) return@LaunchedEffect
        projectsError = false
        try {
            val list = c.projects()
            projects = list
            projectIdx = defaultProjectIndex(list, loadLastProjectId())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            projectsError = true
        }
    }

    val project = projects?.getOrNull(projectIdx)
    val noProjects = replyTo == null && chatId == null && projects?.isEmpty() == true
    val canSend = !sending && commands != null && transcript.isNotBlank() &&
        (replyTo != null || chatId != null || project != null)

    DictateContent(
        transcript = transcript,
        needsKeyboard = needsKeyboard,
        sending = sending,
        error = error,
        showProjectRow = replyTo == null && chatId == null,
        noProjects = noProjects,
        project = project,
        projectsError = projectsError,
        canSend = canSend,
        isChatReply = chatId != null,
        onCycleProject = { projectIdx = nextProjectIndex(projectIdx, projects?.size ?: 0) },
        onRetryProjects = { loadTick++ },
        onSend = {
            val c = commands ?: return@DictateContent
            sending = true
            error = null
            scope.launch {
                when (val r = if (chatId != null) submitChatReply(c, chatId, transcript) else submitPrompt(c, replyTo, project?.id, transcript)) {
                    is SendResult.Ok -> {
                        project?.let { saveLastProjectId(it.id) }
                        onSent(r.agentId)
                    }
                    is SendResult.Failed -> {
                        error = r.message
                        sending = false
                    }
                }
            }
        },
        onKeyboard = keyboard,
        onRedo = {
            if (projectsError) loadTick++
            launchSpeech()
        },
    )
}

/** Stateless body of [DictateScreen]; also rendered directly by the screenshot tests. */
@Composable
internal fun DictateContent(
    transcript: String,
    needsKeyboard: Boolean,
    sending: Boolean,
    error: String?,
    showProjectRow: Boolean,
    noProjects: Boolean,
    project: ProjectInfo?,
    projectsError: Boolean,
    canSend: Boolean,
    isChatReply: Boolean,
    onCycleProject: () -> Unit,
    onRetryProjects: () -> Unit,
    onSend: () -> Unit,
    onKeyboard: () -> Unit,
    onRedo: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = listState) { padding ->
        TransformingLazyColumn(
            state = listState,
            contentPadding = padding,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Top),
        ) {
            item {
                Text(
                    transcript.ifBlank { if (needsKeyboard) "Nothing heard" else "Listening…" },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (showProjectRow) {
                item {
                    when {
                        noProjects -> Text(
                            NO_PROJECTS_COPY,
                            style = MaterialTheme.typography.bodyExtraSmall,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        project != null -> FilledTonalButton(
                            onClick = onCycleProject,
                            enabled = !sending,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(project.name, maxLines = 1) },
                        )
                        projectsError -> FilledTonalButton(
                            onClick = onRetryProjects,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(PROJECTS_RETRY_COPY, style = MaterialTheme.typography.bodyExtraSmall) },
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Send") },
                )
            }
            if (needsKeyboard) {
                item {
                    FilledTonalButton(
                        onClick = onKeyboard,
                        enabled = !sending,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Type instead") },
                    )
                }
            }
            item {
                FilledTonalButton(
                    onClick = onRedo,
                    enabled = !sending,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Redo") },
                )
            }
            if (sending) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
                if (isChatReply) {
                    item {
                        Text(CHAT_SENDING_COPY, style = MaterialTheme.typography.bodyExtraSmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            error?.let { msg ->
                item {
                    Text(
                        msg,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
