package ink.jvm.chatter.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import ink.jvm.chatter.ChatterApp

/** "提醒我" on a message: a local alarm, then a notification. The phone has to be alive at that moment. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ChatterApp
        val now = System.currentTimeMillis()
        val left = ArrayList<String>()
        for (line in app.prefs.reminders.lineSequence()) {
            if (line.isBlank()) continue
            val bar = line.indexOf('|')
            if (bar <= 0) continue
            val at = line.substring(0, bar).toLongOrNull() ?: continue
            if (at <= now + 2_000) Notifications.reminder(context, line.substring(bar + 1))
            else left.add(line)
        }
        app.prefs.reminders = left.joinToString("\n")
        arm(context)
    }

    companion object {
        private const val REQ = 47

        fun arm(ctx: Context) {
            val app = ctx.applicationContext as ChatterApp
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, REQ, Intent(ctx, ReminderReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val next = app.prefs.reminders.lineSequence().mapNotNull { line ->
                val bar = line.indexOf('|')
                if (bar <= 0) null else line.substring(0, bar).toLongOrNull()
            }.minOrNull()
            if (next == null) {
                am.cancel(pi)
                return
            }
            val at = maxOf(next, System.currentTimeMillis() + 1000)
            try {
                if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } catch (_: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        }
    }
}
