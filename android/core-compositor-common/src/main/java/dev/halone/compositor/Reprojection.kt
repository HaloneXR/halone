package dev.halone.compositor

import dev.halone.glasses.api.Frustum
import dev.halone.glasses.math.Quaternion

/**
 * Where a display pixel should sample the frame that was already rendered for
 * it, given that the head has moved since — and keeps moving while the panel is
 * being lit.
 *
 * The renderer draws one frame for one pose. The display then paints that frame
 * row by row over a whole scanout interval, so no row is lit at the pose it was
 * drawn for and no two rows are lit at the same pose. Against a see-through
 * view of the real world the result is a *shear*, not a lag: world-locked
 * content leans during head motion.
 *
 * The fix is to warp the finished image at display time rather than to predict
 * harder at render time. Each display pixel's direction is fixed in the head's
 * frame; what changes is where the head is pointing when that particular row
 * lights up. So take the direction, carry it back into the frame that was
 * rendered, and sample there:
 *
 * ```
 * d_render = (P_render · E)⁻¹ · P_display(t) · E · d_display
 * ```
 *
 * with `E` the eye's own rotation relative to the head. Two rotations are
 * enough for the whole frame — one for the first row lit and one for the last —
 * because the rows in between interpolate, which is what makes the correction a
 * function of raster position rather than an average across the image. It is
 * the same start-pose/mid-pose pair NRSDK feeds `NRRenderingCalcuWarpMatrix`.
 *
 * Rotation only, which here is exact rather than an approximation: without
 * positional tracking the eye does not translate relative to the head, so there
 * is no parallax for a depth-less warp to get wrong.
 */
object Reprojection {

    /**
     * Maps a texture coordinate in the rendered image to a view-space tangent,
     * as `tangent = uv * scale + offset`.
     *
     * Takes the frustum the image was *rendered* with, overfill included: the
     * margin is part of the texture, so it is part of this mapping.
     */
    fun tangentScale(rendered: Frustum, out: FloatArray) {
        out[0] = rendered.right - rendered.left
        out[1] = rendered.top - rendered.bottom
    }

    fun tangentOffset(rendered: Frustum, out: FloatArray) {
        out[0] = rendered.left
        out[1] = rendered.bottom
    }

    /**
     * `E⁻¹ · P_render⁻¹ · P_display · E`, as a column-major 3x3 ready for
     * `glUniformMatrix3fv`.
     *
     * @param headFromEye the eye's rotation relative to the head. Constant, and
     *   small — about 1.3° on the unit we have — but it is what makes the warp
     *   belong to each eye rather than to the head.
     */
    fun rotation(
        renderPose: Quaternion,
        displayPose: Quaternion,
        headFromEye: Quaternion,
        out: FloatArray,
    ) {
        val eyeFromHead = headFromEye.conjugate()
        val delta = eyeFromHead * renderPose.conjugate() * displayPose * headFromEye
        matrix3(delta.normalized(), out)
    }

    /** Column-major, to match GLSL's `mat3`. */
    fun matrix3(q: Quaternion, out: FloatArray) {
        val (w, x, y, z) = q
        out[0] = 1f - 2f * (y * y + z * z)
        out[1] = 2f * (x * y + w * z)
        out[2] = 2f * (x * z - w * y)
        out[3] = 2f * (x * y - w * z)
        out[4] = 1f - 2f * (x * x + z * z)
        out[5] = 2f * (y * z + w * x)
        out[6] = 2f * (x * z + w * y)
        out[7] = 2f * (y * z - w * x)
        out[8] = 1f - 2f * (x * x + y * y)
    }

    /**
     * The shader's inner loop, in Kotlin, so the geometry can be tested without
     * a GPU. Keep the two in step.
     */
    fun sample(
        u: Float,
        v: Float,
        rotation: FloatArray,
        scale: FloatArray,
        offset: FloatArray,
    ): FloatArray {
        val x = u * scale[0] + offset[0]
        val y = v * scale[1] + offset[1]
        val z = -1f
        val rx = rotation[0] * x + rotation[3] * y + rotation[6] * z
        val ry = rotation[1] * x + rotation[4] * y + rotation[7] * z
        val rz = rotation[2] * x + rotation[5] * y + rotation[8] * z
        val depth = maxOf(-rz, MIN_DEPTH)
        return floatArrayOf(
            (rx / depth - offset[0]) / scale[0],
            (ry / depth - offset[1]) / scale[1],
        )
    }

    /**
     * How much overfill the warp needs to stay inside the rendered image, as a
     * fraction of frame size per side.
     *
     * The reprojection reaches outside the frame by whatever the head turned
     * through, so without this margin the leading edge runs out of texture and
     * repeats its last column — a smear that appears only during fast motion,
     * which is exactly when it is least welcome.
     */
    fun overfillFor(rendered: Frustum, rateRadiansPerSecond: Float, seconds: Float): Float {
        val travel = Math.tan((rateRadiansPerSecond * seconds).toDouble()).toFloat()
        // A rotation moves a tangent by 1 + tan² rather than by the angle, so
        // the edge of a wide frame travels further than its centre does. Small
        // — a few percent of a few percent — but it is the edge that runs out
        // of texture, so the margin is sized for the edge.
        val edgeX = maxOf(-rendered.left, rendered.right)
        val edgeY = maxOf(-rendered.bottom, rendered.top)
        return maxOf(
            travel * (1f + edgeX * edgeX) / (rendered.right - rendered.left),
            travel * (1f + edgeY * edgeY) / (rendered.top - rendered.bottom),
        )
    }

    const val MIN_DEPTH = 1e-3f
}
