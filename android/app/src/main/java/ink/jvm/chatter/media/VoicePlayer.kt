package ink.jvm.chatter.media

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.media.PlaybackParams
import android.os.Build
import android.os.PowerManager
import ink.jvm.chatter.util.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Voice notes. The clip is fetched through the authenticated (and decrypting) client into the cache, then
 * played from the file. Raise the phone to your ear and playback moves to the earpiece with the screen off,
 * like WeChat; lower it and it goes back to the speaker. 1.5: playback speed, seeking, and a completion
 * hook so the screen can auto-play the next unheard clip.
 */
class VoicePlayer(private val ctx: Context, private val http: OkHttpClient) {
    /** Message id currently playing, null when idle. */
    val playing = MutableStateFlow<String?>(null)
    /** 0..1 position of the current clip. */
    val progress = MutableStateFlow(0f)
    /** True while audio goes to the earpiece (phone at the ear). */
    val earpiece = MutableStateFlow(false)
    /** 1.0 / 1.5 / 2.0, applied to the current and following clips. */
    val speed = MutableStateFlow(1f)
    /** Id of the clip that just played to its end (for auto-playing the next one). */
    val finished = MutableStateFlow<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var mp: MediaPlayer? = null
    private var file: File? = null
    private var fetchJob: Job? = null
    private var near = false
    private var wake: PowerManager.WakeLock? = null

    private val proximity = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val sensor = e.sensor
            val isNear = e.values[0] < minOf(sensor.maximumRange, 5f)
            if (isNear != near) {
                near = isNear
                if (mp != null) reroute()
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun toggle(id: String, url: String, size: Long) {
        if (playing.value == id) {
            stop()
            return
        }
        stop()
        playing.value = id
        progress.value = 0f
        fetchJob = scope.launch {
            val f = try {
                fetch(id, url, size)
            } catch (e: Exception) {
                Diag.warn("Voice", "fetch failed", e)
                stop()
                return@launch
            }
            if (playing.value != id) return@launch
            file = f
            startSensor()
            play(f, 0)
        }
    }

    /** Cycles 1x → 1.5x → 2x → 1x and applies it at once. */
    fun cycleSpeed(): Float {
        val next = when {
            speed.value < 1.4f -> 1.5f
            speed.value < 1.9f -> 2f
            else -> 1f
        }
        setSpeed(next)
        return next
    }

    fun setSpeed(v: Float) {
        speed.value = v
        mp?.let { p -> runCatching { if (p.isPlaying) p.playbackParams = PlaybackParams().setSpeed(v) } }
    }

    /** Short beep before the next voice note in a run, so consecutive clips don't blur together. */
    fun chime() {
        val stream = if (earpiece.value) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC
        runCatching {
            val tg = ToneGenerator(stream, 50)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
            scope.launch {
                kotlinx.coroutines.delay(200)
                runCatching { tg.release() }
            }
        }
    }

    /** Drag on the progress bar: 0..1 of the current clip. */
    fun seek(fraction: Float) {
        val p = mp ?: return
        val d = runCatching { p.duration }.getOrDefault(0)
        if (d <= 0) return
        runCatching { p.seekTo((d * fraction.coerceIn(0f, 1f)).toInt()) }
        progress.value = fraction.coerceIn(0f, 1f)
    }

    private suspend fun fetch(id: String, url: String, size: Long): File = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, "voice").apply { mkdirs() }
        val f = File(dir, "$id.m4a")
        if (f.exists() && f.length() > 0) return@withContext f
        http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            val tmp = File(dir, "$id.part")
            FileOutputStream(tmp).use { out -> resp.body!!.byteStream().copyTo(out) }
            tmp.renameTo(f)
        }
        // keep the voice cache small
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(60)?.forEach { it.delete() }
        f
    }

    private fun play(f: File, positionMs: Int) {
        val p = MediaPlayer()
        val toEar = near
        p.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(if (toEar) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        runCatching { p.setDataSource(f.absolutePath) }.onFailure { p.release(); stop(); return }
        p.setOnPreparedListener { mpl ->
            if (positionMs > 0) mpl.seekTo(positionMs)
            runCatching { if (speed.value != 1f) mpl.playbackParams = PlaybackParams().setSpeed(speed.value) }
            mpl.start()
        }
        p.setOnCompletionListener {
            val id = playing.value
            stop()
            finished.value = id
        }
        p.setOnErrorListener { _, _, _ -> stop(); true }
        applyRoute(toEar)
        p.prepareAsync()
        mp = p
        earpiece.value = toEar
    }

    /** Proximity changed mid-clip: recreate the player on the other output at the same position. */
    private fun reroute() {
        val old = mp ?: return
        val f = file ?: return
        val pos = runCatching { old.currentPosition }.getOrDefault(0)
        runCatching { old.stop() }
        runCatching { old.release() }
        mp = null
        play(f, pos)
    }

    /** Set by CallManager for the duration of a call: the call owns AudioManager mode and route. */
    @Volatile var inCall = false
    private var routedToEar = false

    private fun applyRoute(toEar: Boolean) {
        if (toEar) acquireWake() else releaseWake()
        if (inCall) return
        if (toEar) {
            routedToEar = true
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                am.availableCommunicationDevices.firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }?.let { runCatching { am.setCommunicationDevice(it) } }
            }
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
        } else if (routedToEar) {
            // Only undo what we did ourselves; a plain media playback never changed the mode.
            routedToEar = false
            if (Build.VERSION.SDK_INT >= 31) runCatching { am.clearCommunicationDevice() }
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = false
            am.mode = AudioManager.MODE_NORMAL
        }
    }

    private fun startSensor() {
        sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sm.registerListener(proximity, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    private fun acquireWake() {
        if (wake?.isHeld == true) return
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        wake = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "chatter:voice").also { runCatching { it.acquire(10 * 60_000L) } }
    }

    private fun releaseWake() {
        wake?.let { if (it.isHeld) runCatching { it.release() } }
        wake = null
    }

    /** Called from a ticker while something plays. */
    fun tick() {
        val p = mp ?: return
        val d = runCatching { p.duration }.getOrDefault(0)
        if (d > 0 && runCatching { p.isPlaying }.getOrDefault(false)) progress.value = p.currentPosition / d.toFloat()
    }

    fun stop() {
        fetchJob?.cancel()
        fetchJob = null
        mp?.let { runCatching { it.stop() }; runCatching { it.release() } }
        mp = null
        file = null
        runCatching { sm.unregisterListener(proximity) }
        near = false
        applyRoute(false)
        earpiece.value = false
        playing.value = null
        progress.value = 0f
    }
}
