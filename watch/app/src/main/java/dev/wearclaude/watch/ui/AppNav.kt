package dev.wearclaude.watch.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.navigation.NavHostController
import androidx.wear.compose.material3.Text
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import dev.wearclaude.watch.data.AgentsState
import kotlinx.coroutines.flow.MutableStateFlow

const val ROUTE_PAIR = "pair"
const val ROUTE_HOME = "home"
const val ROUTE_ARCHIVE = "archive"
const val ROUTE_DICTATE_REPLY = "dictate/{replyTo}"
const val ROUTE_DICTATE_CHAT = "dictate-chat/{id}"
const val ROUTE_AGENT = "agent/{id}"
const val ROUTE_CHAT = "chat/{id}"
const val ROUTE_APPROVAL = "approval/{approvalId}"

private val EMPTY_STATE = MutableStateFlow(AgentsState())

@Composable
fun AppNav(vm: MainViewModel, pendingRoute: String? = null, onRouteHandled: () -> Unit = {}) {
    val paired by vm.paired.collectAsState()
    val pairing by vm.pairing.collectAsState()
    val nav = rememberSwipeDismissableNavController()

    LaunchedEffect(paired) {
        val target = when (paired) {
            true -> ROUTE_HOME
            false -> ROUTE_PAIR
            null -> return@LaunchedEffect
        }
        nav.navigate(target) {
            popUpTo(nav.graph.id) { inclusive = true }
            launchSingleTop = true
        }
    }

    // Deep link from a notification; waits until pairing state is known, then navigates once.
    LaunchedEffect(pendingRoute, paired) {
        val route = pendingRoute ?: return@LaunchedEffect
        when (paired) {
            null -> return@LaunchedEffect
            true -> nav.navigate(route)
            false -> {}
        }
        onRouteHandled()
    }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val repoForOpen by vm.repo.collectAsState()
    LaunchedEffect(repoForOpen) {
        val r = repoForOpen ?: return@LaunchedEffect
        var seen: Set<String>? = null
        r.state.map { it.loaded to it.approvals.keys }.distinctUntilChanged().collect { (loaded, current) ->
            val res = autoOpenIds(seen, loaded, current)
            seen = res.seeded
            val fresh = res.fresh
            if (fresh.isEmpty() || !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@collect
            val id = fresh.first()
            val onIt = nav.currentBackStackEntry?.arguments?.getString("approvalId") == id
            if (!onIt) {
                vibrateOnce(context)
                nav.navigate("approval/${Uri.encode(id)}")
            }
        }
    }

    // Route list is static; while loading (paired == null) the start destination shows blank.
    SwipeDismissableNavHost(navController = nav, startDestination = ROUTE_PAIR) {
        composable(ROUTE_PAIR) {
            if (paired == null) Box(Modifier.fillMaxSize()) else PairScreen(pairing, vm::pair)
        }
        composable(ROUTE_HOME) {
            val repo by vm.repo.collectAsState()
            val state by (repo?.state ?: EMPTY_STATE).collectAsState()
            HomeScreen(
                ui = homeItems(state),
                groups = remember(state) { activeChatGroups(state) },
                archivedCount = remember(state) { archivedCount(state) },
                onOpenChat = { nav.navigate("chat/${Uri.encode(it)}") },
                onOpenArchive = { nav.navigate(ROUTE_ARCHIVE) },
                onOpenApproval = { nav.navigate("approval/${Uri.encode(it)}") },
            )
        }
        composable(ROUTE_ARCHIVE) {
            val repo by vm.repo.collectAsState()
            val state by (repo?.state ?: EMPTY_STATE).collectAsState()
            ArchiveScreen(
                groups = remember(state) { archivedChatGroups(state) },
                onOpenChat = { nav.navigate("chat/${Uri.encode(it)}") },
            )
        }
        composable(ROUTE_DICTATE_REPLY) { entry ->
            DictateRoute(vm, nav, entry.arguments?.getString("replyTo").orEmpty())
        }
        composable(ROUTE_AGENT) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val ctx = LocalContext.current
            LaunchedEffect(id) { dev.wearclaude.watch.service.Notifier(ctx.applicationContext).cancelAgent(id) }
            val repo by vm.repo.collectAsState()
            val commands by vm.commands.collectAsState()
            val state by (repo?.state ?: EMPTY_STATE).collectAsState()
            AgentDetailScreen(
                id = id,
                state = state,
                repo = repo,
                commands = commands,
                onReply = { nav.navigate("dictate/${Uri.encode(id)}") },
                onOpenApproval = { nav.navigate("approval/${Uri.encode(it)}") },
            )
        }
        composable(ROUTE_CHAT) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            val ctx = LocalContext.current
            LaunchedEffect(id) { dev.wearclaude.watch.service.Notifier(ctx.applicationContext).cancelChat(id) }
            val repo by vm.repo.collectAsState()
            val state by (repo?.state ?: EMPTY_STATE).collectAsState()
            ChatScreen(id, state, repo, onReply = { nav.navigate("dictate-chat/${Uri.encode(id)}") })
        }
        composable(ROUTE_DICTATE_CHAT) { entry ->
            val commands by vm.commands.collectAsState()
            DictateScreen(
                commands = commands,
                replyTo = null,
                loadLastProjectId = vm::lastProjectId,
                saveLastProjectId = vm::saveLastProject,
                onSent = { nav.popBackStack() },
                chatId = entry.arguments?.getString("id").orEmpty(),
            )
        }
        composable(ROUTE_APPROVAL) { entry ->
            val approvalId = entry.arguments?.getString("approvalId").orEmpty()
            val repo by vm.repo.collectAsState()
            val commands by vm.commands.collectAsState()
            val state by (repo?.state ?: EMPTY_STATE).collectAsState()
            ApprovalScreen(approvalId, state, commands, onClose = { nav.popBackStack() })
        }
    }
}

@Composable
private fun DictateRoute(vm: MainViewModel, nav: NavHostController, replyTo: String) {
    val commands by vm.commands.collectAsState()
    DictateScreen(
        commands = commands,
        replyTo = replyTo,
        loadLastProjectId = vm::lastProjectId,
        saveLastProjectId = vm::saveLastProject,
        onSent = { nav.popBackStack() },
    )
}

private fun vibrateOnce(context: Context) {
    try {
        val effect = VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE)
        if (Build.VERSION.SDK_INT >= 31) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(effect)
        }
    } catch (_: Exception) {
    }
}
