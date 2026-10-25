package dev.halone.app.pip

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The phone's screen, mirrored into a window on the glasses' display.
 *
 * Nothing of the glasses' own is used: any display the phone can show a
 * presentation on will do. The projection draws into a texture the size
 * of the phone's screen, scaled to whatever size the window is.
 */
class PictureInPicture(
    private val context: Context,
    private val projection: MediaProjection,
    display: Display,
    frame: PipFrame,
    private val onEnded: () -> Unit,
    private val log: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    // The activity's own displays answer in its orientation, which is portrait whatever the phone shows.
    private val displays = context.applicationContext.getSystemService(DisplayManager::class.java)
    private val limit = max(display.mode.physicalWidth, display.mode.physicalHeight)
    private var ended = false

    /** The size the phone's screen is captured at, which follows it as it rotates. */
    var contentWidth = 0
        private set
    var contentHeight = 0
        private set

    /** Told on the main thread when the phone turns and the window changes shape. */
    var onResized: (() -> Unit)? = null

    var frame: PipFrame = frame
        set(value) {
            field = value
            presentation.place()
        }

    private val presentation = Window(display)

    private val virtualDisplay: VirtualDisplay

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            log("  pip: screen sharing stopped")
            end()
        }

        override fun onCapturedContentResize(width: Int, height: Int) = resize(width, height)
    }

    private val phoneListener = object : DisplayManager.DisplayListener {
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) phoneSize().let { (width, height) -> resize(width, height) }
        }

        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit
    }

    init {
        projection.registerCallback(projectionCallback, handler)
        val (phoneWidth, phoneHeight) = phoneSize()
        val (width, height, dpi) = captureSize(phoneWidth, phoneHeight)
        contentWidth = width
        contentHeight = height
        virtualDisplay = projection.createVirtualDisplay(
            "Halone picture in picture", width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, handler,
        ) ?: error("the projection gave no display")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) displays.registerDisplayListener(phoneListener, handler)
        presentation.setOnDismissListener {
            log("  pip: the display went away")
            end()
        }
        presentation.show()
        log("  pip: mirroring ${width}x$height onto display ${display.displayId}")
    }

    fun stop() = end()

    private fun end() {
        if (ended) return
        ended = true
        displays.unregisterDisplayListener(phoneListener)
        projection.unregisterCallback(projectionCallback)
        virtualDisplay.release()
        runCatching { projection.stop() }
        presentation.setOnDismissListener(null)
        runCatching { presentation.dismiss() }
        onEnded()
    }

    private fun resize(phoneWidth: Int, phoneHeight: Int) {
        if (ended || phoneWidth <= 0 || phoneHeight <= 0) return
        val (width, height, dpi) = captureSize(phoneWidth, phoneHeight)
        if (width == contentWidth && height == contentHeight) return
        log("  pip: the phone's screen is now ${width}x$height")
        contentWidth = width
        contentHeight = height
        virtualDisplay.resize(width, height, dpi)
        presentation.place()
        onResized?.invoke()
    }

    private fun phoneSize(): Pair<Int, Int> {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        displays.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    /** The phone's screen, scaled down to no more than the glasses show across. */
    private fun captureSize(phoneWidth: Int, phoneHeight: Int): Triple<Int, Int, Int> {
        val scale = min(1f, limit.toFloat() / max(phoneWidth, phoneHeight))
        fun even(value: Int) = (value * scale).roundToInt() and 1.inv()
        return Triple(even(phoneWidth), even(phoneHeight), (context.resources.displayMetrics.densityDpi * scale).roundToInt())
    }

    private inner class Window(display: Display) : Presentation(context, display) {
        private val texture = TextureView(context)
        private val root = FrameLayout(context)
        private var surface: Surface? = null

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            root.setBackgroundColor(Color.BLACK)
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    texture.setDefaultBufferSize(contentWidth, contentHeight)
                    attach(Surface(texture))
                }

                // The view resets the buffer to its own size whenever it is resized.
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) =
                    texture.setDefaultBufferSize(contentWidth, contentHeight)

                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    attach(null)
                    return true
                }

                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
            root.addView(texture, FrameLayout.LayoutParams(0, 0))
            root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
            setContentView(root)
        }

        private fun attach(target: Surface?) {
            if (!ended) virtualDisplay.surface = target
            surface?.release()
            surface = target
        }

        fun place() {
            if (root.width == 0 || contentHeight == 0) return
            texture.surfaceTexture?.setDefaultBufferSize(contentWidth, contentHeight)
            val rect = frame.place(root.width.toFloat(), root.height.toFloat(), contentWidth.toFloat() / contentHeight)
            val params = texture.layoutParams as FrameLayout.LayoutParams
            val width = rect.width().roundToInt()
            val height = rect.height().roundToInt()
            val left = rect.left.roundToInt()
            val top = rect.top.roundToInt()
            if (params.width == width && params.height == height && params.leftMargin == left && params.topMargin == top) return
            params.width = width
            params.height = height
            params.leftMargin = left
            params.topMargin = top
            texture.layoutParams = params
        }
    }
}
