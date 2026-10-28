package dev.halone.shell

import org.json.JSONArray
import org.json.JSONObject

/** Where settings survive a restart. SharedPreferences on the device, memory in tests. */
interface SettingsStore {
    fun load(): String?
    fun save(json: String)
}

/**
 * The shell's settings, validated here rather than trusted from the app that
 * set them. Keys, defaults and ranges come from `settings-schema.json`, which
 * the system apps build ships and the simulator validates against too.
 */
class ShellSettings(private val store: SettingsStore, schemaJson: String) {

    private sealed interface Rule {
        val default: Any
    }

    private data class Toggle(override val default: Boolean) : Rule
    private data class Range(override val default: Double, val min: Double, val max: Double) : Rule
    private data class Choice(override val default: String, val values: Set<String>) : Rule
    private data class Strings(override val default: List<String>, val maxItems: Int) : Rule

    private val rules: Map<String, Rule> = parseSchema(schemaJson)
    private val values = LinkedHashMap<String, Any>()

    init {
        rules.forEach { (key, rule) -> values[key] = rule.default }
        store.load()?.let { stored ->
            val json = runCatching { JSONObject(stored) }.getOrNull() ?: return@let
            json.keys().forEach { key -> runCatching { values[key] = normalise(key, json.get(key)) } }
        }
    }

    fun toJson(): JSONObject = JSONObject().also { json ->
        values.forEach { (key, value) -> json.put(key, if (value is List<*>) JSONArray(value) else value) }
    }

    fun set(key: String, value: Any?) {
        values[key] = normalise(key, value)
        store.save(toJson().toString())
    }

    fun boolean(key: String): Boolean = values[key] as Boolean
    fun number(key: String): Double = values[key] as Double
    fun string(key: String): String = values[key] as String

    @Suppress("UNCHECKED_CAST")
    fun strings(key: String): List<String> = values[key] as List<String>

    private fun normalise(key: String, value: Any?): Any {
        val rule = rules[key] ?: throw HaloneException("invalid-setting", "unknown setting: $key")
        return when (rule) {
            is Toggle -> value as? Boolean
            is Range -> (value as? Number)?.toDouble()?.takeIf { it.isFinite() && it in rule.min..rule.max }
            is Choice -> (value as? String)?.takeIf { it in rule.values }
            is Strings -> stringList(value)?.takeIf { it.size <= rule.maxItems && it.toSet().size == it.size }
        } ?: throw HaloneException("invalid-setting", "invalid value for $key: ${JSONObject.wrap(value)}")
    }

    private fun stringList(value: Any?): List<String>? {
        val items = when (value) {
            is JSONArray -> (0 until value.length()).map { value.opt(it) }
            is List<*> -> value
            else -> return null
        }
        return items.map { (it as? String)?.takeIf { s -> s.isNotEmpty() && s.length <= MAX_ITEM_LENGTH } ?: return null }
    }

    private fun parseSchema(json: String): Map<String, Rule> {
        val schema = JSONObject(json)
        return schema.keys().asSequence().associateWith { key ->
            val rule = schema.getJSONObject(key)
            when (val type = rule.getString("type")) {
                "boolean" -> Toggle(rule.getBoolean("default"))
                "number" -> Range(rule.getDouble("default"), rule.getDouble("min"), rule.getDouble("max"))
                "enum" -> {
                    val values = rule.getJSONArray("values")
                    Choice(rule.getString("default"), (0 until values.length()).map { values.getString(it) }.toSet())
                }
                "list" -> {
                    val defaults = rule.getJSONArray("default")
                    Strings((0 until defaults.length()).map { defaults.getString(it) }, rule.getInt("maxItems"))
                }
                else -> error("unknown setting type $type for $key")
            }
        }
    }

    companion object {
        const val FOLLOW_ENABLED = "follow.enabled"
        const val CRASH_REPORTS = "diagnostics.crashReports"
        const val FOLLOW_MARGIN_DEGREES = "follow.marginDegrees"
        const val FOLLOW_CATCH_UP_SECONDS = "follow.catchUpSeconds"
        const val SLOT_SPACING_DEGREES = "layout.slotSpacingDegrees"
        const val COLLISION_POLICY = "layout.collisionPolicy"
        const val DOCK_VISIBILITY = "dock.visibility"
        const val DOCK_PINNED_APPS = "dock.pinnedApps"
        const val WINDOWS_AUTO_HIDE = "windows.autoHide"
        const val WINDOWS_AUTO_HIDE_DELAY_SECONDS = "windows.autoHideDelaySeconds"
        const val WINDOWS_SHAPE_BUTTON = "windows.shapeButton"
        const val WINDOWS_RECLAIM_PHONE = "windows.reclaimPhone"
        const val BROWSER_AUTO_HIDE_TOOLBAR = "browser.autoHideToolbar"
        const val BROWSER_USER_AGENT = "browser.userAgent"
        const val BROWSER_AD_BLOCKER = "browser.adBlocker"
        const val BROWSER_SEARCH_ENGINE = "browser.searchEngine"
        /** Room for any app id: a plugin's is its Android package, `/`, and its own id of up to 200 characters. */
        private const val MAX_ITEM_LENGTH = 512
        const val PANEL_DISTANCE_METRES = "panel.distanceMetres"
        const val DISPLAY_BRIGHTNESS = "display.brightness"
        const val DISPLAY_REFRESH_RATE = "display.refreshRate"
        const val DISPLAY_STEREO = "display.stereo"
        const val MEDIA_SCREEN_WIDTH_DEGREES = "media.screenWidthDegrees"
        const val MEDIA_SPHERE_ZOOM = "media.sphereZoom"
        const val MEDIA_SPHERE_DISTANCE_METRES = "media.sphereDistanceMetres"
        const val BED_MODE_ENABLED = "bedMode.enabled"
        const val BED_MODE_DWELL_SECONDS = "bedMode.dwellSeconds"
        const val BED_MODE_COUNTDOWN_SECONDS = "bedMode.countdownSeconds"
        const val BED_MODE_FORWARD_DEGREES = "bedMode.forwardDegrees"
        const val DEVELOPER_ENABLED = "developer.enabled"
        /** The refresh rate and frames drawn, along the bottom of the phone pad. */
        const val DEVELOPER_DISPLAY_STATS = "developer.displayStats"
        const val TEST_MODE_ENABLED = "testMode.enabled"
        /** Off: each pair of glasses uses its driver's own tuning, and the two below are ignored. */
        const val TRACKING_CUSTOM = "tracking.custom"
        const val TRACKING_PREDICTION_MS = "tracking.predictionMs"
        const val TRACKING_STEADINESS_DEGREES = "tracking.steadinessDegrees"
        /** `auto`, `wait` for each frame to reach the display, or run `free` of it. */
        const val TRACKING_FRAME_PACING = "tracking.framePacing"
        const val INPUT_POINTER = "input.pointer"
        const val TOUCHPAD_SPEED = "input.touchpadSpeed"
        const val MOUSE_SPEED = "input.mouseSpeed"
        const val GESTURE_PREFIX = "gesture."
        const val GLASSES_SCROLL_PX = "gesture.glasses.scrollPx"

        fun glassesTap(side: String) = "gesture.glasses.$side.tap"
        fun glassesDoubleTap(side: String) = "gesture.glasses.$side.doubleTap"
        fun padTap(fingers: Int) = "gesture.pad.tap$fingers"
        fun padHold(fingers: Int) = "gesture.pad.hold$fingers"

        const val SCHEMA_ASSET = "settings-schema.json"
    }
}
