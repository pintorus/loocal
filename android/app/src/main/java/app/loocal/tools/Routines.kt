package app.loocal.tools

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.loocal.domain.Errand
import app.loocal.domain.ErrandRun

/**
 * Routines: a phone can't keep a multi-gigabyte model waiting on a timer, so (as on iOS) each
 * scheduled time posts a notification; opening Loocal from it, or being in it, runs the errand.
 */
object RoutineAlarm {
    private const val CHANNEL = "errands"

    /** One alarm per routine, at its next run; replaced whenever routines change or one fires. */
    fun reschedule(context: Context, errands: List<Errand>) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val prefs = context.getSharedPreferences("routines", Context.MODE_PRIVATE)
        prefs.getStringSet("scheduled", emptySet())!!.forEach { alarms.cancel(intent(context, it, "", "")) }
        val scheduled = mutableSetOf<String>()
        for (errand in errands) {
            // A minute ahead at least, so an alarm that just fired doesn't line itself up again at once.
            val next = if (errand.paused) continue else errand.next(System.currentTimeMillis() + 60_000) ?: continue
            alarms.setWindow(AlarmManager.RTC_WAKEUP, next, 60_000, intent(context, errand.id, errand.title, errand.instructions))
            scheduled.add(errand.id)
        }
        prefs.edit().putStringSet("scheduled", scheduled).apply()
    }

    private fun intent(context: Context, id: String, title: String, body: String) = PendingIntent.getBroadcast(
        context, ("routine$id").hashCode(),
        Intent(context, RoutineReceiver::class.java).putExtra("id", id).putExtra("title", title),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun notify(context: Context, id: Int, title: String, body: String) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) manager.createNotificationChannel(NotificationChannel(CHANNEL, "Errands", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 0, context.packageManager.getLaunchIntentForPackage(context.packageName), PendingIntent.FLAG_IMMUTABLE)
        manager.notify(id, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title).setContentText(body).setContentIntent(open).setAutoCancel(true).build())
    }

    /** Lets you know a result is in when Loocal finished it while you were elsewhere. */
    fun announce(context: Context, run: ErrandRun) {
        if (ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        val lines = run.text.lines().map { it.replace("**", "").trim() }.filter { it.isNotEmpty() }
        notify(context, ("done" + run.id).hashCode(), run.title, lines.drop(1).firstOrNull() ?: "Your errand is done.")
    }
}

class RoutineReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        RoutineAlarm.notify(context, ("routine" + intent.getStringExtra("id")).hashCode(), intent.getStringExtra("title") ?: "Errand", "Ready to run. Open Loocal and it’s done on this phone.")
        // Line up the next time.
        RoutineAlarm.reschedule(context, app.loocal.domain.ErrandStore(context.filesDir).load().first)
    }
}
