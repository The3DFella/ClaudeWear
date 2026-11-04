package dev.wearclaude.watch.service

import android.os.SystemClock
import dev.wearclaude.watch.data.AgentsState
import dev.wearclaude.watch.ui.AppController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The single place that diffs consecutive states into alerts and hands them to [notifier] (which buzzes or posts a
 * notification depending on whether the app is on screen). Lives as long as the app process; the watch service only
 * keeps the process and connection alive.
 */
class AlertCenter(
    private val controller: AppController,
    private val notifier: Notifier,
    private val scope: CoroutineScope,
) {
    /** When the app last went to the background, or null while on screen / never. Read by the watch service. */
    @Volatile var backgroundedAtMs: Long? = null
        private set

    fun onForeground() { backgroundedAtMs = null }

    fun onBackground() { backgroundedAtMs = SystemClock.elapsedRealtime() }

    fun start() {
        scope.launch {
            var prev: AgentsState? = null
            var alerted: Set<String>? = null
            controller.watchedState().collect { state ->
                if (state == null) {
                    prev = null
                    alerted = null
                    return@collect
                }
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
            }
        }
    }
}
