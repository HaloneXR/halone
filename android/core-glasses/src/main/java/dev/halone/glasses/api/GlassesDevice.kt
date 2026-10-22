package dev.halone.glasses.api

import java.io.Closeable
import kotlin.math.roundToInt

/**
 * A connected pair of glasses.
 *
 * Optional hardware features are exposed as nullable sub-interfaces rather than
 * as a bag of boolean capability flags plus methods that throw. A caller that
 * wants to set the display mode writes `device.display?.let { ... }` and the
 * compiler makes the "what if this device can't" case impossible to forget.
 */
interface GlassesDevice : Closeable {
    val info: DeviceInfo
    val optics: Optics

    /** Every supported device tracks head orientation; that is the point of it. */
    val tracking: TrackingSource

    /** Non-null when the device can switch between mono and stereo layouts. */
    val display: DisplayControl?

    /** Non-null when display brightness is controllable over the data link. */
    val brightness: BrightnessControl?

    /**
     * Non-null when the device stores a factory calibration blob that can be
     * read back over the data link.
     *
     * Defaulted so adding this did not break existing drivers: a driver that
     * knows nothing about calibration keeps compiling and reports "no".
     */
    val calibration: CalibrationSource? get() = null

    /**
     * Non-null when the device reports display refreshes on a clock shared with
     * its IMU, so a compositor can aim at a measured photon rather than at a
     * tuned constant. Defaulted for the same reason as [calibration].
     */
    val scanout: ScanoutSource? get() = null

    /**
     * Non-null when the glasses can world-lock the image themselves, in their
     * own hardware (RayNeo's "pinned" mode). Defaulted for the same reason as
     * [calibration].
     */
    val onboardPinning: OnboardPinning? get() = null

    fun addEventListener(listener: DeviceEventListener)
    fun removeEventListener(listener: DeviceEventListener)

    /**
     * Hints for correlating this device with an Android `Display`. Matched
     * case-insensitively against `Display.getName()`. Necessary because the
     * data link and the video link are discovered through entirely separate
     * Android APIs with no shared identifier.
     */
    val displayNameHints: List<String>
}

interface DisplayControl {
    val supportedModes: List<DisplayMode>

    /** Reads the mode back from the hardware rather than returning a cached value. */
    fun currentMode(): Result<DisplayMode>

    fun setMode(mode: DisplayMode): Result<Unit>

    /**
     * True when changing mode makes the host tear down and re-add the display,
     * invalidating any `Display` reference and surface. XREAL does this, so
     * callers must be ready to rebuild rather than assuming a seamless switch.
     */
    val modeChangeRecreatesDisplay: Boolean

    /**
     * True when the glasses take whatever refresh rate the host's video
     * signal has (XREAL One series): modes that differ only in rate are one
     * mode to the glasses, and the host picks the rate by the display mode it
     * asks Android for.
     */
    val rateChosenByHost: Boolean get() = false
}

/** The same layout and frame, whatever the refresh rate. */
fun DisplayMode.sameSignalAs(other: DisplayMode): Boolean =
    layout == other.layout && frameWidth == other.frameWidth && frameHeight == other.frameHeight

/**
 * The glasses' own world-locking. While [enabled], the glasses move the image
 * against the head themselves, with their own tracker and at their own
 * latency, so the host must draw the scene head-locked. One screen's worth of
 * scene, and no reprojection of Halone's own — but nothing between the
 * sensor and the panel but the glasses' own chip.
 *
 * Changes made on the glasses themselves arrive as
 * [DeviceEvent.OnboardPinningChanged].
 */
interface OnboardPinning {
    val enabled: Boolean
    fun setEnabled(enabled: Boolean): Result<Unit>
}

interface BrightnessControl {
    val range: IntRange
    fun current(): Result<Int>
    fun set(level: Int): Result<Unit>
}

/**
 * Reads the per-unit factory calibration the glasses store about themselves.
 *
 * This is deliberately raw. The blob's contents are vendor-specific and, for
 * XREAL, an undocumented protobuf; parsing it is a separate problem from
 * getting the bytes off the device, and conflating the two would mean a parser
 * bug loses the capture. So the driver's job ends at "here are the bytes".
 *
 * Worth having at all because the blob is the only source of several things we
 * currently guess: per-eye field of view, lens distortion, and factory
 * accelerometer and gyroscope bias.
 */
interface CalibrationSource {
    /**
     * Reads the whole blob. Takes exclusive use of the sensor link for the
     * duration — any tracking stream is paused and resumed around it, so
     * callers do not have to stop tracking themselves.
     *
     * @param onReply invoked with a copy of every reply packet, trimmed to its
     *   real length, in arrival order. Capture these: if our framing
     *   assumptions turn out to be wrong, the raw replies still contain the
     *   truth and can be re-parsed offline, where the assembled blob cannot.
     */
    fun read(onReply: (ByteArray) -> Unit = {}): Result<ByteArray>

    /**
     * The calibration to actually render with: read from the attached glasses
     * if they will give it up, otherwise the driver's bundled copy.
     *
     * Never fails, because there is no useful thing for a caller to do about it
     * — the alternative to a stranger's calibration is the hardcoded guesses it
     * replaced, which were worse. [DeviceCalibration.source] says which was
     * used, and anything that tunes against these numbers should check it.
     *
     * Takes about two seconds against hardware, so call it once at connect
     * rather than per frame.
     */
    fun load(): DeviceCalibration
}

/**
 * The best stereo mode available: verified modes first, then full over half
 * SBS, then resolution, then refresh rate. Verification outranks refresh rate
 * deliberately — a mode the firmware rejects is worth less than a slower one
 * that works.
 */
fun DisplayControl.preferredStereoMode(): DisplayMode? =
    supportedModes.filter { it.isStereo }.maxWithOrNull(MODE_PREFERENCE)

/** The best mono mode, for the monoscopic fallback and for exiting stereo. */
fun DisplayControl.preferredMonoMode(): DisplayMode? =
    supportedModes.filter { !it.isStereo }.maxWithOrNull(MODE_PREFERENCE)

/** A refresh rate the glasses offer, and whether any 3D mode runs at it. */
data class RefreshRate(val hz: Int, val stereo: Boolean)

fun DisplayControl.refreshRates(): List<RefreshRate> =
    supportedModes.groupBy { it.refreshHz.roundToInt() }
        .map { (hz, modes) -> RefreshRate(hz, modes.any { it.isStereo }) }
        .sortedBy { it.hz }

/**
 * The mode for the wearer's choice of rate and 3D. A rate the glasses only
 * offer in 2D gives a 2D mode whatever [stereo] says; a rate they do not offer
 * at all gives their preferred mode.
 */
fun DisplayControl.modeFor(refreshHz: Int, stereo: Boolean): DisplayMode? {
    val (threeD, twoD) = supportedModes.filter { it.refreshHz.roundToInt() == refreshHz }.partition { it.isStereo }
    val best = if (stereo) threeD.maxWithOrNull(MODE_PREFERENCE) ?: twoD.maxWithOrNull(MODE_PREFERENCE)
    else twoD.maxWithOrNull(MODE_PREFERENCE) ?: threeD.maxWithOrNull(MODE_PREFERENCE)
    return best ?: if (stereo) preferredStereoMode() else preferredMonoMode() ?: preferredStereoMode()
}

private val MODE_PREFERENCE: Comparator<DisplayMode> = compareBy(
    { if (it.verified) 1 else 0 },
    { if (it.layout == StereoLayout.FULL_SBS) 1 else 0 },
    { it.perEyeWidth.toLong() * it.perEyeHeight },
    { it.refreshHz },
)
