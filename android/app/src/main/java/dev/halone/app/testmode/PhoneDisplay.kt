package dev.halone.app.testmode

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.view.Display
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import java.io.Closeable

/**
 * The glasses' screen in test mode: a private virtual display the environment
 * renders to as it would to the glasses', shown in a 16:9 [view] on the phone.
 */
class PhoneDisplay(context: Context) : Closeable {
    private val virtual: VirtualDisplay = context.getSystemService(DisplayManager::class.java).createVirtualDisplay(
        "Halone Test Mode",
        WIDTH,
        HEIGHT,
        context.resources.displayMetrics.densityDpi,
        null,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
    )

    val display: Display get() = virtual.display

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
                    virtual.surface = holder.surface
                }

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    virtual.surface = null
                }
            })
        })
    }

    override fun close() = virtual.release()

    companion object {
        const val WIDTH = 1920
        const val HEIGHT = 1080
    }
}
