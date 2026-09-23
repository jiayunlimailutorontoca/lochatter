package ink.jvm.chatter.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import ink.jvm.chatter.ChatterApp

/**
 * Dead man's switch. The service re-arms this alarm while it lives; if the ROM kills the process
 * the alarm fires within [INTERVAL_MS] and brings it back. Exact + allow-while-idle alarms fire in
 * Doze too (throttled to roughly one per 9 minutes there), and an exact alarm is one of the few
 * triggers Android 12+ lets a background app start a foreground service from.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ChatterApp
        if (!app.prefs.loggedIn) return
        ink.jvm.chatter.util.Diag.log(TAG, "watchdog tick")
        // Keep the CPU up long enough for reconnect + catch-up sync + notification.
        val pm = context.getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "chatter:watchdog")
        runCatching { wl.acquire(20_000) }
        ChatService.start(context)
        app.repo.connect()
        app.repo.probe()
        schedule(context, INTERVAL_MS)
    }

    companion object {
        private const val TAG = "Watchdog"
        const val INTERVAL_MS = 5 * 60_000L
        private const val ACTION = "ink.jvm.chatter.WATCHDOG"

        private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 7,
            Intent(ctx, WatchdogReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun exactAllowed(ctx: Context): Boolean =
            Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

        fun schedule(ctx: Context, delayMs: Long) {
            val am = ctx.getSystemService(AlarmManager::class.java)
            val at = SystemClock.elapsedRealtime() + delayMs
            val pi = pending(ctx)
            try {
                if (exactAllowed(ctx)) {
                    am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                } else {
                    am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                }
            } catch (e: Exception) {
                Log.w(TAG, "schedule failed: $e")
                runCatching { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) }
            }
        }

        fun cancel(ctx: Context) {
            ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx))
        }
    }
}
