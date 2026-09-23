package ink.jvm.chatter.service

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import ink.jvm.chatter.ChatterApp
import ink.jvm.chatter.call.CallManager
import ink.jvm.chatter.data.WsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service whose only job is to keep the process alive so the WebSocket in
 * ChatRepository stays connected.
 * During a call it re-declares itself with microphone/camera types so Android keeps the mic open.
 */
class ChatService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var screenReceiver: BroadcastReceiver? = null
    private var statusText = "连接中…"
    private var inCall = false
    private var callVideo = false
    private var peerName = ""
    /** Screen sharing needs the mediaProjection foreground type before MediaProjection may start. */
    private var screenShare = false

    override fun onCreate() {
        super.onCreate()
        val app = application as ChatterApp
        val repo = app.repo
        val calls = app.calls
        peerName = app.prefs.peerName.ifEmpty { "对方" }

        foreground()

        if (!app.prefs.loggedIn) {
            stopSelf()
            return
        }
        repo.connect()
        WatchdogReceiver.schedule(this, WatchdogReceiver.INTERVAL_MS)
        KeepAliveJob.schedule(this)

        scope.launch {
            repo.connection.collect { st ->
                statusText = when (st) {
                    WsClient.State.CONNECTED -> "已连接"
                    WsClient.State.CONNECTING -> "正在重连…"
                    WsClient.State.DISCONNECTED -> "连不上，收不到消息和来电"
                }
                if (!inCall) getSystemService(NotificationManager::class.java)
                    .notify(Notifications.ID_STATUS, Notifications.status(this@ChatService, statusText))
            }
        }
        scope.launch {
            calls.state.collect { st ->
                when (st) {
                    is CallManager.State.Incoming -> {}
                    is CallManager.State.Active -> {
                        Notifications.cancelCall(this@ChatService)
                        inCall = true
                        callVideo = st.video
                        foreground()
                    }
                    is CallManager.State.Outgoing -> {
                        inCall = true
                        callVideo = st.video
                        foreground()
                    }
                    else -> {
                        Notifications.cancelCall(this@ChatService)
                        if (inCall) {
                            inCall = false
                            screenShare = false
                            foreground()
                        }
                    }
                }
            }
        }

        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                repo.kick()
                calls.onNetworkAvailable()
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        netCallback = cb

        // Screen on / unlock / charger: cheap moments to make sure the socket is really alive after Doze.
        // TIME_TICK (once a minute while the CPU is awake) catches sockets the network dropped silently.
        val sr = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_TIME_TICK ->
                        if (repo.connection.value != WsClient.State.CONNECTED ||
                            System.currentTimeMillis() - repo.lastRxAt > STALE_MS
                        ) repo.probe()
                    else -> repo.probe()
                }
            }
        }
        ContextCompat.registerReceiver(
            this, sr,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_TIME_TICK)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        screenReceiver = sr
    }

    /** (Re)declares the foreground notification and, on Android 14+, the service type set. */
    private fun foreground() {
        val notification = if (inCall) Notifications.inCall(this, peerName, callVideo)
            else Notifications.status(this, statusText)
        var type = 0
        if (Build.VERSION.SDK_INT >= 34) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            if (inCall) {
                type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                if (callVideo) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                if (screenShare) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            }
        } else if (Build.VERSION.SDK_INT >= 29 && inCall && screenShare) {
            type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        }
        try {
            ServiceCompat.startForeground(this, Notifications.ID_STATUS, notification, type)
        } catch (e: Exception) {
            Log.w(TAG, "startForeground(type=$type) failed: $e; falling back")
            val fallback = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            runCatching { ServiceCompat.startForeground(this, Notifications.ID_STATUS, notification, fallback) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.hasExtra(EXTRA_SCREEN_SHARE) == true) {
            screenShare = intent.getBooleanExtra(EXTRA_SCREEN_SHARE, false)
            foreground()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Swiped away from recents: some ROMs kill the process right after this. Ask to be restarted soon. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        WatchdogReceiver.schedule(this, 3_000)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        netCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        scope.cancel()
        (application as ChatterApp).repo.disconnect()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ChatService"
        private const val STALE_MS = 3 * 60_000L
        const val EXTRA_SCREEN_SHARE = "screenShare"

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, ChatService::class.java))
            } catch (e: Exception) {
                // Android 12+: not allowed from the background unless battery-whitelisted; the next foreground moment retries.
                Log.w(TAG, "start failed: $e")
            }
        }

        /** Flip the mediaProjection foreground type on/off around a screen share. */
        fun setScreenShare(ctx: Context, on: Boolean) {
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, ChatService::class.java).putExtra(EXTRA_SCREEN_SHARE, on)) }
                .onFailure { Log.w(TAG, "screenShare($on) failed: $it") }
        }

        fun stop(ctx: Context) {
            WatchdogReceiver.cancel(ctx)
            KeepAliveJob.cancel(ctx)
            ctx.stopService(Intent(ctx, ChatService::class.java))
        }
    }
}
