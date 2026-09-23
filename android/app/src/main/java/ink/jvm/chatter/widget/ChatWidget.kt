package ink.jvm.chatter.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import ink.jvm.chatter.R
import ink.jvm.chatter.data.ProtoJson
import ink.jvm.chatter.ui.MainActivity
import ink.jvm.chatter.ui.fmtTime
import kotlinx.serialization.Serializable

/** Everything the home-screen widget shows; the app pushes a fresh one through [ChatWidget.update]. */
@Serializable
data class WidgetState(
    val peerName: String,
    val online: Boolean,
    val lastSeen: Long?,
    val battery: Int?,
    val charging: Boolean?,
    val lastMessage: String?,
    val lastMessageTs: Long?,
    val unread: Int,
    val countdownTitle: String?,
    val countdownDays: Int?,
    val botName: String?,
)

/**
 * Plain RemoteViews widget: peer presence, battery, last message, unread count, countdown and three
 * shortcut buttons (voice call, video call, ask the assistant). The last state is cached as JSON in the
 * "widget" SharedPreferences so the launcher's periodic onUpdate can re-render without the data layer.
 */
class ChatWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val state = load(context)
        for (id in appWidgetIds) render(context, appWidgetManager, id, state)
    }

    override fun onEnabled(context: Context) {
        val mgr = AppWidgetManager.getInstance(context) ?: return
        val ids = mgr.getAppWidgetIds(ComponentName(context, ChatWidget::class.java))
        val state = load(context)
        for (id in ids) render(context, mgr, id, state)
    }

    override fun onAppWidgetOptionsChanged(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle) {
        render(context, appWidgetManager, appWidgetId, load(context))
    }

    companion object {
        private const val PREFS = "widget"
        private const val KEY_STATE = "state"
        /** Below this launcher-reported min height (dp) the compact 2x1 layout is used. */
        private const val SMALL_BELOW_DP = 100
        private const val ACTION_PREFIX = "ink.jvm.chatter.widget."

        /** Renders [state] on every placed widget and remembers it for later launcher-driven refreshes. */
        fun update(ctx: Context, state: WidgetState) {
            runCatching {
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_STATE, ProtoJson.encodeToString(WidgetState.serializer(), state))
                    .apply()
            }
            val mgr = AppWidgetManager.getInstance(ctx) ?: return
            val ids = mgr.getAppWidgetIds(ComponentName(ctx, ChatWidget::class.java))
            for (id in ids) render(ctx, mgr, id, state)
        }

        private fun load(ctx: Context): WidgetState? {
            val json = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_STATE, null) ?: return null
            return runCatching { ProtoJson.decodeFromString(WidgetState.serializer(), json) }.getOrNull()
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int, state: WidgetState?) {
            val minHeight = runCatching { mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) }.getOrDefault(0)
            val small = minHeight in 1 until SMALL_BELOW_DP
            val views = RemoteViews(ctx.packageName, if (small) R.layout.widget_chat_small else R.layout.widget_chat)
            val s = state ?: WidgetState("lochatter", false, null, null, null, null, null, 0, null, null, null)

            views.setTextViewText(R.id.widget_name, s.peerName)
            views.setImageViewResource(R.id.widget_dot, if (s.online) R.drawable.widget_dot_online else R.drawable.widget_dot_offline)
            val lastSeen = s.lastSeen
            views.setTextViewText(
                R.id.widget_status,
                when {
                    s.online -> "在线"
                    lastSeen != null && lastSeen > 0 -> "最后在线 ${fmtTime(lastSeen)}"
                    else -> "离线"
                },
            )
            views.setTextViewText(R.id.widget_bot_label, "问${s.botName?.takeIf { it.isNotBlank() } ?: "助手"}")

            if (!small) {
                val battery = s.battery
                if (battery != null) {
                    views.setViewVisibility(R.id.widget_battery, View.VISIBLE)
                    views.setTextViewText(R.id.widget_battery, "${if (s.charging == true) "⚡" else "🔋"} $battery%")
                } else {
                    views.setViewVisibility(R.id.widget_battery, View.GONE)
                }

                val last = s.lastMessage?.takeIf { it.isNotBlank() }
                views.setTextViewText(R.id.widget_last, last ?: "还没有消息")
                val ts = s.lastMessageTs
                if (last != null && ts != null && ts > 0) {
                    views.setViewVisibility(R.id.widget_last_time, View.VISIBLE)
                    views.setTextViewText(R.id.widget_last_time, fmtTime(ts))
                } else {
                    views.setViewVisibility(R.id.widget_last_time, View.GONE)
                }

                if (s.unread > 0) {
                    views.setViewVisibility(R.id.widget_unread, View.VISIBLE)
                    views.setTextViewText(R.id.widget_unread, if (s.unread > 99) "99+" else s.unread.toString())
                } else {
                    views.setViewVisibility(R.id.widget_unread, View.GONE)
                }

                val title = s.countdownTitle?.takeIf { it.isNotBlank() }
                val days = s.countdownDays
                if (title != null && days != null) {
                    views.setViewVisibility(R.id.widget_countdown, View.VISIBLE)
                    views.setTextViewText(
                        R.id.widget_countdown,
                        when {
                            days == 0 -> "$title 就是今天！"
                            days < 0 -> "$title 已过去 ${-days} 天"
                            else -> "距离 $title 还有 $days 天"
                        },
                    )
                } else {
                    views.setViewVisibility(R.id.widget_countdown, View.GONE)
                }
            }

            views.setOnClickPendingIntent(R.id.widget_root, launch(ctx, 0, null))
            views.setOnClickPendingIntent(R.id.widget_btn_voice, launch(ctx, 1, "call_voice"))
            views.setOnClickPendingIntent(R.id.widget_btn_video, launch(ctx, 2, "call_video"))
            views.setOnClickPendingIntent(R.id.widget_btn_bot, launch(ctx, 3, "open_bot"))

            runCatching { mgr.updateAppWidget(id, views) }
        }

        /** Distinct action strings keep the four PendingIntents from collapsing into one (extras alone don't). */
        private fun launch(ctx: Context, requestCode: Int, action: String?): PendingIntent {
            val intent = Intent(ctx, MainActivity::class.java)
                .setAction(ACTION_PREFIX + (action ?: "open"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            if (action != null) intent.putExtra(MainActivity.EXTRA_ACTION, action)
            return PendingIntent.getActivity(ctx, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}
