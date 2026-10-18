package dev.halone.desktop.compositor.render

import dev.halone.compositor.ChromeLayout
import dev.halone.compositor.CursorShape
import dev.halone.compositor.CursorStyle
import dev.halone.compositor.HitPart
import dev.halone.compositor.Notice
import dev.halone.compositor.NoticeLayout
import dev.halone.compositor.PanelPose
import dev.halone.compositor.PanelScene
import dev.halone.compositor.PanelSceneHit
import dev.halone.compositor.ResizeDrag
import dev.halone.compositor.ResizeHandles
import dev.halone.compositor.ScenePanel
import dev.halone.compositor.StereoRegion
import dev.halone.compositor.TitleBar
import dev.halone.compositor.WindowPanel
import dev.halone.desktop.compositor.math.Mat4
import dev.halone.desktop.compositor.vk.FrameTarget
import dev.halone.desktop.compositor.vk.TextureSource
import dev.halone.desktop.compositor.vk.VulkanContext
import dev.halone.glasses.math.Quaternion
import dev.halone.glasses.math.Vec3
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkClearValue
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkRect2D
import org.lwjgl.vulkan.VkRenderingAttachmentInfo
import org.lwjgl.vulkan.VkRenderingInfo
import org.lwjgl.vulkan.VkViewport
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.tan

/** The page in a panel's top band, drawn from its texture's top [visibleHeightPx] rows. */
class PanelLayer(var texture: TextureSource, val producerIndex: Int?, var visibleHeightPx: Int)

class DesktopPanel(
    id: String,
    override var widthPx: Int,
    override var heightPx: Int,
    var texture: TextureSource,
    pose: PanelPose = PanelPose(),
) : ScenePanel(id, pose), WindowPanel {
    var cornerRadiusPx: Float = 24f
    override var title: String = id
    override var tag: String? = null
    override var stereoRegion: StereoRegion? = null

    /** The producer's number for this panel, when a producer draws it. */
    var producerIndex: Int? = null

    /** A page drawn in a band across the top over the content, as a web window's browser chrome; null for none. */
    var layer: PanelLayer? = null

    /** Whether keys go to [layer], as when its field was last pressed, rather than to the content. */
    var keysToLayer = false

    /** The producer panel keys go to. */
    val keyProducerIndex: Int? get() = layer?.takeIf { keysToLayer }?.producerIndex ?: producerIndex

    /** The band [layer] takes across the top, in this panel's pixels. */
    val bandPx: Int get() = layer?.visibleHeightPx ?: 0

    /** Asks whatever draws the content to lay it out at a new size. */
    var onResizeContent: ((widthPx: Int, heightPx: Int) -> Unit)? = null

    override fun resizeContent(widthPx: Int, heightPx: Int) {
        this.widthPx = widthPx
        this.heightPx = heightPx
        onResizeContent?.invoke(widthPx, heightPx)
    }

    /** The shape the content under the pointer asks the arrow to take. */
    var cursorShape: CursorShape = CursorShape.DEFAULT
}

/**
 * The desktop counterpart of StereoCompositor: steps the scene each frame as
 * it does, then draws every window with its chrome (frame, content, title bar,
 * resize handle), the resize preview and the cursor, with ChromeLayout's
 * shared geometry.
 */
class DesktopCompositor(private val vk: VulkanContext, colorFormat: Int, val verticalFovDegrees: Float) : AutoCloseable {
    var stereo: StereoRenderer? = null

    /** How the pointer is drawn; a mouse gets the arrow. */
    var cursorStyle = CursorStyle.ARROW

    /** Where the pointer aims, set by the input every frame; null hides the cursor. */
    var pointerDirection: Vec3? = null

    var lastHit: PanelSceneHit<DesktopPanel>? = null
        private set

    var headYawRadians = 0f
        private set

    /** Held in front of the wearer over everything else while set. */
    var notice: Notice? = null

    val scene = PanelScene<DesktopPanel>()
    private val panels = PanelPipeline(vk, colorFormat)
    private val chrome = ChromePipeline(vk, colorFormat)
    private val titleBars = TitleBarPainter(vk)
    private val notices = NoticePainter(vk)
    private val cursors = CursorTextures(vk)
    private val viewProjection = FloatArray(16)
    private var headRotation = Mat4.identity()
    private var frame = 0L

    fun advance(head: Quaternion, dt: Float) {
        frame++
        pointerDirection?.let {
            scene.updateGrab(it)
            scene.updateResize(it)
        }
        val (yawDegrees, pitchDegrees, _) = head.toEulerDegrees()
        headYawRadians = Math.toRadians(yawDegrees.toDouble()).toFloat()
        scene.gazePitchRadians = Math.toRadians(pitchDegrees.toDouble()).toFloat()
        scene.advanceFollow(headYawRadians, dt)
        lastHit = pointerDirection?.let { scene.hitTest(it) }
        titleBars.keepOnly(scene.panels.mapTo(HashSet()) { it.id }, frame, ::forget)
        notices.release(frame, ::forget)
    }

    fun record(target: FrameTarget, head: Quaternion, warpPoses: (() -> Pair<Quaternion, Quaternion>)? = null) {
        headRotation = Mat4.rotation(head)
        val stereo = stereo
        if (stereo != null) stereo.record(target, head, ::drawScene, warpPoses) else recordMono(target, head)
    }

    private fun recordMono(target: FrameTarget, head: Quaternion) = stackPush().use { stack ->
        val near = 0.5f
        val top = near * tan(Math.toRadians(verticalFovDegrees / 2.0)).toFloat()
        val right = top * target.width / target.height
        val projection = Mat4.multiply(Mat4.VULKAN_CLIP, Mat4.frustum(-right, right, -top, top, near, 200f))
        val view = Mat4.invertRigid(Mat4.rotation(head))
        Mat4.multiply(projection, view, viewProjection)

        val clear = VkClearValue.calloc(stack)
        clear.color().float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 1f)
        val attachment = VkRenderingAttachmentInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
            .imageView(target.view).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
            .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE).clearValue(clear)
        val area = VkRect2D.calloc(stack)
        area.extent().set(target.width, target.height)
        vkCmdBeginRendering(target.commands, VkRenderingInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_RENDERING_INFO)
            .renderArea(area).layerCount(1).pColorAttachments(attachment))
        vkCmdSetViewport(target.commands, 0, VkViewport.calloc(1, stack).width(target.width.toFloat()).height(target.height.toFloat()).maxDepth(1f))
        vkCmdSetScissor(target.commands, 0, VkRect2D.calloc(1, stack).also { it[0].extent().set(target.width, target.height) })

        drawScene(target.commands, viewProjection)
        vkCmdEndRendering(target.commands)
    }

    /** The scene through [viewProjection], into whatever rendering is open, in StereoCompositor's order. */
    fun drawScene(cmd: VkCommandBuffer, viewProjection: FloatArray) {
        val focused = scene.focused
        val grabbed = scene.grabbed
        val windows = scene.windowsOpacity
        val showChrome = windows > 0f
        for (panel in scene.drawOrder()) {
            if (showChrome) drawFrame(cmd, panel, viewProjection, panel === focused, panel === grabbed, windows)
            if (!drawContent(cmd, panel, viewProjection, windows)) continue
            if (showChrome) drawTitleBar(cmd, panel, viewProjection, panel === focused || panel === grabbed, windows)
            if (showChrome && windows >= 1f) drawResizeHandle(cmd, panel, viewProjection)
        }
        scene.resizing?.let { drawResizePreview(cmd, it, viewProjection) }
        scene.dock?.let { drawContent(cmd, it, viewProjection, scene.dockOpacity) }
        scene.launcher?.let { drawContent(cmd, it, viewProjection, scene.launcherOpacity) }
        scene.popup?.let { drawContent(cmd, it, viewProjection, if (scene.popupShown) 1f else 0f) }
        drawCursor(cmd, viewProjection)
        notice?.let { drawNotice(cmd, it, viewProjection) }
    }

    /** Draws a panel's page, and the page in its top band; false if it has nothing to show yet. */
    private fun drawContent(cmd: VkCommandBuffer, panel: DesktopPanel, vp: FloatArray, alpha: Float): Boolean {
        if (!panel.texture.ready) return false
        if (alpha <= 0f) return true
        val width = panel.widthPx.toFloat()
        val height = panel.heightPx.toFloat()
        panels.bind(cmd)
        val layer = panel.layer
        val band = panel.bandPx.toFloat()
        if (layer == null || band <= 0f) {
            panels.draw(cmd, panel.texture, Mat4.multiply(vp, panelModel(panel, width, height)), width, height, panel.cornerRadiusPx, alpha)
            return true
        }
        panels.drawPart(cmd, panel.texture, Mat4.multiply(vp, partModel(panel, band, height - band)), width, height,
            band, height - band, 1f, panel.cornerRadiusPx, alpha)
        if (layer.texture.ready) {
            panels.drawPart(cmd, layer.texture, Mat4.multiply(vp, partModel(panel, 0f, band)), width, height,
                0f, band, band / layer.texture.heightPx, panel.cornerRadiusPx, alpha)
        }
        return true
    }

    /** A full-width band of the window, [top] and [partHeight] in its pixels from its top edge. */
    private fun partModel(panel: DesktopPanel, top: Float, partHeight: Float): FloatArray {
        val metresPerPx = panel.pose.widthMetres / panel.widthPx
        val basis = panelBasis(panel, 0f, -(top + partHeight / 2f - panel.heightPx / 2f) * metresPerPx, ChromeLayout.drawDistance(scene, panel))
        return Mat4.multiply(basis, Mat4.scale(panel.widthPx * metresPerPx, partHeight * metresPerPx, 1f))
    }

    /** Rotated to [panel]'s heading and elevation, then moved [x], [y] across it and [distance] out. */
    private fun panelBasis(panel: DesktopPanel, x: Float, y: Float, distance: Float): FloatArray {
        val pose = panel.pose
        val model = Mat4.multiply(Mat4.rotationY(pose.yawRadians), Mat4.rotationX(pose.pitchRadians))
        return Mat4.multiply(model, Mat4.translation(x, y, -distance))
    }

    /** A rectangle centred on the window, in its pixels. */
    private fun panelModel(panel: DesktopPanel, widthPx: Float, heightPx: Float): FloatArray {
        val metresPerPx = panel.pose.widthMetres / panel.widthPx
        val basis = panelBasis(panel, 0f, 0f, ChromeLayout.drawDistance(scene, panel))
        return Mat4.multiply(basis, Mat4.scale(widthPx * metresPerPx, heightPx * metresPerPx, 1f))
    }

    private fun drawFrame(cmd: VkCommandBuffer, panel: DesktopPanel, vp: FloatArray, focused: Boolean, grabbed: Boolean, opacity: Float) {
        val margin = ChromeLayout.frameMarginPx
        val width = panel.widthPx.toFloat()
        val height = panel.heightPx.toFloat()
        val model = panelModel(panel, width + 2f * margin, height + 2f * margin)
        val alpha = ChromeLayout.frameAlpha(focused, grabbed, opacity)
        val color = ChromeLayout.frameColor(grabbed, panel.follow.enabled)
        chrome.bind(cmd)
        chrome.frame(cmd, Mat4.multiply(vp, model), width, height, margin, panel.cornerRadiusPx,
            ChromeLayout.FRAME_GAP_PX, ChromeLayout.FRAME_THICKNESS_PX, color.r, color.g, color.b, alpha)
    }

    private fun drawTitleBar(cmd: VkCommandBuffer, panel: DesktopPanel, vp: FloatArray, active: Boolean, opacity: Float) {
        val hovered = lastHit?.takeIf { it.panel === panel }?.part
        val texture = titleBars.texture(panel, hovered, frame, ::forget)
        val bar = TitleBar.bar(panel.widthPx, panel.heightPx)
        val metresPerPx = panel.pose.widthMetres / panel.widthPx
        val basis = panelBasis(panel,
            (bar.left + bar.width / 2f - panel.widthPx / 2f) * metresPerPx,
            -(bar.top + bar.height / 2f - panel.heightPx / 2f) * metresPerPx,
            ChromeLayout.drawDistance(scene, panel))
        val model = Mat4.multiply(basis, Mat4.scale(bar.width * metresPerPx, bar.height * metresPerPx, 1f))
        panels.bind(cmd)
        panels.draw(cmd, texture, Mat4.multiply(vp, model), bar.width, bar.height, 0f, ChromeLayout.titleBarAlpha(active, opacity))
    }

    private fun drawResizeHandle(cmd: VkCommandBuffer, panel: DesktopPanel, vp: FloatArray) {
        val handle = ChromeLayout.handle(scene, panel, lastHit, pointerDirection) ?: return
        val corner = handle.corner
        val width = panel.widthPx.toFloat()
        val height = panel.heightPx.toFloat()
        val metresPerPx = panel.pose.widthMetres / width
        val quad = ChromeLayout.handleQuad(width, height, panel.cornerRadiusPx, corner)
        val basis = panelBasis(panel, (quad.centreX - width / 2f) * metresPerPx, -(quad.centreY - height / 2f) * metresPerPx,
            ChromeLayout.drawDistance(scene, panel))
        val size = quad.sizePx * metresPerPx
        val model = Mat4.multiply(basis, Mat4.scale(size * -corner.sx, size * corner.sy, 1f))
        chrome.bind(cmd)
        chrome.arc(cmd, Mat4.multiply(vp, model), quad.outsidePx, quad.insidePx, panel.cornerRadiusPx,
            ResizeHandles.GAP_PX, ResizeHandles.THICKNESS_PX, if (handle.onHandle) 1f else ChromeLayout.HANDLE_NEAR_ALPHA)
    }

    private fun drawResizePreview(cmd: VkCommandBuffer, drag: ResizeDrag<DesktopPanel>, vp: FloatArray) {
        val panel = drag.panel
        val width = panel.widthPx.toFloat()
        val height = panel.heightPx.toFloat()
        val metresPerPx = panel.pose.widthMetres / width
        val fixed = drag.corner.opposite
        val fixedPoint = fixed.pointPx(width, height)
        val centreX = fixedPoint.x - fixed.sx * drag.widthPx / 2f
        val centreY = fixedPoint.y + fixed.sy * drag.heightPx / 2f
        val margin = ChromeLayout.PREVIEW_THICKNESS_PX + 1f
        val basis = panelBasis(panel, (centreX - width / 2f) * metresPerPx, -(centreY - height / 2f) * metresPerPx,
            ChromeLayout.drawDistance(scene, panel) * (1f - ChromeLayout.CURSOR_STANDOFF_FRACTION))
        val model = Mat4.multiply(basis, Mat4.scale((drag.widthPx + 2f * margin) * metresPerPx, (drag.heightPx + 2f * margin) * metresPerPx, 1f))
        chrome.bind(cmd)
        chrome.frame(cmd, Mat4.multiply(vp, model), drag.widthPx.toFloat(), drag.heightPx.toFloat(), margin,
            panel.cornerRadiusPx, 0f, ChromeLayout.PREVIEW_THICKNESS_PX, 1f, 1f, 1f, 0.9f)
    }

    /** StereoCompositor's placeOnPanel and placeBetweenPanels: on the window hit, or at the first window's distance. */
    private fun drawCursor(cmd: VkCommandBuffer, vp: FloatArray) {
        val hit = lastHit
        val placed: Pair<FloatArray, Float> = if (hit != null) {
            val panel = hit.panel
            val pose = panel.pose
            val u = hit.hit.x / panel.widthPx - 0.5f
            val v = 0.5f - hit.hit.y / panel.heightPx
            panelBasis(panel, u * pose.widthMetres, v * pose.heightMetres,
                ChromeLayout.drawDistance(scene, panel) * (1f - ChromeLayout.CURSOR_STANDOFF_FRACTION)) to pose.widthMetres / panel.widthPx
        } else {
            val direction = pointerDirection ?: return
            val length = direction.length
            if (length < 1e-6f) return
            val d = direction * (1f / length)
            val reference = scene.panels.firstOrNull() ?: return
            val distance = reference.pose.distanceMetres
            val basis = Mat4.multiply(
                Mat4.multiply(Mat4.rotationY(atan2(-d.x, -d.z)), Mat4.rotationX(asin(d.y.coerceIn(-1f, 1f)))),
                Mat4.translation(0f, 0f, -distance),
            )
            basis to reference.pose.widthMetres / reference.widthPx
        }
        val shape = hit?.takeIf { it.part == HitPart.CONTENT }?.panel?.cursorShape ?: CursorShape.DEFAULT
        val arrow = if (cursorStyle == CursorStyle.ARROW) cursors.arrow(shape) else null
        if (cursorStyle == CursorStyle.ARROW && shape == CursorShape.NONE) return
        val picture = arrow ?: cursors.ring
        val size = (if (arrow != null) ChromeLayout.ARROW_PX else ChromeLayout.RING_PX) * placed.second
        val model = Mat4.multiply(
            Mat4.multiply(placed.first, Mat4.scale(size, size, 1f)),
            Mat4.translation(0.5f - picture.hotspotX, picture.hotspotY - 0.5f, 0f),
        )
        val texture = picture.texture
        panels.bind(cmd)
        panels.draw(cmd, texture, Mat4.multiply(vp, model), texture.widthPx.toFloat(), texture.heightPx.toFloat(), 0f, 1f)
    }

    private fun drawNotice(cmd: VkCommandBuffer, notice: Notice, vp: FloatArray) {
        val distance = scene.panels.firstOrNull()?.pose?.distanceMetres ?: SIZE_REFERENCE_METRES
        val metresPerPx = NoticeLayout.metresPerPx(distance)
        fun card(centreXPx: Float, centreYPx: Float, widthPx: Float, heightPx: Float): FloatArray {
            val local = Mat4.multiply(
                Mat4.translation((centreXPx - NoticeLayout.WIDTH_PX / 2f) * metresPerPx, -(centreYPx - NoticeLayout.HEIGHT_PX / 2f) * metresPerPx, -distance),
                Mat4.scale(widthPx * metresPerPx, heightPx * metresPerPx, 1f),
            )
            return Mat4.multiply(vp, Mat4.multiply(headRotation, local))
        }
        panels.bind(cmd)
        panels.draw(cmd, notices.texture(notice.text, frame, ::forget),
            card(NoticeLayout.WIDTH_PX / 2f, NoticeLayout.HEIGHT_PX / 2f, NoticeLayout.WIDTH_PX, NoticeLayout.HEIGHT_PX),
            NoticeLayout.WIDTH_PX, NoticeLayout.HEIGHT_PX, 0f, 1f)
        chrome.bind(cmd)
        val track = NoticeLayout.TRACK
        chrome.solid(cmd, card(NoticeLayout.WIDTH_PX / 2f, NoticeLayout.BAR_MID_Y_PX, NoticeLayout.BAR_WIDTH_PX, NoticeLayout.BAR_HEIGHT_PX),
            track[0], track[1], track[2], track[3])
        val filled = NoticeLayout.filledPx(notice)
        if (filled > 0f) {
            val fill = NoticeLayout.FILL
            chrome.solid(cmd, card(NoticeLayout.BAR_INSET_PX + filled / 2f, NoticeLayout.BAR_MID_Y_PX, filled, NoticeLayout.BAR_HEIGHT_PX),
                fill[0], fill[1], fill[2], fill[3])
        }
    }

    fun forget(texture: TextureSource) = texture.views.forEach(panels::forget)

    override fun close() {
        stereo?.close()
        titleBars.close()
        notices.close()
        cursors.close()
        chrome.close()
        panels.close()
    }

    private companion object {
        const val SIZE_REFERENCE_METRES = 4f
    }
}
