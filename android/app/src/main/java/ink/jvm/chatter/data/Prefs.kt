package ink.jvm.chatter.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ink.jvm.chatter.BuildConfig
import ink.jvm.chatter.crypto.SecurePrefs

/** Small settings store. Token is app-private storage; the phone lock is the security boundary. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("chatter", Context.MODE_PRIVATE)

    /**
     * Secrets (device token, e2e private keys) live in [SecurePrefs]: AES-GCM under an Android Keystore key.
     * Falls back to the plain file if the keystore is broken. Values written by versions before 1.5 through
     * androidx EncryptedSharedPreferences are migrated once.
     */
    private val secureStore = SecurePrefs(context)
    private val secure: Store = if (secureStore.available) object : Store {
        override fun get(name: String): String? = secureStore.getString(name)
        override fun put(name: String, value: String?) = secureStore.putString(name, value)
        override fun clear() = secureStore.clear()
    } else object : Store {
        override fun get(name: String): String? = sp.getString(name, null)
        override fun put(name: String, value: String?) { sp.edit().putString(name, value).apply() }
        override fun clear() {}
    }

    private interface Store {
        fun get(name: String): String?
        fun put(name: String, value: String?)
        fun clear()
    }

    init {
        // one-time migration of a token stored by versions before 0.9
        if (secureStore.available) sp.getString("token", null)?.let { t ->
            secure.put("token", t)
            sp.edit().remove("token").apply()
        }
        // 1.5: move secrets out of androidx EncryptedSharedPreferences (unmaintained alpha) into SecurePrefs.
        if (secureStore.available && !sp.getBoolean("secureMigrated", false)) {
            runCatching {
                val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
                val old = EncryptedSharedPreferences.create(context, "chatter.secure", key, EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
                for (name in listOf("token", "e2ePriv", "keyRing")) {
                    old.getString(name, null)?.let { v -> if (secure.get(name) == null) secure.put(name, v) }
                }
                old.edit().clear().apply()
            }.onFailure { Log.w("Prefs", "secure migration skipped: ${it.message}") }
            sp.edit().putBoolean("secureMigrated", true).apply()
        }
    }

    var serverUrl: String
        get() = sp.getString("server", DEFAULT_SERVER) ?: DEFAULT_SERVER
        set(v) { sp.edit().putString("server", v).apply() }

    var token: String?
        get() = secure.get("token")
        set(v) { secure.put("token", v) }

    /** Highest peer seq we told the server we read; seeds the unread divider on a cold start. */
    var readUpto: Long
        get() = sp.getLong("readUpto", 0)
        set(v) { sp.edit().putLong("readUpto", v).apply() }

    // ---- end-to-end encryption ----

    /** My ECDH identity (PKCS#8 / X.509, base64). The private half lives in the encrypted store. */
    var e2ePriv: String?
        get() = secure.get("e2ePriv")
        set(v) { secure.put("e2ePriv", v) }

    /** 1.5: the whole key ring (identity + rotating epoch keys + the peer's verified epoch keys), JSON. */
    var keyRing: String?
        get() = secure.get("keyRing")
        set(v) { secure.put("keyRing", v) }

    /** When we last rotated our epoch key (ms); 0 = never. */
    var lastRotationAt: Long
        get() = sp.getLong("lastRotationAt", 0)
        set(v) { sp.edit().putLong("lastRotationAt", v).apply() }

    /** Ask the assistant to transcribe a voice note when the phone cannot (sends the audio to it in the clear). */
    var botTranscribeFallback: Boolean
        get() = sp.getBoolean("botTranscribeFallback", false)
        set(v) { sp.edit().putBoolean("botTranscribeFallback", v).apply() }

    /** Voice-note playback speed: 1.0 / 1.5 / 2.0. */
    var voiceSpeed: Float
        get() = sp.getFloat("voiceSpeed", 1f)
        set(v) { sp.edit().putFloat("voiceSpeed", v).apply() }

    var e2ePub: String?
        get() = sp.getString("e2ePub", null)
        set(v) { sp.edit().putString("e2ePub", v).apply() }

    /** Peer key we currently trust (TOFU); a different key from the server raises the safety-number warning. */
    var peerPub: String?
        get() = sp.getString("peerPub", null)
        set(v) { sp.edit().putString("peerPub", v).apply() }

    /** The two of you compared the safety number for the current key pair. */
    var e2eVerified: Boolean
        get() = sp.getBoolean("e2eVerified", false)
        set(v) { sp.edit().putBoolean("e2eVerified", v).apply() }

    /** Disappearing-message timer agreed for the chat (seconds, 0 = off); mirrors the server. */
    var ttlSeconds: Long
        get() = sp.getLong("ttlSeconds", 0)
        set(v) { sp.edit().putLong("ttlSeconds", v).apply() }

    /** Unsent composer text. */
    var draft: String
        get() = sp.getString("draft", "") ?: ""
        set(v) { sp.edit().putString("draft", v).apply() }

    /** 1.7: unsent text on the assistant page. */
    var botDraft: String
        get() = sp.getString("botDraft", "") ?: ""
        set(v) { sp.edit().putString("botDraft", v).apply() }

    /** Text size multiplier: 0.9 / 1.0 / 1.15 / 1.3. */
    var fontScale: Float
        get() = sp.getFloat("fontScale", 1f)
        set(v) { sp.edit().putFloat("fontScale", v).apply() }

    /** Ask for fingerprint / screen lock when the app comes to the front. */
    var appLock: Boolean
        get() = sp.getBoolean("appLock", false)
        set(v) { sp.edit().putBoolean("appLock", v).apply() }

    /** FLAG_SECURE: no screenshots, hidden in recents. */
    var secureScreen: Boolean
        get() = sp.getBoolean("secureScreen", false)
        set(v) { sp.edit().putBoolean("secureScreen", v).apply() }

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0)
        set(v) { sp.edit().putLong("lastUpdateCheck", v).apply() }

    /** versionCode the user chose to skip in the update dialog. */
    var skippedVersion: Int
        get() = sp.getInt("skippedVersion", 0)
        set(v) { sp.edit().putInt("skippedVersion", v).apply() }

    var userId: Long
        get() = sp.getLong("userId", 0)
        set(v) { sp.edit().putLong("userId", v).apply() }

    var userName: String
        get() = sp.getString("userName", "") ?: ""
        set(v) { sp.edit().putString("userName", v).apply() }

    var peerId: Long
        get() = sp.getLong("peerId", 0)
        set(v) { sp.edit().putLong("peerId", v).apply() }

    var peerName: String
        get() = sp.getString("peerName", "") ?: ""
        set(v) { sp.edit().putString("peerName", v).apply() }

    /** Display name of the in-chat assistant as last told by the server; empty until a 1.1+ server said hello. */
    var botName: String
        get() = sp.getString("botName", "") ?: ""
        set(v) { sp.edit().putString("botName", v).apply() }

    /** How the main chat shows assistant traffic: "collapsed" (one line per question) or "hidden" (nothing). */
    var botInMain: String
        get() = sp.getString("botInMain", "collapsed") ?: "collapsed"
        set(v) { sp.edit().putString("botInMain", v).apply() }

    /** Highest assistant seq seen with the assistant page open; replies above it count as unread. */
    var botReadSeq: Long
        get() = sp.getLong("botReadSeq", 0)
        set(v) { sp.edit().putLong("botReadSeq", v).apply() }

    /** Notify when the assistant answers a question I asked (never for the peer's questions). */
    var botNotify: Boolean
        get() = sp.getBoolean("botNotify", true)
        set(v) { sp.edit().putBoolean("botNotify", v).apply() }

    // ---- 1.3 ----

    /** 开车模式: read every new assistant answer aloud with the system TTS. */
    var botDriveMode: Boolean
        get() = sp.getBoolean("botDriveMode", false)
        set(v) { sp.edit().putBoolean("botDriveMode", v).apply() }

    /** Voice notes on the assistant page are transcribed on the phone and sent as text when possible. */
    var botVoiceToText: Boolean
        get() = sp.getBoolean("botVoiceToText", true)
        set(v) { sp.edit().putBoolean("botVoiceToText", v).apply() }

    /** When on, transcription is posted to [cloudSttUrl] instead of the on-device SenseVoice model. Default off. */
    var cloudStt: Boolean
        get() = sp.getBoolean("cloudStt", false)
        set(v) { sp.edit().putBoolean("cloudStt", v).apply() }

    /** Full POST URL of an OpenAI-compatible transcription endpoint, or this app's own `/stt`. */
    var cloudSttUrl: String
        get() = sp.getString("cloudSttUrl", "") ?: ""
        set(v) { sp.edit().putString("cloudSttUrl", v).apply() }

    /**
     * After a human call, the user may summarize this phone's captions.
     * Default off. The getter is unused and stays.
     * Call audio is never uploaded. The transcript text is sent only when the user
     * has selected the cloud summary endpoint and then taps summarize.
     */
    var callSummary: Boolean
        get() = sp.getBoolean("callSummary", false)
        set(v) { sp.edit().putBoolean("callSummary", v).apply() }

    /** Selected summary engine. Default is the cloud endpoint. Local ids use the on-device GPU. */
    var summaryModel: String
        get() = sp.getString("summaryModel", "cloud") ?: "cloud"
        set(v) { sp.edit().putString("summaryModel", v).apply() }

    /** OpenAI-compatible chat base URL for the optional cloud summary. Empty until the user fills it in. */
    var cloudLlmUrl: String
        get() = sp.getString("cloudLlmUrl", "") ?: ""
        set(v) { sp.edit().putString("cloudLlmUrl", v).apply() }

    /** Bearer token for [cloudLlmUrl]. May be empty. Never log this value. */
    var cloudLlmKey: String
        get() = sp.getString("cloudLlmKey", "") ?: ""
        set(v) { sp.edit().putString("cloudLlmKey", v).apply() }

    /** Model name sent as the OpenAI `model` field. Required when the cloud option is selected. */
    var cloudLlmModel: String
        get() = sp.getString("cloudLlmModel", "") ?: ""
        set(v) { sp.edit().putString("cloudLlmModel", v).apply() }

    /** Fetch OpenGraph cards for links in text messages (the phone contacts the site). */
    var linkPreview: Boolean
        get() = sp.getBoolean("linkPreview", true)
        set(v) { sp.edit().putBoolean("linkPreview", v).apply() }

    /** Ask before downloading full-size images / videos / files on mobile data. */
    var wifiOnlyMedia: Boolean
        get() = sp.getBoolean("wifiOnlyMedia", false)
        set(v) { sp.edit().putBoolean("wifiOnlyMedia", v).apply() }

    /** Chat wallpaper: a built-in id (rose / sky / mint / lavender / dusk / plain) or "file:<absolute path>". */
    var chatBg: String
        get() = sp.getString("chatBg", "rose") ?: "rose"
        set(v) { sp.edit().putString("chatBg", v).apply() }

    /** Accent colour set: rose / sky / mint / lavender. */
    var accent: String
        get() = sp.getString("accent", "rose") ?: "rose"
        set(v) { sp.edit().putString("accent", v).apply() }

    /** Bubble corner style: round / square. */
    var bubbleStyle: String
        get() = sp.getString("bubbleStyle", "round") ?: "round"
        set(v) { sp.edit().putString("bubbleStyle", v).apply() }

    /** Custom incoming-call ringtone URI; null = the phone's default. */
    var ringtoneUri: String?
        get() = sp.getString("ringtoneUri", null)
        set(v) { sp.edit().putString("ringtoneUri", v).apply() }

    /** Quiet hours: calls only vibrate, messages do not ring. Start/end in minutes since midnight. */
    var quietEnabled: Boolean
        get() = sp.getBoolean("quietEnabled", false)
        set(v) { sp.edit().putBoolean("quietEnabled", v).apply() }

    var quietStart: Int
        get() = sp.getInt("quietStart", 23 * 60)
        set(v) { sp.edit().putInt("quietStart", v).apply() }

    var quietEnd: Int
        get() = sp.getInt("quietEnd", 7 * 60)
        set(v) { sp.edit().putInt("quietEnd", v).apply() }

    /** True right now if quiet hours are on and the clock is inside the window (wraps past midnight). */
    fun inQuietHours(): Boolean {
        if (!quietEnabled) return false
        val now = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
        val s = quietStart; val e = quietEnd
        return if (s <= e) now in s until e else now >= s || now < e
    }

    /** Distinct sound when the peer quotes one of my messages or pats me. */
    var notifyQuote: Boolean
        get() = sp.getBoolean("notifyQuote", true)
        set(v) { sp.edit().putBoolean("notifyQuote", v).apply() }

    /** Last battery level we told the server (throttle: only re-send on a 5 % change or charging flip). */
    var lastBatterySent: Int
        get() = sp.getInt("lastBatterySent", -1)
        set(v) { sp.edit().putInt("lastBatterySent", v).apply() }

    var lastChargingSent: Boolean
        get() = sp.getBoolean("lastChargingSent", false)
        set(v) { sp.edit().putBoolean("lastChargingSent", v).apply() }

    /** 1.8: force the human chat to look unread on this phone until it is opened. */
    var markUnreadPeer: Boolean
        get() = sp.getBoolean("markUnreadPeer", false)
        set(v) { sp.edit().putBoolean("markUnreadPeer", v).apply() }

    var markUnreadBot: Boolean
        get() = sp.getBoolean("markUnreadBot", false)
        set(v) { sp.edit().putBoolean("markUnreadBot", v).apply() }

    /** Pin the assistant row on this phone. The human chat pin lives in the shared store. */
    var pinBot: Boolean
        get() = sp.getBoolean("pinBot", false)
        set(v) { sp.edit().putBoolean("pinBot", v).apply() }

    /** Lines of `epoch|preview` for message reminders. */
    var reminders: String
        get() = sp.getString("reminders", "") ?: ""
        set(v) { sp.edit().putString("reminders", v).apply() }

    /** Cached copy of the shared store (key -> value as received), so the UI has it before the socket is up. */
    var sharedCache: String
        get() = sp.getString("sharedCache", "{}") ?: "{}"
        set(v) { sp.edit().putString("sharedCache", v).apply() }

    /** Highest server seq we have applied (including deletions), so sync never re-fetches what we already handled. */
    var lastSeq: Long
        get() = sp.getLong("lastSeq", 0)
        set(v) { sp.edit().putLong("lastSeq", v).apply() }

    /** Whether the user has seen the "keep me alive in background" guidance. */
    var bgGuideShown: Boolean
        get() = sp.getBoolean("bgGuideShown", false)
        set(v) { sp.edit().putBoolean("bgGuideShown", v).apply() }

    /** Wall-clock time we last had a working socket; 0 = never. */
    var lastConnectedAt: Long
        get() = sp.getLong("lastConnectedAt", 0)
        set(v) { sp.edit().putLong("lastConnectedAt", v).apply() }

    /** Process start times (newest last), kept for 24 h. More than one per day means the ROM killed us. */
    var processStarts: List<Long>
        get() = (sp.getString("processStarts", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
        set(v) { sp.edit().putString("processStarts", v.joinToString(",")).apply() }

    fun recordProcessStart() {
        val cutoff = System.currentTimeMillis() - 24 * 3600_000L
        processStarts = (processStarts.filter { it > cutoff } + System.currentTimeMillis()).takeLast(200)
    }

    /** Restarts in the last 24 h, not counting the first launch in that window. */
    fun killsLast24h(): Int {
        val cutoff = System.currentTimeMillis() - 24 * 3600_000L
        return (processStarts.count { it > cutoff } - 1).coerceAtLeast(0)
    }

    val loggedIn: Boolean get() = !token.isNullOrEmpty()

    fun clear() {
        val server = serverUrl
        val guide = bgGuideShown
        val starts = sp.getString("processStarts", "")
        val lock = appLock; val sec = secureScreen; val scale = fontScale
        val bg = chatBg; val acc = accent; val bub = bubbleStyle; val ringtone = ringtoneUri
        val qe = quietEnabled; val qs = quietStart; val qEnd = quietEnd
        // The identity key survives logout so re-logging in on the same phone keeps the history decryptable.
        val priv = e2ePriv; val pub = e2ePub; val ring = keyRing
        sp.edit().clear().putString("server", server).putBoolean("bgGuideShown", guide).putString("processStarts", starts)
            .putBoolean("appLock", lock).putBoolean("secureScreen", sec).putFloat("fontScale", scale).putString("e2ePub", pub)
            .putString("chatBg", bg).putString("accent", acc).putString("bubbleStyle", bub).putString("ringtoneUri", ringtone)
            .putBoolean("quietEnabled", qe).putInt("quietStart", qs).putInt("quietEnd", qEnd).putBoolean("secureMigrated", true).apply()
        secure.clear()
        e2ePriv = priv
        keyRing = ring
    }

    companion object {
        const val DEFAULT_SERVER = BuildConfig.DEFAULT_SERVER
    }
}
