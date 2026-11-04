package dev.wearclaude.watch.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.TimeSource
import androidx.wear.compose.material3.TimeText
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import dev.wearclaude.watch.data.AgentsState
import dev.wearclaude.watch.data.ChatInfo
import dev.wearclaude.watch.data.ChatItem
import dev.wearclaude.watch.data.PendingApproval
import dev.wearclaude.watch.ui.ApprovalScreen
import dev.wearclaude.watch.ui.ArchiveScreen
import dev.wearclaude.watch.ui.ChatScreen
import dev.wearclaude.watch.ui.DictateContent
import dev.wearclaude.watch.ui.HomeScreen
import dev.wearclaude.watch.ui.HomeUi
import dev.wearclaude.watch.ui.PairScreen
import dev.wearclaude.watch.ui.PairUiState
import dev.wearclaude.watch.ui.activeChatGroups
import dev.wearclaude.watch.ui.archivedChatGroups
import dev.wearclaude.watch.ui.archivedCount
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the real screens with made-up demo data for the README (docs/images/raw).
 * Regenerate: ./gradlew :app:recordRoborazziDebug --tests "*DemoScreenshots*"
 * Under a plain testDebugUnitTest run these only render (no files are written).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.WearOSLargeRound, sdk = [33])
class DemoScreenshots {
    @get:Rule val compose = createComposeRule()

    /**
     * The extra side inset keeps text inside the round mask the README frames apply; [timeText] is off for
     * screens that auto-scroll (the clock scrolls away on a device).
     */
    private fun shoot(
        name: String,
        timeText: Boolean = true,
        scrollToEnd: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            MaterialTheme {
                AppScaffold(timeText = { if (timeText) TimeText(timeSource = FixedTime) }) {
                    Box(Modifier.fillMaxSize().padding(horizontal = 14.dp)) { content() }
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        if (scrollToEnd) {
            repeat(3) { compose.onRoot().performTouchInput { swipeUp() } }
            compose.mainClock.advanceTimeBy(3_000)
            compose.waitForIdle()
        }
        compose.onRoot().captureRoboImage("../../docs/images/raw/$name.png")
    }

    @Test fun home() = shoot("home") {
        val s = DemoData.state()
        HomeScreen(
            ui = HomeUi(0, null, null, reconnecting = false),
            onOpenApproval = {},
            groups = activeChatGroups(s),
            archivedCount = archivedCount(s),
        )
    }

    @Test fun archive() = shoot("archive") {
        ArchiveScreen(archivedChatGroups(DemoData.state()), onOpenChat = {})
    }

    @Test fun chat() = shoot("chat", timeText = false, scrollToEnd = true) {
        ChatScreen(DemoData.CHAT_ID, DemoData.state(DemoData.chatWithWatchPrompt()), repo = null)
    }

    @Test fun chatBefore() = shoot("chat-before", timeText = false, scrollToEnd = true) {
        ChatScreen(DemoData.CHAT_ID, DemoData.state(DemoData.chatBase()), repo = null)
    }

    @Test fun chatAfter() = shoot("chat-after", timeText = false, scrollToEnd = true) {
        ChatScreen(DemoData.CHAT_ID, DemoData.state(DemoData.chatAnswered()), repo = null)
    }

    @Test fun reply() = shoot("reply") { dictate(sending = false) }

    @Test fun sending() = shoot("sending") { dictate(sending = true) }

    @Test fun approval() = shoot("approval") {
        val s = DemoData.state().copy(
            approvals = mapOf(
                "ap1" to PendingApproval(
                    approvalId = "ap1",
                    agentId = "a1",
                    tool = "Bash",
                    summary = "Running `npm install chart.js`",
                    // 4:32 left (+ slack so the first frame doesn't tick to 4:31).
                    expiresAt = System.currentTimeMillis() + 272_900,
                ),
            ),
        )
        ApprovalScreen("ap1", s, commands = null, onClose = {})
    }

    @Test fun pair() = shoot("pair") {
        PairScreen(PairUiState.Idle, onPair = { _, _ -> }, initialUrl = "192.168.1.20:8787", initialCode = "482913")
    }

    @Composable
    private fun dictate(sending: Boolean) {
        DictateContent(
            transcript = "Make the icons a bit bigger",
            needsKeyboard = false,
            sending = sending,
            error = null,
            showProjectRow = false,
            noProjects = false,
            project = null,
            projectsError = false,
            canSend = !sending,
            isChatReply = true,
            onCycleProject = {},
            onRetryProjects = {},
            onSend = {},
            onKeyboard = {},
            onRedo = {},
        )
    }

    private object FixedTime : TimeSource {
        @Composable override fun currentTime(): String = "10:09"
    }
}

/** Believable, generic demo content. No real user data. */
object DemoData {
    const val CHAT_ID = "c-forecast"
    private const val MIN = 60_000L
    private const val HOUR = 60 * MIN
    private const val DAY = 24 * HOUR

    private val archivedTitles = listOf(
        "weather-app" to "Set up CI workflow",
        "weather-app" to "Add city search",
        "weather-app" to "Cache API responses",
        "portfolio-site" to "Write project README",
        "portfolio-site" to "Add contact form",
        "portfolio-site" to "Optimize hero images",
        "portfolio-site" to "Fix mobile nav overlap",
        "data-pipeline" to "Add retry on S3 upload",
        "data-pipeline" to "Schema validation for orders",
        "data-pipeline" to "Nightly job alerting",
        "data-pipeline" to "Parquet export",
        "data-pipeline" to "Dedupe customer rows",
    )

    fun state(items: List<ChatItem> = chatWithWatchPrompt()): AgentsState {
        val now = System.currentTimeMillis()
        val active = listOf(
            ChatInfo(CHAT_ID, "Add hourly forecast view", "weather-app", now - 20_000, "working", titled = true),
            ChatInfo("c-login", "Fix flaky login test", "weather-app", now - 5 * MIN - 10_000, "idle", ended = true, titled = true),
            ChatInfo("c-dark", "Dark mode toggle", "portfolio-site", now - 2 * HOUR - 5 * MIN, "idle", ended = true, titled = true),
            ChatInfo("c-csv", "Speed up CSV import", "data-pipeline", now - DAY - HOUR, "idle", ended = true, titled = true),
        )
        val archived = archivedTitles.mapIndexed { i, (project, title) ->
            ChatInfo("arch-$i", title, project, now - (2 + i) * DAY - i * HOUR, "idle", ended = true, titled = true, archived = true)
        }
        return AgentsState(
            chats = (active + archived).associateBy { it.id },
            chatItems = mapOf(CHAT_ID to items),
            loaded = true,
        )
    }

    fun chatBase(): List<ChatItem> = listOf(
        ChatItem("prompt", "Add an hourly forecast strip under the current temperature", 1),
        ChatItem("step", "Reading ForecastView.kt", 2),
        ChatItem("step", "Editing ForecastView.kt", 3),
        ChatItem("step", "Running `npm test`", 4),
        ChatItem("reply", "Added a scrollable hourly strip with icons and temps. All 42 tests pass.", 5),
    )

    fun chatWithWatchPrompt(): List<ChatItem> = chatBase() + ChatItem("prompt", "Make the icons a bit bigger", 6)

    fun chatAnswered(): List<ChatItem> = chatWithWatchPrompt() + listOf(
        ChatItem("step", "Editing ForecastView.kt", 7),
        ChatItem("reply", "Bumped the hourly icons from 24px to 32px and re-ran the tests. All green.", 8),
    )
}
