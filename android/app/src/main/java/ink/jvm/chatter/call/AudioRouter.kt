package ink.jvm.chatter.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Output routes in the order the picker lists them. */
enum class AudioRoute { EARPIECE, SPEAKER, WIRED, BLUETOOTH }

/** One selectable output. [name] is what the picker shows; Bluetooth uses the headset's own name. */
data class AudioDevice(val route: AudioRoute, val name: String) {
    /** Label under the in-call button. */
    val shortName: String
        get() = when (route) {
            AudioRoute.EARPIECE -> "听筒"
            AudioRoute.SPEAKER -> "扬声器"
            AudioRoute.WIRED -> "耳机"
            AudioRoute.BLUETOOTH -> "蓝牙"
        }
}

/**
 * Call audio routing with an explicit device list, like the phone dialer:
 * 听筒 / 扬声器 / 有线耳机 / 蓝牙. WebRTC's JavaAudioDeviceModule follows whatever
 * communication device is selected here.
 *
 * Auto policy (until the user picks something): Bluetooth → wired → earpiece for voice calls,
 * speaker for video calls. A headset that shows up mid-call takes over; when the chosen device
 * disappears we drop back to auto.
 */
class AudioRouter(private val ctx: Context) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var preferSpeaker = false
    private var userChoice: AudioRoute? = null
    private var focusReq: AudioFocusRequest? = null
    private var scoReceiver: BroadcastReceiver? = null
    private var commListener: Any? = null // AudioManager.OnCommunicationDeviceChangedListener on API 31+
    private var scoRetries = 0
    private var lastWant: AudioRoute? = null
    private var reapplyRetries = 0

    private val _devices = MutableStateFlow<List<AudioDevice>>(emptyList())
    /** Outputs available right now, sorted 听筒 / 扬声器 / 耳机 / 蓝牙. Empty outside a call. */
    val devices: StateFlow<List<AudioDevice>> = _devices.asStateFlow()

    private val _current = MutableStateFlow(AudioDevice(AudioRoute.EARPIECE, "听筒"))
    /** Where audio is going now. */
    val current: StateFlow<AudioDevice> = _current.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            if (!running) return
            // A headset plugged in / connected mid-call wins over whatever was chosen before.
            if (added.any { routeOf(it) == AudioRoute.WIRED || routeOf(it) == AudioRoute.BLUETOOTH }) userChoice = null
            // Give the framework a moment to publish the new device in the communication list.
            handler.postDelayed({ if (running) apply() }, 400)
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (running) apply()
        }
    }

    fun start(preferSpeaker: Boolean) {
        if (running) return
        running = true
        this.preferSpeaker = preferSpeaker
        userChoice = null
        scoRetries = 0
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        requestFocus()
        am.registerAudioDeviceCallback(deviceCallback, handler)

        val rx = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!running) return
                val st = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                Log.i(TAG, "SCO state=$st")
                when (st) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> scoRetries = 0
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED ->
                        if (_current.value.route == AudioRoute.BLUETOOTH && scoRetries < 3) {
                            scoRetries++
                            handler.postDelayed({
                                if (running && _current.value.route == AudioRoute.BLUETOOTH) startSco()
                            }, 800)
                        }
                }
            }
        }
        ContextCompat.registerReceiver(
            ctx, rx, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        scoReceiver = rx

        if (Build.VERSION.SDK_INT >= 31) {
            val l = AudioManager.OnCommunicationDeviceChangedListener { device ->
                if (!running) return@OnCommunicationDeviceChangedListener
                // The system moved us (headset gone, SCO failed…): show the truth, not what we asked for.
                val r = device?.let { routeOf(it) } ?: return@OnCommunicationDeviceChangedListener
                if (r != _current.value.route) {
                    Log.i(TAG, "system switched route to $r")
                    _current.value = _devices.value.firstOrNull { it.route == r } ?: AudioDevice(r, defaultName(r))
                    // Something else (a media player, a ROM quirk on background) took the route away while the
                    // device we want is still there: take it back, a few times at most so we never fight a headset.
                    val want = lastWant
                    if (want != null && want != r && _devices.value.any { it.route == want } && reapplyRetries < 3) {
                        reapplyRetries++
                        handler.postDelayed({ if (running && _current.value.route != want) apply() }, 500)
                    }
                }
            }
            am.addOnCommunicationDeviceChangedListener(ContextCompat.getMainExecutor(ctx), l)
            commListener = l
        }
        apply()
    }

    /** User picked an output in the sheet. Sticks until that device disappears. */
    fun select(route: AudioRoute) {
        userChoice = route
        scoRetries = 0
        reapplyRetries = 0
        apply()
    }

    /** Re-assert the current choice (after the activity was hidden / shown). No-op outside a call. */
    fun reapply() {
        if (!running) return
        reapplyRetries = 0
        apply()
    }

    /** Speaker on/off shortcut for the plain two-way toggle. Off means the best non-speaker device. */
    fun setSpeaker(on: Boolean) {
        val target = if (on) {
            AudioRoute.SPEAKER
        } else {
            _devices.value.filter { it.route != AudioRoute.SPEAKER }.maxByOrNull { it.route.ordinal }?.route ?: AudioRoute.EARPIECE
        }
        select(target)
    }

    fun speakerOn(): Boolean = _current.value.route == AudioRoute.SPEAKER

    fun hasHeadset(): Boolean = _devices.value.any { it.route == AudioRoute.WIRED || it.route == AudioRoute.BLUETOOTH }

    fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        runCatching { am.unregisterAudioDeviceCallback(deviceCallback) }
        scoReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        scoReceiver = null
        if (Build.VERSION.SDK_INT >= 31) {
            (commListener as? AudioManager.OnCommunicationDeviceChangedListener)?.let {
                runCatching { am.removeOnCommunicationDeviceChangedListener(it) }
            }
            commListener = null
        }
        stopSco()
        if (Build.VERSION.SDK_INT >= 31) runCatching { am.clearCommunicationDevice() }
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = false
        am.mode = AudioManager.MODE_NORMAL
        abandonFocus()
        userChoice = null
        lastWant = null
        _devices.value = emptyList()
        _current.value = AudioDevice(AudioRoute.EARPIECE, "听筒")
    }

    // ---- routing ----

    private fun apply() {
        if (!running) return
        val infos = enumerate()
        val list = ArrayList<AudioDevice>()
        for (d in infos) {
            val r = routeOf(d) ?: continue
            if (list.any { it.route == r }) continue
            val name = if (r == AudioRoute.BLUETOOTH) {
                d.productName?.toString()?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", true) } ?: "蓝牙耳机"
            } else {
                defaultName(r)
            }
            list += AudioDevice(r, name)
        }
        if (list.none { it.route == AudioRoute.SPEAKER }) list += AudioDevice(AudioRoute.SPEAKER, "扬声器")
        list.sortBy { it.route.ordinal }

        val avail = list.map { it.route }.toSet()
        val choice = userChoice?.takeIf { it in avail }
        if (userChoice != null && choice == null) userChoice = null // the chosen device went away
        val want = choice ?: when {
            AudioRoute.BLUETOOTH in avail -> AudioRoute.BLUETOOTH
            AudioRoute.WIRED in avail -> AudioRoute.WIRED
            preferSpeaker || AudioRoute.EARPIECE !in avail -> AudioRoute.SPEAKER
            else -> AudioRoute.EARPIECE
        }

        lastWant = want
        route(want, infos)
        _devices.value = list
        _current.value = list.first { it.route == want }
        Log.i(TAG, "route → $want (${list.joinToString { it.route.name }})")
    }

    private fun route(want: AudioRoute, infos: List<AudioDeviceInfo>) {
        if (Build.VERSION.SDK_INT >= 31) {
            val target = infos.firstOrNull { routeOf(it) == want }
            if (target != null) {
                if (want != AudioRoute.BLUETOOTH) stopSco()
                val ok = runCatching { am.setCommunicationDevice(target) }.getOrDefault(false)
                if (!ok) Log.w(TAG, "setCommunicationDevice(${target.type}) refused")
                // SCO is still what carries the microphone on many classic headsets.
                if (want == AudioRoute.BLUETOOTH && target.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) startSco()
                return
            }
        }
        @Suppress("DEPRECATION")
        when (want) {
            AudioRoute.SPEAKER -> {
                stopSco()
                am.isSpeakerphoneOn = true
            }
            AudioRoute.EARPIECE, AudioRoute.WIRED -> {
                stopSco()
                am.isSpeakerphoneOn = false
            }
            AudioRoute.BLUETOOTH -> {
                am.isSpeakerphoneOn = false
                startSco()
            }
        }
    }

    private fun enumerate(): List<AudioDeviceInfo> =
        if (Build.VERSION.SDK_INT >= 31) {
            am.availableCommunicationDevices
        } else {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }

    private fun routeOf(d: AudioDeviceInfo): AudioRoute? = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> AudioRoute.EARPIECE
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioRoute.SPEAKER
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE -> AudioRoute.WIRED
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        TYPE_BLE_HEADSET,
        TYPE_BLE_SPEAKER,
        TYPE_HEARING_AID -> AudioRoute.BLUETOOTH
        else -> null
    }

    private fun defaultName(r: AudioRoute): String = when (r) {
        AudioRoute.EARPIECE -> "听筒"
        AudioRoute.SPEAKER -> "扬声器"
        AudioRoute.WIRED -> "有线耳机"
        AudioRoute.BLUETOOTH -> "蓝牙耳机"
    }

    private fun startSco() {
        @Suppress("DEPRECATION")
        if (!am.isBluetoothScoOn) {
            runCatching { am.startBluetoothSco() }
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn = true
        }
    }

    private fun stopSco() {
        @Suppress("DEPRECATION")
        if (am.isBluetoothScoOn) {
            @Suppress("DEPRECATION")
            am.isBluetoothScoOn = false
            runCatching { am.stopBluetoothSco() }
        }
    }

    // ---- focus ----

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAcceptsDelayedFocusGain(false)
            .build()
        am.requestAudioFocus(req)
        focusReq = req
    }

    private fun abandonFocus() {
        focusReq?.let { am.abandonAudioFocusRequest(it) }
        focusReq = null
    }

    private companion object {
        const val TAG = "AudioRouter"
        // Constants newer than minSdk; they are plain ints, so comparing against them is safe everywhere.
        const val TYPE_HEARING_AID = 23
        const val TYPE_BLE_HEADSET = 26
        const val TYPE_BLE_SPEAKER = 27
    }
}
