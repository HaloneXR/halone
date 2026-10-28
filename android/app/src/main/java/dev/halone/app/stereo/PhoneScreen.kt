package dev.halone.app.stereo

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The phone's own screen, as far as the glasses care: Android hands frames to
 * every display on its beat, so glasses running at a rate it cannot refresh in
 * step with get their frames unevenly, however fast they are drawn.
 */
object PhoneScreen {

    /** Every rate the phone's screen can refresh at, adaptive screens' slower steps included. */
    fun rates(context: Context): List<Float> {
        val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return emptyList()
        @Suppress("DEPRECATION")
        val listed = display.supportedRefreshRates.toList()
        val divided = display.supportedModes.flatMap { mode -> (1..MAX_DIVISOR).map { mode.refreshRate / it } }
        return (listed + divided).filter { it >= MIN_RATE }.distinct()
    }

    /** Whether the phone's screen can refresh at [hz] or a whole multiple of it. */
    fun inStep(hz: Float, rates: List<Float>): Boolean = rates.any { rate ->
        val multiple = rate / hz
        multiple.roundToInt() >= 1 && abs(multiple - multiple.roundToInt()) < TOLERANCE
    }

    private const val MAX_DIVISOR = 12
    private const val MIN_RATE = 20f
    private const val TOLERANCE = 0.02f
}
