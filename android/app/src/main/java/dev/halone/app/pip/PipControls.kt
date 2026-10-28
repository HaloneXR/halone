package dev.halone.app.pip

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * The phone's side of picture in picture: the glasses' display in small,
 * with the window on it to drag around and pinch to size.
 */
class PipControls(
    private val activity: Activity,
    private val displayWidth: Int,
    private val displayHeight: Int,
    frame: PipFrame,
    private val onChange: (PipFrame) -> Unit,
    private val onSettled: (PipFrame) -> Unit,
    private val onStop: () -> Unit,
) {
    private val density = activity.resources.displayMetrics.density
    private var view: View? = null
    private val stage = Stage(frame)

    fun show() {
        if (view != null) return
        val root = activity.findViewById<View>(android.R.id.content) as ViewGroup
        val title = TextView(activity).apply {
            text = "Picture in Picture"
            textSize = 28f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }
        val hint = TextView(activity).apply {
            text = "Drag to move the window on the glasses, pinch to resize it"
            textSize = 16f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }
        val stop = TextView(activity).apply {
            text = "Stop Picture in Picture"
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = RippleDrawable(
                ColorStateList.valueOf(PRESSED),
                GradientDrawable().apply {
                    cornerRadius = dp(32).toFloat()
                    setColor(STOP)
                },
                null,
            )
            setOnClickListener { onStop() }
        }
        val screen = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            isClickable = true
            addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(hint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(stage, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                topMargin = dp(16)
                bottomMargin = dp(16)
            })
            addView(stop, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(64)))
            ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                setPadding(bars.left + dp(24), bars.top + dp(48), bars.right + dp(24), bars.bottom + dp(48))
                insets
            }
        }
        root.addView(screen, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        screen.bringToFront()
        ViewCompat.requestApplyInsets(screen)
        view = screen
    }

    fun hide() {
        view?.let { (it.parent as? ViewGroup)?.removeView(it) }
        view = null
    }

    /** The phone's screen as captured, whose shape the window takes. */
    fun setContent(width: Int, height: Int) {
        stage.aspect = width.toFloat() / height
        stage.invalidate()
    }

    private fun dp(value: Int) = (value * density).toInt()

    private inner class Stage(var frame: PipFrame) : View(activity) {
        var aspect = 9f / 16f
        private val area = RectF()
        private var lastX = 0f
        private var lastY = 0f

        private val display = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = DISPLAY }
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = OUTLINE
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = WINDOW }
        private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2 * density
            color = ACCENT
        }

        private val scaling = ScaleGestureDetector(activity, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                update(frame.scaled(detector.scaleFactor, area.width(), area.height(), aspect))
                return true
            }
        }).apply { isQuickScaleEnabled = false }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val ratio = displayWidth.toFloat() / displayHeight
            val width = minOf(w.toFloat(), h * ratio)
            val height = width / ratio
            area.set((w - width) / 2, (h - height) / 2, (w + width) / 2, (h + height) / 2)
        }

        override fun onDraw(canvas: Canvas) {
            val corner = 12 * density
            canvas.drawRoundRect(area, corner, corner, display)
            canvas.drawRoundRect(area, corner, corner, outline)
            val window = frame.place(area.width(), area.height(), aspect)
            window.offset(area.left, area.top)
            canvas.drawRoundRect(window, 4 * density, 4 * density, fill)
            canvas.drawRoundRect(window, 4 * density, 4 * density, edge)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            scaling.onTouchEvent(event)
            var x = 0f
            var y = 0f
            val lifted = if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) event.actionIndex else -1
            val count = event.pointerCount - if (lifted >= 0) 1 else 0
            for (i in 0 until event.pointerCount) {
                if (i == lifted) continue
                x += event.getX(i)
                y += event.getY(i)
            }
            x /= count
            y /= count
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> update(frame.moved(x - lastX, y - lastY, area.width(), area.height(), aspect))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onSettled(frame)
            }
            lastX = x
            lastY = y
            return true
        }

        private fun update(next: PipFrame) {
            if (next == frame) return
            frame = next
            invalidate()
            onChange(next)
        }
    }

    private companion object {
        val ACCENT = Color.rgb(0xff, 0x9f, 0x43)
        val WINDOW = Color.argb(0x40, 0xff, 0x9f, 0x43)
        val DISPLAY = Color.rgb(0x10, 0x14, 0x1c)
        val OUTLINE = Color.rgb(0x2a, 0x33, 0x40)
        val STOP = Color.rgb(0x5a, 0x1f, 0x24)
        val MUTED = Color.rgb(0x8b, 0x98, 0xab)
        val PRESSED = Color.argb(0x40, 0xff, 0xff, 0xff)
    }
}
