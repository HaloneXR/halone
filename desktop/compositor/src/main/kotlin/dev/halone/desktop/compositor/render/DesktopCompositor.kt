package dev.halone.desktop.compositor.render

import dev.halone.compositor.ChromeLayout
import dev.halone.compositor.PanelPose
import dev.halone.compositor.PanelScene
import dev.halone.compositor.PanelSceneHit
import dev.halone.compositor.ResizeDrag
import dev.halone.compositor.ResizeHandles
import dev.halone.compositor.ScenePanel
import dev.halone.compositor.TitleBar
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

class DesktopPanel(
    id: String,
    override var widthPx: Int,
    override var heightPx: Int,
    var texture: TextureSource,
    pose: PanelPose = PanelPose(),
) : ScenePanel(id, pose) {
    var cornerRadiusPx: Float = 24f
    var title: String = id
    var tag: String? = null
}

/**
 * The desktop counterpart of StereoCompositor: steps the scene each frame as
 * it does, then draws every window with its chrome (frame, content, title bar,
 * resize handle), the resize preview and the cursor, with ChromeLayout's
 * shared geometry.
 */
class DesktopCompositor(private val vk: VulkanContext, colorFormat: Int, val verticalFovDegrees: Float) : AutoCloseable {
    var stereo: StereoRenderer? = null
    var cursorTexture: TextureSource? = null

    /** Where the pointer aims, set by the input every frame; null hides the cursor. */
    var pointerDirection: Vec3? = null

    var lastHit: PanelSceneHit<DesktopPanel>? = null
        private set

    var headYawRadians = 0f
        private set

    val scene = PanelScene<DesktopPanel>()
    private val panels = PanelPipeline(vk, colorFormat)
    private val chrome = ChromePipeline(vk, colorFormat)
    private val titleBars = TitleBarPainter(vk)
    private val viewProjection = FloatArray(16)
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
    }

    fun record(target: FrameTarget, head: Quaternion, warpPoses: (() -> Pair<Quaternion, Quaternion>)? = null) {
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
            if (!panel.texture.ready) continue
            panels.bind(cmd)
            panels.draw(cmd, panel.texture, Mat4.multiply(viewProjection, panelModel(panel, panel.widthPx.toFloat(), panel.heightPx.toFloat())),
                panel.widthPx.toFloat(), panel.heightPx.toFloat(), panel.cornerRadiusPx, windows)
            if (showChrome) drawTitleBar(cmd, panel, viewProjection, panel === focused || panel === grabbed, windows)
            if (showChrome && windows >= 1f) drawResizeHandle(cmd, panel, viewProjection)
        }
        scene.resizing?.let { drawResizePreview(cmd, it, viewProjection) }
        drawCursor(cmd, viewProjection)
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
        val ring = cursorTexture ?: return
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
        val size = ChromeLayout.RING_PX * placed.second
        val model = Mat4.multiply(placed.first, Mat4.scale(size, size, 1f))
        panels.bind(cmd)
        panels.draw(cmd, ring, Mat4.multiply(vp, model), ring.widthPx.toFloat(), ring.heightPx.toFloat(), 0f, 1f)
    }

    fun forget(texture: TextureSource) = panels.forget(texture.view)

    override fun close() {
        stereo?.close()
        titleBars.close()
        chrome.close()
        panels.close()
    }
}
