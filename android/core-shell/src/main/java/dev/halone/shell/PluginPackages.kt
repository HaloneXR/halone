package dev.halone.shell

/** An installed plugin that declares a panel provider, as the platform describes it; nothing of it has run. */
data class PluginPackage(
    val packageName: String,
    val serviceClass: String,
    val label: String,
    val versionName: String,
    /** The contract version it declares. */
    val contract: Int,
    /** SHA-256 of its signing certificates, in hex, `,`-joined when there are several. */
    val certificate: String,
    /** When it was last installed or updated, so a reinstall of the same version still counts as a change. */
    val updatedAt: Long = 0,
)

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
        const val MIN_WINDOW_PX = 200
        const val MAX_WINDOW_PX = 4096
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
}
