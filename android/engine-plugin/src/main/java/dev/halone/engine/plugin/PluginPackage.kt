package dev.halone.engine.plugin

import android.os.Bundle
import dev.halone.plugin.PluginContract

/** An installed APK that declares a panel provider, as the package manager describes it; nothing of it has run. */
data class PluginPackage(
    val packageName: String,
    val serviceClass: String,
    val label: String,
    val versionName: String,
    /** The contract version its meta-data declares. */
    val contract: Int,
    /** SHA-256 of its signing certificates, in hex, `,`-joined when there are several. */
    val certificate: String,
    /** When it was last installed or updated, so a reinstall of the same version still counts as a change. */
    val updatedAt: Long = 0,
    /** The settings list its meta-data points at, as JSON text; null for none. */
    val settingsSchema: String? = null,
) {
    val compatible: Boolean get() = contract in 1..PluginContract.VERSION
}

/** One app a plugin listed, checked. */
data class ProvidedApp(
    val id: String,
    val name: String,
    val description: String,
    val widthPx: Int,
    val heightPx: Int,
    val hidden: Boolean,
    val iconVersion: String,
    /** As `#rrggbb`. */
    val color: String? = null,
) {
    companion object {
        private const val MAX_ID = 200
        private const val MAX_NAME = 80
        private const val MAX_DESCRIPTION = 300
        private const val MAX_ICON_VERSION = 64
        const val MIN_WINDOW_PX = 200
        const val MAX_WINDOW_PX = 4096

        /** The app a plugin described, or null when it gave no usable id or name. */
        fun fromBundle(bundle: Bundle): ProvidedApp? {
            val id = bundle.getString(PluginContract.KEY_ID)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_ID } ?: return null
            val name = bundle.getString(PluginContract.KEY_NAME)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return ProvidedApp(
                id = id,
                name = name.take(MAX_NAME),
                description = bundle.getString(PluginContract.KEY_DESCRIPTION).orEmpty().take(MAX_DESCRIPTION),
                widthPx = bundle.getInt(PluginContract.KEY_WIDTH, 1024).coerceIn(MIN_WINDOW_PX, MAX_WINDOW_PX),
                heightPx = bundle.getInt(PluginContract.KEY_HEIGHT, 768).coerceIn(MIN_WINDOW_PX, MAX_WINDOW_PX),
                hidden = bundle.getBoolean(PluginContract.KEY_HIDDEN, false),
                iconVersion = bundle.getString(PluginContract.KEY_ICON_VERSION).orEmpty().replace('/', '_').take(MAX_ICON_VERSION),
                color = if (bundle.containsKey(PluginContract.KEY_COLOR)) "#%06x".format(bundle.getInt(PluginContract.KEY_COLOR) and 0xFFFFFF) else null,
            )
        }
    }
}

/** A status item a plugin set, checked. */
data class ProvidedStatusItem(
    val id: String,
    val iconPng: ByteArray,
    val label: String,
    val text: String,
    val tinted: Boolean,
) {
    override fun equals(other: Any?) = other is ProvidedStatusItem && id == other.id && label == other.label &&
        text == other.text && tinted == other.tinted && iconPng.contentEquals(other.iconPng)

    override fun hashCode() = id.hashCode()

    companion object {
        private const val MAX_ID = 64
        private const val MAX_LABEL = 120
        private const val MAX_TEXT = 12
        private const val MAX_ICON_BYTES = 64 * 1024
        private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)

        /** The item, or null when its id is unusable or its icon is not a small PNG. */
        fun fromBundle(id: String, bundle: Bundle): ProvidedStatusItem? {
            val key = id.trim().takeIf { it.isNotEmpty() && it.length <= MAX_ID } ?: return null
            val icon = bundle.getByteArray(PluginContract.KEY_STATUS_ICON)
                ?.takeIf { it.size in PNG.size..MAX_ICON_BYTES && it.copyOf(PNG.size).contentEquals(PNG) }
                ?: return null
            return ProvidedStatusItem(
                id = key,
                iconPng = icon,
                label = bundle.getString(PluginContract.KEY_STATUS_LABEL).orEmpty().take(MAX_LABEL),
                text = bundle.getString(PluginContract.KEY_STATUS_TEXT).orEmpty().take(MAX_TEXT),
                tinted = bundle.getBoolean(PluginContract.KEY_STATUS_TINTED, true),
            )
        }
    }
}
