package com.kzvpn.app

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import kotlin.math.min

/** Resolution-independent reproduction of the approved mint/teal power button. */
class EneidaPowerButton(context: Context) : View(context) {
    enum class Mode { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipCircle = Path()
    private val arcBounds = RectF()
    private val progressBounds = RectF()
    private var offSurface: Shader? = null
    private var onSurface: Shader? = null
    private var cx = 0f
    private var cy = 0f
    private var radius = 0f
    private var diameter = 0f
    private var mode = Mode.DISCONNECTED
    private var caption = "Включить"
    private var connectedFraction = 0f
    private var pressFraction = 0f
    private var rippleFraction = 1f
    private var successFraction = 1f
    private var rotation = 0f
    private var touchX = 0f
    private var touchY = 0f
    private var effectsActive = true
    private var trackingTouch = false
    private var transition: ValueAnimator? = null
    private var press: ValueAnimator? = null
    private var ripple: ValueAnimator? = null
    private var success: ValueAnimator? = null
    private var progress: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        defaultFocusHighlightEnabled = false
        isHapticFeedbackEnabled = true
        contentDescription = "Включить VPN"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setVisualState(next: Mode, label: String, enabled: Boolean) {
        if (mode == next && caption == label && isEnabled == enabled) {
            syncProgress()
            return
        }
        val previous = mode
        mode = next
        caption = label
        isEnabled = enabled
        contentDescription = when (next) {
            Mode.CONNECTED -> "Выключить VPN"
            Mode.CONNECTING -> "Подключение VPN"
            Mode.DISCONNECTING -> "Выключение VPN"
            Mode.DISCONNECTED -> if (label == "Включить") "Включить VPN" else label
        }
        if (Build.VERSION.SDK_INT >= 30) {
            stateDescription = when (next) {
                Mode.DISCONNECTED -> "VPN отключён"
                Mode.CONNECTING -> "Подключение"
                Mode.CONNECTED -> "VPN подключён"
                Mode.DISCONNECTING -> "Отключение"
            }
        }
        val target = if (next == Mode.CONNECTED || next == Mode.DISCONNECTING) 1f else 0f
        transition?.cancel()
        transition = animate(connectedFraction, target, 420L) { connectedFraction = it }
        if (previous == Mode.CONNECTING && next == Mode.CONNECTED) {
            success?.cancel()
            success = animate(0f, 1f, 760L) { successFraction = it }
        }
        syncProgress()
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        invalidate()
    }

    fun setEffectsActive(active: Boolean) {
        effectsActive = active
        if (active) syncProgress() else stopEffects()
    }

    fun stopEffects() {
        listOf(transition, press, ripple, success, progress).forEach { it?.cancel() }
        transition = null
        press = null
        ripple = null
        success = null
        progress = null
        trackingTouch = false
        isPressed = false
        pressFraction = 0f
        rippleFraction = 1f
        successFraction = 1f
        connectedFraction = if (mode == Mode.CONNECTED || mode == Mode.DISCONNECTING) 1f else 0f
        invalidate()
    }

    private fun canAnimate() = effectsActive && isAttachedToWindow &&
        windowVisibility == VISIBLE && isShown && ValueAnimator.areAnimatorsEnabled()

    private fun animate(from: Float, to: Float, durationMs: Long, update: (Float) -> Unit): ValueAnimator? {
        if (!canAnimate() || from == to) {
            update(to)
            invalidate()
            return null
        }
        update(from)
        return ValueAnimator.ofFloat(from, to).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                update(it.animatedValue as Float)
                invalidate()
            }
            start()
        }
    }

    private fun syncProgress() {
        val busy = mode == Mode.CONNECTING || mode == Mode.DISCONNECTING
        if (busy && canAnimate()) {
            if (progress?.isRunning != true) {
                progress = ValueAnimator.ofFloat(0f, 360f).apply {
                    duration = 1_500L
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener { rotation = it.animatedValue as Float; invalidate() }
                    start()
                }
            }
        } else {
            progress?.cancel()
            progress = null
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        syncProgress()
    }

    override fun onDetachedFromWindow() {
        stopEffects()
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (isAttachedToWindow) {
            if (visibility == VISIBLE) syncProgress() else stopEffects()
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow) {
            if (isShown) syncProgress() else stopEffects()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w / 2f
        cy = h / 2f
        diameter = min(w, h) * 0.78f
        radius = diameter / 2f
        if (radius <= 0f) return
        clipCircle.rewind()
        clipCircle.addCircle(cx, cy, radius, Path.Direction.CW)
        offSurface = RadialGradient(cx - radius * 0.35f, cy - radius * 0.55f, radius * 1.9f,
            intArrayOf(Color.rgb(160, 248, 230), EneidaTheme.ACCENT, Color.rgb(61, 207, 178)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        onSurface = RadialGradient(cx - radius * 0.25f, cy - radius * 0.5f, radius * 1.8f,
            intArrayOf(Color.rgb(34, 79, 69), Color.rgb(19, 56, 47), Color.rgb(10, 39, 33)),
            floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
        haloPaint.shader = RadialGradient(cx, cy, radius * 1.34f,
            intArrayOf(Color.argb(0, 120, 228, 203), Color.argb(8, 120, 228, 203),
                Color.argb(100, 120, 228, 203), Color.argb(0, 120, 228, 203),
                Color.argb(0, 120, 228, 203)),
            floatArrayOf(0f, 0.52f, 0.735f, 0.92f, 1f), Shader.TileMode.CLAMP)
        shadowPaint.shader = RadialGradient(cx, cy + radius * 0.035f, radius * 1.2f,
            intArrayOf(Color.argb(95, 0, 0, 0), Color.argb(60, 0, 0, 0),
                Color.TRANSPARENT, Color.TRANSPARENT),
            floatArrayOf(0f, 0.78f, 0.88f, 1f), Shader.TileMode.CLAMP)
        val glyphRadius = diameter * 0.16f
        val glyphY = cy - diameter * 0.06f
        arcBounds.set(cx - glyphRadius, glyphY - glyphRadius, cx + glyphRadius, glyphY + glyphRadius)
        progressBounds.set(cx - radius - diameter * 0.035f, cy - radius - diameter * 0.035f,
            cx + radius + diameter * 0.035f, cy + radius + diameter * 0.035f)
        touchX = cx
        touchY = cy
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (radius <= 0f) return
        val busy = mode == Mode.CONNECTING || mode == Mode.DISCONNECTING
        val unavailable = !isEnabled && !busy && mode == Mode.DISCONNECTED
        val mix = connectedFraction.coerceIn(0f, 1f)
        val save = canvas.save()
        val scale = 1f - pressFraction * 0.035f
        canvas.scale(scale, scale, cx, cy)

        haloPaint.alpha = (255f * if (unavailable) 0f else maxOf(mix, if (busy) 0.35f else 0f)).toInt()
        // Keep the gradient boundary outside the geometry to avoid a GPU hairline at its end stop.
        canvas.drawCircle(cx, cy, radius * 1.25f, haloPaint)
        canvas.drawCircle(cx, cy + radius * 0.035f, radius * 1.08f, shadowPaint)
        bodyPaint.alpha = 255
        bodyPaint.shader = if (unavailable) null else offSurface
        bodyPaint.color = EneidaTheme.SURFACE_RAISED
        canvas.drawCircle(cx, cy, radius, bodyPaint)
        if (!unavailable && mix > 0f) {
            bodyPaint.shader = onSurface
            bodyPaint.alpha = (255 * mix).toInt()
            canvas.drawCircle(cx, cy, radius, bodyPaint)
        }
        bodyPaint.shader = null
        bodyPaint.alpha = 255

        if (rippleFraction < 1f) {
            val clip = canvas.save()
            canvas.clipPath(clipCircle)
            ripplePaint.color = if (mix > 0.5f) Color.WHITE else EneidaTheme.ON_ACCENT
            ripplePaint.alpha = ((1f - rippleFraction) * 35).toInt()
            canvas.drawCircle(touchX, touchY, diameter * 0.9f * rippleFraction, ripplePaint)
            canvas.restoreToCount(clip)
        }
        linePaint.strokeWidth = diameter * 0.0105f
        linePaint.color = if (unavailable) EneidaTheme.BORDER else blend(Color.argb(28, 210, 255, 245), EneidaTheme.ACCENT, mix)
        canvas.drawCircle(cx, cy, radius - linePaint.strokeWidth / 2f, linePaint)

        if (busy) {
            linePaint.color = EneidaTheme.ACCENT
            linePaint.strokeWidth = diameter * 0.012f
            canvas.drawArc(progressBounds, rotation - 90f, 74f, false, linePaint)
        }
        if (successFraction < 1f) {
            linePaint.color = Color.argb(((1f - successFraction) * 135).toInt(), 120, 228, 203)
            linePaint.strokeWidth = diameter * 0.009f
            canvas.drawCircle(cx, cy, radius * (1f + successFraction * 0.22f), linePaint)
        }

        // Keep the foreground readable while the background crossfades.
        val darkForeground = mix < 0.56f
        val glyph = if (unavailable) EneidaTheme.TEXT_SOFT
            else if (darkForeground) EneidaTheme.BACKGROUND else EneidaTheme.ACCENT
        linePaint.color = glyph
        linePaint.strokeWidth = diameter * 0.047f
        canvas.drawArc(arcBounds, -50f, 280f, false, linePaint)
        canvas.drawLine(cx, cy - diameter * 0.24f, cx, cy - diameter * 0.08f, linePaint)
        textPaint.color = if (unavailable) EneidaTheme.TEXT_SOFT
            else if (darkForeground) EneidaTheme.BACKGROUND else EneidaTheme.TEXT_PRIMARY
        val desiredText = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
            diameter * 0.098f / resources.displayMetrics.density, resources.displayMetrics)
        textPaint.textSize = desiredText
        val width = textPaint.measureText(caption)
        if (width > diameter * 0.82f) textPaint.textSize = desiredText * diameter * 0.82f / width
        canvas.drawText(caption, cx, cy + diameter * 0.265f, textPaint)

        if (isFocused && !isInTouchMode) {
            linePaint.color = EneidaTheme.ACCENT
            linePaint.strokeWidth = resources.displayMetrics.density * 1.5f
            canvas.drawCircle(cx, cy, radius + resources.displayMetrics.density * 6f, linePaint)
        }
        canvas.restoreToCount(save)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val inside = (event.x - cx) * (event.x - cx) + (event.y - cy) * (event.y - cy) <= radius * radius
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isEnabled || !inside) return false
                trackingTouch = true
                touchX = event.x
                touchY = event.y
                press?.cancel()
                press = animate(pressFraction, 1f, 90L) { pressFraction = it }
            }
            MotionEvent.ACTION_MOVE -> {
                if (trackingTouch && !inside) {
                    trackingTouch = false
                    press?.cancel()
                    press = animate(pressFraction, 0f, 180L) { pressFraction = it }
                    cancelTouch(event)
                    return true
                }
                if (!trackingTouch) return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                press?.cancel()
                press = animate(pressFraction, 0f, 180L) { pressFraction = it }
                val cancelled = !trackingTouch || !inside
                trackingTouch = false
                if (cancelled && event.actionMasked == MotionEvent.ACTION_UP) {
                    cancelTouch(event)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun cancelTouch(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event)
        cancel.action = MotionEvent.ACTION_CANCEL
        super.onTouchEvent(cancel)
        cancel.recycle()
    }

    override fun performClick(): Boolean {
        if (!isEnabled) return false
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        ripple?.cancel()
        ripple = animate(0f, 1f, 520L) { rippleFraction = it }
        return super.performClick()
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.text = caption
        info.isClickable = isClickable
        info.isEnabled = isEnabled
    }

    private fun blend(from: Int, to: Int, fraction: Float): Int {
        fun channel(a: Int, b: Int) = (a + (b - a) * fraction).toInt().coerceIn(0, 255)
        return Color.argb(channel(Color.alpha(from), Color.alpha(to)),
            channel(Color.red(from), Color.red(to)), channel(Color.green(from), Color.green(to)),
            channel(Color.blue(from), Color.blue(to)))
    }
}
