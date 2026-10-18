package dev.halone.compositor

/** How the pointer is drawn, by what moves it. */
enum class CursorStyle {
    /** The phone aimed like a remote: a beam from below the view to a ring on the target. */
    BEAM,

    /** Head gaze: the ring alone. */
    RETICLE,

    /** A mouse or touchpad: an arrow, or whatever shape the content under it asks for. */
    ARROW,
}

/** The shapes content can ask the arrow to take, named as CSS names them. */
enum class CursorShape(val css: String) {
    DEFAULT("default"),
    POINTER("pointer"),
    TEXT("text"),
    VERTICAL_TEXT("vertical-text"),
    CROSSHAIR("crosshair"),
    GRAB("grab"),
    GRABBING("grabbing"),
    NOT_ALLOWED("not-allowed"),
    ALL_SCROLL("all-scroll"),
    EW_RESIZE("ew-resize"),
    NS_RESIZE("ns-resize"),
    NWSE_RESIZE("nwse-resize"),
    NESW_RESIZE("nesw-resize"),
    HELP("help"),
    WAIT("wait"),
    ZOOM_IN("zoom-in"),
    ZOOM_OUT("zoom-out"),
    CONTEXT_MENU("context-menu"),
    COPY("copy"),
    ALIAS("alias"),
    CELL("cell"),

    /** Content that hides the pointer, as CSS `cursor: none` does. */
    NONE("none"),
    ;

    companion object {
        private val byCss = entries.associateBy { it.css }

        /** The shape CSS calls [name]; the default arrow for one it has no picture for. */
        fun ofCss(name: String): CursorShape = byCss[name] ?: DEFAULT
    }
}
