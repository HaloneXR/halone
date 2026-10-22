package dev.halone.app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import dev.halone.compositor.CursorStyle
import dev.halone.compositor.HitPart
import dev.halone.compositor.ImmersiveLayer
import dev.halone.compositor.Panel
import dev.halone.compositor.SceneHit
import dev.halone.compositor.TouchPhase
import dev.halone.display.DisplayProbe
import dev.halone.engine.webview.TextField
import dev.halone.engine.webview.WebViewEngine
import dev.halone.glasses.api.DeviceCalibration
import dev.halone.glasses.api.DeviceDescriptorDump
import dev.halone.glasses.api.DeviceEvent
import dev.halone.glasses.api.GlassesDevice
import dev.halone.glasses.api.TrackingSample
import dev.halone.glasses.api.preferredMonoMode
import dev.halone.glasses.api.preferredStereoMode
import dev.halone.glasses.api.modeFor
import dev.halone.glasses.api.refreshRates
import dev.halone.glasses.api.DisplayMode
import dev.halone.glasses.drivers.xreal.IncompleteCalibration
import dev.halone.glasses.fusion.DisplayClock
import dev.halone.glasses.fusion.HeadTracker
import dev.halone.glasses.input.TapDetector
import dev.halone.glasses.input.TapGestures
import dev.halone.glasses.input.TapSide
import dev.halone.glasses.math.Vec3
import dev.halone.shell.DisplayRate
import dev.halone.shell.DisplayState
import dev.halone.shell.KeyChord
import dev.halone.shell.RecordingState
import dev.halone.shell.ShellListener
import dev.halone.shell.ShellSettings
import dev.halone.shell.Shortcuts
import dev.halone.app.display.StereoTestPresentation
import dev.halone.app.panel.GeckoKeyboard
import dev.halone.app.pointer.CursorImages
import dev.halone.app.pointer.CursorInput
import dev.halone.app.pointer.PadGestures
import dev.halone.app.pointer.PhonePointer
import dev.halone.app.pointer.feed
import dev.halone.app.session.XrSessionService
import dev.halone.app.shell.BedModeController
import dev.halone.app.shell.KeyCaptureService
import dev.halone.app.shell.PhoneShell
import dev.halone.app.shell.ShellSession
import dev.halone.app.stereo.GlassesStereoSession
import dev.halone.app.testmode.PhoneDisplay
import dev.halone.app.testmode.PhoneGlasses
import dev.halone.app.tap.TapRecorder
import dev.halone.app.tap.TapScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ACTION_USB_PERMISSION = "dev.halone.app.USB_PERMISSION"

/**
 * Lets development actions be driven without bringing the activity to the
 * front: `adb shell am broadcast -a dev.halone.app.ACTION --es action usb`.
 * `am start ... -e action usb` works too, from the foreground.
 */
private const val ACTION_DEV = "dev.halone.app.ACTION"
private const val FRAME_TIME_MS = 16.6f
private const val CLOCK_BURST = 5
private const val CLOCK_BURST_GAP_MS = 25L
private const val CLOCK_RESYNC_MS = 5_000L

class MainActivity : AppCompatActivity() {

    private lateinit var home: HomeScreen
    private var phoneSettings: PhoneSettings? = null
    private var phoneShell: PhoneShell? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        home = HomeScreen(
            this,
            onPrimary = ::onPrimary,
            onSettings = ::openSettings,
            onShareReport = ::shareCrashReport,
            onDismissReport = ::dismissCrashReport,
            onTestModeUnlocked = ::unlockTestMode,
        )
        setContentView(home.view)
        registerDevActions()
        // A foreground service keeps the process running; this keeps the phone
        // awake, since the glasses go dark with it.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        registerReceiver(permissionReceiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_NOT_EXPORTED)
        // Exported so `adb shell am broadcast` can reach it during development.
        registerReceiver(actionReceiver, IntentFilter(ACTION_DEV), Context.RECEIVER_EXPORTED)
        registerReceiver(
            usbReceiver,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            Context.RECEIVER_NOT_EXPORTED,
        )
        onBackPressedDispatcher.addCallback(this, settingsBack)
        XrSessionService.onTaskRemoved = {
            logLine("app swiped away")
            releaseGlasses()
            finish()
        }
        getSystemService(android.hardware.display.DisplayManager::class.java).registerDisplayListener(displayListener, null)
        logLine("Halone ${BuildConfig.VERSION_NAME}, Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT}) on ${android.os.Build.MODEL}")
        logLine("drivers:\n${connection.describeSupported()}")
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            // Only affects whether the session notification is visible; the
            // service runs either way.
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        refreshHome()
        recoverFromStereo()
        dispatchIntent(intent)
    }

    /**
     * The glasses are still side by side from a session whose process was
     * killed, where nothing could put them back: connect, which puts them back.
     */
    private fun recoverFromStereo() {
        if (stereoSession != null || !glassesInStereo()) return
        val found = connection.findSupported() ?: return
        if (!connection.hasPermission(found.first)) return
        logLine("glasses left side by side: restoring their normal mode")
        connect()
    }

    /** Whether the glasses' display is in a side-by-side mode, twice as wide as it is tall or more. */
    private fun glassesInStereo(): Boolean =
        getSystemService(android.hardware.display.DisplayManager::class.java).displays.any { display ->
            display.displayId != android.view.Display.DEFAULT_DISPLAY &&
                (android.os.Build.VERSION.SDK_INT < 31 || display.deviceProductInfo != null) &&
                display.mode.physicalWidth >= 2 * display.mode.physicalHeight
        }

    override fun onResume() {
        super.onResume()
        lifecycle("resumed")
        home.showCrashReport(CrashReports.pending(this) != null)
        refreshHome()
    }

    override fun onPause() {
        lifecycle("paused")
        super.onPause()
    }

    /**
     * Where the activity is when it comes and goes: a window that leaves the
     * phone for the glasses' display looks like a force close from the phone.
     */
    private fun lifecycle(event: String) {
        logLine("activity $event on display ${display?.displayId}" + if (isFinishing) " (finishing)" else "")
    }

    /** Glasses plugged in or pulled out; the system delivers both while the app is running. */
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED && testDisplay == null && connection.device != null && connection.findSupported() == null) {
                glassesGone()
            }
            refreshHome()
        }
    }

    /** Where the glasses stand, and their model when there are any. */
    private fun glassesStatus(): Pair<GlassesStatus, String?> {
        val found = connection.findSupported()
        if (found == null) glassesFoundAt = null else if (glassesFoundAt == null) glassesFoundAt = android.os.SystemClock.uptimeMillis()
        val model = connection.device?.info?.displayName ?: found?.second?.match?.info?.displayName
        val status = when {
            shellSession != null -> GlassesStatus.RUNNING
            starting != null -> GlassesStatus.STARTING
            found == null -> GlassesStatus.DISCONNECTED
            noVideoOutput() -> GlassesStatus.NO_VIDEO_OUTPUT
            tracker != null -> GlassesStatus.READY
            connection.linkMissing(found.first, found.second) -> GlassesStatus.LINK_OFF
            !connection.hasPermission(found.first) -> GlassesStatus.AWAITING_PERMISSION
            else -> GlassesStatus.READY
        }
        return status to model
    }

    /** When the glasses' USB interfaces were first seen, to give their display time to appear. */
    private var glassesFoundAt: Long? = null

    /**
     * The glasses are on USB but no display of theirs came up: the phone's port
     * carries data but not video. Android tells apps nothing about DisplayPort
     * Alt Mode, so this is the only way to know.
     */
    private fun noVideoOutput(): Boolean {
        val since = glassesFoundAt ?: return false
        val waited = android.os.SystemClock.uptimeMillis() - since
        if (waited < DISPLAY_GRACE_MS) {
            home.view.postDelayed({ refreshHome() }, DISPLAY_GRACE_MS - waited + 100)
            return false
        }
        val displays = getSystemService(android.hardware.display.DisplayManager::class.java).displays
        return displays.none { display ->
            display.displayId != android.view.Display.DEFAULT_DISPLAY &&
                (android.os.Build.VERSION.SDK_INT < 31 || display.deviceProductInfo != null)
        }
    }

    private val displayListener = object : android.hardware.display.DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            logLine("display $displayId added: ${describeDisplay(displayId)}")
            refreshHome()
        }

        override fun onDisplayRemoved(displayId: Int) {
            logLine("display $displayId removed")
            refreshHome()
        }

        override fun onDisplayChanged(displayId: Int) {
            if (displayId != android.view.Display.DEFAULT_DISPLAY) logLine("display $displayId changed: ${describeDisplay(displayId)}")
        }
    }

    private fun describeDisplay(displayId: Int): String {
        val display = getSystemService(android.hardware.display.DisplayManager::class.java).getDisplay(displayId) ?: return "gone"
        val mode = display.mode
        val metrics = android.util.DisplayMetrics().also { @Suppress("DEPRECATION") display.getRealMetrics(it) }
        return "\"${display.name}\" ${mode.physicalWidth}x${mode.physicalHeight}@${"%.0f".format(mode.refreshRate)}" +
            " ${metrics.widthPixels}x${metrics.heightPixels} ${metrics.densityDpi}dpi state ${display.state}" +
            " frames at ${"%.0f".format(display.refreshRate)}"
    }

    private fun refreshHome() {
        if (!::home.isInitialized) return
        val (status, model) = glassesStatus()
        val setup = connection.findSupported()?.second?.match?.setup
        val offerLink = setup != null && connectFailedAt > 0 && status != GlassesStatus.LINK_OFF && tracker == null
        home.testModeUnlocked = savedSettings().boolean(ShellSettings.TEST_MODE_ENABLED)
        val offerTestMode = home.testModeUnlocked && (status == GlassesStatus.DISCONNECTED || status == GlassesStatus.NO_VIDEO_OUTPUT)
        home.render(
            status,
            model,
            help = setup?.linkOffHelp,
            notice = homeNotice,
            action = when {
                offerLink -> "Turn on TCP/IP" to ::turnOnLink
                offerTestMode -> "Start Test Mode" to ::startTestMode
                else -> null
            },
        )
    }

    private fun unlockTestMode() {
        savedSettings().set(ShellSettings.TEST_MODE_ENABLED, true)
        refreshHome()
    }

    /** Set while test mode runs the environment on the phone's own screen and sensors. */
    private var testDisplay: PhoneDisplay? = null

    private fun startTestMode() {
        if (shellSession != null || starting != null || connection.device != null) return
        val glasses = PhoneGlasses(this)
        if (!glasses.tracking.available) {
            homeNotice = "Test mode needs a gyroscope and an accelerometer, and this phone lacks one."
            refreshHome()
            return
        }
        logLine("▶ starting test mode")
        testDisplay = PhoneDisplay(this)
        XrSessionService.withoutDevice = true
        connection.standIn(glasses)
        connected(glasses, null)
        toggleEnvironment()
        starting?.invokeOnCompletion { runOnUiThread { if (shellSession == null) endTestMode() } }
    }

    private fun endTestMode() {
        val display = testDisplay ?: return
        testDisplay = null
        glassesGone()
        display.close()
        XrSessionService.withoutDevice = false
        logLine("test mode ended")
    }

    private fun shareCrashReport() {
        val report = CrashReports.reportFile(this).takeIf { it.exists() } ?: return
        CrashReports.share(this, report, "Halone crash report", "Share crash report")
    }

    private fun dismissCrashReport() {
        CrashReports.clear(this)
        home.showCrashReport(false)
    }

    /** Why the last attempt at something failed, for the home screen; cleared once things work. */
    private var homeNotice: String? = null

    private fun onPrimary(status: GlassesStatus) {
        when (status) {
            GlassesStatus.AWAITING_PERMISSION -> requestPermissionAndConnect()
            GlassesStatus.LINK_OFF -> turnOnLink()
            GlassesStatus.READY -> remindThenStart()
            GlassesStatus.RUNNING -> toggleEnvironment()
            else -> Unit
        }
    }

    /**
     * Shows the device's "before starting" reminder, once per start, until the
     * wearer says not to. It's for settings only the glasses' own menu can
     * change, which the host can't check.
     */
    private fun remindThenStart() {
        val found = connection.findSupported()
        val reminder = found?.second?.match?.setup?.beforeStarting
        val key = "skip-reminder-${found?.second?.match?.info?.driverId}"
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (reminder == null || shellSession != null || prefs.getBoolean(key, false)) {
            toggleEnvironment()
            return
        }
        val dontAsk = android.widget.CheckBox(this).apply { text = "Don't remind me again" }
        val padding = (20 * resources.displayMetrics.density).toInt()
        com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle("Before you start")
            .setMessage(reminder)
            .setView(android.widget.FrameLayout(this).apply {
                setPadding(padding, 0, padding, 0)
                addView(dontAsk)
            })
            .setPositiveButton("Start") { _, _ ->
                if (dontAsk.isChecked) prefs.edit().putBoolean(key, true).apply()
                toggleEnvironment()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Set while a USB permission request is for turning the link on rather than for connecting. */
    private var linkRequested = false

    /**
     * Asks the glasses to turn their data link on. They disconnect and come
     * back as a new device, which the USB receiver picks up.
     */
    private fun turnOnLink() {
        val (usbDevice, probe) = connection.findSupported() ?: return
        if (!getSystemService(UsbManager::class.java).hasPermission(usbDevice)) {
            linkRequested = true
            connection.requestPermission(
                usbDevice,
                PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName), PendingIntent.FLAG_MUTABLE),
            )
            return
        }
        lifecycleScope.launch {
            logLine("▶ turning the glasses' network connection on")
            val result = withContext(Dispatchers.IO) { connection.enableLink(usbDevice, probe) }
            result.onFailure {
                logLine("  could not: ${it.message}")
                homeNotice = "Couldn't turn it on: ${it.message}"
                refreshHome()
                return@launch
            }
            logLine("  done; waiting for the glasses to reconnect")
            homeNotice = "Turning it on. The glasses will disconnect and reconnect by themselves."
            connectFailedAt = 0L
            refreshHome()
            val back = waitUntil(LINK_RETURN_TIMEOUT_MS) {
                val found = connection.findSupported()
                found != null && !connection.linkMissing(found.first, found.second)
            }
            homeNotice = if (back) {
                logLine("  the glasses are back with their network on")
                null
            } else {
                logLine("  the glasses did not come back with their network on")
                "The glasses didn't come back with their network on. Unplug them and plug them back in. " +
                    "If that doesn't help, turn on TCP/IP in their own menu."
            }
            refreshHome()
        }
    }

    /** The glasses went away under a running session: stop everything that needed them. */
    private fun glassesGone() {
        if (connection.device == null && tracker == null) return
        logLine("glasses disconnected")
        if (stereoSession != null) runCatching { leaveStereo() }.onFailure { logLine("  leaving stereo: $it") }
        clockPacer?.cancel()
        clockPacer = null
        displayClock = null
        tracker = null
        lastInertial = null
        connection.close()
        XrSessionService.stop(this)
        refreshHome()
        displayChanged()
    }

    /** What the bridge's `display.state` answers, on any thread. */
    private fun displayState(): DisplayState {
        val session = stereoSession
        val compositor = session?.compositor
        val running = shellSession != null && compositor != null
        return DisplayState(
            running = running,
            stereo = running && compositor?.stereo == true,
            refreshHz = session?.display?.mode?.refreshRate?.toDouble()?.takeIf { running },
            renderFps = session?.renderFps?.takeIf { running },
            rates = connection.device?.display?.refreshRates().orEmpty().let { offered ->
                val phone = dev.halone.app.stereo.PhoneScreen.rates(this)
                offered.map { DisplayRate(it.hz, it.stereo, dev.halone.app.stereo.PhoneScreen.inStep(it.hz.toFloat(), phone)) }
            },
            restartNeeded = running && connection.device?.let { chosenMode(it) }?.let { it.key != enteredMode?.key } == true,
        )
    }

    private fun displayChanged() {
        shellSession?.shell?.displayChanged()
        phoneShell?.displayChanged()
    }

    /** The settings as saved, read afresh when no shell holds them. */
    private fun savedSettings(): ShellSettings = shellSession?.shell?.settings ?: ShellSettings(
        dev.halone.app.shell.PreferencesStore(this),
        assets.open(ShellSettings.SCHEMA_ASSET).bufferedReader().use { it.readText() },
    )

    /** The mode the wearer's refresh rate and 3D settings ask for, or null for glasses Halone cannot switch. */
    private fun chosenMode(device: GlassesDevice): DisplayMode? {
        val control = device.display ?: return null
        val settings = savedSettings()
        return control.modeFor(settings.number(ShellSettings.DISPLAY_REFRESH_RATE).roundToInt(), settings.boolean(ShellSettings.DISPLAY_STEREO))
    }

    /** The mode the running environment was started in. */
    private var enteredMode: DisplayMode? = null

    /** The rate to ask Android for, for glasses that take whatever rate the phone sends. */
    private fun rateFor(device: GlassesDevice, mode: DisplayMode?): Float? =
        mode?.refreshHz?.takeIf { device.display?.rateChosenByHost == true }

    /** A mode switch or a lost display being carried through; the windows stay open either way. */
    private var displayWork: Job? = null

    /**
     * Puts the running environment into the mode the refresh rate and 3D
     * settings ask for. The switch recreates the glasses' display; the
     * compositor and its windows move onto the new one.
     */
    private fun applyChosenMode() {
        val device = connection.device ?: return
        val session = stereoSession ?: return
        if (shellSession == null || starting != null || displayWork != null) return
        val wanted = chosenMode(device) ?: return
        if (wanted.key == enteredMode?.key) return
        displayWork = lifecycleScope.launch {
            try {
                // So the request that changed the setting is answered first.
                delay(MODE_SWITCH_DELAY_MS)
                logLine("▶ switching to $wanted")
                session.detach()
                val switched = session.enterStereo(device, wanted)
                if (switched != null) enteredMode = wanted
                val display = switched ?: session.awaitGlassesDisplay(device, null, DISPLAY_RETURN_MS)
                if (display == null) {
                    logLine("  no glasses display to go back to")
                    return@launch
                }
                session.moveTo(display, rateFor(device, enteredMode))
                applyTrackingSettings()
            } finally {
                displayWork = null
                displayChanged()
            }
        }
    }

    /**
     * The system took the environment off the glasses: their display went
     * away, usually with the video link, and comes back under a new id. Moves
     * the compositor onto it when it does, windows and all. Unplugging ends
     * the session instead, through [glassesGone].
     */
    private fun glassesDisplayLost() {
        if (isFinishing || isDestroyed || shellSession == null || displayWork != null) return
        val session = stereoSession ?: return
        val lostId = session.display?.displayId
        session.detach()
        testDisplay?.let {
            session.moveTo(it.display)
            return
        }
        logLine("▶ the glasses' display went away; waiting for it to come back")
        displayWork = lifecycleScope.launch {
            try {
                while (shellSession != null) {
                    val device = connection.device ?: return@launch
                    val display = session.awaitGlassesDisplay(device, lostId, DISPLAY_RETURN_MS) ?: continue
                    logLine("  back on display ${display.displayId}")
                    session.moveTo(display, rateFor(device, enteredMode))
                    applyTrackingSettings()
                    return@launch
                }
            } finally {
                displayWork = null
                displayChanged()
            }
        }
    }

    /** Settings on the phone: the environment's own shell while it runs, one of its own otherwise. */
    private fun openSettings() {
        if (phoneSettings?.shown == true) return
        val bridge = shellSession?.bridge ?: (phoneShell ?: PhoneShell(this, glassesStatus().second ?: "No glasses", ::displayState, ::logOnMain).also { phoneShell = it }).bridge
        phoneSettings = PhoneSettings(this, bridge, onClosed = ::settingsClosed, log = ::logOnMain).also { it.show() }
        settingsBack.isEnabled = true
    }

    private fun settingsClosed() {
        phoneSettings = null
        settingsBack.isEnabled = false
        phoneShell?.close()
        phoneShell = null
        home.showCrashReport(CrashReports.pending(this) != null)
        refreshHome()
    }

    private val settingsBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            phoneSettings?.back()
        }
    }

    /** The buttons along the top of the pointer pad. */
    private val padActions = object : PointerPad.Actions {
        override fun recenterView() = this@MainActivity.recenterView()

        override fun recenterPointer() = runGesture("recenter-cursor", "PAD")

        override fun screenshot() {
            runCatching { shellSession?.shell?.captureScreen(0) }.onFailure { logLine("  screenshot: $it") }
        }

        override fun toggleRecording() {
            val shell = shellSession?.shell ?: return
            runCatching { if (shell.recording.recording) shell.stopRecording() else shell.startRecording(0) }
                .onFailure { logLine("  recording: $it") }
        }

        override fun nextPointerMode() {
            val shell = shellSession?.shell ?: return
            val modes = listOf("phone", "touchpad", "gaze")
            val current = runCatching { shell.settings.string(ShellSettings.INPUT_POINTER) }.getOrNull()
            shell.setSetting(ShellSettings.INPUT_POINTER, modes[(modes.indexOf(current) + 1) % modes.size])
        }

        override fun toggleFollow() {
            val shell = shellSession?.shell ?: return
            shell.setSetting(ShellSettings.FOLLOW_ENABLED, !shell.settings.boolean(ShellSettings.FOLLOW_ENABLED))
        }

        override fun stop() {
            if (stereoSession != null) leaveStereo()
        }
    }

    /**
     * Actions for development, driven over adb (see [ACTION_DEV]) and listed
     * in logcat at startup: bringing up a new device, checking calibration and
     * display modes, and reporting on a running session.
     */
    private fun registerDevActions() {
        fun action(key: String, block: () -> Unit) {
            actions[key] = block
        }
        action("start") { toggleEnvironment() }
        action("test-mode") { startTestMode() }
        action("relink") { testDisplay?.recreate() ?: logLine("  only in test mode") }
        action("stop") { if (stereoSession != null) leaveStereo() }
        action("usb") { dumpUsb() }
        action("connect") { requestPermissionAndConnect() }
        action("imu") { streamTracking() }
        action("axes") { axisWalkthrough() }
        action("gravity") { gravityCheck() }
        action("drift") { driftTest() }
        action("calib") { dumpCalibration() }
        action("calibsource") { reportCalibration() }
        action("calibcheck") { calibrationCheck() }
        action("readmode") { readMode() }
        action("sbs") { setStereo(true) }
        action("mirror") { setStereo(false) }
        action("displays") { logLine(DisplayProbe.report(this)) }
        action("pattern") { showTestPattern() }
        action("dismiss") { dismissTestPattern() }
        action("latencyest") { latencyEstimate() }
        action("taprecord") { recordTaps() }
        action("recenter") { recenterView() }
        action("gaze") { reportGaze() }
        action("mag") { reportMagnetometer() }
        action("frames") { reportFrames() }
        action("timing") { timingStatus() }
        action("present") { presentStatus() }
        action("hitches") { reportHitches() }
        action("phasesweep") { sweepDrawPhase() }
        action("panels") { reportPanels() }
        action("why") { reportArrangement() }
        action("shellstatus") { reportShell() }
        action("focusfield") { focusField() }
        action("focustap") {
            val engine = stereoSession?.scene?.focused?.engine ?: return@action logLine("no window focused")
            engine.dispatchTap(engine.widthPx / 2f, engine.heightPx / 2f)
        }
        action("resize") {
            val session = stereoSession ?: run { logLine("not in stereo"); return@action }
            val panel = session.scene.panels.lastOrNull() ?: run { logLine("  no window"); return@action }
            val oldWidth = panel.widthPx
            val oldHeight = panel.heightPx
            panel.engine.resize(oldWidth * 5 / 4, oldHeight * 5 / 4)
            session.scene.resized(panel, oldWidth, oldHeight, dev.halone.compositor.Corner.TOP_LEFT)
            logLine("  resized '${panel.id}' ${oldWidth}x$oldHeight -> ${panel.widthPx}x${panel.heightPx}")
        }
        action("inputlog") {
            val on = cursorInput.trace == null
            cursorInput.trace = if (on) ({ logLine(it) }) else null
            inputTrace = on
            logLine("  input log ${if (on) "on" else "off"}")
        }
        action("settings") { openSettings() }
        action("crashtest") { window.decorView.post { throw IllegalStateException("crash test from the dev action") } }
        logLine("dev actions: ${actions.keys.joinToString(", ")}")
    }

    private val connection by lazy { GlassesConnection(this) }

    private var tracker: HeadTracker? = null

    private var displayClock: DisplayClock? = null

    private var clockPacer: Job? = null

    private var presentation: StereoTestPresentation? = null

    private val actions = LinkedHashMap<String, () -> Unit>()

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            // The system appends its result to this PendingIntent's extras, which
            // an immutable PendingIntent silently drops. Ask the framework
            // directly rather than trusting the extra.
            val extra = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            val usbDevice = connection.findSupported()?.first
            val granted = extra || (usbDevice != null && connection.hasPermission(usbDevice))
            logLine("USB permission ${if (granted) "granted" else "DENIED"} (extra=$extra)")
            val forLink = linkRequested
            linkRequested = false
            when {
                !granted -> Unit
                forLink -> turnOnLink()
                else -> connect()
            }
            refreshHome()
        }
    }

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra("action")?.split(',')?.forEach { run(it.trim()) }
        }
    }

    /** A screenshot taken on the phone takes one of the glasses too. */
    private val screenCaptured = if (android.os.Build.VERSION.SDK_INT >= 34) {
        Activity.ScreenCaptureCallback { runCatching { shellSession?.shell?.captureScreen(0) } }
    } else {
        null
    }

    override fun onStart() {
        super.onStart()
        lifecycle("started")
        if (android.os.Build.VERSION.SDK_INT >= 34) screenCaptured?.let { registerScreenCaptureCallback(mainExecutor, it) }
    }

    override fun onStop() {
        lifecycle("stopped")
        super.onStop()
        if (android.os.Build.VERSION.SDK_INT >= 34) screenCaptured?.let { unregisterScreenCaptureCallback(it) }
        reclaimFromClosedApp()
    }

    /**
     * Closing an Android app's window can hand the phone to that app: when
     * the app's virtual display goes away, Android moves its activity onto
     * the phone's own screen, in front of Halone — which then loses the pad.
     * If Halone is pushed back just after a window closed while the
     * environment runs, it brings itself back. A wearer leaving Halone on
     * purpose (Home, another app) closed no window, so is left alone.
     */
    private fun reclaimFromClosedApp() {
        val shell = shellSession ?: return
        if (isFinishing || !shell.shell.settings.boolean(ShellSettings.WINDOWS_RECLAIM_PHONE)) return
        val closedAt = stereoSession?.lastPanelClosedAt ?: return
        if (android.os.SystemClock.uptimeMillis() - closedAt > RECLAIM_AFTER_CLOSE_MS) return
        logLine("  an app window closed and took the phone; bringing Halone back")
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }.onFailure { logLine("  could not come back: ${it.message}") }
    }

    private var projectionResult: ((android.media.projection.MediaProjection?) -> Unit)? = null

    private var permissionsResult: ((Set<String>) -> Unit)? = null

    private val androidPermissions = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val done = permissionsResult
        permissionsResult = null
        done?.invoke(results.filterValues { it }.keys)
    }

    /** Asks on the phone for permissions a web page needs, such as the microphone's; one request at a time. */
    private fun requestAndroidPermissions(permissions: Array<String>, onResult: (Set<String>) -> Unit) {
        if (permissionsResult != null) return onResult(emptySet())
        permissionsResult = onResult
        androidPermissions.launch(permissions)
    }

    private val audioPermission = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) askToShareSound() else finishProjection(null)
    }

    private val shareSound = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) return@registerForActivityResult finishProjection(null)
        XrSessionService.allowProjection(this) {
            val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
            finishProjection(runCatching { manager.getMediaProjection(result.resultCode, data) }.onFailure { logLine("  recorder: no projection: $it") }.getOrNull())
        }
    }

    /**
     * Gets what a recording needs to capture sound, asking on the phone:
     * the microphone permission, which Android wants even for playback
     * capture, then consent to capture. [onResult] gets null if either is refused.
     */
    private fun requestProjection(onResult: (android.media.projection.MediaProjection?) -> Unit) {
        projectionResult = onResult
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            askToShareSound()
        } else {
            audioPermission.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    /**
     * Only the sound is used: frames come from the compositor. The whole
     * screen is the one choice offered, as any other suggests that the
     * choice decides what is recorded.
     */
    private fun askToShareSound() {
        val manager = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        val intent = if (android.os.Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        shareSound.launch(intent)
    }

    private fun finishProjection(projection: android.media.projection.MediaProjection?) {
        val result = projectionResult ?: return
        projectionResult = null
        result(projection)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        dispatchIntent(intent)
    }

    override fun onDestroy() {
        lifecycle("destroyed" + if (isChangingConfigurations) " for a configuration change" else "")
        super.onDestroy()
        XrSessionService.onTaskRemoved = null
        releaseGlasses()
        stopPhonePointer()
        XrSessionService.stop(this)
        phoneSettings?.hide()
        phoneShell?.close()
        presentation?.dismiss()
        runCatching { unregisterReceiver(permissionReceiver) }
        runCatching { unregisterReceiver(actionReceiver) }
        runCatching { unregisterReceiver(usbReceiver) }
        getSystemService(android.hardware.display.DisplayManager::class.java).unregisterDisplayListener(displayListener)
    }

    /** Leaves the glasses as the app found them, in their normal mode rather than side by side. */
    private fun releaseGlasses() {
        if (stereoSession != null) logLine("closing: restoring the glasses' normal mode")
        if (stereoSession != null) runCatching { leaveStereo() }.onFailure { logLine("  leaving stereo: $it") }
        connection.close()
    }

    /** Runs chained actions in order, letting a connect finish before the next one, since it now happens off the main thread. */
    private fun dispatchIntent(intent: Intent?) {
        val keys = intent?.getStringExtra("action")?.split(',')?.map { it.trim() } ?: return
        lifecycleScope.launch {
            for (key in keys) {
                run(key)
                connecting?.join()
            }
        }
    }

    /** Lets every development action be driven over adb: `am start ... -e action displays`. */
    private fun run(key: String) {
        val block = actions[key] ?: run {
            logLine("unknown action '$key'. available: ${actions.keys.joinToString(", ")}")
            return
        }
        logLine("--- running '$key' ---")
        runCatching(block).onFailure { logLine("ERROR: ${it.stackTraceToString()}") }
    }

    private fun dumpUsb() {
        val devices = connection.attachedDevices()
        if (devices.isEmpty()) {
            logLine("No USB devices attached.")
            return
        }
        logLine("${devices.size} USB device(s):")
        devices.forEach { logLine(DeviceDescriptorDump.describe(it)) }

        val supported = connection.findSupported()
        logLine(
            supported?.let { (_, probe) ->
                "==> recognised by driver '${probe.driver.id}' as ${probe.match.info.displayName}, " +
                    "needs interfaces ${probe.match.transports.interfaceIds}"
            } ?: "==> no attached device is recognised by any driver"
        )
    }

    private var starting: kotlinx.coroutines.Job? = null

    /**
     * The whole environment in one press, for using it rather than developing
     * it: connect the glasses (asking for USB access the first time), enter
     * stereo, start the shell. Each step waits for the one before, and a step
     * already done is skipped. Pressed again, it stops everything.
     */
    private fun toggleEnvironment() {
        if (starting != null) return
        if (shellSession != null) {
            leaveStereo()
            return
        }
        starting = lifecycleScope.launch {
            try {
                refreshHome()
                if (tracker == null) {
                    logLine("▶ connecting the glasses")
                    val since = android.os.SystemClock.uptimeMillis()
                    requestPermissionAndConnect()
                    if (!waitUntil(CONNECT_TIMEOUT_MS) { tracker != null || connectFailedAt >= since }) {
                        logLine("  the glasses did not connect: are they plugged in, and was USB access allowed?")
                        return@launch
                    }
                }
                // Every start begins with Halone holding the scene; the
                // glasses' own button hands it over again.
                connection.device?.onboardPinning?.takeIf { it.enabled }?.let { pinning ->
                    withContext(Dispatchers.IO) { pinning.setEnabled(false) }
                }
                if (stereoSession?.compositor == null) {
                    logLine("▶ entering stereo")
                    val attempt = enterStereo()
                    // Stops early when the attempt gives up, rather than
                    // sitting out the whole timeout after a failure it has
                    // already logged.
                    waitUntil(STEREO_TIMEOUT_MS) {
                        stereoSession?.compositor != null || attempt?.isActive != true
                    }
                    if (stereoSession?.compositor == null) {
                        logLine("  stereo did not start")
                        return@launch
                    }
                }
                logLine("▶ starting the shell")
                startShell()
            } finally {
                starting = null
                refreshHome()
            }
        }
    }

    private suspend fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            delay(200)
        }
        return condition()
    }

    private fun requestPermissionAndConnect() {
        val found = connection.findSupported()
        if (found == null) {
            logLine("No supported glasses found. Attach them and try 'usb' to see what is present.")
            return
        }
        val (usbDevice, probe) = found
        logLine("Found ${probe.match.info.displayName} via driver '${probe.driver.id}'")
        if (connection.hasPermission(usbDevice)) {
            connect()
        } else {
            logLine("Requesting USB permission...")
            connection.requestPermission(
                usbDevice,
                PendingIntent.getBroadcast(
                    this, 0, Intent(ACTION_USB_PERMISSION).setPackage(packageName),
                    PendingIntent.FLAG_MUTABLE,
                ),
            )
        }
    }

    private var connecting: Job? = null

    /** When the last connect attempt failed, so a start waiting on it can give up at once. */
    @Volatile
    private var connectFailedAt = 0L

    /**
     * Off the main thread: a network device opens sockets, which Android
     * refuses there outright, and reading its calibration can take seconds.
     */
    private fun connect() {
        if (connecting != null) return
        connecting = lifecycleScope.launch {
            try {
                val connected = withContext(Dispatchers.IO) {
                    connection.connect().map { device ->
                        device.addEventListener { event ->
                            logOnMain("  event: $event")
                            if (event is DeviceEvent.Disconnected) runOnUiThread { glassesGone() }
                            // The wearer handed world-locking to the glasses with their
                            // own button (or it came back): draw head-locked while
                            // they hold the image, so it is not stabilised twice.
                            if (event is DeviceEvent.OnboardPinningChanged) runOnUiThread {
                                stereoSession?.compositor?.lockToHead = event.enabled
                                if (event.enabled) recenterView()
                            }
                        }
                        // It pauses the sensor stream, so it happens once here
                        // rather than lazily later.
                        device to device.calibration?.load()
                    }
                }
                connected
                    .onSuccess { (device, calibration) -> connected(device, calibration) }
                    .onFailure {
                        logLine("Connect failed: ${it.message}")
                        homeNotice = "Couldn't connect: ${it.message}"
                        connectFailedAt = android.os.SystemClock.uptimeMillis()
                    }
            } finally {
                connecting = null
                refreshHome()
            }
        }
    }

    private fun connected(device: GlassesDevice, calibration: DeviceCalibration?) {
        homeNotice = null
        // Holds the process out of the background freezer, which otherwise
        // stops the render thread and freezes the glasses on a stale frame
        // within seconds.
        XrSessionService.start(this)
        logLine("Connected: ${device.info.displayName} (${device.info.driverId})")
        logLine(
            "  optics: ${device.optics.perEyeWidth}x${device.optics.perEyeHeight}/eye, " +
                "${device.optics.diagonalFovDegrees}° diagonal, ${device.optics.imuRateHz}Hz nominal"
        )
        logLine("  display control: ${if (device.display != null) "yes" else "no"}")
        logLine("  brightness control: ${if (device.brightness != null) "yes" else "no"}")
        logLine("  on-device fusion: ${device.tracking.providesOrientation}")

        calibration?.let { logLine("  ${it.describe().replace("\n", "\n  ")}") }
        if (calibration?.source == DeviceCalibration.Source.FALLBACK) {
            logLine("  WARNING: these are another unit's numbers — do not tune against them")
        }

        val head = HeadTracker(device.optics, calibration = calibration)
        tracker = head
        applyTrackingSettings()
        // One sink, fanned out: the tracker drives the pose while the
        // development actions keep the last raw sample for axis identification.
        device.tracking.start { sample ->
            head.onSample(sample)
            if (sample is TrackingSample.Inertial) {
                lastInertial = sample
                lastSampleHostNanos = System.nanoTime()
                tapRecorder?.record(sample, lastSampleHostNanos)
                detectGlassesTap(sample, lastSampleHostNanos)
            }
        }
        logLine("  tracking started")
        startDisplayTiming(device)
        if (stereoSession == null && starting == null && glassesInStereo()) setStereo(false)
        refreshHome()
        displayChanged()
    }

    /**
     * Subscribes to the display's own timing, so the compositor can aim at a
     * refresh rather than at a tuned constant.
     *
     * The beacons arrive by themselves; the clock round trip has to be asked
     * for, and is paced here rather than in the driver because how often a
     * host wants to spend a USB exchange on clock accuracy is the host's call.
     */
    private fun startDisplayTiming(device: GlassesDevice) {
        val scanout = device.scanout ?: run {
            logLine("  display timing: not reported by this device")
            return
        }
        val clock = DisplayClock()
        displayClock = clock
        scanout.start { clock.onBeacon(it) }
            .onFailure { logLine("  display timing: ${it.message}"); return }

        clockPacer?.cancel()
        clockPacer = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                // Best of a short burst: the offset is only known to within half
                // the round trip, so a few tries buy more than a single quiet one.
                repeat(CLOCK_BURST) {
                    scanout.syncClock().onSuccess { clock.onClockSample(it) }
                    delay(CLOCK_BURST_GAP_MS)
                }
                delay(CLOCK_RESYNC_MS)
            }
        }
        logLine("  display timing: listening (beacons arrive every few seconds)")
    }

    private fun requireDevice(): GlassesDevice? {
        val d = connection.device
        if (d == null) logLine("Not connected — run 'connect' first.")
        return d
    }

    private fun streamTracking() {
        val head = tracker ?: run { logLine("Not connected — run 'connect' first."); return }
        lifecycleScope.launch {
            val start = head.pose.sampleCount
            repeat(10) {
                delay(1_000)
                val pose = head.pose
                val (yaw, pitch, roll) = pose.orientation.toEulerDegrees()
                logLine(
                    "  yaw=%.1f pitch=%.1f roll=%.1f  w=[%s]  samples=%d  biasCalibrated=%b"
                        .format(yaw, pitch, roll, pose.angularVelocity, pose.sampleCount, head.biasCalibrated)
                )
            }
            val total = head.pose.sampleCount - start
            logLine("stream: $total samples in 10s (~${total / 10} Hz)")
        }
    }

    /**
     * Walks the user through known poses so the driver's [AxisConvention] can be
     * pinned down instead of assumed. Desk measurements are not a known enough
     * pose to determine the signs.
     */
    private fun axisWalkthrough() {
        requireDevice() ?: return
        lifecycleScope.launch {
            val steps = listOf(
                "Hold the glasses as if WORN, looking straight ahead. Gravity should read +Y.",
                "Now TILT YOUR CHIN DOWN (pitch down). Watch which gyro axis goes negative.",
                "Return to level, then TURN LEFT (yaw left). Canonical: gyro Y positive.",
                "Return to level, then TILT YOUR HEAD to the RIGHT shoulder (roll right).",
            )
            for ((i, instruction) in steps.withIndex()) {
                logLine("  step ${i + 1}/4: $instruction")
                logLine("  ...hold for 4 seconds")
                delay(4_000)
                val sample = lastInertial
                if (sample == null) {
                    logLine("  no inertial samples yet — does this device fuse on-device?")
                    return@launch
                }
                logLine("  gyro=[${sample.gyro}]  accel=[${sample.accel}]")
            }
            logLine("  Compare against AxisConvention's documented frame and set the driver's AXES.")
        }
    }

    @Volatile
    private var lastInertial: TrackingSample.Inertial? = null

    /**
     * When the newest sample reached us, on *our* clock.
     *
     * The IMU's own timestamp counts from the glasses' internal clock, so
     * subtracting it from `System.nanoTime()` is meaningless — it produced an
     * "age" of eleven days. Stamping arrival locally gives the part of the
     * pipeline we can actually attribute: USB transfer plus queueing.
     */
    @Volatile
    private var lastSampleHostNanos = 0L

    /**
     * Reports where gravity currently sits in the device frame.
     *
     * The single most useful measurement for pinning down a driver's axis
     * convention: hold the glasses in a known pose and the dominant accelerometer
     * axis tells you which way "up" is in the hardware's own frame. Our canonical
     * frame expects +Y when worn level.
     */
    private fun gravityCheck() {
        requireDevice() ?: return
        lifecycleScope.launch {
            delay(600)
            val sample = lastInertial ?: run { logLine("  no inertial samples yet"); return@launch }
            val a = sample.accel
            val axes = listOf("+X" to a.x, "+Y" to a.y, "+Z" to a.z)
            val dominant = axes.maxByOrNull { kotlin.math.abs(it.second) }!!
            val sign = if (dominant.second > 0) "+" else "-"
            logLine("  accel = [$a]  |a| = %.2f m/s^2".format(a.length))
            logLine("  gravity is on ${sign}${dominant.first.drop(1)} (%.2f)".format(dominant.second))
            logLine("  canonical frame expects +Y when the glasses are worn level")
            logLine("  gyro = [${sample.gyro}]")
        }
    }

    private fun driftTest() {
        val head = tracker ?: run { logLine("Not connected — run 'connect' first."); return }
        logLine("  keep the glasses perfectly still for 60s...")
        lifecycleScope.launch {
            while (!head.biasCalibrated) {
                logLine("  waiting for bias calibration...")
                delay(2_000)
            }
            recentreHead(head)
            logLine("  recentred; bias calibrated. measuring.")
            repeat(6) {
                delay(10_000)
                val (yaw, pitch, roll) = head.pose.orientation.toEulerDegrees()
                logLine("  t=+${(it + 1) * 10}s  yaw=%.2f pitch=%.2f roll=%.2f".format(yaw, pitch, roll))
            }
            val (yaw, _, _) = head.pose.orientation.toEulerDegrees()
            logLine("  total yaw drift over 60s: %.2f° (%.2f°/min)".format(yaw, yaw))
        }
    }

    /**
     * Pulls the factory calibration blob and writes it next to a hex log of
     * every reply that built it.
     *
     * The raw log is not redundant. Our framing for this transfer is inferred
     * rather than measured (see `docs/protocol/xreal-hid.md`), so if the
     * assembled blob turns out to be wrong, the raw replies are the only thing
     * that lets it be re-assembled without putting the glasses back on.
     */
    private fun dumpCalibration() {
        val device = requireDevice() ?: return
        val source = device.calibration ?: run {
            logLine("this device exposes no calibration blob")
            return
        }
        val dir = getExternalFilesDir(null) ?: run {
            logLine("no external files dir to write to")
            return
        }

        logLine("  pausing tracking and pulling the blob — about a thousand round trips...")
        lifecycleScope.launch(Dispatchers.IO) {
            val raw = StringBuilder()
            var replies = 0
            val startedNs = System.nanoTime()

            val result = source.read { packet ->
                replies++
                raw.append(hex(packet)).append('\n')
                if (replies % 200 == 0) logOnMain("  ...$replies replies")
            }

            val elapsedMs = (System.nanoTime() - startedNs) / 1_000_000
            val stamp = fileTimestampFormat.format(Date())
            val serial = device.info.serial ?: "unknown"

            val rawFile = java.io.File(dir, "calibration-$stamp-raw.txt")
            runCatching { rawFile.writeText(raw.toString()) }
                .onFailure { logOnMain("  could not write the raw log: ${it.message}") }

            val blob = result.getOrNull()
                ?: (result.exceptionOrNull() as? IncompleteCalibration)?.partial

            if (blob != null) {
                val suffix = if (result.isSuccess) "" else "-partial"
                val blobFile = java.io.File(dir, "calibration-$stamp$suffix.bin")
                runCatching { blobFile.writeBytes(blob) }
                    .onSuccess { logOnMain("  wrote ${blob.size} bytes to ${blobFile.name}") }
                    .onFailure { logOnMain("  could not write the blob: ${it.message}") }
            }

            result
                .onSuccess {
                    logOnMain("  serial $serial, ${it.size} bytes in $replies replies (${elapsedMs}ms)")
                    // Nebula reads 54313 bytes from an Air 2 Pro. A different
                    // number is not wrong, but it is worth noticing.
                    if (it.size != NEBULA_BLOB_BYTES) {
                        logOnMain("  note: the official app reads $NEBULA_BLOB_BYTES bytes from this model")
                    }
                    logOnMain("  first 32 bytes: ${hex(it.copyOf(32))}")
                }
                .onFailure { logOnMain("  FAILED: ${it.message}") }

            logOnMain("  raw replies in ${rawFile.name}")
            logOnMain("  adb pull ${dir.absolutePath}/")
            logOnMain("  then: python3 tools/walk-calibration.py <the .bin>")
        }
    }

    private fun hex(b: ByteArray): String = b.joinToString(" ") { "%02x".format(it) }

    private fun reportCalibration() {
        val head = tracker ?: run { logLine("Not connected — run 'connect' first."); return }
        val calibration = head.calibration ?: run {
            logLine("no calibration loaded — running on per-model guesses")
            return
        }
        logLine(calibration.describe().replace("\n", "\n  "))
        calibration.left?.frustum?.let {
            logLine("  left frustum tangents: l=%.5f r=%.5f b=%.5f t=%.5f".format(it.left, it.right, it.bottom, it.top))
            logLine("  vertical asymmetry %.3f° (a symmetric projection cannot express this)".format(it.verticalAsymmetryDegrees))
        }
        calibration.imu?.let {
            logLine("  factory gyro bias %s rad/s = %.4f °/s total".format(
                it.gyroBias, it.gyroBias.length * 180f / Math.PI.toFloat()))
            logLine("  magnetometer factory calibration: ${if (it.magnetometerUncalibrated) "none stored" else "present"}")
        }
    }

    /**
     * Settles the one thing the blob does not state: whether its bias vectors
     * are meant to be added to a raw sample or subtracted from it.
     *
     * Guessing has a 50% chance of *doubling* the error it was meant to remove,
     * and the result would look like a slightly worse calibration rather than
     * an inverted one. At rest the truth is directly observable: the gyro
     * should read zero and the accelerometer should read gravity, so whichever
     * sign gets closer to that is the convention.
     */
    private fun calibrationCheck() {
        val head = tracker ?: run { logLine("Not connected — run 'connect' first."); return }
        val imu = head.calibration?.imu ?: run { logLine("no calibration loaded"); return }

        logLine("  put the glasses down and keep them still for 15s...")
        lifecycleScope.launch {
            val samples = ArrayList<TrackingSample.Inertial>()
            repeat(750) {
                delay(20)
                lastInertial?.let { samples += it }
            }
            val seen = samples.size
            // A worn head is never still, and averaging through the motion is
            // what made the first run of this report a gyro bias of 3.7°/s, so
            // moving samples are discarded rather than averaged down. Still is
            // measured from the median, not from zero: the raw gyro at rest
            // reads the bias itself, which on an original Air (0.028 rad/s)
            // is above the threshold, so no sample would ever qualify.
            fun median(axis: (Vec3) -> Float) = samples.map { axis(it.gyro) }.sorted().getOrElse(seen / 2) { 0f }
            val center = Vec3(median { it.x }, median { it.y }, median { it.z })
            var n = 0
            var gx = 0.0; var gy = 0.0; var gz = 0.0
            var ax = 0.0; var ay = 0.0; var az = 0.0
            samples.filter { (it.gyro - center).length <= STILL_GYRO_RAD_S }.forEach { s ->
                gx += s.gyro.x; gy += s.gyro.y; gz += s.gyro.z
                ax += s.accel.x; ay += s.accel.y; az += s.accel.z
                n++
            }
            if (n < MIN_STILL_SAMPLES) {
                logLine("  only $n of $seen samples were still enough (need $MIN_STILL_SAMPLES).")
                logLine("  take the glasses off and rest them on a table, then run 'calibcheck' again.")
                return@launch
            }
            logLine("  $n of $seen samples qualified as still")

            val gyro = Vec3((gx / n).toFloat(), (gy / n).toFloat(), (gz / n).toFloat())
            val accel = Vec3((ax / n).toFloat(), (ay / n).toFloat(), (az / n).toFloat())
            val deg = 180f / Math.PI.toFloat()

            logLine("  averaged $n still samples (canonical frame, uncorrected)")
            logLine("  gyro  measured %s = %.4f °/s".format(gyro, gyro.length * deg))
            logLine("  gyro  factory  %s = %.4f °/s".format(imu.gyroBias, imu.gyroBias.length * deg))
            val gyroMinus = (gyro - imu.gyroBias).length * deg
            val gyroPlus = (gyro + imu.gyroBias).length * deg
            logLine("    subtracting: %.4f °/s residual".format(gyroMinus))
            logLine("    adding:      %.4f °/s residual".format(gyroPlus))
            logLine("    -> %s".format(if (gyroMinus < gyroPlus) "SUBTRACT is correct" else "ADD is correct"))

            logLine("  accel measured %s, |a| = %.4f m/s²".format(accel, accel.length))
            logLine("  accel factory  %s".format(imu.accelBias))
            val accelMinus = kotlin.math.abs((accel - imu.accelBias).length - EARTH_GRAVITY)
            val accelPlus = kotlin.math.abs((accel + imu.accelBias).length - EARTH_GRAVITY)
            logLine("    subtracting: |a| = %.4f, off by %.4f".format((accel - imu.accelBias).length, accelMinus))
            logLine("    adding:      |a| = %.4f, off by %.4f".format((accel + imu.accelBias).length, accelPlus))
            logLine("    raw is off by %.4f".format(kotlin.math.abs(accel.length - EARTH_GRAVITY)))
            logLine("    -> %s".format(if (accelMinus < accelPlus) "SUBTRACT is correct" else "ADD is correct"))
        }
    }

    private fun timingStatus() {
        val clock = displayClock ?: run { logLine("  no display timing on this device"); return }
        logLine("  ${clock.describe().replace("\n", "\n  ")}")
        logLine("  last beacon %.1f s ago".format(clock.beaconAgeNs / 1e9))

        val compositor = stereoSession?.compositor ?: run { logLine("  not in stereo"); return }
        logLine("  aiming %d refreshes + %.1f ms ahead".format(
            compositor.refreshesAhead, compositor.scanoutOffsetMs))
        logLine("  last frame's horizon %.2f ms, %s".format(
            compositor.lastHorizonMs,
            if (compositor.horizonFromHardware) "from hardware timing" else "from the tuned constant"))
        logLine("  horizon spread %.2f..%.2f ms (%.2f ms wide, one refresh is %.2f)".format(
            compositor.horizonMinMs, compositor.horizonMaxMs,
            compositor.horizonMaxMs - compositor.horizonMinMs,
            (displayClock?.periodNs ?: 16_666_667.0) / 1e6))
        logLine("  mean %.2f ms, %d of %d frames off it by more than a quarter refresh".format(
            compositor.horizonMeanMs, compositor.horizonOutliers, compositor.horizonSamples))
        logLine("  %d refresh crossings since the last reset".format(compositor.refreshCrossings))
        logLine("  frame sits at phase %.3f of the cycle; newest IMU sample %.2f ms old".format(
            compositor.lastRefreshPhase, compositor.lastSampleAgeMs))
        tracker?.let {
            logLine("  the tuned constant would have said %.2f ms".format(
                it.predictionHorizonMs(FRAME_TIME_MS)))
        }
    }

    private fun presentStatus() {
        val compositor = stereoSession?.compositor ?: run { logLine("  not in stereo"); return }
        if (!compositor.presentTimingAvailable) {
            logLine("  frame timestamps unavailable on this surface")
            return
        }
        logLine("  ${describePresentTiming(compositor)}")
    }

    private fun describePresentTiming(compositor: dev.halone.compositor.StereoCompositor): String {
        val t = compositor.presentTiming
        val period = (displayClock?.periodNs ?: 16_666_667.0) / 1e6
        return ("draw->present %.2f ms (sd %.2f, %.2f refreshes), draw->latch %.2f, " +
            "aim error %.2f sd %.2f [%.2f..%.2f], %d shown, %d dropped, %d expired").format(
            t.drawToPresent.mean, t.drawToPresent.sd, t.drawToPresent.mean / period,
            t.drawToLatch.mean,
            t.aimError.mean, t.aimError.sd, t.aimError.min, t.aimError.max,
            t.presented, t.dropped, t.expired)
    }

    /**
     * How steadily frames reach the glasses at each draw phase, a tenth of a
     * refresh apart: the spread of draw-to-present is what shows as stutter.
     */
    private fun sweepDrawPhase() {
        val compositor = stereoSession?.compositor ?: run { logLine("  not in stereo"); return }
        val before = compositor.drawPhase
        lifecycleScope.launch {
            for (step in 0 until 10) {
                compositor.drawPhase = step / 10f
                delay(PHASE_SETTLE_MS)
                compositor.presentTiming.reset()
                delay(PHASE_MEASURE_MS)
                logLine("  phase %.1f: %s".format(step / 10f, describePresentTiming(compositor)))
            }
            compositor.drawPhase = before
        }
    }

    private fun reportHitches() {
        val compositor = stereoSession?.compositor ?: run { logLine("  not in stereo"); return }
        val hitches = compositor.presentTiming.hitches.toList()
        logLine("  ${hitches.size} hitches since the last reset (ms from the pose sample)")
        for (h in hitches) {
            logLine(("    aim %+6.2f  gpu done %6.2f  latch %6.2f  present %6.2f  " +
                "waited %5.2f  prev latch seen %5.2f late").format(
                h.aimErrorMs, h.renderingCompleteMs, h.latchMs, h.presentMs, h.waitedMs, h.latchSeenLateMs))
        }
    }

    private fun readMode() {
        val device = requireDevice() ?: return
        val display = device.display ?: run { logLine("device has no display control"); return }
        display.currentMode()
            .onSuccess { logLine("current mode: $it (verified=${it.verified})") }
            .onFailure { logLine("read failed: ${it.message}") }
        logLine("supported: ${display.supportedModes.joinToString { it.key }}")
    }

    private fun setStereo(stereo: Boolean) {
        val device = requireDevice() ?: return
        val display = device.display ?: run { logLine("device has no display control"); return }

        val mode = if (stereo) display.preferredStereoMode() else display.preferredMonoMode()
        if (mode == null) {
            logLine("no ${if (stereo) "stereo" else "mono"} mode available on this device")
            return
        }
        logLine("setting $mode")
        display.setMode(mode)
            .onSuccess {
                logLine("  ok. ${if (display.modeChangeRecreatesDisplay) "display will be recreated with a new id" else "seamless switch"}")
                logLine("  re-read: ${display.currentMode()}")
            }
            .onFailure { logLine("  failed: ${it.message}") }
    }

    private fun showTestPattern() {
        val display = DisplayProbe.externalDisplay(this)
        if (display == null) {
            logLine("No external display found — connect the glasses.")
            logLine(DisplayProbe.report(this))
            return
        }
        val sbs = DisplayProbe.sbsMode(display)
        logLine("Using display ${display.displayId} \"${display.name}\"")
        logLine(
            sbs?.let { "SBS candidate: id=${it.modeId} ${it.physicalWidth}x${it.physicalHeight} @${"%.2f".format(it.refreshRate)}" }
                ?: "No wide mode advertised — is the device in stereo mode? Run 'sbs' first."
        )

        presentation?.dismiss()
        presentation = StereoTestPresentation(this, display, sbs?.modeId) { logOnMain(it) }.also {
            it.show()
            logLine("test pattern shown")
        }
    }

    private fun dismissTestPattern() {
        presentation?.dismiss()
        presentation = null
        logLine("test pattern dismissed")
    }

    private var stereoSession: GlassesStereoSession? = null

    private var shellSession: ShellSession? = null

    private var bedMode: BedModeController? = null

    @Volatile
    private var tapRecorder: TapRecorder? = null

    /**
     * T1: raw IMU while the wearer taps the frame on spoken cues, then makes the
     * movements a tap detector must ignore. Written to the app's external files
     * directory for offline analysis; nothing here decides what a tap is.
     */
    private fun recordTaps() {
        if (tracker == null) {
            logLine("no head tracker — run 'connect' first")
            return
        }
        if (tapRecorder != null) {
            logLine("  already recording")
            return
        }
        val recorder = TapRecorder(capacity = TAP_RECORD_SECONDS * 1_100)
        tapRecorder = recorder
        lifecycleScope.launch {
            try {
                TapScript(this@MainActivity) { logOnMain(it) }.run(recorder)
            } finally {
                tapRecorder = null
            }
            delay(50)
            val name = "taps-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val files = withContext(Dispatchers.IO) { recorder.write(getExternalFilesDir(null)!!, name) }
            logLine("  recorded ${recorder.count} samples")
            files.forEach { logLine("  wrote ${it.absolutePath}") }
        }
    }

    private var phonePointer: PhonePointer? = null

    private var pointerPad: PointerPad? = null

    private var pointerJob: kotlinx.coroutines.Job? = null

    /**
     * Aims the panel cursor with the phone instead of the head, and turns the
     * phone screen into a click surface.
     *
     * Head gaze is fine for pointing at one thing but tiring for real use, and
     * you cannot look at one panel while acting on another. Wired through the
     * compositor's single `pointerDirection`, so both sources share the hit test.
     */
    private fun startPhonePointer() {
        val session = stereoSession ?: run { logLine("enter stereo first"); return }
        val pointer = phonePointer ?: PhonePointer(this) { logOnMain(it) }.also { phonePointer = it }
        if (!pointer.available) {
            logLine("  no rotation vector sensor; cannot use the phone as a pointer")
            return
        }
        if (!pointer.start()) return

        logLine("  hold the phone however is comfortable, aimed at the panel centre")
        logLine("  the grip is detected from gravity; calibrating in 2s")
        logLine("  tap anywhere on the phone to click")

        showPad()

        pointerJob?.cancel()
        pointerJob = lifecycleScope.launch {
            delay(2_000)
            pointer.calibrate()
            // Feed the compositor continuously; the sensor rate and the frame
            // rate are unrelated, so this samples rather than pushes.
            while (true) {
                session.compositor?.pointerDirection = pointer.direction()
                delay(8)
            }
        }
    }

    private fun showPad() {
        startHitHistory()
        if (pointerPad == null) pointerPad = PointerPad(this, padGestures, padActions, testDisplay?.view).also { it.show() }
        applyTrackingSettings()
        startPadStats()
    }

    /**
     * Tracking tuning and the pad's stats line, from the Developer settings.
     *
     * Kept in the shell's settings rather than anywhere of the pad's own, so
     * a value set once stays set: it is saved when it changes and read back
     * here on every connect, every compositor and every change. With custom
     * tuning off, each pair of glasses gets its driver's tuning back.
     */
    private fun applyTrackingSettings() {
        val settings = shellSession?.shell?.settings
        val custom = settings?.bool(ShellSettings.TRACKING_CUSTOM) == true
        tracker?.let { head ->
            val optics = connection.device?.optics
            if (custom) {
                head.predictionOverrideMs = settings!!.num(ShellSettings.TRACKING_PREDICTION_MS)?.toFloat()
                settings.num(ShellSettings.TRACKING_STEADINESS_DEGREES)?.let { head.restDeadbandDegrees = it.toFloat() }
            } else {
                head.predictionOverrideMs = null
                optics?.let { head.restDeadbandDegrees = it.restDeadbandDegrees }
            }
        }
        stereoSession?.let { session ->
            val compositor = session.compositor ?: return@let
            val pacing = settings?.str(ShellSettings.TRACKING_FRAME_PACING)
            compositor.paceOnLatch = when (pacing) {
                "wait" -> true
                "free", "glasses" -> false
                else -> session.autoPaceOnLatch
            }
            compositor.paceToGlasses = when (pacing) {
                "glasses" -> true
                "wait", "free" -> false
                else -> session.autoPaceToGlasses
            }
        }
        pointerPad?.showStats = settings?.bool(ShellSettings.DEVELOPER_DISPLAY_STATS) == true
    }

    // Lenient reads: a settings file from an older build lacks the newer keys.
    private fun ShellSettings.bool(key: String) = runCatching { boolean(key) }.getOrNull()
    private fun ShellSettings.num(key: String) = runCatching { number(key) }.getOrNull()
    private fun ShellSettings.str(key: String) = runCatching { string(key) }.getOrNull()

    private var padStatsJob: kotlinx.coroutines.Job? = null

    /**
     * Once a second along the bottom of the pad: the mode the glasses' display
     * is actually in, the rates it offers at that size, and how many frames
     * the compositor really drew. The only way to check a refresh rate without
     * adb, and the two numbers disagree in useful ways — a 120 Hz mode drawn
     * at 60 fps is a rendering problem, a 60 Hz mode is a mode-selection one.
     */
    private fun startPadStats() {
        padStatsJob?.cancel()
        padStatsJob = lifecycleScope.launch {
            while (isActive && pointerPad != null) {
                val display = stereoSession?.display
                if (stereoSession?.compositor != null && display != null) {
                    val fps = stereoSession?.renderFps
                    val mode = display.mode
                    val offered = display.supportedModes
                        .filter { it.physicalWidth == mode.physicalWidth && it.physicalHeight == mode.physicalHeight }
                        .map { it.refreshRate.roundToInt() }
                        .distinct().sorted().joinToString("/")
                    // The user's frame-rate matching preference decides whether
                    // a non-seamless switch, which 60 -> 120 on USB-C is, is
                    // allowed at all; worth seeing beside the mode.
                    val switching = if (android.os.Build.VERSION.SDK_INT >= 31) {
                        when (getSystemService(android.hardware.display.DisplayManager::class.java).matchContentFrameRateUserPreference) {
                            android.hardware.display.DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER -> "never"
                            android.hardware.display.DisplayManager.MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY -> "seamless only"
                            android.hardware.display.DisplayManager.MATCH_CONTENT_FRAMERATE_ALWAYS -> "always"
                            else -> "unknown"
                        }
                    } else {
                        "n/a"
                    }
                    pointerPad?.setStats(
                        "Glasses ${mode.physicalWidth}×${mode.physicalHeight} @ ${mode.refreshRate.roundToInt()} Hz " +
                            "(offers $offered, switching: $switching) · drawing ${fps?.roundToInt() ?: "–"} fps"
                    )
                }
                delay(1_000)
            }
        }
    }

    /** A gesture's action from the shell's settings, or the shipped default when no shell is running. */
    private fun gestureAction(key: String): String =
        shellSession?.shell?.settings?.let { runCatching { it.string(key) }.getOrNull() } ?: DEFAULT_GESTURES.getValue(key)

    private var activeHold: String? = null

    private val padGestures = object : PadGestures.Listener {
        override fun onTap(fingers: Int) = runGesture(gestureAction(ShellSettings.padTap(fingers)), "PAD")

        override fun onHoldStart(fingers: Int) {
            val action = gestureAction(ShellSettings.padHold(fingers))
            activeHold = action
            when (action) {
                "scroll" -> beginHoldPress()
                "move-window" -> grabPanel()
                else -> runGesture(action, "PAD")
            }
        }

        override fun onHoldEnd(fingers: Int) {
            when (activeHold) {
                "scroll" -> endHoldPress()
                "move-window" -> dropPanel()
            }
            activeHold = null
        }

        override fun onScrollStart() = beginTouch()
        override fun onScrollMove(dxDp: Float, dyDp: Float) = moveTouchBy(dxDp * SCROLL_PX_PER_DP, dyDp * SCROLL_PX_PER_DP)
        override fun onScrollEnd() = endTouch()
    }

    /** A held press not yet known to be a drag or a long press, and where it began. */
    private var holdPressJob: kotlinx.coroutines.Job? = null

    private var holdPressHit: SceneHit? = null

    private var holdPressStartedAt = 0L

    private var holdPressAim: dev.halone.glasses.math.Vec3? = null

    private var holdPressDragging = false

    /**
     * A one-finger hold on the pad, decided as on a touchscreen: moving the
     * pointer presses where the hold began and drags from there, keeping it
     * still opens the context menu there. See [PadGestures.holdIntent].
     */
    private fun beginHoldPress() {
        val compositor = stereoSession?.compositor ?: return
        holdPressHit = compositor.lastHit
        holdPressAim = compositor.pointerDirection ?: compositor.lastAim
        holdPressStartedAt = android.os.SystemClock.uptimeMillis()
        holdPressDragging = false
        holdPressJob?.cancel()
        holdPressJob = lifecycleScope.launch {
            while (decideHoldPress(released = false)) delay(TOUCH_FOLLOW_MS)
        }
    }

    private fun endHoldPress() {
        holdPressJob?.cancel()
        holdPressJob = null
        if (!holdPressDragging && (holdPressHit != null || holdPressAim != null)) decideHoldPress(released = true)
        if (holdPressDragging) endTouch()
        holdPressHit = null
        holdPressAim = null
        holdPressDragging = false
    }

    /** True while the hold is still undecided. */
    private fun decideHoldPress(released: Boolean): Boolean {
        val compositor = stereoSession?.compositor ?: return false
        val now = compositor.pointerDirection ?: compositor.lastAim
        val moved = angleDegrees(holdPressAim, now)
        return when (PadGestures.holdIntent(android.os.SystemClock.uptimeMillis() - holdPressStartedAt, moved, released)) {
            PadGestures.HoldIntent.UNDECIDED -> true
            PadGestures.HoldIntent.DRAG -> {
                holdPressDragging = true
                beginTouch(followPointer = true, at = holdPressHit)
                false
            }
            PadGestures.HoldIntent.MENU -> {
                val hit = holdPressHit
                holdPressHit = null
                holdPressAim = null
                if (hit != null) pointerPad?.tick()
                contextAt(hit, "PAD")
                false
            }
        }
    }

    private fun angleDegrees(a: dev.halone.glasses.math.Vec3?, b: dev.halone.glasses.math.Vec3?): Float {
        if (a == null || b == null || a.length < 1e-6f || b.length < 1e-6f) return 0f
        val cos = ((a.x * b.x + a.y * b.y + a.z * b.z) / (a.length * b.length)).coerceIn(-1f, 1f)
        return Math.toDegrees(kotlin.math.acos(cos).toDouble()).toFloat()
    }

    /** Asks whatever is under the pointer for its context menu. */
    private fun contextAt(hit: SceneHit?, source: String) {
        if (hit == null) return logLine("  $source context menu ignored: nothing under the pointer")
        val scene = stereoSession?.scene
        if (hit.part != HitPart.CONTENT || hit.panel === scene?.popup) return
        logLine("  $source context menu on '${hit.panel.id}' at panel pixel (%.1f, %.1f)".format(hit.hit.x, hit.hit.y))
        hit.panel.engine.dispatchContextMenu(hit.hit.x, hit.hit.y)
    }

    private fun middleClickAt(hit: SceneHit?, source: String) {
        if (dismissMenuOutside(hit)) return
        if (hit == null || hit.part != HitPart.CONTENT) return logLine("  $source middle click ignored: nothing under the pointer")
        hit.panel.engine.dispatchMiddleClick(hit.hit.x, hit.hit.y)
    }

    /** A press anywhere but on the open menu closes it, and does nothing else; a dialog stays until answered. */
    private fun dismissMenuOutside(hit: SceneHit?): Boolean {
        val scene = stereoSession?.scene ?: return false
        if (!scene.popupShown || (hit != null && hit.panel === scene.popup)) return false
        val shell = shellSession?.shell
        if (shell?.menu?.modal == true) return true
        shell?.dismissMenu() ?: scene.hidePopup()
        return true
    }

    /** Actions that happen at once, whichever gesture they are bound to. */
    private fun runGesture(action: String, source: String, hit: SceneHit? = stereoSession?.compositor?.lastHit) {
        when (action) {
            "click" -> if (!dismissMenuOutside(hit)) {
                hit?.let { tapAtHit(it, source) } ?: logLine("  $source click ignored: nothing under the pointer")
            }
            "context-menu" -> contextAt(hit, source)
            "middle-click" -> middleClickAt(hit, source)
            "recenter-cursor" -> when {
                cursorInput.running -> cursorInput.recenter()
                else -> phonePointer?.calibrate() ?: logLine("  no pointer to recentre")
            }
            "recenter-view" -> recenterView()
            "toggle-move" -> toggleGrab()
            "screenshot" -> runCatching { shellSession?.shell?.captureScreen(0) }.onFailure { logLine("  $source screenshot: $it") }
            "scroll-up", "scroll-down" -> hit?.let { scrollAt(it, action == "scroll-down") }
            "disabled" -> Unit
            else -> logLine("  $source: '$action' needs a hold")
        }
    }

    private fun scrollAt(hit: SceneHit, down: Boolean) {
        val distance = (shellSession?.shell?.settings?.number(ShellSettings.GLASSES_SCROLL_PX) ?: DEFAULT_GLASSES_SCROLL_PX).toFloat()
        val (x, y) = hit.hit.x to hit.hit.y
        // A finger dragging upward is what brings the content below into view.
        hit.panel.engine.dispatchScroll(x, y, x, if (down) y - distance else y + distance, steps = 20)
    }

    private val tapDetector = TapDetector()

    private val glassesTaps = TapGestures(
        wantsDouble = { side -> gestureAction(ShellSettings.glassesDoubleTap(side.key)) != "disabled" },
        listener = object : TapGestures.Listener {
            override fun onSingle(side: TapSide, at: Long) = onGlassesGesture(ShellSettings.glassesTap(side.key), at)
            override fun onDouble(side: TapSide, at: Long) = onGlassesGesture(ShellSettings.glassesDoubleTap(side.key), at)
        },
    )

    private val TapSide.key get() = name.lowercase()

    private val glassesTapTick = Runnable { decideGlassesTaps() }

    /** On the reader thread: the detector sees every sample, the UI thread hears only taps. */
    private fun detectGlassesTap(sample: TrackingSample.Inertial, receivedNs: Long) {
        val tap = tapDetector.onSample(sample) ?: return
        val onsetMs = (receivedNs - (sample.timestampNs - tap.timestampNs)) / 1_000_000
        runOnUiThread {
            if (stereoSession?.compositor == null) return@runOnUiThread
            logLine("  glasses tap ${tap.side.key} (%.0f m/s²)".format(tap.peakMps2))
            glassesTaps.tap(tap.side, onsetMs)
            decideGlassesTaps()
        }
    }

    private fun decideGlassesTaps() {
        glassesTaps.tick(System.nanoTime() / 1_000_000)
        window.decorView.removeCallbacks(glassesTapTick)
        val deadline = glassesTaps.nextDeadline() ?: return
        window.decorView.postDelayed(glassesTapTick, (deadline - System.nanoTime() / 1_000_000).coerceAtLeast(0L))
    }

    private fun onGlassesGesture(key: String, atMs: Long) {
        // Aimed where the pointer was just before the knock: the tap itself
        // jolts the head by up to a degree, some 50 panel pixels.
        runGesture(gestureAction(key), "GLASSES", hitBefore(atMs - TAP_AIM_LEAD_MS))
    }

    /** Where the pointer was over the last half second, so a tap can use its aim from before it landed. */
    private val hitTimes = LongArray(HIT_HISTORY)

    private val hits = arrayOfNulls<SceneHit>(HIT_HISTORY)

    private var hitCursor = 0

    private var hitHistoryJob: kotlinx.coroutines.Job? = null

    private fun startHitHistory() {
        if (hitHistoryJob != null) return
        hitHistoryJob = lifecycleScope.launch {
            while (true) {
                hitTimes[hitCursor] = System.nanoTime() / 1_000_000
                hits[hitCursor] = stereoSession?.compositor?.lastHit
                hitCursor = (hitCursor + 1) % HIT_HISTORY
                delay(HIT_HISTORY_STEP_MS)
            }
        }
    }

    private fun hitBefore(atMs: Long): SceneHit? {
        var best: SceneHit? = null
        var bestAt = Long.MIN_VALUE
        for (i in 0 until HIT_HISTORY) {
            if (hitTimes[i] in (bestAt + 1)..atMs) {
                bestAt = hitTimes[i]
                best = hits[i]
            }
        }
        return best ?: stereoSession?.compositor?.lastHit
    }

    /** The panel a live touch is going to, and where on it the finger is. */
    private var touchPanel: Panel? = null

    private var touchX = 0f

    private var touchY = 0f

    private var touchFollowJob: kotlinx.coroutines.Job? = null

    /**
     * Puts a finger down on whatever the pointer is on. A scroll then moves it
     * by the wearer's finger; a hold moves it wherever the pointer goes, as a
     * mouse drag would, for as long as the pointer stays on that panel.
     */
    private fun beginTouch(followPointer: Boolean = false, at: SceneHit? = null) {
        val current = stereoSession?.compositor?.lastHit
        if (dismissMenuOutside(at ?: current)) return
        val hit = at ?: current ?: run {
            if (!beginRotate(followPointer)) logLine("  touch ignored: nothing under the pointer")
            return
        }
        if (hit.part != HitPart.CONTENT) {
            pressOnChrome(hit)
            return
        }
        touchPanel = hit.panel
        touchX = hit.hit.x
        touchY = hit.hit.y
        hit.panel.engine.dispatchTouch(TouchPhase.DOWN, touchX, touchY)
        if (!followPointer) return
        touchFollowJob = lifecycleScope.launch {
            while (true) {
                delay(TOUCH_FOLLOW_MS)
                val now = stereoSession?.compositor?.lastHit ?: continue
                if (now.panel !== touchPanel) continue
                touchX = now.hit.x
                touchY = now.hit.y
                now.panel.engine.dispatchTouch(TouchPhase.MOVE, touchX, touchY)
            }
        }
    }

    private fun moveTouchBy(dx: Float, dy: Float) {
        rotating?.let { layer ->
            layer.anchorYawRadians = Panel.wrapPi(layer.anchorYawRadians - dx * ROTATE_RADIANS_PER_PX)
            return
        }
        val panel = touchPanel ?: return
        touchX += dx
        touchY += dy
        panel.engine.dispatchTouch(TouchPhase.MOVE, touchX, touchY)
    }

    private fun endTouch() {
        if (rotating != null) {
            rotateJob?.cancel()
            rotateJob = null
            rotating = null
            return
        }
        if (releaseOnChrome()) return
        touchFollowJob?.cancel()
        touchFollowJob = null
        touchPanel?.engine?.dispatchTouch(TouchPhase.UP, touchX, touchY)
        touchPanel = null
    }

    /** The video a drag that began on empty space is turning, when its player allows that. */
    private var rotating: ImmersiveLayer? = null

    private var rotateJob: kotlinx.coroutines.Job? = null

    /**
     * Starts turning a rotatable video from a press on empty space: a held
     * press carries it round with the pointer, as if the scene were grabbed;
     * a swipe turns it by [moveTouchBy]. False when there is nothing to turn.
     */
    private fun beginRotate(followPointer: Boolean): Boolean {
        val compositor = stereoSession?.compositor ?: return false
        val layer = compositor.immersive?.takeIf { it.rotatable } ?: return false
        rotating = layer
        if (!followPointer) return true
        val startAim = yawOf(compositor.lastAim)
        val startAnchor = layer.anchorYawRadians
        rotateJob = lifecycleScope.launch {
            while (true) {
                delay(TOUCH_FOLLOW_MS)
                layer.anchorYawRadians = Panel.wrapPi(startAnchor + Panel.wrapPi(yawOf(compositor.lastAim) - startAim))
            }
        }
        return true
    }

    /** Native yaw of a direction, positive left, as panel poses use. */
    private fun yawOf(direction: Vec3) = kotlin.math.atan2(-direction.x, -direction.z)

    /** What a press on a title bar is holding: the window it picked up, or a button. */
    private var chromePress: SceneHit? = null

    private var chromePressAim: Vec3? = null

    /**
     * A press on the bar picks the window up at once, so a drag carries it from
     * the first movement; a press that never moves is a click and is undone on
     * release. A press on a button only counts if it is released on it.
     */
    private fun pressOnChrome(hit: SceneHit) {
        val session = stereoSession ?: return
        val compositor = session.compositor ?: return
        chromePress = hit
        chromePressAim = compositor.lastAim
        if (hit.part == HitPart.TITLE_BAR && session.scene.grabbed == null) {
            session.scene.beginGrab(hit.panel, compositor.lastAim)
        }
        hit.part.corner?.let { session.scene.beginResize(hit.panel, it) }
    }

    /** Ends a press that began on a title bar; false if there was none. */
    private fun releaseOnChrome(): Boolean {
        val press = chromePress ?: return false
        chromePress = null
        val session = stereoSession ?: return true
        val compositor = session.compositor ?: return true
        if (press.part == HitPart.TITLE_BAR) {
            if (session.scene.grabbed !== press.panel) return true
            if (degreesBetween(chromePressAim, compositor.lastAim) < CLICK_SLOP_DEGREES) session.scene.cancelGrab()
            else dropPanel()
            return true
        }
        if (press.part.corner != null) {
            val drag = session.scene.endResize() ?: return true
            val panel = drag.panel
            val oldWidth = panel.widthPx
            val oldHeight = panel.heightPx
            if (drag.widthPx == oldWidth && drag.heightPx == oldHeight) return true
            panel.engine.resize(drag.widthPx, drag.heightPx)
            session.scene.resized(panel, oldWidth, oldHeight, drag.corner.opposite)
            logLine("  resized '${panel.id}' ${oldWidth}x$oldHeight -> ${panel.widthPx}x${panel.heightPx}")
            return true
        }
        val now = compositor.lastHit
        if (now?.panel === press.panel && now.part == press.part) activateChrome(press.panel, press.part)
        return true
    }

    private fun activateChrome(panel: Panel, part: HitPart) {
        val shell = shellSession?.shell
        when (part) {
            HitPart.PIN -> {
                val pinned = panel.follow.enabled
                if (shell != null && runCatching { shell.setPinned(panel.id, pinned) }.isSuccess) return
                stereoSession?.scene?.setFollow(panel, panel.follow.copy(enabled = !pinned))
            }
            HitPart.BACK -> panel.engine.dispatchBack()
            HitPart.SIZE -> {
                if (shell != null && runCatching { shell.cycleSize(panel.id) }
                        .onSuccess { (w, h) -> logLine("  resized '${panel.id}' to ${w}x$h, remembered for next time") }
                        .isSuccess
                ) return
                val session = stereoSession ?: return
                val oldWidth = panel.widthPx
                val oldHeight = panel.heightPx
                val (width, height) = dev.halone.compositor.TitleBar.nextSize(oldWidth, oldHeight)
                panel.engine.resize(width, height)
                // About its bottom centre, so the bar under it stays put.
                session.scene.resized(panel, oldWidth, oldHeight, dev.halone.compositor.Corner.BOTTOM_LEFT, keepCentreX = true)
                logLine("  resized '${panel.id}' ${oldWidth}x$oldHeight -> ${width}x$height")
            }
            HitPart.CLOSE -> {
                if (shell != null && runCatching { shell.close(panel.id) }.isSuccess) return
                stereoSession?.closePanel(panel)
            }
            else -> Unit
        }
    }

    private fun degreesBetween(a: Vec3?, b: Vec3?): Float {
        if (a == null || b == null || a.length < 1e-6f || b.length < 1e-6f) return 0f
        val cos = (a.x * b.x + a.y * b.y + a.z * b.z) / (a.length * b.length)
        return Math.toDegrees(kotlin.math.acos(cos.coerceIn(-1f, 1f)).toDouble()).toFloat()
    }

    /**
     * Taps whichever panel the pointer is actually on.
     *
     * With more than one panel the hit test is the only thing that knows where
     * a tap belongs, so input routing goes through it rather than through a
     * remembered "the" panel.
     */
    private fun tapAtHit(hit: SceneHit, label: String) {
        if (hit.part != HitPart.CONTENT) {
            activateChrome(hit.panel, hit.part)
            return
        }
        val engine = hit.panel.engine
        logLine("  $label tap on '${hit.panel.id}' at panel pixel (%.1f, %.1f)"
            .format(hit.hit.x, hit.hit.y))
        engine.dispatchTap(hit.hit.x, hit.hit.y)
    }

    private fun stopPhonePointer() {
        pointerJob?.cancel()
        pointerJob = null
        stereoSession?.compositor?.pointerDirection = null
        phonePointer?.close()
        phonePointer = null
        logLine("  back to head gaze; the pad still clicks where you look")
    }

    /**
     * The first wearable milestone: a live web panel sitting in world space,
     * rendered once per eye, with the glasses' own IMU driving the camera.
     */
    private fun enterStereo(modeKey: String? = null): Job? {
        val device = requireDevice() ?: return null
        val head = tracker ?: run { logLine("no head tracker — run 'connect' first"); return null }
        val mode = modeKey?.let { key ->
            device.display?.supportedModes?.firstOrNull { it.key == key }
                ?: run { logLine("  $key is not a mode this device lists"); return null }
        } ?: chosenMode(device)
        enteredMode = mode

        val session = stereoSession ?: GlassesStereoSession(this) { logOnMain(it) }
        session.onDisplayChanged = { runOnUiThread { displayChanged() } }
        session.onLost = ::glassesDisplayLost
        stereoSession = session

        return lifecycleScope.launch {
            val display = testDisplay?.display ?: session.enterStereo(device, mode)
            if (display == null) {
                logLine("  could not enter stereo")
                return@launch
            }

            session.show(display, head, device.optics, displayClock, rateFor(device, mode))
            // A compositor built while the glasses pin the image must draw head-locked.
            session.compositor?.lockToHead = device.onboardPinning?.enabled == true
            applyTrackingSettings()

            // Whatever the wearer happens to be facing becomes forward. This
            // also papers over the unverified IMU axis convention: the constant
            // offset disappears, though a wrong axis would still show up as
            // motion in the wrong direction.
            // Seeded from the factory calibration when there is one, so this
            // no longer waits out the minute the stillness detector can take to
            // latch — which is the reason the first run of a session used to
            // come up un-recentred.
            while (!head.hasUsableBias && testDisplay == null) delay(500)
            recentreHead(head)
            logLine("  recentred; bias ${if (head.biasCalibrated) "measured" else "seeded from calibration"}")
        }
    }

    /**
     * Sums the pipeline stages we can see from software.
     *
     * Covers everything except the glasses' own DP-receiver-to-photons delay, so
     * it is a lower bound rather than the answer — but it is enough to replace
     * the current zero prediction constants with something defensible, and it
     * cross-checks the camera measurement.
     */
    private fun latencyEstimate() {
        val head = tracker ?: run { logLine("run 'connect' first"); return }
        val display = DisplayProbe.externalDisplay(this)
            ?: run { logLine("no external display"); return }

        lastInertial ?: run { logLine("no IMU samples"); return }
        val sampleAgeMs = (System.nanoTime() - lastSampleHostNanos) / 1_000_000f

        val refreshHz = display.mode.refreshRate
        val frameMs = 1000f / refreshHz
        val presentationDeadlineMs = display.presentationDeadlineNanos / 1_000_000f
        val vsyncOffsetMs = display.appVsyncOffsetNanos / 1_000_000f

        logLine("  display ${display.mode.physicalWidth}x${display.mode.physicalHeight} @${"%.1f".format(refreshHz)}Hz")
        logLine("  frame time            %.2f ms".format(frameMs))
        logLine("  presentation deadline %.2f ms".format(presentationDeadlineMs))
        logLine("  app vsync offset      %.2f ms".format(vsyncOffsetMs))
        logLine("  newest sample age     %.2f ms  (arrival stamped on our clock)".format(sampleAgeMs))
        logLine("  sample count ${head.pose.sampleCount}, bias calibrated ${head.biasCalibrated}")

        // A frame sampled just after vsync is presented one frame later, plus the
        // compositor's own deadline; averaged over arrival phase that is half a
        // frame of sampling latency.
        val estimate = sampleAgeMs + frameMs * 0.5f + frameMs + presentationDeadlineMs
        logLine("  ESTIMATE (excluding panel): %.1f ms".format(estimate))
        logLine("    = %.1f sample arrival + %.1f half-frame sampling + %.1f render + %.1f deadline"
            .format(sampleAgeMs, frameMs * 0.5f, frameMs, presentationDeadlineMs))
        logLine("  add the panel's own latency, typically under one frame, for motion-to-photon")

        // Worth stating plainly: stereo is not free in latency terms.
        val fastest = display.supportedModes.maxByOrNull { it.refreshRate }
        if (fastest != null && fastest.refreshRate > refreshHz + 1f) {
            val fastFrameMs = 1000f / fastest.refreshRate
            val fastEstimate = sampleAgeMs + fastFrameMs * 1.5f + presentationDeadlineMs
            logLine("  NOTE: this display also offers %.0fHz (%dx%d) -> ~%.1f ms".format(
                fastest.refreshRate, fastest.physicalWidth, fastest.physicalHeight, fastEstimate))
            logLine("  stereo costs latency here: SBS is 60Hz only, mono goes faster")
        }
    }

    private fun reportFrames() {
        val r = stereoSession?.compositor ?: run { logLine("not in stereo"); return }
        logLine("  frames drawn ${r.framesDrawn}, long frames ${r.longFrames}, worst %.1f ms"
            .format(r.worstFrameMs))
    }

    private fun reportMagnetometer() {
        val mag = tracker?.magnetometer ?: run { logLine("  no tracker"); return }
        logLine(
            "  magnetometer ${if (mag.enabled) "on" else "off"}, ${if (mag.anchored) "anchored" else "not anchored"}, " +
                "error %.1f°, %d corrections, %d rejected (weak %d, strength %d, vertical %d, outlier %d), %d reanchors".format(
                    Math.toDegrees(mag.lastErrorRadians.toDouble()), mag.corrections, mag.rejections,
                    mag.rejectedWeak, mag.rejectedMagnitude, mag.rejectedHorizontal, mag.rejectedOutlier, mag.reanchors,
                ),
        )
    }

    private fun reportGaze() {
        val compositor = stereoSession?.compositor ?: run { logLine("not in stereo"); return }
        val hit = compositor.lastHit
        val (yaw, pitch, roll) = tracker?.pose?.orientation?.toEulerDegrees() ?: Triple(0f, 0f, 0f)
        logLine("  head yaw=%.1f pitch=%.1f roll=%.1f".format(yaw, pitch, roll))
        logLine(
            if (hit == null) "  gaze is off every panel"
            else "  gaze hits '${hit.panel.id}' at pixel (%.1f, %.1f), %.2f m away"
                .format(hit.hit.x, hit.hit.y, hit.distanceMetres)
        )
        logLine("  frames drawn: ${compositor.framesDrawn}")
    }

    private fun recentreHead(head: HeadTracker) {
        val turn = head.recenter()
        phonePointer?.turnWorld(turn)
    }

    /**
     * Turns the world with the head, as a recentre does, so the phone pointer
     * keeps aiming where it physically points: at the ceiling, where the
     * windows now are.
     */
    private fun setBedMode(enter: Boolean) {
        val head = tracker ?: run { logLine("  no tracker"); return }
        val forward = shellSession?.shell?.settings?.number(ShellSettings.BED_MODE_FORWARD_DEGREES)?.toFloat() ?: 90f
        val turn = if (enter) head.enterBedMode(forward) else head.exitBedMode()
        phonePointer?.turnWorld(turn)
        stereoSession?.scene?.recentre()
        logLine(if (enter) "  bed mode on, forward %.0f° up".format(forward) else "  bed mode off")
    }

    private fun stopBedMode() {
        bedMode?.stop()
        bedMode = null
    }

    private fun recenterView() {
        val head = tracker ?: run { logLine("  no tracker"); return }
        recentreHead(head)
        // Every panel's own heading has to come back too, or smooth follow
        // leaves them wherever they had wandered to and recentring appears to do
        // nothing.
        stereoSession?.scene?.recentre()
        logLine("  recentred (head + panel)")
    }

    /**
     * Picks up whatever the pointer is on, and carries it until it is dropped.
     *
     * Grabbing does not pin: a follower dropped somewhere keeps following, from
     * its new place. Where it was put is recorded as its slot, which is the only
     * frame a follower's position has — see `Scene.endGrab`.
     */
    private fun grabPanel() {
        val session = stereoSession ?: run { logLine("not in stereo"); return }
        val compositor = session.compositor ?: run { logLine("not in stereo"); return }
        session.scene.grabbed?.let {
            logLine("  already holding '${it.id}' — 'drop' to put it down")
            return
        }
        val hit = compositor.lastHit
            ?: run { logLine("  nothing under the pointer to grab"); return }
        if (!session.scene.beginGrab(hit.panel, compositor.lastAim)) {
            logLine("  no usable pointer direction")
            return
        }
        logLine("  holding '${hit.panel.id}' — move, then 'drop'")
    }

    private fun dropPanel() {
        val session = stereoSession ?: run { logLine("not in stereo"); return }
        val compositor = session.compositor ?: run { logLine("not in stereo"); return }
        val panel = session.scene.endGrab(compositor.headYawRadians)
            ?: run { logLine("  not holding anything"); return }
        logLine("  dropped '${panel.id}' at %+.0f°, slot %+.0f°%s".format(
            Math.toDegrees(panel.pose.yawRadians.toDouble()),
            Math.toDegrees(panel.slotYawRadians.toDouble()),
            if (panel.pose.pitchRadians != 0f)
                ", %+.0f° elevation".format(Math.toDegrees(panel.pose.pitchRadians.toDouble()))
            else "",
        ))
    }

    private fun toggleGrab() {
        if (stereoSession?.scene?.grabbed == null) grabPanel() else dropPanel()
    }

    private fun reportArrangement() {
        val session = stereoSession ?: run { logLine("not in stereo"); return }
        val compositor = session.compositor ?: run { logLine("not in stereo"); return }
        logLine("  " + session.scene.describe(compositor.headYawRadians))
    }

    private fun reportPanels() {
        val session = stereoSession ?: run { logLine("not in stereo"); return }
        val focused = session.scene.focused
        val panels = session.scene.panels
        if (panels.isEmpty()) {
            logLine("  no panels in the scene")
            return
        }
        panels.forEach { panel ->
            logLine("  %s%s slot %+.0f° at %+.0f°, %.1f° wide, %.2f x %.2f m at %.1f m, %s".format(
                if (panel === focused) "* " else "  ",
                panel.id,
                Math.toDegrees(panel.slotYawRadians.toDouble()),
                Math.toDegrees(panel.pose.yawRadians.toDouble()),
                angularWidthDegrees(panel),
                panel.pose.widthMetres, panel.pose.heightMetres, panel.pose.distanceMetres,
                when {
                    panel === session.scene.grabbed -> "held"
                    !panel.follow.enabled -> "pinned"
                    panel.slotPlacedByHand -> "follows, placed by hand"
                    else -> "follows"
                },
            ))
        }
        // Live headings, not slots: a pinned panel keeps the slot it had, so slot
        // spacing stops describing what the wearer is looking at.
        panels.sortedBy { it.pose.yawRadians }.zipWithNext { left, right ->
            val spacing = Math.toDegrees((right.pose.yawRadians - left.pose.yawRadians).toDouble())
            val gap = spacing - (angularWidthDegrees(left) + angularWidthDegrees(right)) / 2f
            logLine("  '${left.id}' to '${right.id}': %.0f° apart, %.1f° of gap between their edges"
                .format(spacing, gap))
        }
    }

    private fun angularWidthDegrees(panel: Panel): Float {
        val half = kotlin.math.atan(panel.pose.widthMetres / 2f / panel.pose.distanceMetres)
        return 2f * Math.toDegrees(half.toDouble()).toFloat()
    }

    /**
     * The web environment on the glasses: the launcher and the dock, and every
     * panel after them is a shell window.
     */
    private fun startShell() {
        val session = stereoSession?.takeIf { it.compositor != null } ?: run { logLine("enter stereo first"); return }
        if (shellSession != null) {
            logLine("  shell already running")
            return
        }
        session.scene.panels.toList().forEach { session.closePanel(it) }
        val device = connection.device
        val shell = try {
            ShellSession(
                context = this,
                stereo = session,
                deviceName = device?.info?.displayName ?: "unknown glasses",
                brightness = device?.brightness,
                onRecenter = { recenterView() },
                onPointer = { applyPointerMode() },
                onTextField = { engine, field -> onTextField(engine, field) },
                geckoKeyboard = geckoKeyboard,
                pluginKeyboard = phoneKeyboard,
                requestProjection = ::requestProjection,
                requestPermissions = ::requestAndroidPermissions,
                display = ::displayState,
                restartDisplay = { runOnUiThread { applyChosenMode() } },
                log = { logOnMain(it) },
            )
        } catch (missing: java.io.FileNotFoundException) {
            logLine("  no system apps in the APK: run 'pnpm build' in web/ and reinstall")
            return
        }
        shellSession = shell
        shell.start()
        KeyCaptureService.router = { event -> isPhysicalKeyboard(event) && onShortcutKey(event, captured = true) }
        bedMode = tracker?.let { head ->
            BedModeController(head, { stereoSession?.compositor }, shell.shell.settings, ::setBedMode).also { it.start() }
        }
        showPad()
        watchInputDevices()
        applyPointerMode()
        pointerHover.start()
        shell.shell.addListener(object : ShellListener {
            override fun onRecordingChanged(state: RecordingState) {
                pointerPad?.setRecording(state.recording)
            }

            override fun onSettingsChanged() {
                runOnUiThread { applyChosenMode() }
                pointerPad?.setFollowing(shell.shell.settings.boolean(ShellSettings.FOLLOW_ENABLED))
                applyTrackingSettings()
            }
        })
        applyTrackingSettings()
        pointerPad?.setFollowing(shell.shell.settings.boolean(ShellSettings.FOLLOW_ENABLED))
        phoneSettings?.hide()
        logLine("  shell started with ${shell.shell.apps.size} apps")
        refreshHome()
        displayChanged()
    }

    /** The panel the phone's keyboard is typing into, while one has a text field focused. */
    private var typingInto: WebViewEngine? = null

    private val textEntry by lazy {
        TextEntryBar(
            this,
            onText = { text, start, end -> typingInto?.textInput?.setText(text, start, end) },
            onSubmit = { typingInto?.textInput?.submit() },
            onDismiss = { typingInto?.textInput?.dismiss() },
            onCapturedPointer = cursorInput::onCaptured,
        )
    }

    private fun onTextField(engine: WebViewEngine, field: TextField?) {
        if (field != null) {
            // A physical keyboard types into the page directly; see dispatchKeyEvent.
            if (physicalKeyboard) return
            typingInto = engine
            textEntry.show(field)
        } else if (typingInto === engine) {
            typingInto = null
            textEntry.hide()
            if (currentPointerMode == "mouse") requestCapture()
        }
    }

    /** The phone keyboard for Gecko web windows, which take it directly rather than through a text bar. */
    private val geckoKeyboard by lazy {
        GeckoKeyboard(
            this,
            wanted = { !physicalKeyboard },
            onShown = { logLine("  keyboard up for a web page") },
            onHidden = { if (currentPointerMode == "mouse") requestCapture() },
            onCapturedPointer = cursorInput::onCaptured,
        )
    }

    /** The phone keyboard for plugin panels, which get its edits over binder. */
    private val phoneKeyboard by lazy {
        dev.halone.app.panel.PhoneKeyboard(
            this,
            wanted = { !physicalKeyboard },
            onShown = { logLine("  keyboard up for a plugin panel") },
            onHidden = { if (currentPointerMode == "mouse") requestCapture() },
            onCapturedPointer = cursorInput::onCaptured,
        )
    }

    private val cursorActions = object : CursorInput.Actions {
        override fun action(key: String) = gestureAction(key)
        override fun run(action: String) = runGesture(action, "CURSOR")
        override fun beginTouch(followPointer: Boolean) = this@MainActivity.beginTouch(followPointer)
        override fun moveTouchBy(dx: Float, dy: Float) = this@MainActivity.moveTouchBy(dx, dy)
        override fun endTouch() = this@MainActivity.endTouch()
        override fun grabStart() = grabPanel()
        override fun grabEnd() = dropPanel()
        override fun touchpadDegreesPerDp() = settingNumber(ShellSettings.TOUCHPAD_SPEED, DEFAULT_TOUCHPAD_SPEED)
        override fun mouseDegreesPerCount() = settingNumber(ShellSettings.MOUSE_SPEED, DEFAULT_MOUSE_SPEED)
    }

    private val pointerHover by lazy {
        dev.halone.app.pointer.PointerHover({ stereoSession?.compositor }, { stereoSession?.scene }, pressing = { touchPanel != null })
    }

    private val cursorInput by lazy { CursorInput({ stereoSession?.compositor }, { stereoSession?.scene }, cursorActions) }

    private fun settingNumber(key: String, default: Double): Float =
        (shellSession?.shell?.settings?.let { runCatching { it.number(key) }.getOrNull() } ?: default).toFloat()

    private val cursorImages by lazy { CursorImages.load(assets) }

    private var currentPointerMode: String? = null

    private var pointingDevice: String? = null

    private var physicalKeyboard = false

    private val feedTouchpad: (MotionEvent) -> Unit = { event ->
        cursorInput.touchpad.feed(event, 1f / resources.displayMetrics.density)
        cursorInput.decideTouchpad()
    }

    /**
     * A connected mouse or trackpad wins over the setting; without one, the
     * setting decides between the gyro pointer, the phone as a touchpad, and
     * head gaze.
     */
    private fun applyPointerMode() {
        val compositor = stereoSession?.compositor ?: return
        val mode = if (pointingDevice != null) "mouse"
        else if (testDisplay != null) "touchpad"
        else shellSession?.shell?.settings?.let { runCatching { it.string(ShellSettings.INPUT_POINTER) }.getOrNull() } ?: "phone"
        if (compositor.cursorImages !== cursorImages) compositor.cursorImages = cursorImages
        compositor.cursorStyle = when (mode) {
            "phone" -> CursorStyle.BEAM
            "touchpad", "mouse" -> CursorStyle.ARROW
            else -> CursorStyle.RETICLE
        }
        if (mode == currentPointerMode) return
        currentPointerMode = mode
        if (mode != "phone" && phonePointer != null) stopPhonePointer()
        if (mode != "mouse") releaseCapture()
        when (mode) {
            "phone" -> {
                cursorInput.stop()
                pointerPad?.touchpad = null
                startPhonePointer()
            }
            "touchpad", "mouse" -> {
                showPad()
                cursorInput.start()
                pointerPad?.touchpad = feedTouchpad
                if (mode == "mouse") requestCapture()
            }
            else -> {
                cursorInput.stop()
                pointerPad?.touchpad = null
            }
        }
        pointerPad?.setMode(mode)
        logLine("  pointer: $mode" + (pointingDevice?.let { " ($it)" } ?: ""))
    }

    private val inputManager by lazy { getSystemService(android.hardware.input.InputManager::class.java) }

    private var watchingInputDevices = false

    private val inputDeviceListener = object : android.hardware.input.InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = refreshInputDevices()
        override fun onInputDeviceRemoved(deviceId: Int) = refreshInputDevices()
        override fun onInputDeviceChanged(deviceId: Int) = refreshInputDevices()
    }

    private fun watchInputDevices() {
        if (!watchingInputDevices) {
            watchingInputDevices = true
            inputManager.registerInputDeviceListener(inputDeviceListener, null)
        }
        refreshInputDevices()
    }

    /** External, non-virtual devices only: the phone's own touchscreen is not a mouse. */
    private fun refreshInputDevices() {
        val devices = inputManager.inputDeviceIds.toList().mapNotNull { InputDevice.getDevice(it) }
            .filter { it.isExternal && !it.isVirtual }
        // A captured mouse reports itself as relative instead: without counting
        // that, capturing the mouse made it vanish, which released the capture,
        // which brought it back — many times a second.
        val pointing = devices.firstOrNull { device ->
            POINTING_SOURCES.any { device.supportsSource(it) }
        }
        val keyboard = devices.any { it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC }
        if (keyboard != physicalKeyboard) {
            physicalKeyboard = keyboard
            logLine("  physical keyboard ${if (keyboard) "connected" else "disconnected"}")
        }
        if (pointing?.name != pointingDevice) {
            pointingDevice = pointing?.name
            devices.forEach { logLine("  input device '${it.name}' sources 0x${Integer.toHexString(it.sources)}") }
            applyPointerMode()
        }
    }

    /** Pointer capture goes to a focused view; this one exists only to hold it. */
    private var captureView: View? = null

    private fun requestCapture() {
        val view = captureView ?: View(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            setOnCapturedPointerListener { _, event -> cursorInput.onCaptured(event) }
        }.also {
            this@MainActivity.findViewById<ViewGroup>(android.R.id.content).addView(it, FrameLayout.LayoutParams(1, 1))
            captureView = it
        }
        view.post {
            view.requestFocus()
            view.requestPointerCapture()
        }
    }

    private fun releaseCapture() {
        captureView?.takeIf { it.hasPointerCapture() }?.releasePointerCapture()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && currentPointerMode == "mouse" && typingInto == null) requestCapture()
    }

    /** A physical keyboard types into the scene's key target: the launcher while it is up, else the focused window. */
    private var inputTrace = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (inputTrace) logLine("  key ${KeyEvent.keyCodeToString(event.keyCode)} action=${event.action} source=0x${Integer.toHexString(event.source)} device='${event.device?.name}'")
        val physical = isPhysicalKeyboard(event)
        if (physical && onShortcutKey(event, captured = false)) return true
        if (physical && !event.isSystem && typingInto == null) {
            if (stereoSession?.scene?.keyTarget?.engine?.dispatchKey(event) == true) return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isPhysicalKeyboard(event: KeyEvent): Boolean {
        val device = event.device
        return device != null && device.isExternal && !device.isVirtual && device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
    }

    /** Meta went down and nothing else has been pressed since: on its release, the launcher. */
    private var metaAlone = false

    /** Keys whose press ran a shortcut, so their release is taken too. */
    private val shortcutKeys = HashSet<Int>()

    /**
     * The keyboard shortcuts, from key capture ([captured]) or the app's own
     * keys. Capture decides only what Android would otherwise take for
     * itself, so every other shortcut is decided once, here, from the app's.
     */
    private fun onShortcutKey(event: KeyEvent, captured: Boolean): Boolean {
        val shell = shellSession?.shell ?: return false
        val captureOn = KeyCaptureService.running
        if (event.keyCode == KeyEvent.KEYCODE_META_LEFT || event.keyCode == KeyEvent.KEYCODE_META_RIGHT) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.repeatCount == 0) metaAlone = true
            } else if (metaAlone) {
                metaAlone = false
                runShortcut(shell, KeyChord("Meta"), captureOn)
            }
            return captured
        }
        if (event.action == KeyEvent.ACTION_DOWN) metaAlone = false
        if (event.action == KeyEvent.ACTION_UP) return shortcutKeys.remove(event.keyCode)
        val chord = chordOf(event) ?: return false
        if (captured && !Shortcuts.reservedBySystem(chord)) return false
        if (event.repeatCount > 0) return event.keyCode in shortcutKeys
        val handled = runShortcut(shell, chord, captureOn)
        if (handled || (captured && Shortcuts.reservedBySystem(chord))) shortcutKeys += event.keyCode
        return event.keyCode in shortcutKeys
    }

    private fun runShortcut(shell: dev.halone.shell.Shell, chord: KeyChord, captureOn: Boolean): Boolean {
        val target = stereoSession?.scene?.keyTarget?.let { panel -> shell.windows().firstOrNull { it.panel === panel } }
        val action = shellSession?.shortcuts?.resolve(chord, inBrowser = target?.kind == dev.halone.shell.WindowKind.WEB, captureOn = captureOn)
            ?: return false
        logLine("  shortcut $chord -> $action")
        return shell.runShortcut(action, target?.id)
    }

    private fun chordOf(event: KeyEvent): KeyChord? {
        val key = when (event.keyCode) {
            in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> ('A' + (event.keyCode - KeyEvent.KEYCODE_A)).toString()
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F${event.keyCode - KeyEvent.KEYCODE_F1 + 1}"
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_GRAVE -> "`"
            KeyEvent.KEYCODE_ESCAPE -> "Escape"
            KeyEvent.KEYCODE_SYSRQ -> "PrintScreen"
            KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
            else -> return null
        }
        return KeyChord(key, ctrl = event.isCtrlPressed, alt = event.isAltPressed, shift = event.isShiftPressed, meta = event.isMetaPressed)
    }

    /** Reproduces a click on a text field without a wearer, to exercise the phone keyboard path. */
    private fun focusField() {
        val engine = focusedWebEngine() ?: run { logLine("  no WebView panel focused"); return }
        engine.evaluate("(() => { const f = document.querySelector('input:not([type=checkbox]):not([type=range]), textarea'); f?.focus(); return f ? 'value=' + JSON.stringify(f.value) + ' ' + f.outerHTML.slice(0, 60) : 'none' })()") {
            logOnMain("  focused: $it")
        }
    }

    private fun reportShell() {
        val shell = shellSession ?: run { logLine("shell not running"); return }
        val connected = shell.bridge.connected()
        shell.shell.windows().forEach { window ->
            logLine(
                "  %s %s%s '%s' %s".format(
                    window.id,
                    if (shell.shell.isFocused(window)) "* " else "",
                    window.kind.wire,
                    window.title,
                    if (window.id in connected) "bridged" else "no bridge",
                ),
            )
        }
    }

    private fun leaveStereo(restoreMode: Boolean = true) {
        val session = stereoSession ?: run { logLine("not in stereo"); return }
        displayWork?.cancel()
        displayWork = null
        if (tracker != null) reportMagnetometer()
        stopBedMode()
        KeyCaptureService.router = null
        pointerHover.stop()
        shellSession?.stop()
        shellSession = null
        cursorInput.stop()
        releaseCapture()
        currentPointerMode = null
        pointerPad?.hide()
        pointerPad = null
        typingInto = null
        textEntry.hide()
        hitHistoryJob?.cancel()
        hitHistoryJob = null
        session.dismiss()
        if (restoreMode && testDisplay == null) connection.device?.let { session.leaveStereo(it) }
        stereoSession = null
        enteredMode = null
        logLine("stereo session ended")
        endTestMode()
        refreshHome()
        displayChanged()
    }

    private fun focusedWebEngine(): WebViewEngine? =
        stereoSession?.scene?.focused?.engine as? WebViewEngine

    private val fileTimestampFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private fun logOnMain(message: String) = runOnUiThread { logLine(message) }

    private fun logLine(message: String) {
        android.util.Log.i(LOG_TAG, message)
        CrashReports.log(message)
    }

    private companion object {
        const val PREFS = "home"
        const val LOG_TAG = "Halone"

        /** What Nebula reads from an Air 2 Pro, for comparison against ours. */
        const val NEBULA_BLOB_BYTES = 54313

        const val EARTH_GRAVITY = 9.81f

        /** ~1.1°/s, the same bar the fusion filter's stillness detector uses. */
        const val STILL_GYRO_RAD_S = 0.02f
        const val MIN_STILL_SAMPLES = 200

        /** Panel pixels a scroll moves per dp of finger travel. A full-width swipe is ~800px. */
        const val SCROLL_PX_PER_DP = 2f
        const val TOUCH_FOLLOW_MS = 16L
        private val ROTATE_RADIANS_PER_PX = Math.toRadians(0.15).toFloat()

        /** Less pointer travel than this between press and release on a title bar is a click. */
        const val CLICK_SLOP_DEGREES = 1f
        const val CONNECT_TIMEOUT_MS = 60_000L
        const val LINK_RETURN_TIMEOUT_MS = 20_000L
        const val STEREO_TIMEOUT_MS = 20_000L

        /** Long enough for the request that asked for a restart to be answered first. */
        const val MODE_SWITCH_DELAY_MS = 300L

        /** How long one wait for the glasses' display lasts; a lost one is waited for until it returns. */
        const val DISPLAY_RETURN_MS = 10_000L
        const val PHASE_SETTLE_MS = 1_000L
        const val PHASE_MEASURE_MS = 3_000L

        /** How soon after a window closes losing the phone counts as caused by it. */
        const val RECLAIM_AFTER_CLOSE_MS = 3_000L
        const val TAP_RECORD_SECONDS = 240
        const val TAP_AIM_LEAD_MS = 30L
        const val HIT_HISTORY = 64
        const val HIT_HISTORY_STEP_MS = 8L
        const val DEFAULT_GLASSES_SCROLL_PX = 300.0

        /** How long the glasses' display may take to appear after their USB interfaces do. */
        const val DISPLAY_GRACE_MS = 6_000L

        /** The schema's defaults, for gestures used before the shell has started. */
        val DEFAULT_GESTURES = mapOf(
            "gesture.glasses.left.tap" to "click",
            "gesture.glasses.right.tap" to "click",
            "gesture.glasses.left.doubleTap" to "disabled",
            "gesture.glasses.right.doubleTap" to "disabled",
            "gesture.pad.tap1" to "click",
            "gesture.pad.tap2" to "disabled",
            "gesture.pad.tap3" to "recenter-cursor",
            "gesture.pad.hold1" to "scroll",
            "gesture.pad.hold2" to "move-window",
            "gesture.pad.hold3" to "recenter-view",
            "gesture.touchpad.tap1" to "click",
            "gesture.touchpad.tap2" to "context-menu",
            "gesture.touchpad.tap3" to "recenter-cursor",
            "gesture.touchpad.hold1" to "disabled",
            "gesture.touchpad.hold2" to "disabled",
            "gesture.touchpad.hold3" to "recenter-view",
        )
        val POINTING_SOURCES = listOf(
            InputDevice.SOURCE_MOUSE,
            InputDevice.SOURCE_MOUSE_RELATIVE,
            InputDevice.SOURCE_TOUCHPAD,
        )
        const val DEFAULT_TOUCHPAD_SPEED = 0.12
        const val DEFAULT_MOUSE_SPEED = 0.04
    }
}
