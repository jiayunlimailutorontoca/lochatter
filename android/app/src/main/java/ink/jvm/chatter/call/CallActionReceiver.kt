package ink.jvm.chatter.call

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import ink.jvm.chatter.ChatterApp
import ink.jvm.chatter.ui.MainActivity

/** Actions from the incoming-call notification, the in-call notification, and the PiP controls. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as ChatterApp
        when (intent.action) {
            ACTION_DECLINE -> app.calls.reject()
            ACTION_ACCEPT -> {
                val incoming = app.calls.state.value as? CallManager.State.Incoming
                if (incoming != null && hasPerms(context, incoming.video)) app.calls.accept()
                openApp(context, expandCall = true)
            }
            ACTION_HANGUP -> app.calls.hangup()
            ACTION_MUTE -> app.calls.setMuted(!app.calls.muted.value)
        }
    }

    private fun hasPerms(ctx: Context, video: Boolean): Boolean {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        if (video && ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false
        return true
    }

    private fun openApp(ctx: Context, expandCall: Boolean) {
        val i = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (expandCall) i.putExtra(MainActivity.EXTRA_OPEN_CALL, true)
        ctx.startActivity(i)
    }

    companion object {
        const val ACTION_DECLINE = "ink.jvm.chatter.CALL_DECLINE"
        const val ACTION_ACCEPT = "ink.jvm.chatter.CALL_ACCEPT"
        const val ACTION_HANGUP = "ink.jvm.chatter.CALL_HANGUP"
        const val ACTION_MUTE = "ink.jvm.chatter.CALL_MUTE"
    }
}
