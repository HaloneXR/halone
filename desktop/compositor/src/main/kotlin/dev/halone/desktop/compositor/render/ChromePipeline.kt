package dev.halone.desktop.compositor.render

import dev.halone.desktop.compositor.vk.Shaders
import dev.halone.desktop.compositor.vk.Stage
import dev.halone.desktop.compositor.vk.VulkanContext
import dev.halone.desktop.compositor.vk.vkCheck
import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkCommandBuffer
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

/**
 * The untextured chrome: StereoCompositor's FrameQuad (a ring around a
 * rounded window), ArcQuad (a resize handle round one corner) and SolidQuad,
 * with their GLSL as it is on Android, chosen per draw. Colours are
 * premultiplied.
 */
class ChromePipeline(private val vk: VulkanContext, colorFormat: Int) : AutoCloseable {
    private val layout: Long
    private val pipeline: Long

    init {
        stackPush().use { stack ->
            val push = VkPushConstantRange.calloc(1, stack)
                .stageFlags(VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(PUSH_BYTES)
            val pHandle = stack.mallocLong(1)
            vkCheck(vkCreatePipelineLayout(vk.device, VkPipelineLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO).pPushConstantRanges(push), null, pHandle), "chrome layout")
            layout = pHandle.get(0)

            val vertex = Shaders.module(vk, "chrome.vert", Stage.VERTEX, VERTEX)
            val fragment = Shaders.module(vk, "chrome.frag", Stage.FRAGMENT, FRAGMENT)
            val stages = VkPipelineShaderStageCreateInfo.calloc(2, stack)
            stages[0].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertex).pName(stack.UTF8("main"))
            stages[1].sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO).stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragment).pName(stack.UTF8("main"))
            val blend = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                .blendEnable(true)
                .srcColorBlendFactor(VK_BLEND_FACTOR_ONE).dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).colorBlendOp(VK_BLEND_OP_ADD)
                .srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).alphaBlendOp(VK_BLEND_OP_ADD)
                .colorWriteMask(0xF)
            val info = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                .pNext(VkPipelineRenderingCreateInfo.calloc(stack).sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
                    .colorAttachmentCount(1).pColorAttachmentFormats(stack.ints(colorFormat)))
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
            vkCheck(vkCreateGraphicsPipelines(vk.device, VK_NULL_HANDLE, info, null, pHandle), "chrome pipeline")
            pipeline = pHandle.get(0)
            vkDestroyShaderModule(vk.device, vertex, null)
            vkDestroyShaderModule(vk.device, fragment, null)
        }
    }

    fun bind(cmd: VkCommandBuffer) = vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline)

    /** FrameQuad: [mvp] maps the unit quad onto the window grown by [marginPx] on every side. */
    fun frame(
        cmd: VkCommandBuffer, mvp: FloatArray, panelWidthPx: Float, panelHeightPx: Float, marginPx: Float,
        radiusPx: Float, gapPx: Float, thicknessPx: Float, r: Float, g: Float, b: Float, a: Float,
    ) = push(cmd, mvp, MODE_FRAME, r * a, g * a, b * a, a,
        floatArrayOf(panelWidthPx + 2f * marginPx, panelHeightPx + 2f * marginPx, panelWidthPx, panelHeightPx),
        floatArrayOf(radiusPx, gapPx, gapPx + thicknessPx, 0f))

    /** ArcQuad: a white handle round one corner, [outsidePx] beyond it and [insidePx] into the window. */
    fun arc(
        cmd: VkCommandBuffer, mvp: FloatArray, outsidePx: Float, insidePx: Float, cornerRadiusPx: Float,
        gapPx: Float, thicknessPx: Float, alpha: Float,
    ) = push(cmd, mvp, MODE_ARC, alpha, alpha, alpha, alpha,
        floatArrayOf(outsidePx, insidePx, cornerRadiusPx, cornerRadiusPx + gapPx),
        floatArrayOf(thicknessPx, 0f, 0f, 0f))

    /** SolidQuad: [r], [g], [b], [a] as given, not premultiplied here. */
    fun solid(cmd: VkCommandBuffer, mvp: FloatArray, r: Float, g: Float, b: Float, a: Float) =
        push(cmd, mvp, MODE_SOLID, r, g, b, a, FloatArray(4), FloatArray(4))

    private fun push(cmd: VkCommandBuffer, mvp: FloatArray, mode: Int, r: Float, g: Float, b: Float, a: Float, p1: FloatArray, p2: FloatArray) =
        stackPush().use { stack ->
            val buffer = stack.malloc(PUSH_BYTES)
            mvp.forEach { buffer.putFloat(it) }
            buffer.putFloat(r).putFloat(g).putFloat(b).putFloat(a)
            p1.forEach { buffer.putFloat(it) }
            p2.forEach { buffer.putFloat(it) }
            buffer.putInt(mode).putInt(0).putInt(0).putInt(0)
            buffer.flip()
            vkCmdPushConstants(cmd, layout, VK_SHADER_STAGE_VERTEX_BIT or VK_SHADER_STAGE_FRAGMENT_BIT, 0, buffer)
            vkCmdDraw(cmd, 4, 1, 0, 0)
        }

    override fun close() {
        vkDestroyPipeline(vk.device, pipeline, null)
        vkDestroyPipelineLayout(vk.device, layout, null)
    }

    private companion object {
        const val PUSH_BYTES = 128
        const val MODE_FRAME = 0
        const val MODE_ARC = 1
        const val MODE_SOLID = 2

        val PUSH = """
            layout(push_constant) uniform Push {
                mat4 mvp;
                vec4 color;
                vec4 p1;
                vec4 p2;
                int mode;
            } pc;
        """.trimIndent()

        val VERTEX = """
            #version 450
            $PUSH
            layout(location = 0) out vec2 vLocal;
            void main() {
                vLocal = vec2(gl_VertexIndex & 1, (gl_VertexIndex >> 1) & 1) - 0.5;
                gl_Position = pc.mvp * vec4(vLocal, 0.0, 1.0);
            }
        """.trimIndent()

        val FRAGMENT = """
            #version 450
            $PUSH
            layout(location = 0) in vec2 vLocal;
            layout(location = 0) out vec4 fragColor;
            float roundedRect(vec2 p, vec2 halfSize, float radius) {
                vec2 q = abs(p) - halfSize + radius;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;
            }
            void main() {
                if (pc.mode == 0) {
                    float d = roundedRect(vLocal * pc.p1.xy, 0.5 * pc.p1.zw, pc.p2.x);
                    float w = fwidth(d);
                    float inner = clamp((d - pc.p2.y) / w + 0.5, 0.0, 1.0);
                    float outer = clamp((pc.p2.z - d) / w + 0.5, 0.0, 1.0);
                    fragColor = pc.color * min(inner, outer);
                } else if (pc.mode == 1) {
                    vec2 extent = pc.p1.xy;
                    float radius = pc.p1.z;
                    float ring = pc.p1.w;
                    vec2 q = vec2(mix(-extent.x, extent.y, vLocal.x + 0.5), mix(-extent.x, extent.y, 0.5 - vLocal.y));
                    float edge = radius - ring;
                    float d;
                    if (q.x < radius && q.y < radius) d = abs(length(q - vec2(radius)) - ring);
                    else if (q.y < radius) d = abs(q.y - edge);
                    else if (q.x < radius) d = abs(q.x - edge);
                    else d = 1000.0;
                    float w = fwidth(d);
                    fragColor = pc.color * clamp((0.5 * pc.p2.x - d) / w + 0.5, 0.0, 1.0);
                } else {
                    fragColor = pc.color;
                }
            }
        """.trimIndent()
    }
}
