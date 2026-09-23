package ink.jvm.chatter.data

import android.util.Log
import ink.jvm.chatter.util.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import kotlin.math.min
import kotlin.random.Random

/**
 * One WebSocket to the server with exponential-backoff reconnect.
 * Frames are delivered on OkHttp's reader thread, in order.
 */
class WsClient(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    private val onFrame: (Frame) -> Unit,
    private val onState: (State) -> Unit,
    private val onAuthFailed: () -> Unit,
) {
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    private val lock = Any()
    private var ws: WebSocket? = null
    private var url = ""
    private var token = ""
    private var wanted = false
    private var attempt = 0
    private var reconnectJob: Job? = null
    private var probeJob: Job? = null

    /** Wall-clock time of the last frame the server sent us (0 = nothing yet). */
    @Volatile var lastRxAt = 0L
        private set

    fun connect(url: String, token: String) {
        synchronized(lock) {
            this.url = url
            this.token = token
            wanted = true
            attempt = 0
            reconnectJob?.cancel()
            if (ws == null) open()
        }
    }

    fun disconnect() {
        synchronized(lock) {
            wanted = false
            reconnectJob?.cancel()
            ws?.close(1000, "bye")
            ws = null
        }
        onState(State.DISCONNECTED)
    }

    /** Network came back: skip the backoff and try now. */
    fun kick() {
        synchronized(lock) {
            if (wanted && ws == null) {
                reconnectJob?.cancel()
                attempt = 0
                open()
            }
        }
    }

    fun send(frame: Frame): Boolean {
        val s = ws ?: return false
        return s.send(frame.toJson())
    }

    /**
     * Zombie check: a socket the network silently dropped (NAT timeout, Doze) still looks open here.
     * Sends an app-level ping; if nothing at all arrives within [timeoutMs] the socket is torn down
     * and reconnected immediately. Cheap enough to call from every watchdog tick.
     */
    fun probe(timeoutMs: Long = 12_000) {
        synchronized(lock) {
            val s = ws
            if (s == null) {
                kick()
                return
            }
            if (probeJob?.isActive == true) return
            val before = lastRxAt
            s.send(Ping(System.currentTimeMillis()).toJson())
            probeJob = scope.launch {
                delay(timeoutMs)
                synchronized(lock) {
                    if (ws === s && lastRxAt == before) {
                        Diag.warn(TAG, "no pong in ${timeoutMs}ms; dropping stale socket")
                        s.cancel()
                        lost(s, "probe timeout", null, immediate = true)
                    }
                }
            }
        }
    }

    private fun open() {
        onState(State.CONNECTING)
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").build()
        ws = client.newWebSocket(req, listener)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                if (ws === webSocket) {
                    attempt = 0
                    onState(State.CONNECTED)
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            lastRxAt = System.currentTimeMillis()
            val frame = try {
                ProtoJson.decodeFromString(Frame.serializer(), text)
            } catch (e: Exception) {
                Log.w(TAG, "dropping frame: ${e.message}")
                return
            }
            onFrame(frame)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            lost(webSocket, "closed $code $reason", null)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            lost(webSocket, "failure: ${t.message}", response?.code)
        }
    }

    private fun lost(socket: WebSocket, why: String, httpCode: Int?, immediate: Boolean = false) {
        synchronized(lock) {
            if (ws !== socket) return // stale callback from a socket we already replaced
            ws = null
            probeJob?.cancel()
            onState(State.DISCONNECTED)
            if (httpCode == 401) {
                Log.w(TAG, "token rejected by server")
                wanted = false
                onAuthFailed()
                return
            }
            if (!wanted) return
            val delayMs = if (immediate) 0L else min(30_000L, 1000L shl min(attempt, 5)) + Random.nextLong(0, 1000)
            attempt++
            Diag.log(TAG, "$why; reconnecting in ${delayMs}ms")
            reconnectJob = scope.launch {
                delay(delayMs)
                synchronized(lock) { if (wanted && ws == null) open() }
            }
        }
    }

    private companion object {
        const val TAG = "WsClient"
    }
}
