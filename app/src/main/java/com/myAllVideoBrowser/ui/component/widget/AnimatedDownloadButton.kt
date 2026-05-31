/*
 * Animated circular download button.
 *
 * This file is a faithful Android-View port of the Jetpack Compose original by
 * Mohamed Rejeb (Apache-2.0):
 *   https://github.com/MohamedRejeb/Animated-Circular-Download-Button
 * Geometry, easings and animation timeline mirror the upstream
 * `DownloadButton.kt`, `RoundedCircularProgressIndicator.kt` and `Utils.kt`,
 * adapted to the View / Canvas / ValueAnimator world we already use here.
 *
 * Differences from the upstream:
 *  - Faster morph timeline (the upstream "tap" delay does not fit our flow,
 *    where progress events are continuously streamed by a worker).
 *  - The wave loop pauses (stays frozen on its current phase) instead of
 *    resetting when the row enters a non-downloading state.
 *  - Identity-aware rebinds: callers can [bind] with a stable id and the
 *    timeline is not restarted unless the id actually changes.
 */
package com.myAllVideoBrowser.ui.component.widget

import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.animation.ValueAnimator.INFINITE
import android.animation.ValueAnimator.RESTART
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.animation.doOnEnd
import com.google.android.material.color.MaterialColors
import com.myAllVideoBrowser.R
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Drop-in replacement for the previous `AnimatedVectorDrawable` download icon.
 *
 * Behaviour modes (set via [bind]):
 *  - **Idle**: a static down-arrow over a tray. Used when the row is not
 *    downloading.
 *  - **Active**: morph from arrow → underline → sinusoidal wave that scrolls
 *    while the percentage text fades in. Driven from `[0..1]` progress.
 *  - **Paused**: the wave is shown frozen so the user knows where they were.
 *  - **Done**: the wave collapses and a check-mark is drawn in.
 */
class AnimatedDownloadButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Mode { IDLE, ACTIVE, PAUSED, DONE }

    // region public API ----------------------------------------------------------------

    /** Stroke colour for the arrow + ring. */
    var strokeColor: Int = Color.BLACK
        set(value) {
            field = value
            strokePaint.color = value
            ringPaint.color = value
            ringTrackPaint.color = withAlpha(value, 0.30f)
            percentPaint.color = value
            invalidate()
        }

    /** Stroke thickness (px). Default is 3 dp, matching the source's `strokeSize`. */
    var strokeWidthPx: Float = dp(3f)
        set(value) {
            field = value
            strokePaint.strokeWidth = value
            ringTrackPaint.strokeWidth = value
            ringPaint.strokeWidth = value
            invalidate()
        }

    /** Callback fired on user tap. */
    var onClick: (() -> Unit)? = null

    /** Current mode — exposed for tests and adapters. */
    var mode: Mode = Mode.IDLE
        private set

    /** Last [bind] identity. Lets us skip restarting the timeline on simple rebinds. */
    private var boundId: Long = 0L
    private var progress: Float = 0f

    /**
     * Apply state from a downloads-list rebind. The identity-aware contract:
     *  - Rebinding with the same [id] but updated [progress] / [mode] does
     *    **not** restart the morph animation (so the wave keeps scrolling
     *    smoothly across consecutive 1-second progress emissions).
     *  - A different [id] resets the timeline before applying the new state.
     */
    fun bind(id: Long, mode: Mode, progress: Float) {
        val clamped = progress.coerceIn(0f, 1f)
        if (id != boundId) {
            // New row recycled into this view → reset before applying.
            boundId = id
            cancelAllAnimators()
            resetAnimationFields()
            this.progress = clamped
            applyMode(mode, fromRebind = false)
            return
        }
        val previousProgress = this.progress
        this.progress = clamped
        applyMode(mode, fromRebind = true)
        // Trigger completion phase if progress reached 1f for the first time.
        if (previousProgress < 1f && clamped >= 1f && this.mode == Mode.DONE) {
            triggerCompletionPhaseIfNeeded()
        }
        invalidate()
    }

    /** Snap to the idle state — used when the row is recycled without a rebind. */
    fun release() {
        boundId = 0L
        cancelAllAnimators()
        resetAnimationFields()
        mode = Mode.IDLE
        progress = 0f
        invalidate()
    }

    // endregion ------------------------------------------------------------------------

    // region paints / paths ------------------------------------------------------------

    private val ringTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = strokeWidthPx
        color = withAlpha(strokeColor, 0.30f)
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = strokeWidthPx
        color = strokeColor
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = strokeWidthPx
        color = strokeColor
    }

    private val percentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = sp(11f)
        color = strokeColor
        isFakeBoldText = true
    }

    private val downloadPath = Path()
    private val ringRect = RectF()

    // endregion ------------------------------------------------------------------------

    // region animators -----------------------------------------------------------------

    /** Mirrors the seven Compose `Animatable`s in the source. */
    private var animationOne = 0f          // shaft length collapses 0..1
    private var animationTwo = 0f          // arrow head spreads to under-line
    private var animationThree = 0f        // arrow head pulled up to the tray
    private var animationFour = 0f         // sinusoidal wave reveal
    private var animationFive = 0f         // wave horizontal scroll (looping)
    private var animationSix = 0f          // wave collapses
    private var animationSeven = 0f        // check-mark draw-in

    private var morphSet: AnimatorSet? = null
    private var waveAnimator: ValueAnimator? = null
    private var completionSet: AnimatorSet? = null
    private var completionTriggered = false
    private var morphInProgress = false

    /**
     * Snappier than the upstream defaults. The original used 200ms with a
     * 1.5×duration delay for branch B, which is ~1.1s before the wave even
     * appears. Here the wave reveals in ~250ms total so progress feels live.
     */
    private val morphDurationMs = 90L

    // endregion ------------------------------------------------------------------------

    // region init / sizing -------------------------------------------------------------

    init {
        // Pick a sensible default stroke colour from the theme.
        strokeColor = MaterialColors.getColor(
            this,
            R.attr.colorPrimary,
            androidx.core.content.ContextCompat.getColor(context, R.color.brand_accent),
        )
        val min = dp(28f).toInt()
        minimumWidth = min
        minimumHeight = min
        isClickable = true
        isFocusable = true
        // A circular ripple gives the canvas button the same tactile feedback
        // the sibling play/close buttons get from selectableItemBackground.
        setupRippleForeground()
    }

    /**
     * Apply a borderless (circular) ripple as the view's foreground so a tap
     * shows a Material ripple. We resolve `selectableItemBackgroundBorderless`
     * from the theme to stay consistent with the play / close buttons in the
     * same row.
     */
    private fun setupRippleForeground() {
        try {
            val outValue = TypedValue()
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless, outValue, true
            )
            if (outValue.resourceId != 0) {
                foreground = androidx.core.content.ContextCompat.getDrawable(
                    context, outValue.resourceId
                )
            }
        } catch (_: Throwable) {
            // Foreground ripple is a nicety — ignore if the theme can't supply it.
        }
    }

    /**
     * Quick press-in / release scale so a tap on the download button is
     * unmistakably felt, even while the morphing animation is mid-flight.
     * Driven directly off touch so it fires the instant the finger lands.
     */
    private var pressAnimator: ValueAnimator? = null

    private fun animatePressScale(pressedDown: Boolean) {
        pressAnimator?.cancel()
        val target = if (pressedDown) 0.84f else 1f
        pressAnimator = ValueAnimator.ofFloat(scaleX, target).apply {
            duration = if (pressedDown) 90L else 160L
            interpolator = if (pressedDown) LinearInterpolator() else OvershootInterpolator(2.2f)
            addUpdateListener { v ->
                val s = v.animatedValue as Float
                scaleX = s
                scaleY = s
            }
            start()
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (isEnabled) animatePressScale(true)
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> animatePressScale(false)
        }
        return super.onTouchEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // The source is constrained to a square; keep that here so the
        // geometry calculations below work uniformly.
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val side = max(min(w, h), suggestedMinimumWidth)
        val spec = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY)
        super.onMeasure(spec, spec)
    }

    private fun min(a: Int, b: Int) = if (a < b) a else b

    // endregion ------------------------------------------------------------------------

    // region drawing -------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        drawRing(canvas)
        drawGlyph(canvas)
        drawPercent(canvas)
    }

    private fun drawRing(canvas: Canvas) {
        val pad = strokeWidthPx / 2f
        ringRect.set(pad, pad, width - pad, height - pad)
        canvas.drawArc(ringRect, 270f, 360f, false, ringTrackPaint)
        canvas.drawArc(ringRect, 270f, progress * 360f, false, ringPaint)
    }

    private fun drawGlyph(canvas: Canvas) {
        val side = width.coerceAtMost(height) * 0.5f
        val left = (width - side) / 2f
        val top = (height - side) / 2f

        canvas.save()
        canvas.translate(left, top)

        downloadPath.reset()

        val w = side
        val h = side
        val arrowStartY = h * 3f / 5f

        // ── Vertical shaft ────────────────────────────────────────────────────
        val shaftHeight = h * (1f - animationOne)
        val shaftYBase = (h - shaftHeight) / 2f * (1f - animationThree)
        val shaftYOffset = animationThree * (h * 0.5f - dp(4f))
        val shaftY = shaftYBase - shaftYOffset

        downloadPath.moveTo(w / 2f, shaftY)
        downloadPath.lineTo(w / 2f, shaftY + shaftHeight)

        val lineWidth = w - (w * (2f / 6f) * (1f - animationTwo))
        val lineX = 0f + (w * (1f / 6f) * (1f - animationTwo))

        when {
            // Phase A: arrow head spreading into under-line.
            animationThree != 1f -> {
                downloadPath.moveTo(lineX, arrowStartY)
                downloadPath.quadTo(
                    lineWidth / 4f + lineX,
                    arrowStartY + ((h - arrowStartY) / (2f - animationTwo) * (1f - animationTwo)),
                    w / 2f,
                    arrowStartY + (h - arrowStartY) * (1f - animationTwo)
                )
                downloadPath.moveTo(lineX + lineWidth, arrowStartY)
                downloadPath.quadTo(
                    lineWidth * 3f / 4f + lineX,
                    arrowStartY + ((h - arrowStartY) / (2f - animationTwo) * (1f - animationTwo)),
                    w / 2f,
                    arrowStartY + (h - arrowStartY) * (1f - animationTwo)
                )
            }

            // Phase B: sinusoidal wave (downloading + completion collapse).
            animationFour > 0f && animationSeven == 0f -> {
                val sineW = lineWidth
                val sineH = h * 1f / 5f
                val startOffset = sineW * (1f - animationFour)
                val endOffset = sineW * animationSix
                val startPct = animationFive
                // Push the wave a few dp up from the arrow baseline so it
                // does not overlap the percentage text drawn under it.
                val waveLift = dp(6f)
                buildSinusoidalPath(
                    path = downloadPath,
                    width = sineW,
                    height = sineH,
                    startOffset = startOffset,
                    endOffset = endOffset,
                    startPercentage = startPct,
                    xOrigin = lineX,
                    yOrigin = arrowStartY - sineH / 2f - waveLift,
                )
            }

            // Phase C: check-mark draw-in.
            else -> {
                val checkH = h * 3f / 6f
                val checkW = w - (w * (2f / 6f) * animationSeven)
                val checkX = 0f + (w * (1f / 6f) * animationSeven)
                downloadPath.moveTo(
                    checkX,
                    arrowStartY - checkH * 0.25f * animationSeven
                )
                downloadPath.lineTo(
                    checkX + checkW / 3f,
                    arrowStartY + checkH * 0.25f * animationSeven
                )
                downloadPath.lineTo(
                    checkX + checkW,
                    arrowStartY - checkH * 0.75f * animationSeven
                )
            }
        }

        canvas.drawPath(downloadPath, strokePaint)
        canvas.restore()
    }

    private fun drawPercent(canvas: Canvas) {
        val alpha = if (animationSeven > 0f) {
            1f - animationSeven
        } else {
            animationFour
        }
        if (alpha <= 0f) return
        percentPaint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
        val text = "${(progress * 100f).roundToInt()}%"
        val cx = width / 2f
        // Sit a bit lower than the icon's vertical center so we leave room
        // for the wave that animates above us.
        val baseY = height * 0.72f + percentPaint.textSize / 2f
        canvas.drawText(text, cx, baseY, percentPaint)
    }

    private fun buildSinusoidalPath(
        path: Path,
        width: Float,
        height: Float,
        startOffset: Float,
        endOffset: Float,
        startPercentage: Float,
        xOrigin: Float,
        yOrigin: Float,
    ) {
        val verticalCenter = height / 2f
        val rangeEnd = (width - startOffset - endOffset).toInt()
        var first = true
        var x = 0
        while (x < rangeEnd) {
            val phase = (x + endOffset + startPercentage * width) * (2f * Math.PI / width)
            val y = (sin(phase) * verticalCenter + verticalCenter).toFloat()
            val px = x.toFloat() + startOffset + xOrigin
            val py = y + yOrigin
            if (first) {
                path.moveTo(px, py)
                first = false
            } else {
                path.lineTo(px, py)
            }
            x += 10
        }
        if (first || endOffset > 0f) {
            path.lineTo(width - endOffset + xOrigin, height / 2f + yOrigin)
            path.lineTo(width + xOrigin, height / 2f + yOrigin)
        }
    }

    // endregion ------------------------------------------------------------------------

    // region animation orchestration ---------------------------------------------------

    private fun applyMode(newMode: Mode, fromRebind: Boolean) {
        val previous = mode
        mode = newMode

        when (newMode) {
            Mode.IDLE -> {
                cancelAllAnimators()
                resetAnimationFields()
                invalidate()
            }
            Mode.ACTIVE -> {
                // If we are already past the morph and the wave is running,
                // keep going. Otherwise, kick off the morph timeline.
                if (animationFour < 1f && !morphInProgress) {
                    startMorphAnimations()
                } else if (waveAnimator?.isStarted != true && animationFour >= 1f) {
                    startWaveLoop()
                }
                invalidate()
            }
            Mode.PAUSED -> {
                // Show the idle download arrow so the user can tell at a glance
                // that the download is stopped. The progress ring around it
                // still indicates how far we got. Resuming (ACTIVE) replays
                // the morph timeline from the start.
                cancelAllAnimators()
                resetAnimationFields()
                invalidate()
            }
            Mode.DONE -> {
                if (animationFour < 1f) snapToWaveReady()
                triggerCompletionPhaseIfNeeded()
                invalidate()
            }
        }
    }

    /** Forcibly set the timeline to the "wave is ready" baseline. */
    private fun snapToWaveReady() {
        animationOne = 1f
        animationTwo = 1f
        animationThree = 1f
        animationFour = 1f
    }

    private fun resetAnimationFields() {
        animationOne = 0f
        animationTwo = 0f
        animationThree = 0f
        animationFour = 0f
        animationFive = 0f
        animationSix = 0f
        animationSeven = 0f
        completionTriggered = false
        morphInProgress = false
    }

    private fun startMorphAnimations() {
        cancelAllAnimators()
        completionTriggered = false
        morphInProgress = true

        // Branch A — animationOne (linear) then animationTwo (overshoot cubic).
        val one = floatAnim(0f, 1f, morphDurationMs, LinearInterpolator())
            .also { it.addUpdateListener { v -> animationOne = v.animatedValue as Float; invalidate() } }
        val two = floatAnim(0f, 1f, morphDurationMs, OvershootCubic)
            .also { it.addUpdateListener { v -> animationTwo = v.animatedValue as Float; invalidate() } }
        val branchA = AnimatorSet().apply { playSequentially(one, two) }

        // Branch B — animationThree (ease-out, slight delay) then animationFour
        // (linear). We then transition to the looping wave.
        val three = floatAnim(0f, 1f, morphDurationMs, EaseOut).apply {
            startDelay = (morphDurationMs * 0.5f).toLong()
            addUpdateListener { v -> animationThree = v.animatedValue as Float; invalidate() }
        }
        val four = floatAnim(0f, 1f, 220L, LinearInterpolator()).apply {
            addUpdateListener { v -> animationFour = v.animatedValue as Float; invalidate() }
        }
        val branchB = AnimatorSet().apply {
            playSequentially(three, four)
            doOnEnd {
                morphInProgress = false
                if (mode == Mode.ACTIVE || mode == Mode.DONE) startWaveLoop()
                if (mode == Mode.DONE) triggerCompletionPhaseIfNeeded()
            }
        }

        morphSet = AnimatorSet().apply {
            playTogether(branchA, branchB)
            start()
        }
    }

    private fun startWaveLoop() {
        if (waveAnimator?.isStarted == true) return
        waveAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 750L                 // smooth, not too fast
            interpolator = LinearInterpolator()
            repeatCount = INFINITE
            repeatMode = RESTART
            addUpdateListener { v ->
                animationFive = v.animatedValue as Float
                invalidate()
                if (mode == Mode.DONE && !completionTriggered && animationFive >= 0.9f) {
                    triggerCompletionPhaseIfNeeded()
                }
            }
            start()
        }
    }

    /**
     * Mirrors the upstream `LaunchedEffect(progress, animationFive)`: when
     * the row reaches DONE and the wave loop is past 90% of its cycle, snap
     * the wave back, then run animationSix (collapse) and animationSeven
     * (check-mark draw-in) sequentially.
     */
    private fun triggerCompletionPhaseIfNeeded() {
        if (completionTriggered) return
        if (mode != Mode.DONE) return
        // If the wave isn't running yet (e.g. snapped DONE before morph), just
        // run the completion phase from the current baseline.
        if (waveAnimator?.isStarted == true && animationFive < 0.9f) return
        completionTriggered = true
        waveAnimator?.cancel()
        waveAnimator = null
        animationFive = 0f

        val six = floatAnim(0f, 1f, 450L, LinearInterpolator()).apply {
            addUpdateListener { v -> animationSix = v.animatedValue as Float; invalidate() }
        }
        val seven = floatAnim(0f, 1f, 450L, LinearInterpolator()).apply {
            addUpdateListener { v -> animationSeven = v.animatedValue as Float; invalidate() }
        }
        completionSet = AnimatorSet().apply {
            playSequentially(six, seven)
            start()
        }
    }

    private fun cancelAllAnimators() {
        morphSet?.cancel(); morphSet = null
        waveAnimator?.cancel(); waveAnimator = null
        completionSet?.cancel(); completionSet = null
        morphInProgress = false
    }

    override fun onDetachedFromWindow() {
        cancelAllAnimators()
        pressAnimator?.cancel()
        pressAnimator = null
        super.onDetachedFromWindow()
    }

    override fun performClick(): Boolean {
        onClick?.invoke()
        return super.performClick()
    }

    // endregion ------------------------------------------------------------------------

    // region helpers -------------------------------------------------------------------

    private fun floatAnim(
        from: Float,
        to: Float,
        durationMs: Long,
        interpolator: android.animation.TimeInterpolator,
    ): ValueAnimator = ValueAnimator.ofFloat(from, to).apply {
        duration = durationMs
        this.interpolator = interpolator
    }

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun withAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha).roundToInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    /**
     * Cubic-bezier overshoot easing equivalent to the Compose
     * `CubicBezierEasing(0.34f, 1.8f, 0.64f, 1f)` used for animationTwo.
     */
    private object OvershootCubic : android.animation.TimeInterpolator {
        private val cb = CubicBezierInterpolator(0.34f, 1.8f, 0.64f, 1f)
        override fun getInterpolation(t: Float): Float = cb.getInterpolation(t)
    }

    /**
     * Compose's `EaseOut` — equivalent to `CubicBezierEasing(0f, 0f, 0.58f, 1f)`.
     */
    private object EaseOut : android.animation.TimeInterpolator {
        private val cb = CubicBezierInterpolator(0f, 0f, 0.58f, 1f)
        override fun getInterpolation(t: Float): Float = cb.getInterpolation(t)
    }

    private class CubicBezierInterpolator(
        private val x1: Float, private val y1: Float,
        private val x2: Float, private val y2: Float,
    ) : android.animation.TimeInterpolator {

        override fun getInterpolation(input: Float): Float {
            var t = input
            repeat(8) {
                val xt = bezier(t, x1, x2)
                val dxt = bezierDerivative(t, x1, x2)
                if (dxt == 0f) return@repeat
                t -= (xt - input) / dxt
                t = t.coerceIn(0f, 1f)
            }
            return bezier(t, y1, y2)
        }

        private fun bezier(t: Float, p1: Float, p2: Float): Float {
            val it = 1f - t
            return 3f * it * it * t * p1 + 3f * it * t * t * p2 + t * t * t
        }

        private fun bezierDerivative(t: Float, p1: Float, p2: Float): Float {
            val it = 1f - t
            return 3f * it * it * p1 + 6f * it * t * (p2 - p1) + 3f * t * t * (1f - p2)
        }
    }

    // endregion ------------------------------------------------------------------------
}
