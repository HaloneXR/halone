package dev.halone.app.testmode

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import java.io.Closeable

/**
 * The glasses' screen in test mode: a private virtual display the environment
 * renders to as it would to the glasses', shown in a 16:9 [view] on the phone.
 */
class PhoneDisplay(private val context: Context) : Closeable {
    private var surface: Surface? = null
    private var virtual: VirtualDisplay = create()

    val display: Display get() = virtual.display

    private val handler = Handler(Looper.getMainLooper())

    /** Replaces the display with a new one, as glasses whose video link dropped and came back. */
    fun recreate() {
        virtual.surface = null
        virtual.release()
        virtual = create(null)
        // The preview's surface goes to the new display only once the old one
        // has let go of it: given at once, the new display's connection to it
        // was seen to fail for good, freezing the preview on its last frame.
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ virtual.surface = surface }, HANDOVER_MS)
    }

    private fun create(surface: Surface? = this.surface): VirtualDisplay = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
        "Halone Test Mode",
        WIDTH,
        HEIGHT,
        context.resources.displayMetrics.densityDpi,
        surface,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
    )

    val view: View = object : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            val height = width * HEIGHT / WIDTH
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
            )
        }
    }.apply {
        addView(SurfaceView(context).apply {
            holder.setFixedSize(WIDTH, HEIGHT)
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) {
                    surface = holder.surface
                    virtual.surface = holder.surface
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    surface = null
                    virtual.surface = null
                }
            })
        })
    }

    override fun close() {
        handler.removeCallbacksAndMessages(null)
        virtual.release()
    }

    companion object {
        const val WIDTH = 1920
        const val HEIGHT = 1080
        private const val HANDOVER_MS = 250L
    }
}
