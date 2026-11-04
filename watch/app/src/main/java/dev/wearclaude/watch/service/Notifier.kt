package dev.wearclaude.watch.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dev.wearclaude.watch.MainActivity
import dev.wearclaude.watch.R

const val EXTRA_OPEN_ROUTE = "open_route"
const val CHANNEL_APPROVALS = "approvals"
const val CHANNEL_UPDATES = "agent_updates"
const val CHANNEL_SERVICE = "service"

private const val FINISH_BUZZ_MS = 200L
private val APPROVAL_VIBRATION = longArrayOf(0, 400, 150, 100, 150, 400) // long-short-long

fun ensureChannels(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java) ?: return
    nm.createNotificationChannel(NotificationChannel(CHANNEL_APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
        enableVibration(true)
        vibrationPattern = APPROVAL_VIBRATION
    })
    nm.createNotificationChannel(NotificationChannel(CHANNEL_UPDATES, "Agent updates", NotificationManager.IMPORTANCE_HIGH).apply {
        enableVibration(true)
    })
    nm.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "Background connection", NotificationManager.IMPORTANCE_LOW))
}

class Notifier(private val context: Context) {
    init { ensureChannels(context) }

    fun cancelAgent(agentId: String) = NotificationManagerCompat.from(context).cancel("status:$agentId".hashCode())

    fun cancelChat(chatId: String) = NotificationManagerCompat.from(context).cancel("chat:$chatId".hashCode())

    fun cancelApproval(approvalId: String) = NotificationManagerCompat.from(context).cancel("approval:$approvalId".hashCode())

    private fun vibrateShort() {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)
            }
            v?.vibrate(VibrationEffect.createOneShot(FINISH_BUZZ_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
        }
    }

    /** Delivers [alert] once: a short buzz while the app is on screen, otherwise a notification. */
    fun alert(alert: Alert) {
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        when (deliveryFor(alert, foreground)) {
            Delivery.None -> return
            Delivery.Vibrate -> { vibrateShort(); return }
            Delivery.Notify -> Unit
        }
        val (title, text) = alertContent(alert)
        val (route, key, channel) = when (alert) {
            is Alert.Done -> Triple("agent/${Uri.encode(alert.agentId)}", "status:${alert.agentId}", CHANNEL_UPDATES)
            is Alert.Failed -> Triple("agent/${Uri.encode(alert.agentId)}", "status:${alert.agentId}", CHANNEL_UPDATES)
            is Alert.ChatFinished -> Triple("chat/${Uri.encode(alert.chatId)}", "chat:${alert.chatId}", CHANNEL_UPDATES)
            is Alert.ApprovalNeeded -> Triple("approval/${Uri.encode(alert.approvalId)}", "approval:${alert.approvalId}", CHANNEL_APPROVALS)
        }
        val id = key.hashCode()
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_ROUTE, route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_wc)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(if (alert is Alert.ApprovalNeeded) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .apply { if (alert is Alert.ApprovalNeeded) setVibrate(APPROVAL_VIBRATION) }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied: the app still works, just without alerts.
        }
    }
}
