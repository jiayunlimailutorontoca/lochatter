package ink.jvm.chatter.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ink.jvm.chatter.ChatterApp

/** Re-arms the connection after reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val app = context.applicationContext as ChatterApp
            if (app.prefs.loggedIn) {
                ChatService.start(context)
                ReminderReceiver.arm(context)
            }
        }
    }
}
