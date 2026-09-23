package ink.jvm.chatter.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import ink.jvm.chatter.ChatterApp
import ink.jvm.chatter.data.Scheduled

/**
 * Scheduled sends live only on this phone: one exact alarm for the earliest pending item. The phone has to be
 * alive at that moment; if the ROM killed us, the message goes out on the next start or reconnect instead.
 */
class ScheduledSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ChatterApp
        if (!app.prefs.loggedIn) return
        val pending = goAsync()
        try {
            val n = app.repo.fireDueScheduled()
            if (n > 0) Notifications.message(context, app.prefs.userName.ifEmpty { "我" }, "定时消息已发出", mine = true)
        } finally {
            pending.finish()
        }
    }

    companion object {
        private const val REQ = 41

        /** Re-arms the alarm for the earliest item (or cancels it when the list is empty). */
        fun arm(ctx: Context, list: List<Scheduled>) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, REQ, Intent(ctx, ScheduledSendReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val next = list.minOfOrNull { it.at }
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
