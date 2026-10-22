package dev.halone.shell

import dev.halone.engine.plugin.PluginPackage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginSettingsTest {

    private val schema = """
        [
          { "key": "raise", "type": "boolean", "default": false, "label": "Raise", "help": "Raises it." },
          { "key": "level", "type": "number", "default": 2, "min": 1, "max": 5, "step": 1, "unit": "x", "label": "Level" },
          { "key": "mode", "type": "enum", "default": "a", "values": ["a", "b"], "labels": { "a": "First" }, "label": "Mode" }
        ]
    """.trimIndent()

    private fun plugin(settings: String? = schema) =
        PluginPackage("dev.example", "dev.example.Provider", "Example", "1.0", 2, "aa", settingsSchema = settings)

    @Test
    fun `a declared list becomes a page with its defaults`() {
        val page = PluginSettings(MemoryStore()).page(plugin())!!
        assertEquals(listOf("toggle", "slider", "choice"), (0 until page.items.length()).map { page.items.getJSONObject(it).getString("kind") })
        assertEquals("First", page.items.getJSONObject(2).getJSONArray("options").getJSONObject(0).getString("label"))
        assertEquals("b", page.items.getJSONObject(2).getJSONArray("options").getJSONObject(1).getString("label"))
        assertEquals(false, page.values.boolean("raise"))
        assertEquals(2.0, page.values.number("level"), 0.0)
    }

    @Test
    fun `values are validated and kept per plugin across restarts`() {
        val store = MemoryStore()
        PluginSettings(store).page(plugin())!!.values.set("raise", true)
        val values = PluginSettings(store).page(plugin())!!.values
        assertEquals(true, values.boolean("raise"))
        assertThrows(HaloneException::class.java) { values.set("level", 9) }
        assertThrows(HaloneException::class.java) { values.set("mode", "c") }
        assertThrows(HaloneException::class.java) { values.set("other", 1) }
    }

    @Test
    fun `no list, or an unusable one, is no page`() {
        val settings = PluginSettings(MemoryStore())
        assertNull(settings.page(plugin(null)))
        assertNull(settings.page(plugin("not json")))
        assertNull(settings.page(plugin("""[{ "key": "x", "type": "list", "default": [], "label": "X" }]""")))
        assertNull(settings.page(plugin("""[{ "key": "x", "type": "number", "default": 9, "min": 0, "max": 1, "label": "X" }]""")))
    }
}
