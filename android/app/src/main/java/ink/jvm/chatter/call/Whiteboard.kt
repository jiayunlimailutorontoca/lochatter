package ink.jvm.chatter.call

import android.util.Log
import ink.jvm.chatter.data.ProtoJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.webrtc.DataChannel
import java.nio.ByteBuffer

/**
 * One drawn line. [points] is x0,y0,x1,y1,… normalised to 0..1 of the board; [color] is ARGB;
 * [width] is in dp. [mine] is local-only (never sent) and drives undo.
 */
@Serializable
data class Stroke(
    val id: String,
    val color: Long,
    val width: Float,
    val points: List<Float>,
    val mine: Boolean = false,
)

/** Wire envelope. ProtoJson's class discriminator is "t", so this serialises as {"t":"stroke", …}. */
@Serializable
private sealed class BoardMsg {
    @Serializable
    @SerialName("stroke")
    data class StrokeMsg(val id: String, val color: Long, val width: Float, val points: List<Float>) : BoardMsg()

    @Serializable
    @SerialName("clear")
    data object ClearMsg : BoardMsg()

    @Serializable
    @SerialName("open")
    data class OpenMsg(val v: Boolean) : BoardMsg()

    @Serializable
    @SerialName("undo")
    data class UndoMsg(val id: String) : BoardMsg()
}

/**
 * Shared whiteboard state synced over a WebRTC DataChannel during a call. Strokes are sent whole when
 * the finger lifts; either side may open/close, undo its own strokes, or clear everything. Outbound
 * messages queue (up to 50) until the channel opens. Callbacks arrive on a WebRTC thread, so state is
 * published through StateFlow.update.
 */
class Whiteboard {
    private val _strokes = MutableStateFlow<List<Stroke>>(emptyList())
    val strokes: StateFlow<List<Stroke>> = _strokes.asStateFlow()

    /** True while the board is shown on both phones (set by whoever opened it last). */
    val open = MutableStateFlow(false)

    private val lock = Any()
    private var channel: DataChannel? = null
    private val pending = ArrayDeque<ByteArray>()

    private val observer = object : DataChannel.Observer {
        override fun onBufferedAmountChange(previousAmount: Long) {}

        override fun onStateChange() {
            if (runCatching { channel?.state() }.getOrNull() == DataChannel.State.OPEN) flush()
        }

        override fun onMessage(buffer: DataChannel.Buffer) {
            val bytes = ByteArray(buffer.data.remaining()).also { buffer.data.get(it) }
            val text = String(bytes, Charsets.UTF_8)
            val msg = runCatching { ProtoJson.decodeFromString(BoardMsg.serializer(), text) }
                .onFailure { Log.w(TAG, "bad board message: ${it.message}") }
                .getOrNull() ?: return
            when (msg) {
                is BoardMsg.StrokeMsg -> _strokes.update { list ->
                    if (list.any { it.id == msg.id }) list
                    else list + Stroke(msg.id, msg.color, msg.width, msg.points, mine = false)
                }
                is BoardMsg.ClearMsg -> _strokes.value = emptyList()
                is BoardMsg.OpenMsg -> open.value = msg.v
                is BoardMsg.UndoMsg -> _strokes.update { list -> list.filterNot { it.id == msg.id && !it.mine } }
            }
        }
    }

    /** Starts listening on [ch]; any messages queued while there was no open channel are sent once it opens. */
    fun attach(ch: DataChannel) {
        synchronized(lock) {
            if (channel === ch) return
            runCatching { channel?.unregisterObserver() }
            channel = ch
        }
        runCatching { ch.registerObserver(observer) }
        if (runCatching { ch.state() }.getOrNull() == DataChannel.State.OPEN) flush()
    }

    fun detach() {
        val ch = synchronized(lock) { channel.also { channel = null } } ?: return
        runCatching { ch.unregisterObserver() }
    }

    /** Adds a stroke drawn on this phone and sends it to the peer. */
    fun addLocal(stroke: Stroke) {
        val s = stroke.copy(mine = true)
        _strokes.update { it + s }
        send(BoardMsg.StrokeMsg(s.id, s.color, s.width, s.points))
    }

    /** Removes my most recent stroke (peer strokes are theirs to undo). */
    fun undoLast() {
        var removed: Stroke? = null
        _strokes.update { list ->
            val last = list.lastOrNull { it.mine } ?: return@update list
            removed = last
            list - last
        }
        removed?.let { send(BoardMsg.UndoMsg(it.id)) }
    }

    fun clear() {
        _strokes.value = emptyList()
        send(BoardMsg.ClearMsg)
    }

    fun setOpen(v: Boolean) {
        open.value = v
        send(BoardMsg.OpenMsg(v))
    }

    /** Call ended: drop the channel, the drawing and anything still queued. */
    fun reset() {
        detach()
        synchronized(lock) { pending.clear() }
        _strokes.value = emptyList()
        open.value = false
    }

    private fun send(msg: BoardMsg) {
        val bytes = ProtoJson.encodeToString(BoardMsg.serializer(), msg).toByteArray(Charsets.UTF_8)
        val ch = synchronized(lock) { channel }
        if (ch != null && runCatching { ch.state() }.getOrNull() == DataChannel.State.OPEN) {
            if (sendRaw(ch, bytes)) return
        }
        synchronized(lock) {
            while (pending.size >= MAX_PENDING) pending.removeFirst()
            pending.addLast(bytes)
        }
    }

    private fun flush() {
        val ch = synchronized(lock) { channel } ?: return
        while (true) {
            val next = synchronized(lock) { pending.removeFirstOrNull() } ?: return
            if (!sendRaw(ch, next)) {
                synchronized(lock) { pending.addFirst(next) }
                return
            }
        }
    }

    private fun sendRaw(ch: DataChannel, bytes: ByteArray): Boolean =
        runCatching { ch.send(DataChannel.Buffer(ByteBuffer.wrap(bytes), false)) }
            .onFailure { Log.w(TAG, "board send failed: ${it.message}") }
            .getOrDefault(false)

    companion object {
        private const val TAG = "Whiteboard"
        private const val MAX_PENDING = 50
        /** Label to use when creating the DataChannel on the offering side. */
        const val CHANNEL_LABEL = "whiteboard"
    }
}
