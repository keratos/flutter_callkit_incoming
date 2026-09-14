package com.hiennv.flutter_callkit_incoming.widgets

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.TypedArray
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.RelativeLayout
import com.hiennv.flutter_callkit_incoming.R
import com.hiennv.flutter_callkit_incoming.Utils
import kotlin.math.min


class RippleRelativeLayout : RelativeLayout {
    private var rippleColor = 0
    private var rippleRadius = 0f
    private var rippleDurationTime = 0
    private var rippleAmount = 0
    private var rippleDelay = 0
    private var rippleScale = 0f
    private var paint: Paint = Paint()
    var isRippleAnimationRunning = false
        private set
    private var animatorSet: AnimatorSet? = null
    private var animatorList: ArrayList<Animator>? = null
    private var rippleParams: LayoutParams? = null
    private val rippleViewList = ArrayList<RippleView>()

    constructor(context: Context?) : super(context) {}
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs) {
        init(context, attrs)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context,
        attrs,
        defStyleAttr
    ) {
        init(context, attrs)
    }

    @SuppressLint("CustomViewStyleable")
    private fun init(context: Context, attrs: AttributeSet?) {
        if (isInEditMode) return

        val typedArray: TypedArray =
            context.obtainStyledAttributes(attrs, R.styleable.ripple_relativeLayout)
        rippleColor = typedArray.getColor(
            R.styleable.ripple_relativeLayout_ripple_color,
            resources.getColor(R.color.ripple_main_color)
        )
        rippleRadius = typedArray.getDimension(
            R.styleable.ripple_relativeLayout_ripple_radius,
            Utils.dpToPx(30f)
        )
        rippleDurationTime = typedArray.getInt(
            R.styleable.ripple_relativeLayout_ripple_duration,
            DEFAULT_DURATION_TIME
        )
        rippleAmount =
            typedArray.getInt(R.styleable.ripple_relativeLayout_ripple_amount, DEFAULT_RIPPLE_COUNT)
        rippleScale =
            typedArray.getFloat(R.styleable.ripple_relativeLayout_ripple_scale, DEFAULT_SCALE)
        typedArray.recycle()

        rippleDelay = rippleDurationTime / rippleAmount
        paint = Paint()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = rippleColor
        rippleParams = LayoutParams(
            (2 * (rippleRadius)).toInt(),
            (2 * (rippleRadius)).toInt()
        )
        rippleParams!!.addRule(CENTER_IN_PARENT, TRUE)
        animatorSet = AnimatorSet()
        animatorSet!!.interpolator = AccelerateDecelerateInterpolator()
        animatorList = ArrayList()
        for (i in 0 until rippleAmount) {
            val rippleView: RippleView = RippleView(getContext())
            addView(rippleView, rippleParams)
            rippleViewList.add(rippleView)
            val scaleXAnimator = ObjectAnimator.ofFloat(rippleView, "ScaleX", 1.0f, rippleScale)
            scaleXAnimator.repeatCount = ObjectAnimator.INFINITE
            scaleXAnimator.repeatMode = ObjectAnimator.RESTART
            scaleXAnimator.duration = rippleDurationTime.toLong()
            animatorList!!.add(scaleXAnimator)
            val scaleYAnimator = ObjectAnimator.ofFloat(rippleView, "ScaleY", 1.0f, rippleScale)
            scaleYAnimator.repeatCount = ObjectAnimator.INFINITE
            scaleYAnimator.repeatMode = ObjectAnimator.RESTART
            scaleYAnimator.duration = rippleDurationTime.toLong()
            animatorList!!.add(scaleYAnimator)
            val alphaAnimator = ObjectAnimator.ofFloat(rippleView, "Alpha", 1.0f, 0f)
            alphaAnimator.repeatCount = ObjectAnimator.INFINITE
            alphaAnimator.repeatMode = ObjectAnimator.RESTART
            alphaAnimator.duration = rippleDurationTime.toLong()
            animatorList!!.add(alphaAnimator)
        }
        animatorSet!!.playTogether(animatorList)
        startRippleAnimation()
    }

    private inner class RippleView(context: Context?) : View(context) {
        override fun onDraw(canvas: Canvas) {
            val radius = min(width, height) / 2f
            canvas.drawCircle(radius, radius, radius, paint!!)
        }

        init {
            visibility = INVISIBLE
        }
    }

    fun startRippleAnimation() {
        if (!isRippleAnimationRunning) {
            for (rippleView in rippleViewList) {
                rippleView.visibility = VISIBLE
            }
            // [breizh-voip] Halo complet dès la première image. Avant, le i-ème
            // anneau ne partait qu'après i × (durée / nombre) : à l'ouverture
            // de l'écran, un seul anneau minuscule, et le motif entier ~5 s plus
            // tard (6 s / 5 anneaux). Chaque anneau est désormais lancé tout de
            // suite, déjà avancé à sa phase — le régime établi, immédiatement.
            // Les animateurs sont démarrés un à un, pas via l'AnimatorSet : le
            // set recalcule le temps de ses enfants à chaque image et écraserait
            // la phase posée. (3 animateurs par anneau : ScaleX, ScaleY, Alpha.)
            animatorList?.forEachIndexed { index, animator ->
                val anneau = index / 3
                val phase = ((rippleAmount - 1 - anneau) * rippleDelay).toLong()
                animator.start()
                (animator as? ObjectAnimator)?.currentPlayTime = phase
            }
            isRippleAnimationRunning = true
        }
    }

    fun stopRippleAnimation() {
        if (isRippleAnimationRunning) {
            animatorList?.forEach { it.end() }
            isRippleAnimationRunning = false
        }
    }

    companion object {
        private const val DEFAULT_RIPPLE_COUNT = 5
        private const val DEFAULT_DURATION_TIME = 6000
        private const val DEFAULT_SCALE = 6.0f
    }
}