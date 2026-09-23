package ink.jvm.chatter.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import ink.jvm.chatter.R
import ink.jvm.chatter.call.CallActionReceiver
import ink.jvm.chatter.data.LocalMessage
import ink.jvm.chatter.ui.MainActivity

object Notifications {
    const val CH_STATUS = "status"
    const val CH_MESSAGES = "messages"
    const val CH_MESSAGES_QUIET = "messages_quiet"
    const val CH_MENTION = "mention"
    const val CH_PAT = "pat"
    const val CH_CALLS = "calls"
    const val CH_BOT = "assistant"
    const val ID_STATUS = 1
    const val ID_MESSAGE = 2
    const val ID_CALL = 3
    const val ID_BOT = 4
    const val ID_PAT = 5
    const val ID_REMIND = 7
    /** An assistant-reply notification is on screen; streamed edits refresh it instead of stacking. */
    @Volatile var botShowing = false

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "连接状态", NotificationManager.IMPORTANCE_MIN).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "新消息", NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            // Quiet hours: shown, no sound, no heads-up.
            NotificationChannel(CH_MESSAGES_QUIET, "新消息（免打扰时段）", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_MENTION, "引用了我的消息", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION), null)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 120, 80, 120)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_PAT, "拍一拍", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 60, 60, 60, 60, 200)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_BOT, "助手回复", NotificationManager.IMPORTANCE_DEFAULT)
        )
        nm.createNotificationChannel(
            // The app plays the ringtone itself, so the channel stays silent.
            NotificationChannel(CH_CALLS, "来电", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    fun status(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_STATUS)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("lochatter")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp(ctx))
            .build()

    /** Conversation lines shown in the MessagingStyle notification; cleared when the app comes to the front. */
    private class Line(val text: String, val ts: Long, val mine: Boolean, val image: android.net.Uri?)
    private val thread = ArrayList<Line>()

    /** [quotedMe]: the peer replied to one of my messages → the dedicated "引用" channel (if enabled). */
    fun message(ctx: Context, from: String, m: LocalMessage, image: android.net.Uri? = null, quotedMe: Boolean = false, quiet: Boolean = false, mentionChannel: Boolean = true) {
        val text = when (m.kind) {
            "image" -> (if (m.once) "[图片·看一次]" else "[图片]") + (m.text?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")
            "audio" -> "[语音]"
            "video" -> if (m.once) "[视频·看一次]" else "[视频]"
            "file" -> "[文件] " + (m.media?.name ?: "")
            "call" -> ink.jvm.chatter.ui.callLabel(m.text)
            "sticker" -> "[表情]"
            "location" -> "[位置] " + (ink.jvm.chatter.util.Locator.decode(m.text)?.first?.address ?: "")
            "card" -> ink.jvm.chatter.ui.markdownFirstLine(m.text ?: "")
            else -> ink.jvm.chatter.ui.stripMarkdown(m.text ?: "")
        }
        val channel = when {
            quiet -> CH_MESSAGES_QUIET
            quotedMe && mentionChannel -> CH_MENTION
            else -> CH_MESSAGES
        }
        message(ctx, from, text, mine = false, image = image, replaceLast = image != null, channel = channel)
    }

    /** Posts / refreshes the conversation notification with inline reply and 「标为已读」 actions. */
    fun message(ctx: Context, from: String, text: String, mine: Boolean, image: android.net.Uri? = null, replaceLast: Boolean = false, channel: String = CH_MESSAGES) {
        if (!canPost(ctx)) return
        val lines: List<Line>
        synchronized(thread) {
            if (replaceLast && thread.isNotEmpty() && thread.last().text == text) thread.removeAt(thread.size - 1)
            thread += Line(text, System.currentTimeMillis(), mine, image)
            while (thread.size > 8) thread.removeAt(0)
            lines = ArrayList(thread)
        }
        val peer = Person.Builder().setName(from).setKey("peer").build()
        val me = Person.Builder().setName("我").setKey("me").build()
        val style = NotificationCompat.MessagingStyle(me)
        lines.forEach { l ->
            val msg = NotificationCompat.MessagingStyle.Message(l.text, l.ts, if (l.mine) me else peer)
            l.image?.let { msg.setData("image/jpeg", it) }
            style.addMessage(msg)
        }
        val replyIntent = PendingIntent.getBroadcast(
            ctx, 8,
            Intent(ctx, ReplyReceiver::class.java).setAction(ReplyReceiver.ACTION_REPLY),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val reply = NotificationCompat.Action.Builder(0, "回复", replyIntent)
            .addRemoteInput(RemoteInput.Builder(ReplyReceiver.KEY_TEXT).setLabel("回复…").build())
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .build()
        val readIntent = PendingIntent.getBroadcast(
            ctx, 9,
            Intent(ctx, ReplyReceiver::class.java).setAction(ReplyReceiver.ACTION_READ),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val read = NotificationCompat.Action.Builder(0, "标为已读", readIntent)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .build()
        val unread = lines.count { !it.mine }
        val quiet = channel == CH_MESSAGES_QUIET
        val b = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFFEE5C8E.toInt())
            .setStyle(style)
            .setAutoCancel(true)
            .setOnlyAlertOnce(mine || quiet)
            .setPriority(if (quiet) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setNumber(unread)
            .setContentIntent(openApp(ctx))
            .addAction(reply)
            .addAction(read)
        if (!quiet) b.setDefaults(NotificationCompat.DEFAULT_ALL) else b.setSilent(true)
        NotificationManagerCompat.from(ctx).notify(ID_MESSAGE, b.build())
    }

    fun cancelMessages(ctx: Context) {
        synchronized(thread) { thread.clear() }
        NotificationManagerCompat.from(ctx).cancel(ID_MESSAGE)
        NotificationManagerCompat.from(ctx).cancel(ID_PAT)
    }

    /** 拍一拍 from the peer: its own short buzz, gone as soon as the app opens. */
    fun pat(ctx: Context, from: String, quiet: Boolean) {
        if (!canPost(ctx)) return
        val b = NotificationCompat.Builder(ctx, if (quiet) CH_MESSAGES_QUIET else CH_PAT)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFFEE5C8E.toInt())
            .setContentTitle(from)
            .setContentText("$from 拍了拍你")
            .setAutoCancel(true)
            .setTimeoutAfter(60_000)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setContentIntent(openApp(ctx))
        if (quiet) b.setSilent(true)
        NotificationManagerCompat.from(ctx).notify(ID_PAT, b.build())
    }

    /** One quiet notification for the assistant, replaced in place as its streamed answer grows. Tap opens its page. */
    fun botReply(ctx: Context, name: String, text: String) {
        if (!canPost(ctx)) return
        val plain = ink.jvm.chatter.ui.stripMarkdown(text)
        val open = PendingIntent.getActivity(
            ctx, 4,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(MainActivity.EXTRA_ACTION, "open_bot"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(ctx, CH_BOT)
            .setSmallIcon(R.drawable.ic_bot)
            .setColor(0xFF3BA776.toInt())
            .setContentTitle(name)
            .setContentText(plain)
            .setStyle(NotificationCompat.BigTextStyle().bigText(plain))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open)
        NotificationManagerCompat.from(ctx).notify(ID_BOT, b.build())
        botShowing = true
    }

    fun cancelBot(ctx: Context) {
        botShowing = false
        NotificationManagerCompat.from(ctx).cancel(ID_BOT)
    }

    /** Heads-up + full-screen intent so the incoming call shows even on the lock screen. */
    fun incomingCall(ctx: Context, from: String, video: Boolean) {
        if (!canPost(ctx)) return
        val open = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(MainActivity.EXTRA_ACTION, "open_chat"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val decline = PendingIntent.getBroadcast(
            ctx, 2,
            Intent(ctx, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_DECLINE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val accept = PendingIntent.getBroadcast(
            ctx, 4,
            Intent(ctx, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_ACCEPT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val caller = Person.Builder().setName(from).setKey("peer").setImportant(true).build()
        val b = NotificationCompat.Builder(ctx, CH_CALLS)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFFEE5C8E.toInt())
            .setContentTitle(from)
            .setContentText(if (video) "视频来电" else "语音来电")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setSilent(true)
            .setFullScreenIntent(open, true)
            .setContentIntent(open)
        if (Build.VERSION.SDK_INT >= 31) {
            b.setStyle(NotificationCompat.CallStyle.forIncomingCall(caller, decline, accept).setIsVideo(video))
        } else {
            b.addAction(0, "拒绝", decline).addAction(0, "接听", accept)
        }
        val n = b.build()
        NotificationManagerCompat.from(ctx).notify(ID_CALL, n)
    }

    /** Local "remind me" ping. Does not send anything to the other person. */
    fun reminder(ctx: Context, text: String) {
        if (!canPost(ctx)) return
        val open = PendingIntent.getActivity(
            ctx, 9,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(MainActivity.EXTRA_ACTION, "open_chat"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("提醒")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(open)
        NotificationManagerCompat.from(ctx).notify(ID_REMIND + (System.currentTimeMillis() % 20).toInt(), b.build())
    }

    fun cancelCall(ctx: Context) {
        NotificationManagerCompat.from(ctx).cancel(ID_CALL)
    }

    /** Ongoing notification while a call is in progress so the user can return from the home screen. */
    fun inCall(ctx: Context, peer: String, video: Boolean): Notification {
        val back = PendingIntent.getActivity(
            ctx, 5,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_OPEN_CALL, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val hangup = PendingIntent.getBroadcast(
            ctx, 6,
            Intent(ctx, CallActionReceiver::class.java).setAction(CallActionReceiver.ACTION_HANGUP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(ctx, CH_CALLS)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFFEE5C8E.toInt())
            .setContentTitle(if (video) "视频通话中" else "语音通话中")
            .setContentText(peer)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setContentIntent(back)
            .addAction(0, "返回通话", back)
            .addAction(0, "挂断", hangup)
            .build()
    }

    private fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
