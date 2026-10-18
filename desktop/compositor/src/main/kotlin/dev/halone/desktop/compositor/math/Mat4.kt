package dev.halone.desktop.compositor.math

import dev.halone.glasses.math.Quaternion
import kotlin.math.cos
import kotlin.math.sin

/**
 * Column-major 4x4 matrices in a FloatArray, with the same conventions as
 * android.opengl.Matrix so the compositor's math reads the same on both sides.
 */
object Mat4 {
    fun identity(out: FloatArray = FloatArray(16)): FloatArray {
        out.fill(0f)
        out[0] = 1f; out[5] = 1f; out[10] = 1f; out[15] = 1f
        return out
    }

    fun multiply(a: FloatArray, b: FloatArray, out: FloatArray = FloatArray(16)): FloatArray {
        val r = FloatArray(16)
        for (col in 0 until 4) for (row in 0 until 4) {
            var sum = 0f
            for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
            r[col * 4 + row] = sum
        }
        r.copyInto(out)
        return out
    }

    fun translation(x: Float, y: Float, z: Float) = identity().also { it[12] = x; it[13] = y; it[14] = z }

    fun scale(x: Float, y: Float, z: Float) = identity().also { it[0] = x; it[5] = y; it[10] = z }

    fun rotationY(radians: Float) = identity().also {
        val c = cos(radians); val s = sin(radians)
        it[0] = c; it[2] = -s; it[8] = s; it[10] = c
    }

    fun rotationX(radians: Float) = identity().also {
        val c = cos(radians); val s = sin(radians)
        it[5] = c; it[6] = s; it[9] = -s; it[10] = c
    }

    /** Same as StereoCompositor.quaternionToMatrix. */
    fun rotation(q: Quaternion, out: FloatArray = FloatArray(16)): FloatArray {
        val (w, x, y, z) = q
        out[0] = 1f - 2f * (y * y + z * z); out[1] = 2f * (x * y + w * z); out[2] = 2f * (x * z - w * y); out[3] = 0f
        out[4] = 2f * (x * y - w * z); out[5] = 1f - 2f * (x * x + z * z); out[6] = 2f * (y * z + w * x); out[7] = 0f
        out[8] = 2f * (x * z + w * y); out[9] = 2f * (y * z - w * x); out[10] = 1f - 2f * (x * x + y * y); out[11] = 0f
        out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f
        return out
    }

    /** android.opengl.Matrix.frustumM: OpenGL clip space, z in [-1, 1], y up. */
    fun frustum(left: Float, right: Float, bottom: Float, top: Float, near: Float, far: Float): FloatArray {
        val m = FloatArray(16)
        m[0] = 2f * near / (right - left)
        m[5] = 2f * near / (top - bottom)
        m[8] = (right + left) / (right - left)
        m[9] = (top + bottom) / (top - bottom)
        m[10] = -(far + near) / (far - near)
        m[11] = -1f
        m[14] = -2f * far * near / (far - near)
        return m
    }

    /** Maps OpenGL clip space onto Vulkan's: y down, z in [0, 1]. Applied after the projection. */
    val VULKAN_CLIP: FloatArray = identity().also {
        it[5] = -1f
        it[10] = 0.5f
        it[14] = 0.5f
    }

    /** Inverse of a rigid transform (rotation plus translation), which is all a view matrix inverts. */
    fun invertRigid(m: FloatArray, out: FloatArray = FloatArray(16)): FloatArray {
        val r = FloatArray(16)
        for (i in 0 until 3) for (j in 0 until 3) r[j * 4 + i] = m[i * 4 + j]
        for (i in 0 until 3) r[12 + i] = -(r[i] * m[12] + r[4 + i] * m[13] + r[8 + i] * m[14])
        r[15] = 1f
        r.copyInto(out)
        return out
    }
}
