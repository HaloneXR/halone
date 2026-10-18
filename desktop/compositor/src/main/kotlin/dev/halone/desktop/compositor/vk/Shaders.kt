package dev.halone.desktop.compositor.vk

import org.lwjgl.system.MemoryStack.stackPush
import org.lwjgl.util.shaderc.Shaderc.*
import org.lwjgl.vulkan.VK13.*
import org.lwjgl.vulkan.VkShaderModuleCreateInfo

enum class Stage(val kind: Int) {
    VERTEX(shaderc_vertex_shader),
    FRAGMENT(shaderc_fragment_shader),
}

object Shaders {
    fun module(vk: VulkanContext, name: String, stage: Stage, source: String): Long {
        val compiler = shaderc_compiler_initialize()
        val options = shaderc_compile_options_initialize()
        try {
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_3)
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance)
            val result = shaderc_compile_into_spv(compiler, source, stage.kind, name, "main", options)
            try {
                if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                    throw IllegalStateException("$name: ${shaderc_result_get_error_message(result)}")
                }
                val spirv = shaderc_result_get_bytes(result)!!
                return stackPush().use { stack ->
                    val info = VkShaderModuleCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                        .pCode(spirv)
                    val pModule = stack.mallocLong(1)
                    vkCheck(vkCreateShaderModule(vk.device, info, null, pModule), "vkCreateShaderModule($name)")
                    pModule.get(0)
                }
            } finally {
                shaderc_result_release(result)
            }
        } finally {
            shaderc_compile_options_release(options)
            shaderc_compiler_release(compiler)
        }
    }
}
