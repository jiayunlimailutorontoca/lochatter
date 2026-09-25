package ink.jvm.chatter

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import coil.ImageLoader
import coil.ImageLoaderFactory
import ink.jvm.chatter.call.CallManager
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.media.LocalSummary
import ink.jvm.chatter.data.Db
import ink.jvm.chatter.data.LocalMessage
import ink.jvm.chatter.data.Prefs
import ink.jvm.chatter.service.Notifications
import ink.jvm.chatter.widget.ChatWidget
import ink.jvm.chatter.widget.WidgetState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.Locale

class ChatterApp : Application(), ImageLoaderFactory {
    lateinit var prefs: Prefs
        private set
    lateinit var db: Db
        private set
    lateinit var repo: ChatRepository
        private set
    lateinit var calls: CallManager
        private set

    /** System text-to-speech, created lazily for 朗读 / 开车模式. */
    private var tts: TextToSpeech? = null
    /** True while the system TTS is reading (1.6: hands-free dictation waits for it). */
    val speaking = kotlinx.coroutines.flow.MutableStateFlow(false)
    private var ttsReady = false

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) LocalSummary.release()
    }

    override fun onCreate() {
        super.onCreate()
        ink.jvm.chatter.util.Diag.init(this)
        prefs = Prefs(this)
        db = Db(this)
        repo = ChatRepository(this, prefs, db)
        calls = CallManager(this, repo)
        Notifications.createChannels(this)
        prefs.recordProcessStart()

        // Posted from the Application so a message still notifies when the process is alive
        // but the foreground service could not be (re)started.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope.launch {
            repo.incoming.collect { m ->
                if (m.fromBot) {
                    // Assistant answers live on their own page: quiet channel, only for the one who asked.
                    if (prefs.botDriveMode) speak(m)
                    if (repo.botVisible || !prefs.botNotify) return@collect
                    val asker = m.reply?.from
                    if (asker != null && asker != prefs.userId && m.kind != "card") return@collect
                    Notifications.botReply(this@ChatterApp, prefs.botName.ifEmpty { "助手" }, ChatRepository.previewOf(m))
                    return@collect
                }
                if (m.kind == "pat") {
                    if (!repo.chatVisible) Notifications.pat(this@ChatterApp, prefs.peerName.ifEmpty { "对方" }, prefs.inQuietHours())
                    return@collect
                }
                if (repo.chatVisible) return@collect
                val from = prefs.peerName.ifEmpty { "新消息" }
                val quotedMe = m.reply?.from == prefs.userId
                val quiet = prefs.inQuietHours()
                Notifications.message(this@ChatterApp, from, m, quotedMe = quotedMe, quiet = quiet, mentionChannel = prefs.notifyQuote)
                val thumb = m.media?.thumbId ?: m.media?.id?.takeIf { m.kind == "image" }
                if (m.kind == "image" && thumb != null && !m.once) launch(Dispatchers.IO) {
                    runCatching {
                        val f = ink.jvm.chatter.util.MediaSaver.fetch(this@ChatterApp, repo, ink.jvm.chatter.data.MediaInfo(thumb, "image/jpeg", -1))
                        val uri = androidx.core.content.FileProvider.getUriForFile(this@ChatterApp, packageName + ".files", f)
                        grantUriPermission("com.android.systemui", uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        if (!repo.chatVisible) Notifications.message(this@ChatterApp, from, m, image = uri, quotedMe = quotedMe, quiet = quiet, mentionChannel = prefs.notifyQuote)
                    }
                }
            }
        }
        scope.launch {
            repo.botEdited.collect { m ->
                if (!repo.botVisible && Notifications.botShowing) Notifications.botReply(this@ChatterApp, prefs.botName.ifEmpty { "助手" }, ChatRepository.previewOf(m))
            }
        }
        scope.launch {
            // 拍一拍 buzz, whether or not the chat is on screen.
            repo.patIncoming.collect { vibrate(longArrayOf(0, 60, 60, 60, 60, 200)) }
        }
        scope.launch {
            calls.state.collect { st ->
                if (st is CallManager.State.Incoming) {
                    if (!repo.appVisible) Notifications.incomingCall(this@ChatterApp, prefs.peerName.ifEmpty { "来电" }, st.video)
                } else {
                    Notifications.cancelCall(this@ChatterApp)
                }
            }
        }
        scope.launch {
            // Home-screen widget: re-render at most every 2 s while things change.
            repo.widgetTick.collectLatest {
                delay(2000)
                runCatching { ChatWidget.update(this@ChatterApp, widgetState()) }
            }
        }

        // Battery for the peer's top bar. Sticky broadcast: registering returns the current state at once.
        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                if (level < 0 || scale <= 0) return
                val pct = level * 100 / scale
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
                repo.reportBattery(pct, charging)
            }
        }
        runCatching { registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
    }

    /** What the widget shows; built here so the widget never touches the data layer directly. */
    fun widgetState(): WidgetState {
        val last = repo.messages.value.lastOrNull { !it.fromBot && !it.toBot && !it.isSystem }
        val next = repo.anniversaries.value.minByOrNull { it.daysLeft() }
        return WidgetState(
            peerName = prefs.peerName.ifEmpty { "对方" },
            online = repo.peerOnline.value,
            lastSeen = repo.peerLastSeen.value,
            battery = repo.peerBattery.value,
            charging = repo.peerCharging.value,
            lastMessage = last?.let { (if (it.from == prefs.userId) "我：" else "") + ChatRepository.previewOf(it) },
            lastMessageTs = last?.ts,
            unread = db.countFromAfter(prefs.peerId, repo.myReadUpto),
            countdownTitle = next?.title,
            countdownDays = next?.daysLeft()?.toInt(),
            botName = prefs.botName.ifEmpty { null },
        )
    }

    fun vibrate(pattern: LongArray) {
        val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31) (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        runCatching { v?.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    /** Reads an assistant answer aloud (system TTS, Chinese). */
    fun speak(m: LocalMessage) = speak(ChatRepository.previewOf(m).let { if (m.kind == "text" || m.kind == "card") ink.jvm.chatter.ui.stripMarkdown(m.text ?: "") else it })

    fun speak(text: String) {
        val t = text.trim().take(4000)
        if (t.isEmpty()) return
        val engine = tts
        if (engine != null && ttsReady) {
            engine.speak(t, TextToSpeech.QUEUE_FLUSH, null, "lochatter")
            return
        }
        if (engine == null) {
            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    runCatching { tts?.language = Locale.CHINA }
                    attachTtsListener()
                    tts?.speak(t, TextToSpeech.QUEUE_FLUSH, null, "lochatter")
                }
            }
        }
    }

    fun stopSpeaking() { runCatching { tts?.stop() }; speaking.value = false }

    private fun attachTtsListener() {
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { speaking.value = true }
            override fun onDone(utteranceId: String?) { speaking.value = false }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { speaking.value = false }
            override fun onError(utteranceId: String?, errorCode: Int) { speaking.value = false }
        })
    }

    /** Warms the engine up and calls [onMissing] (main thread) with a hint when it cannot speak Chinese; used when 开车模式 is switched on. */
    fun checkChineseTts(onMissing: (String) -> Unit) {
        val hint = "手机没有中文朗读引擎，回复读不出来。请在系统设置里装一个语音引擎（如「讯飞语音」或「Google 文字转语音」）"
        fun missing(engine: TextToSpeech?): Boolean = runCatching { (engine?.isLanguageAvailable(Locale.CHINA) ?: -2) < TextToSpeech.LANG_AVAILABLE }.getOrDefault(true)
        val engine = tts
        if (engine != null && ttsReady) { if (missing(engine)) onMissing(hint); return }
        if (engine == null) {
            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (!ttsReady) { onMissing(hint); return@TextToSpeech }
                runCatching { tts?.language = Locale.CHINA }
                attachTtsListener()
                if (missing(tts)) onMissing(hint)
            }
        }
    }

    /** Coil uses the bearer-authenticated client so <img> loads from /media/{id} just work. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(repo.authHttp)
            .crossfade(true)
            .components {
                if (android.os.Build.VERSION.SDK_INT >= 28) add(coil.decode.ImageDecoderDecoder.Factory()) else add(coil.decode.GifDecoder.Factory())
                add(coil.decode.VideoFrameDecoder.Factory())
            }
            .build()
}
