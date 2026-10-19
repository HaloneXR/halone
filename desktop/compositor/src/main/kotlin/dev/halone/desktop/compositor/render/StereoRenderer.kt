package dev.halone.desktop.compositor.render

import dev.halone.compositor.DistortionMesh
import dev.halone.compositor.Reprojection
import org.lwjgl.vulkan.VkPushConstantRange
import dev.halone.desktop.compositor.math.Mat4
import dev.halone.desktop.compositor.vk.FrameLoop
import dev.halone.desktop.compositor.vk.FrameTarget
import dev.halone.desktop.compositor.vk.HostBuffer
import dev.halone.desktop.compositor.vk.Shaders
import dev.halone.desktop.compositor.vk.Stage
import dev.halone.desktop.compositor.vk.StaticTexture
import dev.halone.desktop.compositor.vk.VulkanContext
import dev.halone.desktop.compositor.vk.vkCheck
import dev.halone.glasses.api.DeviceCalibration
import dev.halone.glasses.api.Frustum
import dev.halone.glasses.api.Optics
import dev.halone.glasses.math.Quaternion
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkClearValue
import org.lwjgl.vulkan.VkCommandBuffer
import org.lwjgl.vulkan.VkDescriptorImageInfo
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo
import org.lwjgl.vulkan.VkDescriptorPoolSize
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfo
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo
import org.lwjgl.vulkan.VkRect2D
import org.lwjgl.vulkan.VkRenderingAttachmentInfo
import org.lwjgl.vulkan.VkRenderingInfo
import org.lwjgl.vulkan.VkSamplerCreateInfo
import org.lwjgl.vulkan.VkVertexInputAttributeDescription
import org.lwjgl.vulkan.VkVertexInputBindingDescription
import org.lwjgl.vulkan.VkViewport
import org.lwjgl.vulkan.VkWriteDescriptorSet
import kotlin.math.tan

/**
 * Side-by-side stereo with lens correction, as StereoCompositor does it: each
 * eye is drawn into an overfilled offscreen image with its calibrated frustum
 * and pose, then warped onto its half of the frame by the factory grid.
 * Reprojection is not wired in yet.
 */
class StereoRenderer(
    private val vk: VulkanContext,
    colorFormat: Int,
    calibration: DeviceCalibration?,
    optics: Optics,
    frameWidth: Int,
    private val frameHeight: Int,
    reprojection: Boolean = true,
    private val inset: Int = 0,
) : AutoCloseable {
    private val eyeRotations = Array(2) { Quaternion.IDENTITY }
    private val tanScale = Array(2) { FloatArray(2) }
    private val tanOffset = Array(2) { FloatArray(2) }
    private val rotationStart = FloatArray(9)
    private val rotationEnd = FloatArray(9)
    private val eyeWidth = frameWidth / 2
    val overfill: Float
    val lensCorrected: Boolean
    val textureWidth: Int
    val textureHeight: Int
    val visibleVerticalDegrees: Float
    private val projections = Array(2) { FloatArray(16) }
    private val eyePoses = Array(2) { Mat4.identity() }
    private val images = LongArray(2)
    private val memories = LongArray(2)
    private val views = LongArray(2)
    private val vertexBuffers: Array<HostBuffer>
    private val indexBuffers: Array<HostBuffer>
    private val indexCounts = IntArray(2)

    private val setLayout: Long
    private val layout: Long
    private val pipeline: Long
    private val pool: Long
    private val sampler: Long
    private val sets = LongArray(2)

    init {
        val left = calibration?.left
        val right = calibration?.right
        val leftGrid = left?.distortion
        val rightGrid = right?.distortion
        lensCorrected = leftGrid != null && rightGrid != null
        val eyeAspect = eyeWidth.toFloat() / frameHeight
        fun frustumFor(index: Int): Frustum =
            (if (index == 0) left else right)?.frustum ?: run {
                val halfV = tan(Math.toRadians(optics.verticalFovDegrees / 2.0)).toFloat()
                Frustum(-halfV * eyeAspect, halfV * eyeAspect, -halfV, halfV)
            }
        val lensNeeded = if (lensCorrected) {
            maxOf(
                DistortionMesh.requiredOverfill(leftGrid!!, left!!.widthPx, left.heightPx),
                DistortionMesh.requiredOverfill(rightGrid!!, right!!.widthPx, right.heightPx),
            ) + OVERFILL_MARGIN
        } else 0f
        val warpNeeded = if (reprojection) {
            Reprojection.overfillFor(frustumFor(0), Math.toRadians(REPROJECTION_RATE_DEGREES_PER_SECOND).toFloat(), SCANOUT_SECONDS)
        } else 0f
        overfill = maxOf(lensNeeded, warpNeeded)
        textureWidth = (eyeWidth * (1f + 2f * overfill)).toInt()
        textureHeight = (frameHeight * (1f + 2f * overfill)).toInt()

        visibleVerticalDegrees = frustumFor(0).verticalDegrees
        for (index in 0 until 2) {
            val f = frustumFor(index).expandedBy(overfill)
            Mat4.multiply(Mat4.VULKAN_CLIP, Mat4.frustum(f.left * NEAR, f.right * NEAR, f.bottom * NEAR, f.top * NEAR, NEAR, FAR), projections[index])
            Reprojection.tangentScale(f, tanScale[index])
            Reprojection.tangentOffset(f, tanOffset[index])
        }

        if (left != null && right != null) {
            val headFromImu = calibration.headFromImu
            val imuFromHead = headFromImu.conjugate()
            val midpoint = (left.positionFromImu + right.positionFromImu) * 0.5f
            for (index in 0 until 2) {
                val eye = if (index == 0) left else right
                val offset = imuFromHead.rotate(eye.positionFromImu - midpoint)
                eyeRotations[index] = eye.rotationFromHead(headFromImu)
                Mat4.multiply(Mat4.translation(offset.x, offset.y, offset.z), Mat4.rotation(eyeRotations[index]), eyePoses[index])
            }
        } else {
            for (index in 0 until 2) {
                val sign = if (index == 0) -1f else 1f
                Mat4.translation(sign * optics.defaultIpdMm / 2000f, 0f, 0f).copyInto(eyePoses[index])
            }
        }

        val meshes = (0 until 2).map { index ->
            val eye = if (index == 0) left else right
            val grid = eye?.distortion
            if (lensCorrected && eye != null && grid != null) DistortionMesh.build(grid, eye.widthPx, eye.heightPx, overfill)
            else DistortionMesh.identity(overfill)
        }
        vertexBuffers = Array(2) { index ->
            val mesh = meshes[index]
            val interleaved = FloatArray(mesh.vertexCount * 4)
            for (v in 0 until mesh.vertexCount) {
                interleaved[v * 4] = mesh.positions[v * 2]
                interleaved[v * 4 + 1] = mesh.positions[v * 2 + 1]
                interleaved[v * 4 + 2] = mesh.texCoords[v * 2]
                interleaved[v * 4 + 3] = mesh.texCoords[v * 2 + 1]
            }
            HostBuffer.of(vk, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, interleaved)
        }
        indexBuffers = Array(2) { index ->
            indexCounts[index] = meshes[index].indexCount
            HostBuffer.of(vk, VK_BUFFER_USAGE_INDEX_BUFFER_BIT, meshes[index].indices)
        }

        for (index in 0 until 2) {
            val (image, memory) = StaticTexture.createImage(vk, textureWidth, textureHeight, VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT or VK_IMAGE_USAGE_SAMPLED_BIT)
            images[index] = image
            memories[index] = memory
            views[index] = StaticTexture.createView(vk, image)
        }

        stackPush().use { stack ->
            val binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                .binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT)
            val pHandle = stack.mallocLong(1)
            vkCheck(vkCreateDescriptorSetLayout(vk.device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(binding), null, pHandle), "distortion set layout")
            setLayout = pHandle.get(0)
            val push = VkPushConstantRange.calloc(1, stack).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(PUSH_BYTES)
            vkCheck(vkCreatePipelineLayout(vk.device, VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO).pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push), null, pHandle), "distortion layout")
            layout = pHandle.get(0)

            val vertex = Shaders.module(vk, "distortion.vert", Stage.VERTEX, VERTEX)
            val fragment = Shaders.module(vk, "distortion.frag", Stage.FRAGMENT, FRAGMENT)
            val stages = VkPipelineShaderStageCreateInfo.calloc(2, stack)
            stages[0].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertex).pName(stack.UTF8("main"))
            stages[1].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragment).pName(stack.UTF8("main"))
            val bindings = VkVertexInputBindingDescription.calloc(1, stack).binding(0).stride(16).inputRate(VK_VERTEX_INPUT_RATE_VERTEX)
            val attributes = VkVertexInputAttributeDescription.calloc(2, stack)
            attributes[0].location(0).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0)
            attributes[1].location(1).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(8)
            val info = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                .pNext(VkPipelineRenderingCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
                    .colorAttachmentCount(1).pColorAttachmentFormats(stack.ints(colorFormat)))
                .pStages(stages)
                .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                    .pVertexBindingDescriptions(bindings).pVertexAttributeDescriptions(attributes))
                .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST))
                .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1).scissorCount(1))
                .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).lineWidth(1f))
                .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .pAttachments(VkPipelineColorBlendAttachmentState.calloc(1, stack).colorWriteMask(0xF)))
                .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)))
                .layout(layout)
            vkCheck(vkCreateGraphicsPipelines(vk.device, VK_NULL_HANDLE, info, null, pHandle), "distortion pipeline")
            pipeline = pHandle.get(0)
            vkDestroyShaderModule(vk.device, vertex, null)
            vkDestroyShaderModule(vk.device, fragment, null)

            vkCheck(vkCreateDescriptorPool(vk.device, VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO).maxSets(2)
                .pPoolSizes(VkDescriptorPoolSize.calloc(1, stack).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2)),
                null, pHandle), "distortion pool")
            pool = pHandle.get(0)
            vkCheck(vkCreateSampler(vk.device, VkSamplerCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE), null, pHandle), "distortion sampler")
            sampler = pHandle.get(0)
            for (index in 0 until 2) {
                val pSet = stack.mallocLong(1)
                vkCheck(vkAllocateDescriptorSets(vk.device, VkDescriptorSetAllocateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO).descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), pSet), "distortion set")
                sets[index] = pSet.get(0)
                val image = VkDescriptorImageInfo.calloc(1, stack).sampler(sampler).imageView(views[index]).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                vkUpdateDescriptorSets(vk.device, VkWriteDescriptorSet.calloc(1, stack).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                    .dstSet(sets[index]).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(image), null)
            }
        }
    }

    fun describe() = "%s, %.1f%% overfill, %dx%d per eye offscreen, visible vertical %.2f°".format(
        if (lensCorrected) "lens correction on" else "lens correction off", overfill * 100, textureWidth, textureHeight, visibleVerticalDegrees)

    /**
     * Draws both eyes with [drawScene] at [head], then warps them onto [target],
     * which must be [frameHeight] high and two eyes wide, plus [inset] on every side. [warpPoses], when given,
     * is asked for the first and last rows' poses only after the eyes are recorded.
     */
    fun record(
        target: FrameTarget,
        head: Quaternion,
        drawScene: (VkCommandBuffer, FloatArray, Int) -> Unit,
        warpPoses: (() -> Pair<Quaternion, Quaternion>)? = null,
    ) = stackPush().use { stack ->
        val cmd = target.commands
        val headMatrix = Mat4.rotation(head)
        val clear = VkClearValue.calloc(stack)
        clear.color().float32(0, 0f).float32(1, 0f).float32(2, 0f).float32(3, 1f)

        for (index in 0 until 2) {
            FrameLoop.transition(cmd, images[index], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, 0,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
            begin(cmd, views[index], textureWidth, textureHeight, clear, 0, 0, textureWidth, textureHeight)
            val view = Mat4.invertRigid(Mat4.multiply(headMatrix, eyePoses[index]))
            drawScene(cmd, Mat4.multiply(projections[index], view), index)
            vkCmdEndRendering(cmd)
            FrameLoop.transition(cmd, images[index], VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)
        }

        val warp = warpPoses?.invoke()
        begin(cmd, target.view, target.width, target.height, clear, 0, 0, target.width, target.height)
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline)
        val half = eyeWidth
        for (index in 0 until 2) {
            val push = stack.calloc(PUSH_BYTES)
            if (warp != null) {
                Reprojection.rotation(head, warp.first, eyeRotations[index], rotationStart)
                Reprojection.rotation(head, warp.second, eyeRotations[index], rotationEnd)
                for (matrix in listOf(rotationStart, rotationEnd)) {
                    for (column in 0 until 3) push.putFloat(matrix[column * 3]).putFloat(matrix[column * 3 + 1]).putFloat(matrix[column * 3 + 2]).putFloat(0f)
                }
            } else {
                push.position(96)
            }
            push.putFloat(tanScale[index][0]).putFloat(tanScale[index][1]).putFloat(tanOffset[index][0]).putFloat(tanOffset[index][1])
            push.putFloat(if (warp != null) 1f else 0f)
            push.rewind()
            vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_FRAGMENT_BIT, 0, push)
            vkCmdSetViewport(cmd, 0, VkViewport.calloc(1, stack).x((inset + index * half).toFloat()).y(inset.toFloat())
                .width(half.toFloat()).height(frameHeight.toFloat()).maxDepth(1f))
            vkCmdSetScissor(cmd, 0, VkRect2D.calloc(1, stack).also { it[0].offset().set(inset + index * half, inset); it[0].extent().set(half, frameHeight) })
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, stack.longs(sets[index]), null)
            vkCmdBindVertexBuffers(cmd, 0, stack.longs(vertexBuffers[index].handle), stack.longs(0))
            vkCmdBindIndexBuffer(cmd, indexBuffers[index].handle, 0, VK_INDEX_TYPE_UINT16)
            vkCmdDrawIndexed(cmd, indexCounts[index], 1, 0, 0, 0)
        }
        vkCmdEndRendering(cmd)
    }

    private fun begin(cmd: VkCommandBuffer, view: Long, width: Int, height: Int, clear: VkClearValue, x: Int, y: Int, w: Int, h: Int) = stackPush().use { stack ->
        val attachment = VkRenderingAttachmentInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
            .imageView(view).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
            .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE).clearValue(clear)
        val area = VkRect2D.calloc(stack)
        area.extent().set(width, height)
        vkCmdBeginRendering(cmd, VkRenderingInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_RENDERING_INFO)
            .renderArea(area).layerCount(1).pColorAttachments(attachment))
        vkCmdSetViewport(cmd, 0, VkViewport.calloc(1, stack).x(x.toFloat()).y(y.toFloat()).width(w.toFloat()).height(h.toFloat()).maxDepth(1f))
        vkCmdSetScissor(cmd, 0, VkRect2D.calloc(1, stack).also { it[0].offset().set(x, y); it[0].extent().set(w, h) })
    }

    override fun close() {
        vkDestroySampler(vk.device, sampler, null)
        vkDestroyDescriptorPool(vk.device, pool, null)
        vkDestroyPipeline(vk.device, pipeline, null)
        vkDestroyPipelineLayout(vk.device, layout, null)
        vkDestroyDescriptorSetLayout(vk.device, setLayout, null)
        for (index in 0 until 2) {
            vkDestroyImageView(vk.device, views[index], null)
            vkDestroyImage(vk.device, images[index], null)
            vkFreeMemory(vk.device, memories[index], null)
        }
        vertexBuffers.forEach { it.close() }
        indexBuffers.forEach { it.close() }
    }

    private companion object {
        const val NEAR = 0.5f
        const val FAR = 200f
        const val OVERFILL_MARGIN = 0.005f
        const val REPROJECTION_RATE_DEGREES_PER_SECOND = 45.0
        const val SCANOUT_SECONDS = 1f / 60f
        const val PUSH_BYTES = 128

        // The mesh, and the reprojection's tangent mapping, are in OpenGL's
        // conventions: NDC with y up, and v counted from the bottom row of the
        // rendered image. Vulkan has both the other way up, so the warp runs in
        // OpenGL's terms and only the final lookup is flipped.
        val VERTEX = """
            #version 450
            layout(location = 0) in vec2 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            layout(location = 0) out vec2 vTexCoord;
            layout(location = 1) out float vRow;
            void main() {
                vTexCoord = aTexCoord;
                vRow = 0.5 - 0.5 * aPosition.y;
                gl_Position = vec4(aPosition.x, -aPosition.y, 0.0, 1.0);
            }
        """.trimIndent()

        val FRAGMENT = """
            #version 450
            layout(set = 0, binding = 0) uniform sampler2D eye;
            layout(push_constant) uniform Push {
                mat3 rotationStart;
                mat3 rotationEnd;
                vec2 tanScale;
                vec2 tanOffset;
                float reproject;
            } pc;
            layout(location = 0) in vec2 vTexCoord;
            layout(location = 1) in float vRow;
            layout(location = 0) out vec4 color;
            vec2 carryBack(vec2 uv, mat3 rotation) {
                vec3 ray = vec3(uv * pc.tanScale + pc.tanOffset, -1.0);
                vec3 turned = rotation * ray;
                float depth = max(-turned.z, 0.001);
                return (turned.xy / depth - pc.tanOffset) / pc.tanScale;
            }
            void main() {
                vec2 uv = vTexCoord;
                if (pc.reproject > 0.5) uv = mix(carryBack(uv, pc.rotationStart), carryBack(uv, pc.rotationEnd), vRow);
                color = texture(eye, vec2(uv.x, 1.0 - uv.y));
            }
        """.trimIndent()
    }
}
