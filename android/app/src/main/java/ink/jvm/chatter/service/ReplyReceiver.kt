package ink.jvm.chatter.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import ink.jvm.chatter.ChatterApp

/** Notification actions: inline reply and "mark as read". */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ChatterApp
        when (intent.action) {
            ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) {
                    app.repo.sendText(text)
                    Notifications.message(context, app.prefs.userName.ifEmpty { "我" }, text, mine = true)
                }
            }
            ACTION_READ -> {
                app.repo.markAllRead()
                Notifications.cancelMessages(context)
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "ink.jvm.chatter.REPLY"
        const val ACTION_READ = "ink.jvm.chatter.MARK_READ"
        const val KEY_TEXT = "text"
    }
}
