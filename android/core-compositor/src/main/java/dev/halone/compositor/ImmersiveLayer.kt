package dev.halone.compositor

import android.graphics.SurfaceTexture

/**
 * A video drawn behind every panel: around the wearer as a sphere, or on a
 * screen in the room, with each eye taking its own half of a stereo frame.
 *
 * The texture handshake is a panel's: the compositor makes the
 * `SurfaceTexture` on the render thread and hands it to [onSurface] on the UI
 * thread, where a decoder can render into it.
 */
class ImmersiveLayer(
    projection: MediaProjection,
    stereo: StereoLayout,
    /** Native radians, positive left, as in `Scene`: where the screen or the front of the sphere is. */
    anchorYawRadians: Float,
    private val onSurface: (SurfaceTexture) -> Unit,
) {
    @Volatile
    var projection: MediaProjection = projection

    @Volatile
    var stereo: StereoLayout = stereo

    @Volatile
    var anchorYawRadians: Float = anchorYawRadians

    @Volatile
    var screenWidthDegrees: Float = 40f

    /** Below 1, a sphere's angles from the gaze are stretched so more of it fits in view. */
    @Volatile
    var sphereZoom: Float = 1f

    /**
     * The radius a 2D sphere is drawn at. The optics focus at one distance,
     * and at infinity vergence fights it; a 3D one keeps its own disparity.
     */
    @Volatile
    var sphereDistanceMetres: Float = 6f

    /** Whether a drag on empty space may turn it, by moving [anchorYawRadians]. */
    @Volatile
    var rotatable: Boolean = false

    @Volatile
    var videoWidthPx: Int = 0

    @Volatile
    var videoHeightPx: Int = 0

    @Volatile
    internal var textureId: Int = 0

    @Volatile
    internal var surfaceTexture: SurfaceTexture? = null

    internal fun deliver(texture: SurfaceTexture) = onSurface(texture)

    internal fun detachTexture(): Int {
        val id = textureId
        surfaceTexture?.release()
        surfaceTexture = null
        textureId = 0
        return id
    }
}

/** The arithmetic of [ImmersiveLayer], apart from GL so it can be tested. */
object ImmersiveGeometry {
    /** Far enough that the screen sits near infinity, close enough that a stereo film keeps its depth. */
    const val SCREEN_DISTANCE_METRES = 6f

    /**
     * The shape of one eye's picture. A side-by-side frame much wider than 2:1
     * is full width per eye; anything narrower is the usual half-width kind,
     * squeezed to be stretched back to the frame's own shape. Likewise for
     * top-bottom. Kept in step with `eyeAspect` in `@halone/sdk`.
     */
    fun eyeAspect(widthPx: Int, heightPx: Int, stereo: StereoLayout): Float {
        if (widthPx <= 0 || heightPx <= 0) return 16f / 9f
        val frame = widthPx.toFloat() / heightPx
        return when {
            stereo == StereoLayout.SIDE_BY_SIDE && frame >= 2.5f -> frame / 2f
            stereo == StereoLayout.TOP_BOTTOM && frame <= 1.2f -> frame * 2f
            else -> frame
        }
    }

    /**
     * The part of the frame [eye] sees (0 left, 1 right), as (u, v, width,
     * height) in image coordinates with v down.
     */
    fun eyeRect(stereo: StereoLayout, eye: Int): FloatArray = when (stereo) {
        StereoLayout.MONO -> floatArrayOf(0f, 0f, 1f, 1f)
        StereoLayout.SIDE_BY_SIDE -> floatArrayOf(0.5f * eye, 0f, 0.5f, 1f)
        StereoLayout.TOP_BOTTOM -> floatArrayOf(0f, 0.5f * eye, 1f, 0.5f)
    }

    /**
     * Where a direction lands on an equirectangular frame, in image
     * coordinates, or null outside a 180° one. [x], [y], [z] are in the
     * layer's own frame: forward -Z, right +X, up +Y.
     */
    fun equirect(x: Float, y: Float, z: Float, halfSphere: Boolean): FloatArray? {
        val length = kotlin.math.sqrt(x * x + y * y + z * z)
        val longitude = kotlin.math.atan2(x, -z)
        val latitude = kotlin.math.asin((y / length).coerceIn(-1f, 1f))
        val pi = Math.PI.toFloat()
        if (halfSphere && kotlin.math.abs(longitude) > pi / 2f) return null
        val u = if (halfSphere) 0.5f + longitude / pi else 0.5f + longitude / (2f * pi)
        return floatArrayOf(u, 0.5f - latitude / pi)
    }

    /**
     * [direction] turned about [forward] so that its angle from it is divided
     * by [zoom], at most half a turn: below 1, more of a sphere fits in view
     * around the gaze. Both unit length. Kept in step with `widen` in the
     * immersive shaders.
     */
    fun widen(direction: FloatArray, forward: FloatArray, zoom: Float): FloatArray {
        val cosine = (direction[0] * forward[0] + direction[1] * forward[1] + direction[2] * forward[2]).coerceIn(-1f, 1f)
        val side = FloatArray(3) { direction[it] - forward[it] * cosine }
        val sine = kotlin.math.sqrt(side[0] * side[0] + side[1] * side[1] + side[2] * side[2])
        if (sine < 1e-6f) return direction.copyOf()
        val angle = minOf(kotlin.math.acos(cosine) / zoom, Math.PI.toFloat())
        return FloatArray(3) { forward[it] * kotlin.math.cos(angle) + side[it] / sine * kotlin.math.sin(angle) }
    }

    /**
     * The direction from the head's centre to where a ray from [eye] along
     * [direction] (unit length) meets a sphere of [radius] around it, the eye
     * being inside. Kept in step with the immersive shader.
     */
    fun sphereHit(eye: FloatArray, direction: FloatArray, radius: Float): FloatArray {
        val b = eye[0] * direction[0] + eye[1] * direction[1] + eye[2] * direction[2]
        val c = eye[0] * eye[0] + eye[1] * eye[1] + eye[2] * eye[2] - radius * radius
        val t = -b + kotlin.math.sqrt(b * b - c)
        val hit = FloatArray(3) { eye[it] + direction[it] * t }
        val length = kotlin.math.sqrt(hit[0] * hit[0] + hit[1] * hit[1] + hit[2] * hit[2])
        return FloatArray(3) { hit[it] / length }
    }
}
