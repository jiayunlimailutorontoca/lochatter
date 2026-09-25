package ink.jvm.chatter.call

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import ink.jvm.chatter.data.CallAccept
import ink.jvm.chatter.data.CallCaption
import ink.jvm.chatter.data.CallHangup
import ink.jvm.chatter.data.CallIce
import ink.jvm.chatter.data.CallInvite
import ink.jvm.chatter.data.CallMedia
import ink.jvm.chatter.data.CallReject
import ink.jvm.chatter.data.CallSdp
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.data.Frame
import ink.jvm.chatter.data.TurnCreds
import ink.jvm.chatter.data.TurnGet
import ink.jvm.chatter.util.Diag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ink.jvm.chatter.data.CallEmoji
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import ink.jvm.chatter.media.LocalStt
import ink.jvm.chatter.media.LocalSummary
import java.util.ArrayDeque
import java.util.UUID

/**
 * One call at a time. A call with the other person encrypts SDP and ICE. A call with the assistant
 * ([withBot]) does not: the assistant has no copy of the couple's key. Media is DTLS-SRTP either way.
 * All state changes happen on the main thread.
 */
class CallManager(private val app: Application, private val repo: ChatRepository) {

    sealed class State {
        data object Idle : State()
        data class Outgoing(val callId: String, val video: Boolean) : State()
        data class Incoming(val callId: String, val video: Boolean) : State()
        data class Active(val callId: String, val video: Boolean, val connected: Boolean, val startedAt: Long) : State()
        data class Ended(val reason: String, val video: Boolean) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val state = MutableStateFlow<State>(State.Idle)
    /** This call is with the assistant, not the other person. */
    val withBot = MutableStateFlow(false)
    val captions = MutableStateFlow(CallCaptions.begin(""))
    val muted = MutableStateFlow(false)
    /** Our camera is switched off (video call only). */
    val cameraOff = MutableStateFlow(false)
    /** Peer told us their camera is off. */
    val peerCameraOff = MutableStateFlow(false)
    /** ICE dropped and we are trying to get the media path back. */
    val reconnecting = MutableStateFlow(false)
    /** Link quality sampled every 2 s from WebRTC stats; bars 0 (unknown) .. 4 (great). */
    data class Stats(val bars: Int, val rttMs: Int, val lossPct: Int, val upKbps: Int, val downKbps: Int, val relayed: Boolean)
    val stats = MutableStateFlow(Stats(0, 0, 0, 0, 0, false))
    /** True while a human call is deliberately restarting ICE to look for a direct path. */
    val tryingDirect = MutableStateFlow(false)
    /** This phone's mic, turned into text on device. Never sent. */
    val localCaptions = MutableStateFlow<List<String>>(emptyList())
    /** Set when the on-device recognizer is not on disk. Cloud transcription is not used. */
    val localCaptionHint = MutableStateFlow<String?>(null)
    /**
     * Transcript of this phone's captions, kept until the user drops or sends the summary.
     * Null unless the call was answered, the setting is on, and the model is already on disk.
     */
    val pendingSummary = MutableStateFlow<String?>(null)
    /** Outputs the user can pick from right now (empty outside a call). */
    val audioDevices: StateFlow<List<AudioDevice>> get() = audio.devices
    /** Where call audio is going. */
    val audioDevice: StateFlow<AudioDevice> get() = audio.current
    val localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val eglBase: EglBase by lazy { EglBase.create() }
    /** Signaling (SDP / ICE) is encrypted with the chat key, so the server cannot swap DTLS fingerprints. */
    val e2eOn: Boolean get() = repo.e2eOn
    val e2eVerified: Boolean get() = repo.prefs.e2eVerified
    /** Emoji reactions during the call (mine and the peer's), for the floating animation. */
    data class Float(val id: Long, val emoji: String, val mine: Boolean)
    val emojiFloats = MutableSharedFlow<Float>(extraBufferCapacity = 32)
    /** Shared drawing board over the call's data channel. */
    val whiteboard = Whiteboard()
    private var dataChannel: DataChannel? = null
    private var whiteboardOffered = false
    private var totalBytes = 0L
    /** We are sending our screen instead of the camera (or as a new video track in a voice call). */
    val screenSharing = MutableStateFlow(false)
    /** The peer is sending their screen. */
    val peerScreenSharing = MutableStateFlow(false)
    private var screenCapturer: org.webrtc.ScreenCapturerAndroid? = null
    private var screenHelper: SurfaceTextureHelper? = null
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var screenSender: org.webrtc.RtpSender? = null

    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var videoSource: VideoSource? = null
    private var localVideo: VideoTrack? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var turn: TurnCreds? = null
    private val pendingIce = ArrayList<IceCandidate>()
    private var remoteSet = false

    private var callId: String? = null
    private var video = false
    private var isCaller = false
    private var answered = false
    private var callStart = 0L
    private var timeoutJob: Job? = null
    private var endedJob: Job? = null
    private var iceJob: Job? = null
    private var ringtone: Ringtone? = null
    private val audio = AudioRouter(app)
    private var proximity: PowerManager.WakeLock? = null
    private var toneJob: Job? = null
    private var tones: ToneGenerator? = null
    private var cameraPaused = false
    private var iceRestarted = false
    private var iceUnstable = false
    private var recoveries = 0
    private var punching = false
    private var punchDropped = false
    private var punchJob: Job? = null
    private var statsJob: Job? = null
    private val captionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rawQueue = ArrayDeque<RawPcm>()
    private var rawJob: Job? = null
    private val pcmLock = Any()
    private val pcmChunks = ArrayDeque<FloatArray>()
    private var pcmSamples = 0
    private var sttRunning = false
    @Volatile private var sttUnavailable = false
    private val transcriptLock = Any()
    private val transcript = StringBuilder()
    private var lastLost = 0L
    private var lastRecv = 0L
    private var lastSent = 0L
    private var lastRecvBytes = 0L
    private var lastStatsAt = 0L

    init {
        scope.launch { repo.callFrames.collect { onFrame(it) } }
        scope.launch { audio.current.collect { applyProximity() } }
    }

    // ---- user actions ----

    /** Assistant calls are voice only. SDP and ICE go out in the clear; see [signal]. */
    fun discardSummary() {
        pendingSummary.value = null
    }

    fun start(withVideo: Boolean, toBot: Boolean = false) {
        if (state.value != State.Idle) return
        if (toBot && withVideo) return
        val id = UUID.randomUUID().toString()
        callId = id
        video = withVideo
        isCaller = true
        answered = false
        withBot.value = toBot
        captions.value = CallCaptions.begin(id)
        endedJob?.cancel()
        state.value = State.Outgoing(id, withVideo)
        Diag.log(TAG, "outgoing ${if (toBot) "assistant" else if (withVideo) "video" else "voice"} call $id")
        if (withVideo) runCatching { startCapture(ensureFactory()) }.onFailure { Log.w(TAG, "preview: ${it.message}") }
        signal(TurnGet)
        signal(CallInvite(id, withVideo, bot = toBot))
        startRingback()
        armTimeout(RING_MS) { finish("timeout", notifyPeer = true) }
    }

    /** Redial the assistant from the ended screen. */
    fun retry() {
        if (state.value !is State.Ended || !withBot.value) return
        endedJob?.cancel()
        state.value = State.Idle
        start(withVideo = false, toBot = true)
    }

    private fun signal(frame: Frame) {
        repo.send(frame, e2e = !withBot.value)
    }

    fun accept() {
        val s = state.value as? State.Incoming ?: return
        stopRinging()
        timeoutJob?.cancel()
        answered = true
        state.value = State.Active(s.callId, s.video, connected = false, startedAt = 0)
        try {
            setupPeerConnection()
        } catch (e: Throwable) {
            Diag.warn(TAG, "setup failed", e)
            signal(CallReject(s.callId, "failed"))
            finish("failed", notifyPeer = false)
            return
        }
        signal(CallAccept(s.callId))
        armTimeout(CONNECT_MS) { if ((state.value as? State.Active)?.connected == false) finish("failed", notifyPeer = true) }
    }

    fun reject() {
        val s = state.value as? State.Incoming ?: return
        signal(CallReject(s.callId, "declined"))
        finish("declined", notifyPeer = false)
    }

    fun hangup() {
        val reason = if (state.value is State.Outgoing) "cancelled" else "normal"
        finish(reason, notifyPeer = true)
    }

    fun setMuted(m: Boolean) {
        muted.value = m
        audioTrack?.setEnabled(!m)
        if (video || withBot.value) callId?.let { signal(CallMedia(it, video = !cameraOff.value, audio = !m)) }
    }

    /** Two-way shortcut used when only 听筒/扬声器 exist. */
    fun setSpeaker(on: Boolean) = audio.setSpeaker(on)

    /** Activity shown / hidden: make sure the route the user picked is still the one in effect. */
    fun reapplyAudioRoute() = audio.reapply()

    /** Explicit pick from the output sheet. */
    fun selectAudioDevice(route: AudioRoute) = audio.select(route)

    fun switchCamera() {
        capturer?.switchCamera(null)
    }

    /** Camera on/off mid-call; tells the peer so they can show a placeholder. */
    fun setCameraOff(off: Boolean) {
        if (!video || cameraOff.value == off) return
        cameraOff.value = off
        if (!cameraPaused) {
            if (off) {
                try { capturer?.stopCapture() } catch (_: Exception) {}
            } else {
                try { capturer?.startCapture(960, 540, 24) } catch (e: Exception) { Log.w(TAG, "camera on: ${e.message}") }
            }
        }
        localVideo?.setEnabled(!off)
        callId?.let { signal(CallMedia(it, video = !off, audio = !muted.value)) }
    }

    /** Pause the local camera while the in-app call bar is showing; PiP keeps the preview. A screen share keeps running. */
    fun setCameraPaused(paused: Boolean) {
        if (!video || capturer == null || paused == cameraPaused) return
        cameraPaused = paused
        if (screenSharing.value) return
        if (paused) {
            try { capturer?.stopCapture() } catch (_: Exception) {}
            localVideo?.setEnabled(false)
        } else if (!cameraOff.value) {
            try { capturer?.startCapture(960, 540, 24) } catch (e: Exception) { Log.w(TAG, "resume camera: ${e.message}") }
            localVideo?.setEnabled(true)
        }
    }

    /** Wi-Fi ↔ cellular: only restart ICE if it already dropped, not on every new network. */
    fun onNetworkAvailable() {
        if (state.value is State.Active && iceUnstable) restartIce("network")
    }

    /** ❤️ / 👋 … during a call: floats up on both screens. */
    fun sendEmoji(emoji: String) {
        val id = callId ?: return
        if (state.value !is State.Active) return
        signal(CallEmoji(id, emoji))
        emojiFloats.tryEmit(Float(System.nanoTime(), emoji, mine = true))
    }

    /** Opens the shared whiteboard on both phones (the peer sees it pop up too). */
    fun openWhiteboard(open: Boolean) {
        whiteboard.setOpen(open)
    }

    /**
     * Screen sharing. [permission] is the result of MediaProjectionManager.createScreenCaptureIntent(); the
     * foreground service must already be running with the mediaProjection type (ChatService.screenShare).
     * Video call: the camera track's source switches to the screen (no renegotiation). Voice call: a video
     * track is added and we send a fresh offer.
     */
    fun startScreenShare(permission: android.content.Intent) {
        val p = pc ?: return
        if (state.value !is State.Active || screenSharing.value) return
        val f = ensureFactory()
        val metrics = app.resources.displayMetrics
        // Half resolution keeps the bitrate cap happy; text stays readable on the other phone.
        val w = (metrics.widthPixels / 2).coerceAtLeast(360)
        val h = (metrics.heightPixels / 2).coerceAtLeast(640)
        val cap = org.webrtc.ScreenCapturerAndroid(permission, object : android.media.projection.MediaProjection.Callback() {
            override fun onStop() { scope.launch { stopScreenShare() } }
        })
        try {
            if (video && videoSource != null && surfaceHelper != null) {
                // Same source, same track: the peer just sees the picture change.
                try { capturer?.stopCapture() } catch (_: Exception) {}
                cap.initialize(surfaceHelper, app, videoSource!!.capturerObserver)
                cap.startCapture(w, h, 15)
                localVideo?.setEnabled(true)
            } else {
                val helper = SurfaceTextureHelper.create("ScreenThread", eglBase.eglBaseContext)
                val src = f.createVideoSource(true)
                cap.initialize(helper, app, src.capturerObserver)
                cap.startCapture(w, h, 15)
                val track = f.createVideoTrack("s0", src)
                screenSender = p.addTrack(track, listOf(STREAM_ID))
                runCatching {
                    val params = screenSender!!.parameters
                    params.encodings.forEach { it.maxBitrateBps = MAX_VIDEO_BPS; it.maxFramerate = 15 }
                    screenSender!!.parameters = params
                }
                screenHelper = helper
                screenSource = src
                screenTrack = track
                localVideoTrack.value = track
                createOffer()
            }
            screenCapturer = cap
            screenSharing.value = true
            callId?.let { signal(CallMedia(it, video = true, audio = !muted.value, screen = true)) }
            Diag.log(TAG, "screen share started ${w}x$h")
        } catch (e: Exception) {
            Log.w(TAG, "screen share failed: ${e.message}")
            runCatching { cap.dispose() }
            if (video && !cameraOff.value) runCatching { capturer?.startCapture(960, 540, 24) }
        }
    }

    fun stopScreenShare() {
        val cap = screenCapturer ?: return
        screenCapturer = null
        screenSharing.value = false
        try { cap.stopCapture() } catch (_: Exception) {}
        runCatching { cap.dispose() }
        if (screenTrack != null) {
            // Voice call: drop the extra track again (the peer's onRemoveTrack clears its picture).
            val sender = screenSender
            val track = screenTrack
            screenSender = null
            screenTrack = null
            localVideoTrack.value = localVideo
            runCatching { if (sender != null) pc?.removeTrack(sender) }
            runCatching { track?.dispose() }
            runCatching { screenSource?.dispose() }
            screenSource = null
            runCatching { screenHelper?.dispose() }
            screenHelper = null
            if (pc != null) createOffer()
        } else if (video && !cameraOff.value && !cameraPaused) {
            try { capturer?.startCapture(960, 540, 24) } catch (e: Exception) { Log.w(TAG, "camera back: ${e.message}") }
        }
        callId?.let { signal(CallMedia(it, video = video && !cameraOff.value, audio = !muted.value, screen = false)) }
        Diag.log(TAG, "screen share stopped")
    }

    // ---- signaling in ----

    private fun onFrame(f: Frame) {
        when (f) {
            is TurnCreds -> turn = f
            is CallInvite -> {
                if (state.value != State.Idle) {
                    signal(CallReject(f.callId, "busy"))
                    return
                }
                callId = f.callId
                video = f.video
                isCaller = false
                answered = false
                state.value = State.Incoming(f.callId, f.video)
                Diag.log(TAG, "incoming ${if (f.video) "video" else "voice"} call ${f.callId}")
                if (f.video && ContextCompat.checkSelfPermission(app, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    runCatching { startCapture(ensureFactory()) }.onFailure { Log.w(TAG, "preview: ${it.message}") }
                }
                signal(TurnGet)
                startRinging()
                armTimeout(RING_MS) { finish("timeout", notifyPeer = false) }
            }
            is CallAccept -> if (f.callId == callId && state.value is State.Outgoing) {
                timeoutJob?.cancel()
                stopRingback()
                answered = true
                if (withBot.value) setSpeaker(true)
                state.value = State.Active(f.callId, video, connected = false, startedAt = 0)
                try {
                    setupPeerConnection()
                    createOffer()
                } catch (e: Throwable) {
                    Diag.warn(TAG, "setup failed", e)
                    finish("failed", notifyPeer = true)
                }
                armTimeout(CONNECT_MS) { if ((state.value as? State.Active)?.connected == false) finish("failed", notifyPeer = true) }
            }
            is CallReject -> if (f.callId == callId) finish(f.reason ?: "declined", notifyPeer = false)
            is CallHangup -> if (f.callId == callId) finish(f.reason ?: "normal", notifyPeer = false)
            is CallSdp -> if (f.callId == callId) onRemoteSdp(f)
            is CallIce -> if (f.callId == callId) onRemoteIce(f)
            is CallMedia -> if (f.callId == callId) {
                peerCameraOff.value = !f.video
                if (f.screen != null) peerScreenSharing.value = f.screen
            }
            is CallEmoji -> if (f.callId == callId) emojiFloats.tryEmit(Float(System.nanoTime(), f.emoji.take(16), mine = false))
            is CallCaption -> if (f.callId == callId) {
                captions.value = CallCaptions.apply(
                    captions.value,
                    CaptionFrame(f.callId, f.who, f.text, f.state, f.phase, f.ts ?: System.currentTimeMillis()),
                )
            }
            else -> {}
        }
    }

    // ---- WebRTC plumbing ----

    private fun ensureFactory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(app)
                .setEnableInternalTracer(false)
                // Real host addresses, so a same-LAN pair can connect without mDNS.
                .setFieldTrials("WebRTC-HideLocalIpsWithMdns/Disabled/")
                .createInitializationOptions()
        )
        // Hardware AEC/NS abort the process on Huawei Android 16 when recording starts.
        // WebRTC's software processor still runs. The samples callback feeds call captions.
        val adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setSamplesReadyCallback { samples -> onMicSamples(samples) }
            .createAudioDeviceModule()
        val f = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f
        return f
    }

    private fun setupPeerConnection() {
        Diag.log(TAG, "setup begin caller=$isCaller")
        val f = ensureFactory()
        val servers = ArrayList<PeerConnection.IceServer>()
        val t = turn
        if (t != null && t.urls.isNotEmpty()) {
            val b = PeerConnection.IceServer.builder(t.urls)
            if (t.username.isNotEmpty()) b.setUsername(t.username).setPassword(t.credential)
            servers.add(b.createIceServer())
        } else {
            Log.w(TAG, "no TURN creds yet; STUN only")
            servers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        }
        val cfg = PeerConnection.RTCConfiguration(servers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            // Do not set a backup-ping or STUN keepalive interval. At this moment the
            // connection list is empty, and this library calls a method on the missing
            // last item from network_thread. Direct retries are the later ICE restart.
        }
        val p = f.createPeerConnection(cfg, observer) ?: throw IllegalStateException("createPeerConnection returned null")
        Diag.log(TAG, "setup pc")
        pc = p
        remoteSet = false
        pendingIce.clear()

        val aSrc = f.createAudioSource(MediaConstraints())
        val aTrack = f.createAudioTrack("a0", aSrc)
        aTrack.setEnabled(!muted.value)
        p.addTrack(aTrack, listOf(STREAM_ID))
        audioSource = aSrc
        audioTrack = aTrack

        if (video) attachVideo(f, p)

        // The whiteboard channel is opened only after the first local description
        // has been applied. Creating it earlier makes setLocalDescription use a
        // transport that does not exist yet.
        repo.voice.stop()
        repo.voice.inCall = true
        audio.start(preferSpeaker = video)
        applyProximity()
        Diag.log(TAG, "setup audio started")
    }

    /** Camera capture + local track. Started as soon as a video call begins so the preview shows while ringing. */
    private fun startCapture(f: PeerConnectionFactory) {
        if (localVideo != null) return
        val enumerator = Camera2Enumerator(app)
        val names = enumerator.deviceNames
        val name = names.firstOrNull { enumerator.isFrontFacing(it) } ?: names.firstOrNull()
        if (name == null) {
            Log.w(TAG, "no camera")
            return
        }
        val cap = enumerator.createCapturer(name, null) ?: return
        val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val src = f.createVideoSource(cap.isScreencast)
        cap.initialize(helper, app, src.capturerObserver)
        cap.startCapture(960, 540, 24)
        val track = f.createVideoTrack("v0", src)
        capturer = cap
        surfaceHelper = helper
        videoSource = src
        localVideo = track
        localVideoTrack.value = track
    }

    private fun attachVideo(f: PeerConnectionFactory, p: PeerConnection) {
        startCapture(f)
        val track = localVideo ?: return
        val sender = p.addTrack(track, listOf(STREAM_ID))
        // Cap upstream so a weak uplink degrades resolution/framerate instead of stalling.
        runCatching {
            val params = sender.parameters
            params.encodings.forEach { it.maxBitrateBps = MAX_VIDEO_BPS; it.maxFramerate = 24 }
            params.degradationPreference = org.webrtc.RtpParameters.DegradationPreference.BALANCED
            sender.parameters = params
        }.onFailure { Log.w(TAG, "bitrate cap: ${it.message}") }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            val id = callId ?: return
            signal(CallIce(id, c.sdp, c.sdpMid, c.sdpMLineIndex))
        }

        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
            Diag.log(TAG, "ice $s")
            scope.launch {
                when (s) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        // A punch's first CONNECTED is the old path still up. Wait until ICE has
                        // actually dropped once before treating the restart as finished.
                        if (!withBot.value && punching && !punchDropped) {
                            markConnected()
                            return@launch
                        }
                        iceJob?.cancel()
                        punching = false
                        punchDropped = false
                        tryingDirect.value = false
                        iceRestarted = false
                        recoveries = 0
                        iceUnstable = false
                        reconnecting.value = false
                        markConnected()
                    }
                    PeerConnection.IceConnectionState.CHECKING -> {
                        if (!withBot.value && punching) punchDropped = true
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        if (!withBot.value && punching) {
                            punchDropped = true
                            return@launch
                        }
                        iceUnstable = true
                        reconnecting.value = true
                        iceJob?.cancel()
                        iceJob = scope.launch {
                            delay(3_000)
                            if (state.value is State.Active) restartIce("disconnected")
                        }
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        if (!withBot.value && punching) {
                            punching = false
                            punchDropped = false
                            tryingDirect.value = false
                            iceJob?.cancel()
                            recover("punch")
                            return@launch
                        }
                        iceUnstable = true
                        reconnecting.value = true
                        restartIce("failed")
                    }
                    else -> {}
                }
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            val track = transceiver.receiver.track()
            if (track is VideoTrack) scope.launch { remoteVideoTrack.value = track }
        }

        override fun onRemoveTrack(receiver: RtpReceiver) {
            val track = receiver.track()
            if (track is VideoTrack) scope.launch { if (remoteVideoTrack.value?.id() == track.id()) remoteVideoTrack.value = null }
        }

        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {}
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {
            if (channel.label() == Whiteboard.CHANNEL_LABEL) {
                dataChannel = channel
                whiteboard.attach(channel)
            }
        }
        override fun onRenegotiationNeeded() {}
    }

    private fun createOffer(iceRestart: Boolean = false) {
        Diag.log(TAG, "create offer restart=$iceRestart")
        val p = pc ?: return
        val constraints = MediaConstraints()
        if (iceRestart) constraints.mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        p.createOffer(Sdp(if (iceRestart) "iceRestart" else "createOffer", onCreate = { desc ->
            Diag.log(TAG, "offer created restart=$iceRestart")
            p.setLocalDescription(Sdp("setLocalOffer", onSet = {
                Diag.log(TAG, "local offer set restart=$iceRestart")
            }), desc)
            callId?.let { signal(CallSdp(it, "offer", desc.description)) }
        }), constraints)
    }

    /** Caller only, after ICE is up, so the transport exists. One follow-up offer carries the channel. */
    private fun openWhiteboard(p: PeerConnection) {
        if (!isCaller || dataChannel != null || whiteboardOffered) return
        val dc = runCatching {
            p.createDataChannel(Whiteboard.CHANNEL_LABEL, DataChannel.Init().apply { ordered = true })
        }.onFailure { Log.w(TAG, "data channel: ${it.message}") }.getOrNull() ?: return
        dataChannel = dc
        whiteboard.attach(dc)
        whiteboardOffered = true
        Diag.log(TAG, "whiteboard opened")
        createOffer(iceRestart = false)
    }

    private fun restartIce(why: String) {
        if (!withBot.value) {
            recover(why)
            return
        }
        if (state.value !is State.Active || pc == null) return
        if (iceRestarted) {
            Log.w(TAG, "ICE $why after a restart; giving up")
            finish("failed", notifyPeer = true)
            return
        }
        iceRestarted = true
        Log.i(TAG, "ICE restart ($why)")
        createOffer(iceRestart = true)
        iceJob?.cancel()
        iceJob = scope.launch {
            delay(12_000)
            if (state.value is State.Active && iceRestarted) finish("failed", notifyPeer = true)
        }
    }

    /** Human calls only. A failed attempt does not hang up; TURN is in the new offer and can come back. */
    private fun recover(why: String) {
        if (state.value !is State.Active || pc == null) return
        if (recoveries >= MAX_RECOVERIES) {
            Log.w(TAG, "ICE $why after $recoveries recoveries; giving up")
            finish("failed", notifyPeer = true)
            return
        }
        recoveries++
        iceUnstable = true
        Log.i(TAG, "ICE recover ($why) #$recoveries")
        createOffer(iceRestart = true)
        iceJob?.cancel()
        iceJob = scope.launch {
            delay(12_000)
            if (state.value is State.Active && iceUnstable) {
                if (recoveries >= MAX_RECOVERIES) finish("failed", notifyPeer = true)
                else recover("timeout")
            }
        }
    }

    /** Caller only, so the two phones do not offer into each other. Stop once the nominated pair is direct. */
    private fun ensurePunchLoop() {
        if (withBot.value || !isCaller) return
        if (punchJob?.isActive == true) return
        punchJob = scope.launch {
            while (true) {
                delay(PUNCH_MS)
                val s = state.value as? State.Active ?: continue
                if (!s.connected || iceUnstable || punching) continue
                if (!stats.value.relayed) continue
                punchOnce()
            }
        }
    }

    private fun punchOnce() {
        if (punching || pc == null || state.value !is State.Active) return
        punching = true
        punchDropped = false
        tryingDirect.value = true
        Log.i(TAG, "hole punch")
        createOffer(iceRestart = true)
        iceJob?.cancel()
        iceJob = scope.launch {
            delay(PUNCH_SETTLE_MS)
            punching = false
            punchDropped = false
            tryingDirect.value = false
            val ice = pc?.iceConnectionState()
            val up = ice == PeerConnection.IceConnectionState.CONNECTED ||
                ice == PeerConnection.IceConnectionState.COMPLETED
            if (state.value is State.Active && !up) recover("punch")
        }
    }

    private fun onRemoteSdp(f: CallSdp) {
        val p = pc ?: return
        val type = if (f.type == "offer") SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
        p.setRemoteDescription(Sdp("setRemote", onSet = {
            scope.launch {
                remoteSet = true
                pendingIce.forEach { p.addIceCandidate(it) }
                pendingIce.clear()
                if (type == SessionDescription.Type.OFFER) {
                    p.createAnswer(Sdp("createAnswer", onCreate = { desc ->
                        p.setLocalDescription(Sdp("setLocalAnswer"), desc)
                        callId?.let { signal(CallSdp(it, "answer", desc.description)) }
                    }), MediaConstraints())
                }
            }
        }), SessionDescription(type, f.sdp))
    }

    private fun onRemoteIce(f: CallIce) {
        val c = IceCandidate(f.sdpMid ?: "0", f.sdpMLineIndex ?: 0, f.candidate)
        if (remoteSet) pc?.addIceCandidate(c) else pendingIce.add(c)
    }

    private fun markConnected() {
        val s = state.value as? State.Active ?: return
        timeoutJob?.cancel()
        applyProximity()
        if (s.connected) {
            ensurePunchLoop()
            return
        }
        callStart = System.currentTimeMillis()
        state.value = s.copy(connected = true, startedAt = callStart)
        pc?.let { openWhiteboard(it) }
        beep(ToneGenerator.TONE_PROP_ACK, 150)
        startStats()
        ensurePunchLoop()
    }

    /** Voice + earpiece: turn the screen off when the phone is against the ear. */
    private fun applyProximity() {
        val nearEar = state.value is State.Active && !video && audio.current.value.route == AudioRoute.EARPIECE
        if (nearEar) acquireProximity() else releaseProximity()
    }

    private fun acquireProximity() {
        if (proximity?.isHeld == true) return
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        val lock = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "chatter:prox")
        runCatching { lock.acquire() }
        proximity = lock
    }

    private fun releaseProximity() {
        val lock = proximity ?: return
        proximity = null
        if (lock.isHeld) runCatching { lock.release() }
    }

    // ---- teardown ----

    private fun finish(reason: String, notifyPeer: Boolean) {
        val current = state.value
        if (current == State.Idle || current is State.Ended) return
        Diag.log(TAG, "call ended: $reason (answered=$answered)")
        statsJob?.cancel()
        statsJob = null
        stats.value = Stats(0, 0, 0, 0, 0, false)
        punchJob?.cancel()
        punchJob = null
        punching = false
        punchDropped = false
        tryingDirect.value = false
        stopRinging()
        stopRingback()
        timeoutJob?.cancel()
        iceJob?.cancel()
        val note = transcriptForSummary()
        val wantSummary = answered && !withBot.value && note.isNotEmpty() &&
            repo.prefs.callSummary && LocalSummary.ready(app)
        if (notifyPeer) callId?.let { signal(CallHangup(it, reason)) }
        if (answered) beep(ToneGenerator.TONE_PROP_NACK, 250)
        logCall(reason)
        if (answered && callStart > 0) runCatching { repo.recordCall(video, (System.currentTimeMillis() - callStart) / 1000, totalBytes) }
        val bot = withBot.value
        teardown()
        if (wantSummary) pendingSummary.value = note
        captions.value = CallCaptions.close(captions.value)
        state.value = State.Ended(reason, video)
        endedJob?.cancel()
        endedJob = scope.launch {
            delay(if (bot) 8_000 else 1_500)
            if (state.value is State.Ended) {
                state.value = State.Idle
                withBot.value = false
                captions.value = CallCaptions.begin("")
            }
        }
    }

    /** Only the caller writes the history entry so it appears exactly once. Assistant calls stay out of the couple's chat. */
    private fun logCall(reason: String) {
        if (!isCaller || withBot.value) return
        val outcome = when {
            answered && callStart > 0 -> "answered"
            reason == "declined" || reason == "busy" -> "declined"
            reason == "cancelled" -> "cancelled"
            else -> "missed"
        }
        val secs = if (outcome == "answered") (System.currentTimeMillis() - callStart) / 1000 else 0
        repo.sendCallLog("${if (video) "video" else "voice"}:$outcome:$secs")
    }

    private fun teardown() {
        if (screenCapturer != null) {
            val cap = screenCapturer
            screenCapturer = null
            screenSharing.value = false
            try { cap?.stopCapture() } catch (_: Exception) {}
            runCatching { cap?.dispose() }
        }
        runCatching { screenTrack?.dispose(); screenSource?.dispose(); screenHelper?.dispose() }
        screenTrack = null; screenSource = null; screenHelper = null; screenSender = null
        peerScreenSharing.value = false
        val oldLocal = localVideo
        val oldRemote = remoteVideoTrack.value
        localVideoTrack.value = null
        remoteVideoTrack.value = null
        localVideo = null

        try { capturer?.stopCapture() } catch (_: Exception) {}
        capturer?.dispose()
        capturer = null
        surfaceHelper?.dispose()
        surfaceHelper = null

        val oldPc = pc
        pc = null
        val oldAudioTrack = audioTrack
        val oldAudioSource = audioSource
        val oldVideoSource = videoSource
        audioTrack = null
        audioSource = null
        videoSource = null

        pendingIce.clear()
        remoteSet = false
        callId = null
        callStart = 0
        cameraPaused = false
        iceRestarted = false
        iceUnstable = false
        recoveries = 0
        totalBytes = 0
        rawJob?.cancel()
        synchronized(rawQueue) { rawQueue.clear() }
        synchronized(pcmLock) {
            pcmChunks.clear()
            pcmSamples = 0
            sttRunning = false
        }
        sttUnavailable = false
        localCaptions.value = emptyList()
        localCaptionHint.value = null
        synchronized(transcriptLock) { transcript.setLength(0) }
        whiteboard.detach()
        whiteboard.reset()
        val oldDc = dataChannel
        dataChannel = null
        whiteboardOffered = false

        releaseProximity()
        audio.stop()
        repo.voice.inCall = false
        tones?.let { g -> tones = null; scope.launch { delay(400); runCatching { g.release() } } } // let the hang-up beep finish
        muted.value = false
        cameraOff.value = false
        peerCameraOff.value = false
        reconnecting.value = false

        // Give renderers a moment to detach their sinks before native objects go away.
        scope.launch(Dispatchers.Default) {
            delay(300)
            runCatching {
                runCatching { oldDc?.close(); oldDc?.dispose() }
                oldPc?.close()
                oldPc?.dispose()
                oldLocal?.dispose()
                oldAudioTrack?.dispose()
                oldAudioSource?.dispose()
                oldVideoSource?.dispose()
            }.onFailure { Log.w(TAG, "dispose: ${it.message}") }
            // remote track is owned by the peer connection; nothing to dispose
            if (oldRemote != null) Unit
        }
    }

    // ---- link quality ----

    private fun startStats() {
        statsJob?.cancel()
        lastLost = 0; lastRecv = 0; lastSent = 0; lastRecvBytes = 0; lastStatsAt = 0
        statsJob = scope.launch {
            while (true) {
                delay(2000)
                val p = pc ?: break
                p.getStats { report -> scope.launch { digest(report) } }
            }
        }
    }

    private fun digest(report: org.webrtc.RTCStatsReport) {
        var rtt = -1.0
        var lost = 0L
        var recv = 0L
        var sent = 0L
        var recvBytes = 0L
        var relayed = false
        for (s in report.statsMap.values) {
            val m = s.members
            when (s.type) {
                "candidate-pair" -> if (m["state"] == "succeeded" && (m["nominated"] as? Boolean) == true) {
                    (m["currentRoundTripTime"] as? Number)?.let { rtt = it.toDouble() }
                    val local = m["localCandidateId"] as? String
                    val lc = local?.let { report.statsMap[it] }
                    if (lc?.members?.get("candidateType") == "relay") relayed = true
                }
                "inbound-rtp" -> {
                    lost += (m["packetsLost"] as? Number)?.toLong() ?: 0
                    recv += (m["packetsReceived"] as? Number)?.toLong() ?: 0
                    recvBytes += (m["bytesReceived"] as? Number)?.toLong() ?: 0
                }
                "outbound-rtp" -> sent += (m["bytesSent"] as? Number)?.toLong() ?: 0
            }
        }
        val now = System.currentTimeMillis()
        val dtMs = if (lastStatsAt == 0L) 0 else now - lastStatsAt
        val dLost = (lost - lastLost).coerceAtLeast(0)
        val dRecv = (recv - lastRecv).coerceAtLeast(0)
        val lossPct = if (dLost + dRecv > 0) (dLost * 100 / (dLost + dRecv)).toInt() else 0
        val upKbps = if (dtMs > 0) ((sent - lastSent).coerceAtLeast(0) * 8 / dtMs).toInt() else 0
        val downKbps = if (dtMs > 0) ((recvBytes - lastRecvBytes).coerceAtLeast(0) * 8 / dtMs).toInt() else 0
        lastLost = lost; lastRecv = recv; lastSent = sent; lastRecvBytes = recvBytes; lastStatsAt = now
        totalBytes = sent + recvBytes
        val rttMs = if (rtt >= 0) (rtt * 1000).toInt() else 0
        val bars = when {
            rtt < 0 && dtMs == 0L -> 0
            rttMs < 150 && lossPct < 2 -> 4
            rttMs < 300 && lossPct < 5 -> 3
            rttMs < 600 && lossPct < 12 -> 2
            else -> 1
        }
        stats.value = Stats(bars, rttMs, lossPct, upKbps, downKbps, relayed)
    }

    // ---- ringing / timers ----

    private fun armTimeout(ms: Long, block: () -> Unit) {
        timeoutJob?.cancel()
        timeoutJob = scope.launch {
            delay(ms)
            block()
        }
    }

    private fun startRinging() {
        val quiet = repo.prefs.inQuietHours()
        if (!quiet) try {
            val uri = repo.prefs.ringtoneUri?.let { android.net.Uri.parse(it) } ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = (RingtoneManager.getRingtone(app, uri) ?: RingtoneManager.getRingtone(app, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)))?.apply {
                if (Build.VERSION.SDK_INT >= 28) isLooping = true
                play()
            }
        } catch (e: Exception) {
            Log.w(TAG, "ringtone: ${e.message}")
        }
        vibrator()?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 800, 800), 0))
    }

    /** Ringback while the peer's phone rings: a short tone every 4 s, on the voice-call stream. */
    private fun startRingback() {
        stopRingback()
        toneJob = scope.launch {
            while (true) {
                beep(ToneGenerator.TONE_SUP_RINGTONE, 1200)
                delay(4000)
            }
        }
    }

    private fun stopRingback() {
        toneJob?.cancel()
        toneJob = null
        tones?.stopTone()
    }

    private fun beep(tone: Int, ms: Int) {
        try {
            // Voice calls ring in the earpiece like a phone; video calls are held away from the ear, so use the media stream.
            val g = tones ?: ToneGenerator(if (video) AudioManager.STREAM_MUSIC else AudioManager.STREAM_VOICE_CALL, 70).also { tones = it }
            g.startTone(tone, ms)
        } catch (e: Exception) { Log.w(TAG, "tone: ${e.message}") }
    }

    private fun stopRinging() {
        ringtone?.stop()
        ringtone = null
        vibrator()?.cancel()
    }

    private fun vibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private class Sdp(
        private val tag: String,
        private val onCreate: ((SessionDescription) -> Unit)? = null,
        private val onSet: (() -> Unit)? = null,
    ) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) { onCreate?.invoke(desc) }
        override fun onSetSuccess() { onSet?.invoke() }
        override fun onCreateFailure(error: String?) { Log.e(TAG, "$tag create failed: $error") }
        override fun onSetFailure(error: String?) { Log.e(TAG, "$tag set failed: $error") }
    }

    private fun transcriptForSummary(): String {
        val raw = synchronized(transcriptLock) { transcript.toString().trim() }
        if (raw.length <= SUMMARY_CHARS) return raw
        return "（前面的话已略）\n" + raw.takeLast(SUMMARY_CHARS)
    }

    private fun onMicSamples(samples: JavaAudioDeviceModule.AudioSamples) {
        try {
            onMicSamplesUnchecked(samples)
        } catch (e: Throwable) {
            Diag.warn(TAG, "mic samples", e)
        }
    }

    private fun onMicSamplesUnchecked(samples: JavaAudioDeviceModule.AudioSamples) {
        if (withBot.value || muted.value) return
        val active = state.value as? State.Active ?: return
        if (!active.connected) return
        val data = samples.data ?: return
        val copy = data.copyOf()
        synchronized(rawQueue) {
            rawQueue.add(RawPcm(copy, samples.sampleRate, samples.channelCount))
            while (rawQueue.size > 50) rawQueue.removeFirst()
        }
        if (rawJob?.isActive == true) return
        rawJob = captionScope.launch { drainMic() }
    }

    private suspend fun drainMic() {
        while (true) {
            val item = synchronized(rawQueue) { if (rawQueue.isEmpty()) null else rawQueue.removeFirst() } ?: break
            val floats = resample16k(item.data, item.rate, item.channels)
            queueForStt(floats)
        }
    }

    private fun queueForStt(chunk: FloatArray) {
        if (chunk.isEmpty() || sttUnavailable) return
        val take = synchronized(pcmLock) {
            if (sttRunning) return
            pcmChunks.add(chunk)
            pcmSamples += chunk.size
            if (pcmSamples < STT_SAMPLES) return
            val out = FloatArray(STT_SAMPLES)
            var filled = 0
            while (filled < STT_SAMPLES && pcmChunks.isNotEmpty()) {
                val c = pcmChunks.removeFirst()
                pcmSamples -= c.size
                val n = minOf(c.size, STT_SAMPLES - filled)
                System.arraycopy(c, 0, out, filled, n)
                filled += n
                if (n < c.size) {
                    val rest = c.copyOfRange(n, c.size)
                    pcmChunks.addFirst(rest)
                    pcmSamples += rest.size
                }
            }
            sttRunning = true
            out
        }
        captionScope.launch {
            try {
                if (!LocalStt.ready(app)) {
                    sttUnavailable = true
                    scope.launch { localCaptionHint.value = "语音识别模型未就绪" }
                    return@launch
                }
                val text = runCatching { LocalStt.transcribePcm(app, take) }.getOrNull() ?: return@launch
                scope.launch { appendCaption(text) }
            } finally {
                synchronized(pcmLock) { sttRunning = false }
            }
        }
    }

    private fun appendCaption(text: String) {
        val line = text.trim()
        if (line.isEmpty() || localCaptions.value.lastOrNull() == line) return
        localCaptions.value = (localCaptions.value + line).takeLast(6)
        synchronized(transcriptLock) {
            if (transcript.isNotEmpty()) transcript.append('\n')
            transcript.append(line)
            if (transcript.length > TRANSCRIPT_MAX) {
                val extra = transcript.length - TRANSCRIPT_MAX
                val nl = transcript.indexOf('\n', extra)
                val cut = if (nl < 0) extra else nl + 1
                transcript.delete(0, cut.coerceAtMost(transcript.length))
            }
        }
    }

    /** Little-endian 16-bit interleaved PCM → 16 kHz mono float. */
    private fun resample16k(data: ByteArray, rate: Int, channels: Int): FloatArray {
        if (rate <= 0 || channels <= 0 || data.size < 2) return FloatArray(0)
        val frames = data.size / 2 / channels
        if (frames <= 0) return FloatArray(0)
        val outLen = (frames.toLong() * 16_000 / rate).toInt().coerceAtLeast(1)
        val out = FloatArray(outLen)
        for (i in 0 until outLen) {
            val src = (i.toLong() * rate / 16_000).toInt().coerceIn(0, frames - 1)
            var acc = 0
            val base = src * channels
            for (ch in 0 until channels) {
                val idx = (base + ch) * 2
                if (idx + 1 >= data.size) break
                val lo = data[idx].toInt() and 0xff
                val hi = data[idx + 1].toInt() shl 8
                acc += (lo or hi).toShort().toInt()
            }
            out[i] = (acc.toFloat() / channels / 32768f).coerceIn(-1f, 1f)
        }
        return out
    }

    private class RawPcm(val data: ByteArray, val rate: Int, val channels: Int)

    private companion object {
        const val TAG = "CallManager"
        const val STREAM_ID = "s0"
        const val RING_MS = 45_000L
        const val CONNECT_MS = 30_000L
        const val MAX_VIDEO_BPS = 1_200_000
        const val PUNCH_MS = 20_000L
        const val PUNCH_SETTLE_MS = 15_000L
        const val MAX_RECOVERIES = 2
        const val STT_SAMPLES = 16_000 * 3
        const val TRANSCRIPT_MAX = 4000
        const val SUMMARY_CHARS = 600
    }
}
