package dev.halone.engine.plugin

import android.os.Bundle
import dev.halone.plugin.PluginContract
import dev.halone.shell.ProvidedApp
import dev.halone.shell.ProvidedStatusItem

private const val MAX_APP_ID = 200
private const val MAX_APP_NAME = 80
private const val MAX_APP_DESCRIPTION = 300
private const val MAX_ICON_VERSION = 64

/** The app a plugin described, or null when it gave no usable id or name. */
fun providedAppOf(bundle: Bundle): ProvidedApp? {
    val id = bundle.getString(PluginContract.KEY_ID)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_APP_ID } ?: return null
    val name = bundle.getString(PluginContract.KEY_NAME)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return ProvidedApp(
        id = id,
        name = name.take(MAX_APP_NAME),
        description = bundle.getString(PluginContract.KEY_DESCRIPTION).orEmpty().take(MAX_APP_DESCRIPTION),
        widthPx = bundle.getInt(PluginContract.KEY_WIDTH, 1024).coerceIn(ProvidedApp.MIN_WINDOW_PX, ProvidedApp.MAX_WINDOW_PX),
        heightPx = bundle.getInt(PluginContract.KEY_HEIGHT, 768).coerceIn(ProvidedApp.MIN_WINDOW_PX, ProvidedApp.MAX_WINDOW_PX),
        hidden = bundle.getBoolean(PluginContract.KEY_HIDDEN, false),
        iconVersion = bundle.getString(PluginContract.KEY_ICON_VERSION).orEmpty().replace('/', '_').take(MAX_ICON_VERSION),
        color = if (bundle.containsKey(PluginContract.KEY_COLOR)) "#%06x".format(bundle.getInt(PluginContract.KEY_COLOR) and 0xFFFFFF) else null,
    )
}

private const val MAX_STATUS_ID = 64
private const val MAX_STATUS_LABEL = 120
private const val MAX_STATUS_TEXT = 12
private const val MAX_ICON_BYTES = 64 * 1024
private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)

/** The status item, or null when its id is unusable or its icon is not a small PNG. */
fun providedStatusItemOf(id: String, bundle: Bundle): ProvidedStatusItem? {
    val key = id.trim().takeIf { it.isNotEmpty() && it.length <= MAX_STATUS_ID } ?: return null
    val icon = bundle.getByteArray(PluginContract.KEY_STATUS_ICON)
        ?.takeIf { it.size in PNG.size..MAX_ICON_BYTES && it.copyOf(PNG.size).contentEquals(PNG) }
        ?: return null
    return ProvidedStatusItem(
        id = key,
        iconPng = icon,
        label = bundle.getString(PluginContract.KEY_STATUS_LABEL).orEmpty().take(MAX_STATUS_LABEL),
        text = bundle.getString(PluginContract.KEY_STATUS_TEXT).orEmpty().take(MAX_STATUS_TEXT),
        tinted = bundle.getBoolean(PluginContract.KEY_STATUS_TINTED, true),
    )
}
