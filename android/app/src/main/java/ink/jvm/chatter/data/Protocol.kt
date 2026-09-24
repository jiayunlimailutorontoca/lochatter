package ink.jvm.chatter.data

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Mirrors docs/protocol.md. Unknown frame types fail to decode and are dropped by WsClient. */
val ProtoJson = Json {
    classDiscriminator = "t"
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

@Serializable
data class UserInfo(val id: Long, val name: String)

@Serializable
data class PeerInfo(val id: Long, val name: String, val online: Boolean = false, val lastSeen: Long? = null, val pubKey: String? = null, val battery: Int? = null, val charging: Boolean? = null)

/** The in-chat assistant (Hermes on the NAS). Always id 0; not a third human. [ttl]: its messages follow the disappearing timer. */
@Serializable
data class BotInfo(val id: Long = 0, val name: String, val online: Boolean = false, val ttl: Boolean = true)

/** Server-side helpers advertised in `hello` (docs/protocol-1.6.md): `/stt` transcription, `/geo/…` places, `/tiles/` map tiles. */
@Serializable
data class Features(val stt: Boolean = false, val geo: Boolean = false, val tiles: Boolean = false, val tileDatum: String? = null)

/** Result of `POST /stt`. */
@Serializable
data class SttResult(val text: String, val language: String? = null, val durationMs: Long? = null)

/** One place from `/geo/…` (WGS-84). */
@Serializable
data class Poi(val name: String, val address: String = "", val lat: Double, val lng: Double, val distance: Int = 0, val type: String = "")

/** `GET /geo/regeo`: the address under a point, the place there when known, nearby places. */
@Serializable
data class GeoResult(val address: String, val name: String? = null, val pois: List<Poi> = emptyList())

/** One entry of the two-person shared key/value store (quick commands, anniversaries, custom stickers). */
@Serializable
data class SharedItem(val key: String, val value: String, val updatedAt: Long = 0)

@Serializable
data class MediaInfo(
    val id: String,
    val mime: String,
    val size: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Int? = null,
    val thumbId: String? = null,
    val name: String? = null,
)

/** Server-side snapshot of the quoted message. */
@Serializable
data class ReplyInfo(val id: String, val from: Long, val text: String)

/**
 * kind: text | image | audio | video | file | call | react | edit | ttl | del | clear.
 * del / clear / react / edit are control entries: applied, never displayed. ttl is shown as a system line.
 * text may be an end-to-end encrypted blob ("e2e:" prefix).
 */
@Serializable
data class ChatMessage(
    val seq: Long,
    val id: String,
    val from: Long,
    val kind: String,
    val text: String? = null,
    val media: MediaInfo? = null,
    val ts: Long,
    val reply: ReplyInfo? = null,
    val editedAt: Long? = null,
    val expiresAt: Long? = null,
    /** "bot" when the message was addressed to the assistant (always plaintext). */
    val to: String? = null,
    /** View-once image / video: the receiver deletes it for both after opening. */
    val once: Boolean? = null,
)

@Serializable
sealed class Frame

// ---- session ----

@Serializable
@SerialName("hello")
data class Hello(
    val connId: String,
    val version: String,
    val serverTs: Long,
    val user: UserInfo,
    val peer: PeerInfo? = null,
    val lastSeq: Long,
    val myReadUpto: Long = 0,
    val peerReadUpto: Long = 0,
    val myPubKey: String? = null,
    val ttlSeconds: Long = 0,
    val bot: BotInfo? = null,
    /** Bot connections only: the two humans. */
    val users: List<UserInfo>? = null,
    /** Human connections: the whole shared key/value store. */
    val shared: List<SharedItem>? = null,
    /** 1.6: what this server can do for us (absent on older servers = nothing). */
    val features: Features? = null,
) : Frame()

@Serializable
@SerialName("ping")
data class Ping(val ts: Long? = null) : Frame()

@Serializable
@SerialName("pong")
data class Pong(val ts: Long? = null, val serverTs: Long) : Frame()

@Serializable
@SerialName("error")
data class ErrorFrame(val code: String, val message: String, val ref: String? = null) : Frame()

// ---- messaging ----

@Serializable
@SerialName("msg.send")
data class MsgSend(
    val id: String,
    val kind: String,
    val text: String? = null,
    val mediaId: String? = null,
    val replyTo: String? = null,
    val to: String? = null,
    val once: Boolean? = null,
    /** Plaintext preview for offline phone push. Not stored. Pictures stay a short label. */
    val notice: String? = null,
) : Frame()

@Serializable
@SerialName("msg.ack")
data class MsgAck(val id: String, val seq: Long, val ts: Long) : Frame()

@Serializable
@SerialName("msg.new")
data class MsgNew(val msg: ChatMessage) : Frame()

@Serializable
@SerialName("sync")
data class Sync(val since: Long, val limit: Int? = null, val before: Long? = null) : Frame()

@Serializable
@SerialName("msg.batch")
data class MsgBatch(val messages: List<ChatMessage>, val hasMore: Boolean, val before: Long? = null) : Frame()

@Serializable
@SerialName("read")
data class ReadMark(val upto: Long, val user: Long? = null) : Frame()

@Serializable
@SerialName("typing")
data class Typing(val user: Long? = null) : Frame()

@Serializable
@SerialName("presence")
data class Presence(val user: Long, val online: Boolean, val lastSeen: Long? = null, val battery: Int? = null, val charging: Boolean? = null) : Frame()

@Serializable
@SerialName("active")
data class Active(val fg: Boolean, val battery: Int? = null, val charging: Boolean? = null) : Frame()

/** Shared key/value changed (sent to both humans, including the writer). */
@Serializable
@SerialName("shared")
data class SharedFrame(val key: String, val value: String, val updatedAt: Long = 0) : Frame()

// ---- calls (server relays and stamps `from`) ----

@Serializable
@SerialName("call.invite")
data class CallInvite(val callId: String, val video: Boolean, val from: Long? = null, val ts: Long? = null, val bot: Boolean = false) : Frame()

@Serializable
@SerialName("call.accept")
data class CallAccept(val callId: String, val from: Long? = null) : Frame()

@Serializable
@SerialName("call.reject")
data class CallReject(val callId: String, val reason: String? = null, val from: Long? = null) : Frame()

@Serializable
@SerialName("call.hangup")
data class CallHangup(val callId: String, val reason: String? = null, val from: Long? = null) : Frame()

@Serializable
@SerialName("call.sdp")
data class CallSdp(val callId: String, val type: String, val sdp: String, val from: Long? = null) : Frame()

@Serializable
@SerialName("call.ice")
data class CallIce(
    val callId: String,
    val candidate: String,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val from: Long? = null,
) : Frame()

@Serializable
@SerialName("call.media")
data class CallMedia(val callId: String, val video: Boolean, val audio: Boolean = true, val screen: Boolean? = null, val from: Long? = null) : Frame()

/** In-call emoji reaction that floats up on both screens. */
@Serializable
@SerialName("call.emoji")
data class CallEmoji(val callId: String, val emoji: String, val from: Long? = null) : Frame()

/**
 * Live subtitle on an assistant call. [who] is "user" or "assistant"; [state] is "partial" or "final".
 * [phase] is listening / thinking / speaking / idle / error. A frame for another call id is ignored.
 */
@Serializable
@SerialName("call.caption")
data class CallCaption(
    val callId: String,
    val who: String,
    val text: String = "",
    val state: String,
    val phase: String? = null,
    val ts: Long? = null,
    val from: Long? = null,
) : Frame()

/** A user published a new end-to-end public key. */
@Serializable
@SerialName("keys")
data class Keys(val user: Long, val pubKey: String, val updatedAt: Long = 0) : Frame()

/** Assistant came online / went offline / was renamed. */
@Serializable
@SerialName("bot")
data class BotFrame(val id: Long = 0, val name: String, val online: Boolean = false, val ttl: Boolean = true) : Frame()

@Serializable
@SerialName("turn.get")
data object TurnGet : Frame()

@Serializable
@SerialName("turn.creds")
data class TurnCreds(val urls: List<String>, val username: String, val credential: String, val ttlSeconds: Long) : Frame()

// ---- HTTP ----

@Serializable
data class LoginRequest(val name: String, val password: String, val device: String? = null)

@Serializable
data class WebTokenResponse(val token: String)

@Serializable
data class WebBoxRequest(val box: String)

@Serializable
data class LoginResponse(val token: String, val user: UserInfo, val peer: UserInfo? = null)

@Serializable
data class ErrorResponse(val error: String)

@Serializable
data class KeyRequest(val pubKey: String)

@Serializable
data class KeyInfo(val userId: Long, val pubKey: String, val updatedAt: Long = 0)

@Serializable
data class PasswordRequest(val oldPassword: String, val newPassword: String, val logoutOthers: Boolean = false)

@Serializable
data class PasswordResponse(val revoked: Int = 0)

@Serializable
data class BotNameRequest(val name: String)

@Serializable
data class BotTtlRequest(val enabled: Boolean)

/** GET/PUT /push. style: hint（只说有消息）| text（带上原文）| count（只报条数）. */
@Serializable
data class PushPref(
    val provider: String = "off",
    val secret: String = "",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val intervalSec: Int = 60,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val style: String = "text",
)

@Serializable
data class PushKeyUp(val s: Int, val r: Int, val key: String)

@Serializable
data class PushKeysRequest(val keys: List<PushKeyUp>)

@Serializable
data class SharedValueRequest(val value: String)

@Serializable
data class DeviceInfo(val id: Long, val device: String? = null, val createdAt: Long, val lastSeen: Long? = null, val current: Boolean = false)

/** /apk/latest.json written by deploy/android-build.sh. */
@Serializable
data class ReleaseInfo(val versionCode: Int, val versionName: String, val url: String, val size: Long = 0, val notes: String = "")

fun Frame.toJson(): String = ProtoJson.encodeToString(Frame.serializer(), this)
