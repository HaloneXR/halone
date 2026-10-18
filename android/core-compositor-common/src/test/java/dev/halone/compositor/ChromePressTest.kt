package dev.halone.compositor

import dev.halone.glasses.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class ChromePressTest {
    private class TestPanel(id: String) : ScenePanel(id, PanelPose()) {
        override val widthPx = 1280
        override val heightPx = 800
    }

    private class Recorder : ChromePress.Actions<TestPanel> {
        val events = mutableListOf<String>()
        var shellPins = false
        override fun resizeContent(panel: TestPanel, widthPx: Int, heightPx: Int) { events += "resize ${panel.id}" }
        override fun pin(panel: TestPanel, pinned: Boolean): Boolean { events += "pin ${panel.id} $pinned"; return shellPins }
        override fun back(panel: TestPanel) { events += "back ${panel.id}" }
        override fun close(panel: TestPanel) { events += "close ${panel.id}" }
        override fun dropped(panel: TestPanel) { events += "dropped ${panel.id}" }
    }

    private val scene = PanelScene<TestPanel>()
    private val panel = scene.add(TestPanel("a"))
    private val actions = Recorder()
    private val chrome = ChromePress(scene, actions)

    private fun aim(yawDegrees: Float): Vec3 {
        val yaw = Math.toRadians(yawDegrees.toDouble()).toFloat()
        return Vec3(-sin(yaw), 0f, -cos(yaw))
    }

    private fun hit(part: HitPart) = PanelSceneHit(panel, PanelHit(640f, 830f), 4f, part)

    @Test
    fun `a still click on the bar puts the window back`() {
        val before = panel.pose
        chrome.press(hit(HitPart.TITLE_BAR), aim(0f))
        assertSame(panel, scene.grabbed)
        scene.updateGrab(aim(0.5f))
        assertTrue(chrome.release(aim(0.5f), hit(HitPart.TITLE_BAR), 0f))
        assertNull(scene.grabbed)
        assertEquals(before, panel.pose)
        assertTrue(actions.events.isEmpty())
    }

    @Test
    fun `dragging the bar past the slop drops the window where it was carried`() {
        chrome.press(hit(HitPart.TITLE_BAR), aim(0f))
        scene.updateGrab(aim(10f))
        chrome.release(aim(10f), null, 0f)
        assertNull(scene.grabbed)
        assertEquals(listOf("dropped a"), actions.events)
        assertEquals(10.0, Math.toDegrees(panel.pose.yawRadians.toDouble()), 0.5)
    }

    @Test
    fun `a button acts only when released on itself`() {
        chrome.press(hit(HitPart.CLOSE), aim(0f))
        chrome.release(aim(0f), hit(HitPart.PIN), 0f)
        assertTrue(actions.events.isEmpty())
        chrome.press(hit(HitPart.CLOSE), aim(0f))
        chrome.release(aim(0f), hit(HitPart.CLOSE), 0f)
        assertEquals(listOf("close a"), actions.events)
    }

    @Test
    fun `pin toggles following when the shell does not take it`() {
        assertTrue(panel.follow.enabled)
        chrome.activate(panel, HitPart.PIN)
        assertFalse(panel.follow.enabled)
        chrome.activate(panel, HitPart.PIN)
        assertTrue(panel.follow.enabled)
        actions.shellPins = true
        chrome.activate(panel, HitPart.PIN)
        assertTrue(panel.follow.enabled)
    }

    @Test
    fun `release without a press is not ours`() {
        assertFalse(chrome.release(aim(0f), null, 0f))
    }
}
