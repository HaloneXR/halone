package dev.halone.desktop.compositor.render

import dev.halone.compositor.StereoLayout
import dev.halone.compositor.StereoRegion
import dev.halone.desktop.compositor.vk.Shaders
import dev.halone.desktop.compositor.vk.Stage
import dev.halone.desktop.compositor.vk.TextureSource
import dev.halone.desktop.compositor.vk.VulkanContext
import dev.halone.desktop.compositor.vk.vkCheck
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.VK13.*
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
import org.lwjgl.vulkan.VkPushConstantRange
import org.lwjgl.vulkan.VkSamplerCreateInfo
import org.lwjgl.vulkan.VkWriteDescriptorSet

/**
 * Draws a panel's content as a rounded rectangle: the desktop counterpart of
 * GlQuads, with the same rounded-rect coverage, premultiplied output and stereo
 * regions. Scanout shear comes later.
 */
class PanelPipeline(private val vk: VulkanContext, colorFormat: Int) : AutoCloseable {
    private val setLayout: Long
    private val layout: Long
    private val pipeline: Long
    private val pool: Long
    private val sampler: Long
    private val sets = HashMap<Long, Long>()

    init {
        stackPush().use { stack ->
            val binding = VkDescriptorSetLayoutBinding.calloc(1, stack)
                .binding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT)
            val pLayout = stack.mallocLong(1)
            vkCheck(vkCreateDescriptorSetLayout(vk.device, VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO).pBindings(binding), null, pLayout), "descriptor set layout")
            setLayout = pLayout.get(0)

            val push = VkPushConstantRange.calloc(1, stack)
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(PUSH_BYTES)
            vkCheck(vkCreatePipelineLayout(vk.device, VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                .pSetLayouts(stack.longs(setLayout)).pPushConstantRanges(push), null, pLayout), "pipeline layout")
            layout = pLayout.get(0)

            val vertex = Shaders.module(vk, "panel.vert", Stage.VERTEX, VERTEX)
            val fragment = Shaders.module(vk, "panel.frag", Stage.FRAGMENT, FRAGMENT)
            val stages = VkPipelineShaderStageCreateInfo.calloc(2, stack)
            stages[0].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertex).pName(stack.UTF8("main"))
            stages[1].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragment).pName(stack.UTF8("main"))

            val blend = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                .blendEnable(true)
                .srcColorBlendFactor(VK_BLEND_FACTOR_ONE).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).colorBlendOp(VK_BLEND_OP_ADD)
                .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).alphaBlendOp(VK_BLEND_OP_ADD)
                .colorWriteMask(0xF)
            val rendering = VkPipelineRenderingCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
                .colorAttachmentCount(1).pColorAttachmentFormats(stack.ints(colorFormat))
            val info = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                .pNext(rendering)
                .pStages(stages)
                .pVertexInputState(VkPipelineVertexInputStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO))
                .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                    .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP))
                .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                    .viewportCount(1).scissorCount(1))
                .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                    .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE).lineWidth(1f))
                .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                    .pAttachments(blend))
                .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)))
                .layout(layout)
            val pPipeline = stack.mallocLong(1)
            vkCheck(vkCreateGraphicsPipelines(vk.device, VK_NULL_HANDLE, info, null, pPipeline), "panel pipeline")
            pipeline = pPipeline.get(0)
            vkDestroyShaderModule(vk.device, vertex, null)
            vkDestroyShaderModule(vk.device, fragment, null)

            val sizes = VkDescriptorPoolSize.calloc(1, stack).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(MAX_TEXTURES)
            vkCheck(vkCreateDescriptorPool(vk.device, VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT)
                .maxSets(MAX_TEXTURES).pPoolSizes(sizes), null, pLayout), "descriptor pool")
            pool = pLayout.get(0)

            vkCheck(vkCreateSampler(vk.device, VkSamplerCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE).addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                .mipmapMode(VK_SAMPLER_MIPMAP_MODE_LINEAR).maxLod(VK_LOD_CLAMP_NONE), null, pLayout), "sampler")
            sampler = pLayout.get(0)
        }
    }

    private fun descriptorFor(view: Long): Long = sets.getOrPut(view) {
        stackPush().use { stack ->
            val pSet = stack.mallocLong(1)
            vkCheck(vkAllocateDescriptorSets(vk.device, VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(pool).pSetLayouts(stack.longs(setLayout)), pSet), "descriptor set")
            val image = VkDescriptorImageInfo.calloc(1, stack)
                .sampler(sampler).imageView(view).imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
            val write = VkWriteDescriptorSet.calloc(1, stack).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(pSet.get(0)).dstBinding(0).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1).pImageInfo(image)
            vkUpdateDescriptorSets(vk.device, write, null)
            pSet.get(0)
        }
    }

    /** Forgets a view's descriptor, for a texture that is going away. */
    fun forget(view: Long) {
        sets.remove(view)?.let { vkFreeDescriptorSets(vk.device, pool, it) }
    }

    fun bind(cmd: VkCommandBuffer) = vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline)

    /**
     * [mvp] maps the unit quad (-0.5..0.5, y up) onto the panel, already in Vulkan clip space.
     * Inside [stereo], [eye] (0 left) sees only its own half of the region, stretched over all of it.
     */
    fun draw(
        cmd: VkCommandBuffer, texture: TextureSource, mvp: FloatArray, widthPx: Float, heightPx: Float, radiusPx: Float, alpha: Float,
        stereo: StereoRegion? = null, eye: Int = 0,
    ) = drawPart(cmd, texture, mvp, widthPx, heightPx, 0f, heightPx, 1f, radiusPx, alpha, stereo, eye)

    /**
     * A full-width band of a window, [topPx] and [partHeightPx] in its pixels,
     * [mvp] mapping the unit quad onto the band; the texture's top [cropV] of
     * its height fills it. The window's rounded corners still cut it.
     */
    fun drawPart(
        cmd: VkCommandBuffer, texture: TextureSource, mvp: FloatArray, widthPx: Float, heightPx: Float,
        topPx: Float, partHeightPx: Float, cropV: Float, radiusPx: Float, alpha: Float,
        stereo: StereoRegion? = null, eye: Int = 0,
    ) =
        stackPush().use { stack ->
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, layout, 0, stack.longs(descriptorFor(texture.view)), null)
            val push = stack.malloc(PUSH_BYTES)
            mvp.forEach { push.putFloat(it) }
            push.putFloat(0f).putFloat(topPx).putFloat(widthPx).putFloat(partHeightPx)
            push.putFloat(widthPx).putFloat(heightPx).putFloat(radiusPx).putFloat(alpha)
            push.putFloat(1f).putFloat(cropV)
            val stereoLayout = when (stereo?.layout) {
                StereoLayout.SIDE_BY_SIDE -> 1f
                StereoLayout.TOP_BOTTOM -> 2f
                else -> 0f
            }
            push.putFloat(stereoLayout).putFloat(eye.toFloat())
            if (stereo != null) push.putFloat(stereo.xPx).putFloat(stereo.yPx).putFloat(stereo.widthPx).putFloat(stereo.heightPx)
            else push.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f)
            push.flip()
            vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT, 0, push)
            vkCmdDraw(cmd, 4, 1, 0, 0)
        }

    override fun close() {
        vkDestroySampler(vk.device, sampler, null)
        vkDestroyDescriptorPool(vk.device, pool, null)
        vkDestroyPipeline(vk.device, pipeline, null)
        vkDestroyPipelineLayout(vk.device, layout, null)
        vkDestroyDescriptorSetLayout(vk.device, setLayout, null)
    }

    private companion object {
        const val PUSH_BYTES = 128
        const val MAX_TEXTURES = 64

        val PUSH = """
            layout(push_constant) uniform Push {
                mat4 mvp;
                vec4 rectPx;
                vec2 sizePx;
                float radiusPx;
                float alpha;
                vec2 uvScale;
                float stereo;
                float eye;
                vec4 stereoRect;
            } pc;
        """.trimIndent()

        val VERTEX = """
            #version 450
            $PUSH
            layout(location = 0) out vec2 vUv;
            void main() {
                vec2 corner = vec2(gl_VertexIndex & 1, (gl_VertexIndex >> 1) & 1);
                vUv = vec2(corner.x, 1.0 - corner.y);
                gl_Position = pc.mvp * vec4(corner - 0.5, 0.0, 1.0);
            }
        """.trimIndent()

        val FRAGMENT = """
            #version 450
            $PUSH
            layout(set = 0, binding = 0) uniform sampler2D content;
            layout(location = 0) in vec2 vUv;
            layout(location = 0) out vec4 color;
            float roundedRect(vec2 p, vec2 halfSize, float radius) {
                vec2 q = abs(p) - halfSize + radius;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
            }
            void main() {
                vec2 window = pc.rectPx.xy + vUv * pc.rectPx.zw;
                float d = roundedRect(window - 0.5 * pc.sizePx, 0.5 * pc.sizePx, pc.radiusPx);
                float coverage = clamp(0.5 - d / fwidth(d), 0.0, 1.0);
                vec2 uv = vUv * pc.uvScale;
                if (pc.stereo != 0.0) {
                    vec2 local = (window - pc.stereoRect.xy) / pc.stereoRect.zw;
                    if (all(greaterThanEqual(local, vec2(0.0))) && all(lessThanEqual(local, vec2(1.0)))) {
                        if (pc.stereo == 1.0) local.x = 0.5 * (local.x + pc.eye);
                        else local.y = 0.5 * (local.y + pc.eye);
                        uv = (pc.stereoRect.xy + local * pc.stereoRect.zw) / pc.sizePx;
                    }
                }
                color = texture(content, uv) * (coverage * pc.alpha);
            }
        """.trimIndent()
    }
}
