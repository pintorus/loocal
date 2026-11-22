package app.loocal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat

/**
 * Keeps a reply (or an errand) going when you leave Loocal: the Android counterpart of iOS's
 * continued-processing task and Live Activity. Its ongoing notification shows the phase and token
 * count only, never the prompt or the reply. Generation already runs on the CPU, so there is no
 * GPU handoff to manage as on iPhone.
 */
class ReplyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(ID, notification(this, intent?.getStringExtra("phase") ?: "Generating", intent?.getIntExtra("tokens", 0) ?: 0, intent?.getStringExtra("model").orEmpty()), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        return START_NOT_STICKY
    }

    companion object {
        private const val ID = 7
        private const val CHANNEL = "replies"
        private var running = false
        private var lastUpdate = 0L

        fun start(context: Context, model: String) {
            if (running) return
            running = true
            ensureChannel(context)
            runCatching { context.startForegroundService(Intent(context, ReplyService::class.java).putExtra("model", model)) }.onFailure { running = false }
        }

        /** Updates the ongoing notification, at most twice a second. */
        fun update(context: Context, phase: String, tokens: Int, model: String) {
            if (!running || System.currentTimeMillis() - lastUpdate < 500) return
            lastUpdate = System.currentTimeMillis()
            context.getSystemService(NotificationManager::class.java).notify(ID, notification(context, phase, tokens, model))
        }

        fun stop(context: Context) {
            if (!running) return
            running = false
            context.stopService(Intent(context, ReplyService::class.java))
        }

        private fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Replies in progress", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            }
        }

        private fun notification(context: Context, phase: String, tokens: Int, model: String): Notification {
            val open = PendingIntent.getActivity(context, 0, context.packageManager.getLaunchIntentForPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle(if (model.isEmpty()) "Loocal" else "Loocal · $model")
                .setContentText(if (tokens > 0) "$phase · $tokens tokens" else phase)
                .setProgress(0, 0, true)
                .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(open)
                .build()
        }
    }
}
