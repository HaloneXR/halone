package dev.halone.compositor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class FreeCursorTest {
    private fun rad(degrees: Float) = Math.toRadians(degrees.toDouble()).toFloat()
    private fun deg(radians: Float) = Math.toDegrees(radians.toDouble()).toFloat()

    /** Two panels 30° apart, each 1.28 m wide at 4 m: edges at ±9.1° about their centres. */
    private fun twoPanels() = Scene().apply {
        add(panel("left", yawDegrees = 15f))
        add(panel("right", yawDegrees = -15f))
    }

    /** Out to the resize handles, 64 px past each edge, at 1 mm a pixel. */
    private val halfWidth = Math.toDegrees(kotlin.math.atan(0.704 / 4.0)).toFloat() - 0.05f
    private val halfHeight = Math.toDegrees(kotlin.math.atan(0.464 / 4.0)).toFloat() - 0.05f

    /** The bottom of the title bar: 70 px of gap and bar under an 800 px panel, at 1 mm a pixel. */
    private val barBottom = Math.toDegrees(kotlin.math.atan(0.47 / 4.0)).toFloat() - 0.05f

    @Test
    fun `moving right lowers the native yaw, and the ray points where the cursor is`() {
        val scene = twoPanels()
        val cursor = FreeCursor()

        cursor.moveBy(rightDegrees = 10f, upDegrees = 0f, scene)

        assertEquals(-10f, deg(cursor.yawRadians), 1e-3f)
        assertSame(scene.panels[1], scene.hitTest(cursor.direction())!!.panel)
    }

    @Test
    fun `it stops at the outer edge of the last panel`() {
        val scene = twoPanels()
        val cursor = FreeCursor()

        cursor.moveBy(rightDegrees = 200f, upDegrees = 0f, scene)
        assertEquals(-15f - halfWidth, deg(cursor.yawRadians), 1e-3f)

        cursor.moveBy(rightDegrees = -500f, upDegrees = 0f, scene)
        assertEquals(15f + halfWidth, deg(cursor.yawRadians), 1e-3f)
    }

    @Test
    fun `it stops at the top of the panels and the bottom of their title bars`() {
        val scene = twoPanels()
        val cursor = FreeCursor()

        cursor.moveBy(0f, upDegrees = 40f, scene)
        assertEquals(halfHeight, deg(cursor.pitchRadians), 1e-3f)

        cursor.moveBy(0f, upDegrees = -90f, scene)
        assertEquals(-barBottom, deg(cursor.pitchRadians), 1e-3f)
    }

    @Test
    fun `it can reach a title bar`() {
        val scene = Scene().apply { add(panel("only")) }
        val cursor = FreeCursor()

        cursor.moveBy(0f, upDegrees = -90f, scene)

        assertEquals(HitPart.TITLE_BAR, scene.hitTest(cursor.direction())?.part)
    }

    @Test
    fun `holding a window frees the cursor, so the window can go anywhere`() {
        val scene = twoPanels()
        val cursor = FreeCursor()
        scene.beginGrab(scene.panels[0], cursor.direction())

        cursor.moveBy(rightDegrees = 60f, upDegrees = -30f, scene)
        assertEquals(-60f, deg(cursor.yawRadians), 1e-3f)
        assertEquals(-30f, deg(cursor.pitchRadians), 1e-3f)

        scene.endGrab(0f)
        cursor.clampTo(scene)
        assertEquals(-barBottom, deg(cursor.pitchRadians), 1e-3f)
    }

    @Test
    fun `the gap between panels is allowed, and hits nothing`() {
        val scene = Scene().apply {
            add(panel("left", yawDegrees = 25f))
            add(panel("right", yawDegrees = -25f))
        }
        val cursor = FreeCursor()

        cursor.placeAt(0f, 0f, scene)

        assertEquals(0f, deg(cursor.yawRadians), 1e-3f)
        assertNull(scene.hitTest(cursor.direction()))
    }

    @Test
    fun `panels moving away pull the cursor with them`() {
        val scene = Scene()
        val only = scene.add(panel("only", yawDegrees = 0f))
        val cursor = FreeCursor()
        cursor.placeAt(0f, 0f, scene)

        only.pose = only.pose.copy(yawRadians = rad(90f))
        cursor.clampTo(scene)

        assertEquals(90f - halfWidth, deg(cursor.yawRadians), 1e-3f)
    }

    @Test
    fun `the span is measured across the seam behind the wearer`() {
        val scene = Scene().apply {
            add(panel("a", yawDegrees = 170f))
            add(panel("b", yawDegrees = -170f))
        }
        val cursor = FreeCursor()

        cursor.placeAt(rad(175f), 0f, scene)
        cursor.moveBy(rightDegrees = -40f, upDegrees = 0f, scene)

        // Carried leftward over ±180° onto b, and stopped at b's far edge.
        assertEquals(-170f + halfWidth, deg(cursor.yawRadians), 1e-2f)
    }

    @Test
    fun `riding, the cursor keeps its place on a panel that moves`() {
        val scene = twoPanels()
        val left = scene.panels[0]
        val cursor = FreeCursor()
        cursor.placeAt(rad(18f), rad(2f), scene)

        cursor.ride(left, scene)
        left.pose = left.pose.copy(yawRadians = rad(25f), pitchRadians = rad(1f))
        cursor.ride(left, scene)

        assertEquals(28f, deg(cursor.yawRadians), 1e-3f)
        assertEquals(3f, deg(cursor.pitchRadians), 1e-3f)
    }

    @Test
    fun `between panels it rides the last one it was on`() {
        val scene = twoPanels()
        val right = scene.panels[1]
        val cursor = FreeCursor()
        cursor.placeAt(0f, 0f, scene)

        cursor.ride(right, scene)
        right.pose = right.pose.copy(yawRadians = rad(-10f))
        cursor.ride(null, scene)

        assertEquals(5f, deg(cursor.yawRadians), 1e-3f)
    }

    @Test
    fun `it stays put while a window is held`() {
        val scene = twoPanels()
        val left = scene.panels[0]
        val cursor = FreeCursor()
        cursor.placeAt(rad(15f), 0f, scene)

        cursor.ride(left, scene)
        scene.beginGrab(left, cursor.direction())
        left.pose = left.pose.copy(yawRadians = rad(25f))
        cursor.ride(left, scene)

        assertEquals(15f, deg(cursor.yawRadians), 1e-3f)
    }
}
