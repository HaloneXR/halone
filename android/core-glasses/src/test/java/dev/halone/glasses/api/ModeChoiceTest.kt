package dev.halone.glasses.api

import org.junit.Assert.assertEquals
import org.junit.Test

class ModeChoiceTest {

    private val control = object : DisplayControl {
        override val supportedModes = listOf(
            DisplayMode("mirror-60", StereoLayout.MONO, 1920, 1080, 60f, verified = true),
            DisplayMode("mirror-90", StereoLayout.MONO, 1920, 1080, 90f),
            DisplayMode("mirror-120", StereoLayout.MONO, 1920, 1080, 120f),
            DisplayMode("sbs-60", StereoLayout.FULL_SBS, 3840, 1080, 60f, verified = true),
            DisplayMode("sbs-90", StereoLayout.FULL_SBS, 3840, 1080, 90f),
            DisplayMode("half-sbs-60", StereoLayout.HALF_SBS, 1920, 1080, 60f),
        )
        override fun currentMode() = Result.success(supportedModes.first())
        override fun setMode(mode: DisplayMode) = Result.success(Unit)
        override val modeChangeRecreatesDisplay = true
    }

    @Test
    fun `each rate says whether 3D runs at it`() {
        assertEquals(
            listOf(RefreshRate(60, true), RefreshRate(90, true), RefreshRate(120, false)),
            control.refreshRates(),
        )
    }

    @Test
    fun `3D at a rate takes full side by side over half`() {
        assertEquals("sbs-60", control.modeFor(60, stereo = true)?.key)
        assertEquals("sbs-90", control.modeFor(90, stereo = true)?.key)
    }

    @Test
    fun `2D at a rate takes the mirror mode`() {
        assertEquals("mirror-90", control.modeFor(90, stereo = false)?.key)
    }

    @Test
    fun `a 2D-only rate stays 2D when 3D is asked for`() {
        assertEquals("mirror-120", control.modeFor(120, stereo = true)?.key)
    }

    @Test
    fun `a rate the glasses lack falls back to their preferred mode`() {
        assertEquals("sbs-60", control.modeFor(72, stereo = true)?.key)
        assertEquals("mirror-60", control.modeFor(72, stereo = false)?.key)
    }
}
