package dev.halone.app

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Where the glasses are on the way to a running environment, as the home screen tells it. */
enum class GlassesStatus(val title: String, val dimmed: Boolean) {
    DISCONNECTED("Glasses Disconnected", true),
    AWAITING_PERMISSION("Awaiting Permission", true),
    NO_VIDEO_OUTPUT("Video Output Not Supported", true),
    LINK_OFF("Network Connection Off", true),
    READY("Glasses Ready", false),
    STARTING("Starting…", false),
    RUNNING("Environment Running", false),
}

/**
 * What the phone shows when it is not a pointer pad: the glasses, whether
 * they are ready, and the button that starts the environment on them. The
 * button in the corner swaps it for picture in picture, which needs only a display.
 */
class HomeScreen(
    context: Context,
    pipMode: Boolean,
    private val onPrimary: (GlassesStatus) -> Unit,
    private val onStartPip: () -> Unit,
    private val onModeChanged: (pipMode: Boolean) -> Unit,
    onSettings: () -> Unit,
    onShareReport: () -> Unit,
    onDismissReport: () -> Unit,
    private val onTestModeUnlocked: () -> Unit,
) {
    private val density = context.resources.displayMetrics.density
    private var status = GlassesStatus.DISCONNECTED

    var testModeUnlocked = false

    var pipMode = pipMode
        private set

    private var iconTaps = 0
    private var lastIconTapAt = 0L
    private var toast: Toast? = null

    private val icon = Symbols.view(context, "eyeglasses", 168f, Color.WHITE).apply {
        setOnClickListener { iconTapped(context) }
    }

    private val pip = Symbols.view(context, "picture_in_picture", 168f, Color.WHITE)

    /** Holds the centre of the column for whichever icon is there; the icons themselves move over it. */
    private val slot = Symbols.view(context, "eyeglasses", 168f, Color.WHITE).apply { visibility = View.INVISIBLE }

    private val swap = View(context).apply {
        isClickable = true
        isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(PRESSED), null, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
        setOnClickListener { toggleMode() }
    }

    private var dimmed = true

    /** Which icon is in the centre: it changes when the swap starts, [pipMode] halfway, once the old text has faded. */
    private var iconsPip = pipMode
    private var swapping = false
    private val fades = HashMap<View, ObjectAnimator>()

    private fun toggleMode() {
        if (swapping) return
        iconsPip = !iconsPip
        swapping = true
        placeIcons(animate = true)
        val parts = listOf(title, detail, secondary, primary)
        parts.forEach { it.animate().alpha(0f).setDuration(SWAP_MS / 2) }
        primary.animate().withEndAction {
            pipMode = iconsPip
            onModeChanged(pipMode)
            parts.forEach { it.animate().alpha(1f).setDuration(SWAP_MS / 2) }
        }
    }

    private fun placeIcons(animate: Boolean) {
        val root = view as FrameLayout
        if (slot.width == 0) return
        if (swapping && !animate) return fadeIcons(animate = true)
        val centre = Rect(0, 0, slot.width, slot.height).also { root.offsetDescendantRectToMyCoords(slot, it) }
        val corner = Rect(0, 0, swap.width, swap.height).also { root.offsetDescendantRectToMyCoords(swap, it) }
        val (big, small) = if (iconsPip) pip to icon else icon to pip
        icon.isClickable = !iconsPip
        swap.contentDescription = if (iconsPip) "Back to the environment" else "Picture in Picture"
        moveIcon(big, centre, 1f, animate)
        moveIcon(small, corner, CORNER_SCALE, animate) { swapping = false }
        fadeIcons(animate)
    }

    private fun fadeIcons(animate: Boolean) {
        val (big, small) = if (iconsPip) pip to icon else icon to pip
        fadeIcon(big, if (dimmed) DIMMED else 1f, animate)
        fadeIcon(small, if (swap.visibility == View.VISIBLE) 1f else 0f, animate)
    }

    private fun fadeIcon(target: View, alpha: Float, animate: Boolean) {
        fades.remove(target)?.cancel()
        if (!animate) {
            target.alpha = alpha
            return
        }
        fades[target] = ObjectAnimator.ofFloat(target, View.ALPHA, alpha).setDuration(SWAP_MS).also { it.start() }
    }

    private fun moveIcon(target: View, to: Rect, scale: Float, animate: Boolean, done: () -> Unit = {}) {
        val x = to.exactCenterX() - target.width / 2f
        val y = to.exactCenterY() - target.height / 2f
        if (!animate) {
            target.translationX = x
            target.translationY = y
            target.scaleX = scale
            target.scaleY = scale
            return
        }
        target.animate().translationX(x).translationY(y).scaleX(scale).scaleY(scale)
            .setDuration(SWAP_MS).withEndAction(done)
    }

    private fun iconTapped(context: Context) {
        val now = SystemClock.uptimeMillis()
        if (now - lastIconTapAt > TAP_RESET_MS) iconTaps = 0
        lastIconTapAt = now
        iconTaps++
        val left = TEST_MODE_TAPS - iconTaps
        val message = when {
            testModeUnlocked -> "Test mode is already on"
            left == 0 -> "Test mode is on".also {
                iconTaps = 0
                onTestModeUnlocked()
            }
            left <= COUNTDOWN_FROM -> if (left == 1) "You are now 1 step away from test mode" else "You are now $left steps away from test mode"
            else -> return
        }
        toast?.cancel()
        toast = Toast.makeText(context, message, Toast.LENGTH_SHORT).also { it.show() }
    }

    private val title = TextView(context).apply {
        textSize = 28f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
    }

    private val detail = TextView(context).apply {
        textSize = 16f
        setTextColor(MUTED)
        gravity = Gravity.CENTER
    }

    /** A second, smaller action under the detail, for a fix the detail suggests. */
    private val secondary = TextView(context).apply {
        textSize = 16f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(ACCENT)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        visibility = View.GONE
    }

    private val primary = TextView(context).apply {
        textSize = 20f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
    }

    private val settings = Symbols.view(context, "settings", 28f, Color.WHITE).apply {
        contentDescription = "Settings"
        isClickable = true
        isFocusable = true
        background = RippleDrawable(ColorStateList.valueOf(PRESSED), null, GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) })
        setOnClickListener { onSettings() }
    }

    /** Shown after a crash, until shared or dismissed. */
    private val crashRow = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
        addView(TextView(context).apply {
            text = "Halone stopped unexpectedly last time."
            textSize = 15f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(link(context, "Share Report", onShareReport))
            addView(link(context, "Dismiss", onDismissReport))
        })
    }

    private fun link(context: Context, label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 16f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setTextColor(ACCENT)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun showCrashReport(visible: Boolean) {
        crashRow.visibility = if (visible) View.VISIBLE else View.GONE
    }

    val view: View = FrameLayout(context).apply {
        setBackgroundColor(Color.BLACK)
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(slot, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
            addView(detail, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(secondary, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        }
        addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
            leftMargin = dp(24)
            rightMargin = dp(24)
            bottomMargin = dp(80)
        })
        addView(settings, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.END))
        addView(swap, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.START))
        addView(icon, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        addView(pip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))
        addView(primary, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(64), Gravity.BOTTOM))
        addView(crashRow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        val version = TextView(context).apply {
            text = "Halone ${BuildConfig.VERSION_NAME}"
            textSize = 12f
            setTextColor(FAINT)
            gravity = Gravity.CENTER
        }
        addView(version, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            (settings.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = bars.top + dp(12)
                rightMargin = bars.right + dp(12)
            }
            (swap.layoutParams as FrameLayout.LayoutParams).apply {
                topMargin = bars.top + dp(12)
                leftMargin = bars.left + dp(12)
            }
            (primary.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = bars.left + dp(24)
                rightMargin = bars.right + dp(24)
                bottomMargin = bars.bottom + dp(48)
            }
            (version.layoutParams as FrameLayout.LayoutParams).bottomMargin = bars.bottom + dp(16)
            (crashRow.layoutParams as FrameLayout.LayoutParams).apply {
                leftMargin = bars.left + dp(24)
                rightMargin = bars.right + dp(24)
                bottomMargin = bars.bottom + dp(48 + 64 + 12)
            }
            requestLayout()
            insets
        }
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeIcons(animate = false) }
    }

    /**
     * [help] is the device's own explanation for [GlassesStatus.LINK_OFF];
     * [notice] is a hint about what just went wrong, shown under the detail.
     */
    fun render(
        status: GlassesStatus,
        model: String?,
        help: String? = null,
        notice: String? = null,
        action: Pair<String, () -> Unit>? = null,
    ) {
        secondary.visibility = if (action != null) View.VISIBLE else View.GONE
        secondary.text = action?.first
        secondary.setPadding(dp(16), dp(10), dp(16), dp(10))
        secondary.setOnClickListener { action?.second?.invoke() }
        this.status = status
        showSwap(status != GlassesStatus.STARTING && status != GlassesStatus.RUNNING)
        dimmed = status.dimmed
        placeIcons(animate = false)
        title.text = status.title
        val text = when (status) {
            GlassesStatus.DISCONNECTED -> "Connect your glasses to the phone's USB-C port"
            GlassesStatus.AWAITING_PERMISSION -> listOfNotNull(model, "Allow Halone to use them").joinToString("\n")
            GlassesStatus.NO_VIDEO_OUTPUT -> "The glasses are connected, but this phone's USB-C port does not send video " +
                "(DisplayPort Alt Mode), so it cannot drive them. Halone needs a phone that can show its screen on a USB-C display."
            GlassesStatus.LINK_OFF -> listOfNotNull(model, help).joinToString("\n")
            else -> model.orEmpty()
        }
        detail.text = listOfNotNull(text.takeIf { it.isNotEmpty() }, notice).joinToString("\n\n")
        primary.setOnClickListener { onPrimary(status) }
        val (label, enabled) = when (status) {
            GlassesStatus.DISCONNECTED -> "Start Environment" to false
            GlassesStatus.AWAITING_PERMISSION -> "Allow Access" to true
            GlassesStatus.NO_VIDEO_OUTPUT -> "Start Environment" to false
            GlassesStatus.LINK_OFF -> "Turn It On" to true
            GlassesStatus.READY -> "Start Environment" to true
            GlassesStatus.STARTING -> "Starting…" to false
            GlassesStatus.RUNNING -> "Stop Environment" to true
        }
        paintPrimary(
            label,
            enabled,
            when {
                !enabled -> DISABLED
                status == GlassesStatus.RUNNING -> STOP
                else -> ACCENT
            },
            dark = enabled && status != GlassesStatus.RUNNING,
        )
    }

    /** Picture in picture's side: it needs nothing of the glasses but [displayConnected]. */
    fun renderPip(displayConnected: Boolean, notice: String? = null) {
        secondary.visibility = View.GONE
        showSwap(true)
        dimmed = !displayConnected
        placeIcons(animate = false)
        title.text = if (displayConnected) "Display Ready" else "No Display Connected"
        val text = if (displayConnected) {
            "Shows this phone's screen in a window on the glasses that you can move and resize"
        } else {
            "Connect your glasses to the phone's USB-C port"
        }
        detail.text = listOfNotNull(text, notice).joinToString("\n\n")
        paintPrimary("Start Picture in Picture", displayConnected, if (displayConnected) PIP else DISABLED, dark = displayConnected)
        primary.setOnClickListener { onStartPip() }
    }

    private fun showSwap(visible: Boolean) {
        swap.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    private fun paintPrimary(label: String, enabled: Boolean, fill: Int, dark: Boolean) {
        primary.text = label
        primary.isEnabled = enabled
        primary.setTextColor(if (dark) Color.BLACK else Color.WHITE)
        primary.background = RippleDrawable(
            ColorStateList.valueOf(PRESSED),
            GradientDrawable().apply {
                cornerRadius = dp(32).toFloat()
                setColor(fill)
            },
            null,
        )
    }

    private fun dp(value: Int) = (value * density).toInt()

    private companion object {
        const val TEST_MODE_TAPS = 10
        const val COUNTDOWN_FROM = 5
        const val TAP_RESET_MS = 3_000L
        const val SWAP_MS = 350L
        const val DIMMED = 0.3f
        const val CORNER_SCALE = 28f / 168f
        val PIP = Color.rgb(0xff, 0x9f, 0x43)
        val ACCENT = Color.rgb(0x5c, 0xc8, 0xff)
        val STOP = Color.rgb(0x5a, 0x1f, 0x24)
        val DISABLED = Color.rgb(0x1d, 0x25, 0x33)
        val MUTED = Color.rgb(0x8b, 0x98, 0xab)
        val FAINT = Color.rgb(0x4a, 0x55, 0x66)
        val PRESSED = Color.argb(0x40, 0xff, 0xff, 0xff)
    }
}
