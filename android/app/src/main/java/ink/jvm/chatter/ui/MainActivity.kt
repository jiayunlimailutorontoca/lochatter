package ink.jvm.chatter.ui

import android.app.PendingIntent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.addCallback
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import ink.jvm.chatter.R
import ink.jvm.chatter.ChatterApp
import ink.jvm.chatter.call.CallActionReceiver
import ink.jvm.chatter.call.CallManager
import ink.jvm.chatter.data.ChatRepository
import ink.jvm.chatter.service.ChatService
import ink.jvm.chatter.service.Notifications

class MainActivity : FragmentActivity() {
    private lateinit var calls: CallManager
    private var prompting = false

    /** True while a system picker is on top: leaving for it must not auto-enter PiP or collapse the call. */
    @Volatile private var pickingMedia = false
    private val imagePicker = registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(pickLimit())) { uris ->
        pickingMedia = false
        syncPipParams()
        if (uris.isNotEmpty()) (application as ChatterApp).repo.sharedMedia.value = uris
    }
    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickingMedia = false
        syncPipParams()
        if (uri != null) (application as ChatterApp).repo.pickedFile.value = uri
    }
    private val stickerPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pickingMedia = false
        syncPipParams()
        if (uri != null) (application as ChatterApp).repo.pickedSticker.value = uri
    }
    private val wallpaperPicker = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pickingMedia = false
        syncPipParams()
        if (uri != null) (application as ChatterApp).repo.pickedWallpaper.value = uri
    }
    private val ringtonePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        pickingMedia = false
        syncPipParams()
        if (res.resultCode == RESULT_OK) {
            val uri: android.net.Uri? = if (Build.VERSION.SDK_INT >= 33) res.data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI, android.net.Uri::class.java)
            else @Suppress("DEPRECATION") res.data?.getParcelableExtra(android.media.RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            (application as ChatterApp).prefs.ringtoneUri = uri?.toString()
            ringtoneChanged.value = ringtoneChanged.value + 1
        }
    }
    private val screenSharePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        pickingMedia = false
        syncPipParams()
        val data = res.data
        if (res.resultCode != RESULT_OK || data == null) return@registerForActivityResult
        // The service must hold the mediaProjection type before MediaProjection may be created (Android 14).
        ChatService.setScreenShare(this, true)
        lifecycleScope.launch {
            kotlinx.coroutines.delay(400)
            (application as ChatterApp).calls.startScreenShare(data)
        }
    }

    /** 共享屏幕: system consent dialog, then the capturer starts in CallManager. */
    fun requestScreenShare() {
        val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java) ?: return
        pickingMedia = true
        syncPipParams()
        runCatching { screenSharePicker.launch(mpm.createScreenCaptureIntent()) }
            .onFailure { pickingMedia = false; android.widget.Toast.makeText(this, "这台手机不支持屏幕共享", android.widget.Toast.LENGTH_SHORT).show() }
    }

    fun stopScreenShare() {
        (application as ChatterApp).calls.stopScreenShare()
        ChatService.setScreenShare(this, false)
    }

    fun pickImages() {
        pickingMedia = true
        syncPipParams()
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
    }

    fun pickFile() {
        pickingMedia = true
        syncPipParams()
        filePicker.launch(arrayOf("*/*"))
    }

    fun pickSticker() {
        pickingMedia = true
        syncPipParams()
        stickerPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun pickWallpaper() {
        pickingMedia = true
        syncPipParams()
        wallpaperPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    fun pickRingtone() {
        pickingMedia = true
        syncPipParams()
        val cur = (application as ChatterApp).prefs.ringtoneUri?.let { android.net.Uri.parse(it) }
        val i = Intent(android.media.RingtoneManager.ACTION_RINGTONE_PICKER)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TYPE, android.media.RingtoneManager.TYPE_RINGTONE)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_TITLE, "来电铃声")
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            .putExtra(android.media.RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, cur)
        runCatching { ringtonePicker.launch(i) }.onFailure { pickingMedia = false; android.widget.Toast.makeText(this, "这台手机没有铃声选择器", android.widget.Toast.LENGTH_SHORT).show() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as ChatterApp
        calls = app.calls
        setQuoteSelf(app.prefs.userId)
        handleOpenCall(intent)
        handleShare(intent)
        onBackPressedDispatcher.addCallback(this) {
            if (!collapseToChat()) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
                isEnabled = true
            }
        }
        applySecureFlag()
        fontScale.value = app.prefs.fontScale
        themePrefs.value = Triple(app.prefs.accent, app.prefs.chatBg, app.prefs.bubbleStyle)
        setContent {
            val scale by fontScale
            val (accent, bg, bubble) = themePrefs.value
            ChatterTheme(fontScale = scale, accent = accent, background = bg, bubbleStyle = bubble) {
                Root(app.repo, app.calls, onCollapse = { collapseToChat() }, onUnlock = { unlock() })
            }
        }
    }

    /** FLAG_SECURE per settings: blocks screenshots and blanks the recents thumbnail. */
    fun applySecureFlag() {
        if ((application as ChatterApp).prefs.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Fingerprint / face / device credential. Falls through unlocked if the phone has nothing enrolled. */
    fun unlock() {
        if (prompting) return
        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            locked.value = false
            return
        }
        prompting = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { prompting = false; locked.value = false }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { prompting = false }
        })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder().setTitle("解锁 lochatter").setAllowedAuthenticators(allowed).build()
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenCall(intent)
        handleShare(intent)
    }

    /** Share sheet (text / images / videos / files) and launcher shortcuts land here. */
    private fun handleShare(intent: Intent?) {
        intent ?: return
        val repo = (application as ChatterApp).repo
        intent.getStringExtra(EXTRA_ACTION)?.let { pendingAction.value = it; intent.removeExtra(EXTRA_ACTION) }
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (!repo.prefs.loggedIn) return
                val uri: android.net.Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (uri != null) routeShared(listOf(uri)) else if (!text.isNullOrBlank()) repo.sharedText.value = text
                intent.action = null
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                if (!repo.prefs.loggedIn) return
                val uris: List<android.net.Uri>? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                if (!uris.isNullOrEmpty()) routeShared(uris)
                intent.action = null
            }
        }
    }

    private fun routeShared(uris: List<android.net.Uri>) {
        val repo = (application as ChatterApp).repo
        val visual = uris.filter { u -> contentResolver.getType(u)?.let { it.startsWith("image/") || it.startsWith("video/") } == true }
        val files = uris - visual.toSet()
        if (visual.isNotEmpty()) repo.sharedMedia.value = visual
        files.forEach { f -> lifecycleScope.launch { runCatching { repo.sendFile(f) }.onFailure { android.widget.Toast.makeText(this@MainActivity, "发送失败：${it.message}", android.widget.Toast.LENGTH_SHORT).show() } } }
        if (files.isNotEmpty()) android.widget.Toast.makeText(this, "正在发送 ${files.size} 个文件", android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onStart() {
        super.onStart()
        val app = application as ChatterApp
        if (app.prefs.appLock && app.prefs.loggedIn && locked.value && calls.state.value == CallManager.State.Idle) unlock()
        app.repo.setForeground(true)
        Notifications.cancelMessages(this)
        calls.reapplyAudioRoute()
    }

    override fun onStop() {
        val app = application as ChatterApp
        if (app.prefs.appLock && calls.state.value == CallManager.State.Idle) locked.value = true
        app.repo.setForeground(false)
        super.onStop()
    }

    /** Home / recents: system PiP when the OEM allows it; otherwise the in-app call bar. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pickingMedia) return
        if (Build.VERSION.SDK_INT >= 31 && canEnterSystemPip()) {
            // setAutoEnterEnabled on the PiP params handles the window; don't also collapse.
            return
        }
        if (!tryEnterSystemPip()) collapseToChat()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip.value = isInPictureInPictureMode
        if (!isInPictureInPictureMode) calls.setCameraPaused(minimized.value)
    }

    internal fun collapseToChat(): Boolean {
        val st = calls.state.value
        if (st !is CallManager.State.Active && st !is CallManager.State.Outgoing) return false
        if (isInPictureInPictureMode || minimized.value) return false
        minimized.value = true
        calls.setCameraPaused(true)
        return true
    }

    internal fun expandCall() {
        minimized.value = false
        calls.setCameraPaused(false)
    }

    private fun handleOpenCall(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_CALL, false) == true) expandCall()
    }

    internal fun tryEnterSystemPip(): Boolean {
        val st = calls.state.value
        if (st !is CallManager.State.Active && st !is CallManager.State.Outgoing) return false
        if (isInPictureInPictureMode || pickingMedia) return false
        if (!canEnterSystemPip()) return false
        return try {
            enterPictureInPictureMode(pipParams(autoEnter = false))
        } catch (_: Exception) {
            false
        }
    }

    internal fun syncPipParams() {
        if (Build.VERSION.SDK_INT < 26) return
        val st = calls.state.value
        val inCall = st is CallManager.State.Active || st is CallManager.State.Outgoing
        runCatching { setPictureInPictureParams(pipParams(autoEnter = inCall && !pickingMedia && canEnterSystemPip())) }
    }

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams {
        val st = calls.state.value
        val video = when (st) {
            is CallManager.State.Active -> st.video
            is CallManager.State.Outgoing -> st.video
            else -> false
        }
        val b = PictureInPictureParams.Builder()
            .setAspectRatio(if (video) Rational(9, 16) else Rational(16, 9))
        if (Build.VERSION.SDK_INT >= 26) b.setActions(pipActions())
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(autoEnter)
        return b.build()
    }

    private fun pipActions(): List<RemoteAction> {
        fun action(id: Int, icon: Int, title: String, action: String) = RemoteAction(
            Icon.createWithResource(this, icon),
            title,
            title,
            PendingIntent.getBroadcast(
                this, id,
                Intent(this, CallActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val muteTitle = if (calls.muted.value) "取消静音" else "静音"
        return listOf(
            action(11, R.drawable.ic_mic_off, muteTitle, CallActionReceiver.ACTION_MUTE),
            action(12, R.drawable.ic_call_end, "挂断", CallActionReceiver.ACTION_HANGUP),
        )
    }

    private fun canEnterSystemPip(): Boolean {
        if (Build.VERSION.SDK_INT < 26) return false
        return packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    companion object {
        /** Photo picker cap. Not 9: the system limit when it has one, otherwise a few hundred. */
        fun pickLimit(): Int {
            if (Build.VERSION.SDK_INT >= 33) {
                val n = android.provider.MediaStore.getPickImagesMaxLimit()
                if (n in 2..500) return n
            }
            return 100
        }

        const val EXTRA_OPEN_CALL = "open_call"
        const val EXTRA_ACTION = "action"
        /** "call_voice" / "call_video" from a launcher shortcut, consumed by ChatScreen once connected. */
        val pendingAction = mutableStateOf<String?>(null)
        val fontScale = mutableStateOf(1f)
        /** accent, background, bubble style (settings → 外观). */
        val themePrefs = mutableStateOf(Triple("rose", "rose", "round"))
        val ringtoneChanged = mutableStateOf(0)
        val pip = mutableStateOf(false)
        val minimized = mutableStateOf(false)
        /** App-lock gate; true until the user authenticates (only when the setting is on). */
        val locked = mutableStateOf(true)
        /** True while a call is ringing, connecting, or active. Daily digest waits. */
        val callBusy = mutableStateOf(false)
    }
}

/** Which full-screen page is on top of the chat. */
private enum class Page { HOME, CHAT, SETTINGS, BOT, GALLERY, FAVORITES, ANNIVERSARIES, LOCATION_VIEW, LOCATION_PICK, FILE, PROFILE, PEER_PROFILE, ALBUM, CALL_NOTES }

@Composable
private fun Root(repo: ChatRepository, calls: CallManager, onCollapse: () -> Boolean, onUnlock: () -> Unit) {
    val ctx = LocalContext.current
    var loggedIn by remember { mutableStateOf(repo.prefs.loggedIn) }
    val locked by MainActivity.locked
    var update by remember { mutableStateOf<ink.jvm.chatter.data.ReleaseInfo?>(null) }
    LaunchedEffect(loggedIn) {
        if (loggedIn) ink.jvm.chatter.util.UpdateChecker.check(repo, force = false)?.let { update = it }
    }
    update?.let { r ->
        UpdateDialog(r, onDownload = { ink.jvm.chatter.util.UpdateChecker.download(ctx, r, null); update = null },
            onSkip = { repo.prefs.skippedVersion = r.versionCode; update = null }, onLater = { update = null })
    }
    val callState by calls.state.collectAsStateWithLifecycle()
    val withBot by calls.withBot.collectAsStateWithLifecycle()
    val botName by repo.botName.collectAsStateWithLifecycle()
    val muted by calls.muted.collectAsStateWithLifecycle()
    val inPip by MainActivity.pip
    val minimized by MainActivity.minimized

    LaunchedEffect(Unit) {
        repo.authLost.collect {
            ChatService.stop(ctx)
            loggedIn = false
        }
    }

    LaunchedEffect(callState) {
        MainActivity.callBusy.value = callState !is CallManager.State.Idle && callState !is CallManager.State.Ended
        when (callState) {
            is CallManager.State.Idle,
            is CallManager.State.Incoming,
            is CallManager.State.Ended -> {
                MainActivity.minimized.value = false
                calls.setCameraPaused(false)
            }
            else -> {}
        }
        (ctx as? MainActivity)?.syncPipParams()
        val act = ctx as? MainActivity
        if (act != null) {
            val awake = callState !is CallManager.State.Idle && callState !is CallManager.State.Ended
            if (awake) act.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else act.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(muted) { (ctx as? MainActivity)?.syncPipParams() }

    LaunchedEffect(callState, inPip) {
        if (inPip && callState is CallManager.State.Idle) {
            val act = ctx as? MainActivity ?: return@LaunchedEffect
            act.startActivity(
                Intent(act, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    var page by remember { mutableStateOf(Page.HOME) }
    var navForward by remember { mutableStateOf(true) }
    // Pages under the current one, newest last. Back always pops; it never stays on the same page.
    val backStack = remember { androidx.compose.runtime.mutableStateListOf<Page>() }
    fun open(next: Page) {
        if (next == page) return
        navForward = true
        backStack.add(page)
        page = next
    }
    fun goBack() {
        navForward = false
        page = if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex) else Page.HOME
    }
    var locationMsg by remember { mutableStateOf<ink.jvm.chatter.data.LocalMessage?>(null) }
    var fileMsg by remember { mutableStateOf<ink.jvm.chatter.data.LocalMessage?>(null) }
    var pickToBot by remember { mutableStateOf(false) }
    fun openLocation(m: ink.jvm.chatter.data.LocalMessage, from: Page) { locationMsg = m; open(Page.LOCATION_VIEW) }
    fun openFile(m: ink.jvm.chatter.data.LocalMessage, from: Page) { fileMsg = m; open(Page.FILE) }
    var botTarget by remember { mutableStateOf<String?>(null) }
    val pendingAction by MainActivity.pendingAction
    LaunchedEffect(pendingAction) {
        if (pendingAction == "open_bot") {
            MainActivity.pendingAction.value = null
            botTarget = null
            open(Page.BOT)
        } else if (pendingAction == "open_chat") {
            MainActivity.pendingAction.value = null
            open(Page.CHAT)
        } else if (pendingAction?.startsWith("jump:") == true && page != Page.CHAT) {
            open(Page.CHAT)
        }
    }
    var crashNotice by remember { mutableStateOf(ink.jvm.chatter.util.Diag.pendingCrashes(ctx).isNotEmpty()) }
    if (crashNotice) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { crashNotice = false },
            title = { androidx.compose.material3.Text("上次运行时崩溃了") },
            text = { androidx.compose.material3.Text("已记录崩溃信息。导出诊断文件发给开发者，能帮助定位问题。") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { crashNotice = false; runCatching { ink.jvm.chatter.util.Diag.share(ctx) } }) { androidx.compose.material3.Text("导出诊断") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { crashNotice = false; ink.jvm.chatter.util.Diag.clearCrashes(ctx) }) { androidx.compose.material3.Text("忽略") } },
        )
    }
    var noteFocus by remember { mutableStateOf<String?>(null) }
    val pendingNote by ink.jvm.chatter.media.CallNotes.pendingId.collectAsStateWithLifecycle()
    LaunchedEffect(callState, locked, loggedIn, pendingNote) {
        if (!loggedIn || (locked && repo.prefs.appLock)) return@LaunchedEffect
        if (callState != CallManager.State.Idle) return@LaunchedEffect
        val id = pendingNote ?: return@LaunchedEffect
        ink.jvm.chatter.media.CallNotes.pendingId.value = null
        noteFocus = id
        open(Page.CALL_NOTES)
    }
    val fullCall = callState != CallManager.State.Idle && (inPip || !minimized)
    androidx.activity.compose.BackHandler(enabled = (fullCall && !inPip) || page != Page.HOME) {
        if (fullCall && !inPip && onCollapse()) return@BackHandler
        goBack()
    }
    val peerName = if (withBot) botName else repo.prefs.peerName.ifEmpty { "对方" }
    val chip: @Composable (() -> Unit) -> Unit = { back ->
        if (minimized && (callState is CallManager.State.Active || callState is CallManager.State.Outgoing)) {
            FloatingCallChip(calls = calls, peerName = peerName, onExpand = { back(); (ctx as? MainActivity)?.expandCall() })
        }
    }
    when {
        !loggedIn -> LoginScreen(repo = repo, onLoggedIn = { loggedIn = true; MainActivity.locked.value = false })
        locked && repo.prefs.appLock && callState == CallManager.State.Idle -> LockScreen(onUnlock)
        fullCall -> CallScreen(calls = calls, peerName = peerName, onCollapse = onCollapse)
        else -> Box(Modifier.fillMaxSize()) {
            val life = androidx.lifecycle.compose.LocalLifecycleOwner.current
            LaunchedEffect(life) {
                life.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                    repo.messages.collect {
                        kotlinx.coroutines.delay(600)
                        val state = calls.state.value
                        val busy = state !is CallManager.State.Idle && state !is CallManager.State.Ended
                        ink.jvm.chatter.media.DailySummary.maybeRun(ctx, repo, busy)
                    }
                }
            }
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    Motion.pages(navForward)
                },
                label = "page",
            ) { dest ->
                when {
                    dest == Page.BOT -> BotScreen(repo, onBack = { goBack() }, targetId = botTarget, onLocation = { openLocation(it, Page.BOT) }, onFile = { openFile(it, Page.BOT) })
                    dest == Page.SETTINGS -> SettingsScreen(
                        repo,
                        onBack = { goBack() },
                        onFontScale = { MainActivity.fontScale.value = it },
                        onOpenNotes = { noteFocus = null; open(Page.CALL_NOTES) },
                    )
                    dest == Page.CALL_NOTES -> CallNotesScreen(repo, startId = noteFocus, onBack = { goBack() })
                    dest == Page.GALLERY -> GalleryPage(repo, onBack = { goBack() }, onJump = { id -> open(Page.CHAT); MainActivity.pendingAction.value = "jump:$id" }, onOpenFile = { openFile(it, Page.GALLERY) })
                    dest == Page.FAVORITES -> FavoritesScreen(repo, onBack = { goBack() }, onJump = { id -> open(Page.CHAT); MainActivity.pendingAction.value = "jump:$id" })
                    dest == Page.ANNIVERSARIES -> AnniversaryScreen(repo, onBack = { goBack() })
                    dest == Page.PROFILE || dest == Page.PEER_PROFILE -> ProfileScreen(repo, mine = dest == Page.PROFILE, onBack = { goBack() }, onAnniversaries = { open(Page.ANNIVERSARIES) }, onAlbum = { open(Page.ALBUM) })
                    dest == Page.ALBUM -> AlbumScreen(repo, onBack = { goBack() }, onOpen = { m ->
                        if (m.kind == "file") openFile(m, Page.ALBUM)
                        else { locationMsg = null; fileMsg = null; open(Page.CHAT); MainActivity.pendingAction.value = "jump:${m.id}" }
                    })
                    dest == Page.LOCATION_VIEW && locationMsg != null -> LocationPreviewScreen(repo, locationMsg!!, onBack = { goBack() })
                    dest == Page.LOCATION_PICK -> LocationPickerScreen(repo, onSend = { fix, live -> repo.sendLocation(fix, live, pickToBot); goBack() }, onCancel = { goBack() })
                    dest == Page.FILE && fileMsg != null -> FilePreviewScreen(repo, fileMsg!!, onBack = { goBack() })
                    dest == Page.HOME -> {
                        LaunchedEffect(Unit) { ChatService.start(ctx) }
                        HomeScreen(
                            repo,
                            onOpenChat = { open(Page.CHAT) },
                            onOpenBot = { botTarget = null; open(Page.BOT) },
                            onSettings = { open(Page.SETTINGS) },
                            onFavorites = { open(Page.FAVORITES) },
                            onAnniversaries = { open(Page.ANNIVERSARIES) },
                            onProfile = { open(Page.PROFILE) },
                            onPeerProfile = { open(Page.PEER_PROFILE) },
                            onAlbum = { open(Page.ALBUM) },
                            gallery = { GalleryPage(repo, onBack = { }, onJump = { id -> open(Page.CHAT); MainActivity.pendingAction.value = "jump:$id" }, onOpenFile = { openFile(it, Page.HOME) }) },
                        )
                    }
                    else -> {
                        LaunchedEffect(Unit) { ChatService.start(ctx) }
                        ChatScreen(
                            repo = repo,
                            calls = calls,
                            onLogout = {
                                repo.logout()
                                ChatService.stop(ctx)
                                loggedIn = false
                            },
                            nav = ChatNav(
                                onSettings = { open(Page.SETTINGS) },
                                onBot = { id -> botTarget = id; open(Page.BOT) },
                                onGallery = { open(Page.GALLERY) },
                                onFavorites = { open(Page.FAVORITES) },
                                onAnniversaries = { open(Page.ANNIVERSARIES) },
                                onBack = { goBack() },
                                onLocation = { openLocation(it, Page.CHAT) },
                                onPickLocation = { toBot -> pickToBot = toBot; open(Page.LOCATION_PICK) },
                                onFile = { openFile(it, Page.CHAT) },
                                onProfile = { open(Page.PEER_PROFILE) },
                                onAlbum = { open(Page.ALBUM) },
                            ),
                        )
                    }
                }
            }
            chip { if (page != Page.HOME && page != Page.CHAT) goBack() }
        }
    }
}

/** Media library page wired to the repository. */
@Composable
private fun GalleryPage(repo: ChatRepository, onBack: () -> Unit, onJump: (String) -> Unit, onOpenFile: (ink.jvm.chatter.data.LocalMessage) -> Unit) {
    val ctx = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var viewer by remember { mutableStateOf<ink.jvm.chatter.data.LocalMessage?>(null) }
    var video by remember { mutableStateOf<ink.jvm.chatter.data.MediaInfo?>(null) }
    var images by remember { mutableStateOf<List<ink.jvm.chatter.data.LocalMessage>>(emptyList()) }
    GalleryScreen(
        load = { repo.mediaMessages().also { all -> images = all.filter { (it.kind == "image" || it.kind == "album") && !it.once } } },
        mediaUrl = repo.api::mediaUrl,
        onBack = onBack,
        onOpenImage = { viewer = it },
        onOpenVideo = { video = it },
        onOpenFile = onOpenFile,
        onSaveAll = { list, progress ->
            var ok = 0
            list.forEachIndexed { i, m ->
                progress(i + 1, list.size)
                m.media?.let { md -> if (runCatching { ink.jvm.chatter.util.MediaSaver.save(ctx, repo, md) }.isSuccess) ok++ }
            }
            ok
        },
        onJumpTo = { onJump(it.id) },
    )
    viewer?.let { m -> if (images.any { it.id == m.id }) MediaViewer(repo, images.asReversed(), m.id, onClose = { viewer = null }) else viewer = null }
    video?.let { VideoPlayerDialog(repo, it, onClose = { video = null }) }
}

