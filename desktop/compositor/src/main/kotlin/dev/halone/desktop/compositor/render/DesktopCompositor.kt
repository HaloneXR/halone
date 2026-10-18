package dev.halone.desktop.compositor.render

import dev.halone.compositor.PanelPose
import dev.halone.compositor.PanelScene
import dev.halone.compositor.ScenePanel
import dev.halone.desktop.compositor.math.Mat4
import dev.halone.desktop.compositor.vk.FrameTarget
import dev.halone.desktop.compositor.vk.TextureSource
import dev.halone.desktop.compositor.vk.VulkanContext
import dev.halone.glasses.math.Quaternion
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkClearValue
import org.lwjgl.vulkan.VkRect2D
import org.lwjgl.vulkan.VkRenderingAttachmentInfo
import org.lwjgl.vulkan.VkRenderingInfo
import org.lwjgl.vulkan.VkViewport
import kotlin.math.tan

class DesktopPanel(
    id: String,
    override val widthPx: Int,
    override val heightPx: Int,
    val texture: TextureSource,
    pose: PanelPose = PanelPose(),
) : ScenePanel(id, pose) {
    var cornerRadiusPx: Float = 24f
}

/**
 * The desktop counterpart of StereoCompositor, mono for now: steps the scene's
 * follow, then draws its panels in draw order with the same model matrices.
 */
class DesktopCompositor(private val vk: VulkanContext, colorFormat: Int, val verticalFovDegrees: Float) : AutoCloseable {
    val scene = PanelScene<DesktopPanel>()
    private val panels = PanelPipeline(vk, colorFormat)
    private val viewProjection = FloatArray(16)

    fun advance(head: Quaternion, dt: Float) {
        val (yawDegrees, _, _) = head.toEulerDegrees()
        scene.advanceFollow(Math.toRadians(yawDegrees.toDouble()).toFloat(), dt)
    }

    fun record(target: FrameTarget, head: Quaternion) = stackPush().use { stack ->
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

        panels.bind(target.commands)
        for (panel in scene.drawOrder()) {
            val pose = panel.pose
            var model = Mat4.multiply(Mat4.rotationY(pose.yawRadians), Mat4.rotationX(pose.pitchRadians))
            model = Mat4.multiply(model, Mat4.translation(0f, 0f, -pose.distanceMetres))
            model = Mat4.multiply(model, Mat4.scale(pose.widthMetres, pose.heightMetres, 1f))
            val mvp = Mat4.multiply(viewProjection, model)
            panels.draw(target.commands, panel.texture, mvp, panel.widthPx.toFloat(), panel.heightPx.toFloat(), panel.cornerRadiusPx, 1f)
        }
        vkCmdEndRendering(target.commands)
    }

    fun forget(texture: TextureSource) = panels.forget(texture.view)

    override fun close() = panels.close()
}
