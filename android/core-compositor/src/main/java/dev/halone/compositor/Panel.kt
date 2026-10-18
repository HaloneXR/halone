package dev.halone.compositor

import android.graphics.SurfaceTexture

/** A [ScenePanel] with an engine and its texture; the arithmetic is in `core-scene`, shared with the simulator. */
class Panel(
    id: String,
    val engine: PanelEngine,
    pose: PanelPose,
    follow: SmoothFollow = SmoothFollow(),
) : ScenePanel(id, pose, follow), WindowPanel {

    override val widthPx: Int get() = engine.widthPx
    override val heightPx: Int get() = engine.heightPx

    @Volatile
    override var title: String = id

    @Volatile
    override var tag: String? = null

    /** Corner radius in the panel's pixels; the dock is a pill. */
    @Volatile
    var cornerRadiusPx: Float = 24f

    @Volatile
    override var stereoRegion: StereoRegion? = null

    override fun resizeContent(widthPx: Int, heightPx: Int) = engine.resize(widthPx, heightPx)

    /** Set by the compositor once phase one of the engine handshake has run. */
    @Volatile
    internal var textureId: Int = 0

    @Volatile
    internal var surfaceTexture: SurfaceTexture? = null

    val attached: Boolean get() = surfaceTexture != null

    @Volatile
    internal var layerTextureId: Int = 0

    @Volatile
    internal var layerSurfaceTexture: SurfaceTexture? = null

    /** As [detachTexture], for the top layer's texture. */
    fun detachLayerTexture(): Int {
        val id = layerTextureId
        layerSurfaceTexture?.release()
        layerSurfaceTexture = null
        layerTextureId = 0
        return id
    }

    /**
     * Gives up the texture, returning the GL name so the compositor can delete
     * it on the render thread. Safe to call more than once.
     */
    fun detachTexture(): Int {
        val id = textureId
        surfaceTexture?.release()
        surfaceTexture = null
        textureId = 0
        return id
    }

    override fun toString() =
        "Panel($id ${engine.widthPx}x${engine.heightPx} yaw=%.1f° %s)".format(
            Math.toDegrees(pose.yawRadians.toDouble()),
            if (follow.enabled) "follows" else "pinned",
        )

    companion object {
        const val MAX_FOLLOW_STEP_SECONDS = ScenePanel.MAX_FOLLOW_STEP_SECONDS

        fun wrapPi(angle: Float): Float = ScenePanel.wrapPi(angle)
    }
}
