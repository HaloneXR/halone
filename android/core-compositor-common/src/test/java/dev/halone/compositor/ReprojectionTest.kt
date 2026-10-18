package dev.halone.compositor

import dev.halone.glasses.api.Frustum
import dev.halone.glasses.math.Quaternion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The warp's geometry, checked without a GPU. [Reprojection.sample] is the
 * fragment shader's inner loop written twice; these tests are what keeps the
 * two honest.
 */
class ReprojectionTest {

    private val calibrated = Frustum(-0.3733f, 0.3400f, -0.1938f, 0.2056f)
    private val scale = FloatArray(2).also { Reprojection.tangentScale(calibrated, it) }
    private val offset = FloatArray(2).also { Reprojection.tangentOffset(calibrated, it) }

    private fun rotation(
        render: Quaternion,
        display: Quaternion,
        eye: Quaternion = Quaternion.IDENTITY,
    ) = FloatArray(9).also { Reprojection.rotation(render, display, eye, it) }

    private fun aboutX(radians: Float): Quaternion {
        val half = radians / 2f
        return Quaternion(Math.cos(half.toDouble()).toFloat(), Math.sin(half.toDouble()).toFloat(), 0f, 0f)
    }

    @Test
    fun `a head that has not moved samples where it drew`() {
        val pose = Quaternion.fromYawRadians(0.4f)
        val r = rotation(pose, pose)
        for (u in listOf(0f, 0.5f, 1f)) {
            for (v in listOf(0f, 0.5f, 1f)) {
                val (su, sv) = Reprojection.sample(u, v, r, scale, offset)
                assertEquals(u, su, 1e-4f)
                assertEquals(v, sv, 1e-4f)
            }
        }
    }

    /**
     * A positive rotation about canonical +Y turns the wearer's head to the
     * left, so world-locked content has to move *right* on the display — which
     * means every pixel samples further left in the frame that was drawn.
     */
    @Test
    fun `turning left pulls the sample leftward`() {
        val render = Quaternion.IDENTITY
        val display = Quaternion.fromYawRadians(0.02f)
        val r = rotation(render, display)
        val (su, sv) = Reprojection.sample(0.5f, 0.5f, r, scale, offset)
        assertTrue("sample moved to $su", su < 0.5f - 1e-3f)
        assertEquals(0.5f, sv, 1e-4f)
    }

    @Test
    fun `turning right pushes the sample rightward`() {
        val r = rotation(Quaternion.IDENTITY, Quaternion.fromYawRadians(-0.02f))
        val (su, _) = Reprojection.sample(0.5f, 0.5f, r, scale, offset)
        assertTrue("sample moved to $su", su > 0.5f + 1e-3f)
    }

    /**
     * +X points right and the frame is right-handed, so a positive rotation
     * about it tips the head's up axis backwards: the wearer looks up, and
     * content must move down.
     */
    @Test
    fun `looking up pulls the sample upward`() {
        val r = rotation(Quaternion.IDENTITY, aboutX(0.02f))
        val (su, sv) = Reprojection.sample(0.5f, 0.5f, r, scale, offset)
        assertEquals(0.5f, su, 1e-4f)
        assertTrue("sample moved to $sv", sv > 0.5f + 1e-3f)
    }

    @Test
    fun `the warp is invertible`() {
        val render = Quaternion.fromYawRadians(0.1f)
        val display = Quaternion.fromYawRadians(0.13f) * aboutX(0.05f)
        val forward = rotation(render, display)
        val back = rotation(display, render)
        val (u, v) = Reprojection.sample(0.7f, 0.3f, forward, scale, offset)
        val (u2, v2) = Reprojection.sample(u, v, back, scale, offset)
        assertEquals(0.7f, u2, 1e-3f)
        assertEquals(0.3f, v2, 1e-3f)
    }

    /**
     * The rotation belongs to the eye, not the head: the same head motion warps
     * the two eyes slightly differently because they do not point the same way.
     */
    @Test
    fun `the eye's own rotation is carried through`() {
        val display = Quaternion.fromYawRadians(0.02f)
        val straight = rotation(Quaternion.IDENTITY, display)
        val tilted = rotation(Quaternion.IDENTITY, display, aboutX(0.0224f))
        val a = Reprojection.sample(0.5f, 0.5f, straight, scale, offset)
        val b = Reprojection.sample(0.5f, 0.5f, tilted, scale, offset)
        assertTrue("the two eyes warped identically", abs(a[1] - b[1]) > 1e-5f)
    }

    /** The identity quaternion has to survive the column-major transcription. */
    @Test
    fun `an unrotated matrix is the identity`() {
        val m = FloatArray(9)
        Reprojection.matrix3(Quaternion.IDENTITY, m)
        assertTrue(m.toList() == listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
    }

    /**
     * A wide frame needs proportionally less margin for the same head rate, and
     * the short axis is what binds.
     */
    @Test
    fun `overfill follows the narrower axis`() {
        val rate = Math.toRadians(45.0).toFloat()
        val overfill = Reprojection.overfillFor(calibrated, rate, 1f / 60f)
        val travel = Math.tan((rate / 60f).toDouble()).toFloat()
        val edge = calibrated.top
        assertEquals(
            travel * (1f + edge * edge) / (calibrated.top - calibrated.bottom),
            overfill,
            1e-6f,
        )
        assertTrue("$overfill", overfill > travel / (calibrated.right - calibrated.left))
    }

    @Test
    fun `a still head needs no margin`() {
        assertEquals(0f, Reprojection.overfillFor(calibrated, 0f, 1f / 60f), 1e-9f)
    }

    /**
     * The margin is exactly what the warp reaches outside the frame at its
     * design rate: a sample starting at the edge of the *visible* image lands
     * on the last texel of the texture and not past it.
     */
    @Test
    fun `the margin covers the worst sample at its design rate`() {
        val rate = Math.toRadians(45.0).toFloat()
        val seconds = 1f / 60f
        val margin = Reprojection.overfillFor(calibrated, rate, seconds)
        val rendered = calibrated.expandedBy(margin)
        val wide = FloatArray(2).also { Reprojection.tangentScale(rendered, it) }
        val origin = FloatArray(2).also { Reprojection.tangentOffset(rendered, it) }

        // Where the display's own edges land in the overfilled texture.
        val edge = margin / (1f + 2f * margin)
        val yaw = rotation(Quaternion.IDENTITY, Quaternion.fromYawRadians(rate * seconds))
        val pitch = rotation(Quaternion.IDENTITY, aboutX(rate * seconds))

        val (leftmost, _) = Reprojection.sample(edge, 0.5f, yaw, wide, origin)
        assertTrue("u $leftmost left the texture", leftmost >= -1e-3f)
        assertTrue("u $leftmost wasted margin", leftmost < edge)

        val (_, topmost) = Reprojection.sample(0.5f, 1f - edge, pitch, wide, origin)
        assertTrue("v $topmost left the texture", topmost <= 1f + 1e-3f)
        assertTrue("v $topmost wasted margin", topmost > 1f - edge)
    }
}
