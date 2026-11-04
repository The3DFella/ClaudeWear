package dev.wearclaude.watch.service

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.wearclaude.watch.R
import dev.wearclaude.watch.WearClaudeApp
import dev.wearclaude.watch.data.AgentsState
import dev.wearclaude.watch.ui.AppController
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private const val SERVICE_NOTIFICATION_ID = 1
private const val TICK_MS = 5_000L

/** Starts the watch service; ignored if the system refuses (callers are all in the foreground). */
fun startWatchService(context: Context) {
    try {
        ContextCompat.startForegroundService(context, Intent(context, AgentWatchService::class.java))
    } catch (_: Exception) {
        // e.g. ForegroundServiceStartNotAllowedException
    }
}

/** State of the current repo; emits null once unpaired, nothing while pairing is still loading. */
@OptIn(ExperimentalCoroutinesApi::class)
fun AppController.watchedState(): Flow<AgentsState?> = paired.flatMapLatest { p ->
    when (p) {
        null -> emptyFlow()
        false -> flowOf(null)
        true -> repo.flatMapLatest { r -> r?.state ?: emptyFlow() }
    }
}

class AgentWatchService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ -> stopSelf() })
    private var counts = 0 to 0
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannels(this)
        val note = buildNotification(counts)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(SERVICE_NOTIFICATION_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(SERVICE_NOTIFICATION_ID, note)
        }
        if (job?.isActive != true) job = scope.launch { watch() }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        val controller = (application as WearClaudeApp).controller
        val notifier = Notifier(this)
        var prev: AgentsState? = null
        var idleSince: Long? = null
        var alerted: Set<String>? = null
        val ticker = flow { while (true) { emit(Unit); delay(TICK_MS) } }
        combine(controller.watchedState(), ticker) { s, _ -> s }.collect { state ->
            val now = SystemClock.elapsedRealtime()
            if (state != null) {
                if (state !== prev) {
                    prev?.let { p ->
                        alertsFor(p, state).forEach(notifier::alert)
                        chatAlerts(p, state).forEach(notifier::alert)
                    }
                    prev = state
                }
                val before = alerted
                val (approvals, updated) = approvalAlerts(state, alerted)
                alerted = updated
                approvals.forEach(notifier::alert)
                if (before != null && updated != null) (before - updated).forEach(notifier::cancelApproval)
                idleSince = nextIdleSince(state, idleSince, now)
                val n = activeAgentCount(state) to workingChatCount(state)
                if (n != counts) {
                    counts = n
                    try {
                        NotificationManagerCompat.from(this).notify(SERVICE_NOTIFICATION_ID, buildNotification(n))
                    } catch (_: SecurityException) {
                    }
                }
            }
            if (shouldStop(state, idleSince, now)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    /** [n]: active watch agents to working PC chats. */
    private fun buildNotification(n: Pair<Int, Int>): Notification =
        NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_wc)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(serviceText(n.first, n.second))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /** API 35+: the dataSync time limit was reached; stop rather than be killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
