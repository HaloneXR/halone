package dev.halone.display

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import dev.halone.glasses.api.DisplayMode
import dev.halone.glasses.api.GlassesDevice
import dev.halone.glasses.api.preferredMonoMode
import dev.halone.glasses.api.preferredStereoMode
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Gets the glasses into the display mode we want, and hands back the `Display`
 * to render on.
 *
 * The awkward part is not the rendering, it is the ordering. The HID mode
 * command is the real mode selector — the glasses only advertise 3840x1080 once
 * they are in SBS — and switching makes the host **destroy and re-add the
 * display with a new id**. So the sequence has to be: write the mode over HID,
 * wait for the new display to arrive, and only then build anything against it.
 * Anything holding the old `Display` is stale.
 */
class GlassesDisplaySession(
    context: Context,
    private val log: (String) -> Unit,
) {
    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    /** Switches the glasses to stereo, waiting out the display churn it causes. */
    suspend fun enterStereo(device: GlassesDevice, mode: DisplayMode? = null): Display? {
        val control = device.display ?: return currentGlassesDisplay(device)

        val stereo = mode ?: control.preferredStereoMode()
        if (stereo == null) {
            log("  this device advertises no stereo mode")
            return null
        }

        val current = control.currentMode().getOrNull()
        if (current?.key == stereo.key) {
            log("  already in ${current.key}; reusing the current display")
            return findGlassesDisplay(device)
        }

        log("  switching to $stereo (display will be recreated)")
        val before = findGlassesDisplay(device)?.displayId

        control.setMode(stereo).onFailure {
            log("  mode switch failed: ${it.message}")
            return null
        }

        val added = awaitNewPresentationDisplay(device, before)
        if (added == null) {
            log("  no new display arrived after the mode switch")
            return null
        }
        log("  new display ${added.displayId} \"${added.name}\", mode " +
            "${added.mode.physicalWidth}x${added.mode.physicalHeight}")
        return added
    }

    /** Returns the glasses to a mono mode so the phone is usable again. */
    fun leaveStereo(device: GlassesDevice) {
        val control = device.display ?: run {
            log("  the glasses choose their own mode; switch them back to 2D with their controls")
            return
        }
        val mono = control.preferredMonoMode() ?: return
        log("  restoring $mono: ${control.setMode(mono)}")
    }

    /**
     * For glasses the host cannot switch (RayNeo GT series): the wearer
     * chooses 2D or 3D with the glasses' own controls, so use the display in
     * whichever mode it is. The compositor draws side by side on a 3840x1080
     * mode and a single image on a 1920x1080 one, and follows a switch made
     * while it runs.
     */
    private fun currentGlassesDisplay(device: GlassesDevice): Display? {
        val display = findGlassesDisplay(device)
        if (display == null) {
            log("  no glasses display found")
            return null
        }
        log(
            "  using display ${display.displayId} \"${display.name}\" as the glasses have it: " +
                if (display.hasSideBySideMode()) "3D (side by side)" else "2D (one image for both eyes)"
        )
        return display
    }

    fun findGlassesDisplay(device: GlassesDevice): Display? {
        val candidates = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).filter(::isPublic)
        return candidates.firstOrNull { display -> named(display, device) } ?: candidates.firstOrNull()
    }

    /** Halone's own windows each have a private virtual display, which is never the glasses'. */
    private fun isPublic(display: Display) = display.flags and Display.FLAG_PRIVATE == 0

    private fun named(display: Display, device: GlassesDevice) =
        device.displayNameHints.any { display.name.contains(it, ignoreCase = true) }

    /** Plugged in rather than made by an app: the glasses by name, or failing that any external screen. */
    private fun isGlasses(display: Display, device: GlassesDevice) =
        isPublic(display) && (named(display, device) || Build.VERSION.SDK_INT >= 31 && display.deviceProductInfo != null)

    /**
     * Waits for a presentation display whose id differs from the one we had.
     * The mode switch tears the old one down, so watching for *any* add would
     * race with the removal of the display we were just using.
     */
    private suspend fun awaitNewPresentationDisplay(device: GlassesDevice, previousId: Int?): Display? =
        awaitDisplay(DISPLAY_SETTLE_MS) { it.displayId != previousId && isGlasses(it, device) }

    /**
     * Waits up to [timeoutMs] for a presentation display, added or changed,
     * that [accept] takes; at the timeout, settles for any current one it
     * takes, since the display may have arrived before the listener existed.
     */
    private suspend fun awaitDisplay(timeoutMs: Long, accept: (Display) -> Boolean): Display? =
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            var settled = false

            val listener = object : DisplayManager.DisplayListener {
                private fun consider(displayId: Int, reason: String) {
                    if (settled) return
                    val display = displayManager.getDisplay(displayId) ?: return
                    if (display.flags and Display.FLAG_PRESENTATION == 0) return
                    if (!accept(display)) return
                    settled = true
                    displayManager.unregisterDisplayListener(this)
                    log("  display $displayId $reason")
                    continuation.resume(display)
                }

                override fun onDisplayAdded(displayId: Int) = consider(displayId, "added")
                override fun onDisplayChanged(displayId: Int) = consider(displayId, "changed")
                override fun onDisplayRemoved(displayId: Int) = Unit
            }

            displayManager.registerDisplayListener(listener, handler)
            continuation.invokeOnCancellation {
                displayManager.unregisterDisplayListener(listener)
            }

            handler.postDelayed({
                if (settled) return@postDelayed
                settled = true
                displayManager.unregisterDisplayListener(listener)
                continuation.resume(
                    displayManager
                        .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
                        .firstOrNull(accept)
                )
            }, timeoutMs)
        }

    /** Shaped like two 16:9 halves glued together — the test `RenderPresentation` picks modes by. */
    private fun Display.hasSideBySideMode(): Boolean =
        supportedModes.any { it.physicalWidth >= 2 * it.physicalHeight }

    private companion object {
        const val DISPLAY_SETTLE_MS = 4_000L
    }
}
