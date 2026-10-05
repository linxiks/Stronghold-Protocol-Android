package com.stronghold.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tan

// 视觉基础：复刻上游 public/css/theme.css、components.css、screens/title.css 的配色与装饰。

internal fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

internal fun Context.dpf(value: Float): Float = value * resources.displayMetrics.density

internal fun Context.styledText(value: String, sizeSp: Float, color: Int): TextView = TextView(this).apply {
    text = value
    textSize = sizeSp
    setTextColor(color)
    includeFontPadding = false
}

/** 上游 `.micro`：Novecento 小号大写注记。 */
internal fun Context.microText(value: String, color: Int): TextView = styledText(value, 9f, color).apply {
    typeface = StrongholdFonts.display(this@microText)
    letterSpacing = 0.18f
}

internal object Palette {
    const val BG_TOP = 0xFF0B0E0D.toInt()
    const val BG_MID = 0xFF0E1311.toInt()
    const val BG_BOTTOM = 0xFF0A0D0C.toInt()

    const val LINE = 0xFF2F3A35.toInt()
    const val LINE_2 = 0xFF3E4B45.toInt()
    const val LINE_3 = 0xFF58675F.toInt()

    const val MINT_400 = 0xFF59F4CA.toInt()
    const val MINT_500 = 0xFF4ED8AF.toInt()
    const val MINT_700 = 0xFF2A9E7F.toInt()
    const val MINT_BORDER = 0xFF6FE8C4.toInt()
    const val MINT_BORDER_HOVER = 0xFF9DFFE2.toInt()

    const val TEXT_HI = 0xFFF2F2F2.toInt()
    const val TEXT_MD = 0xFFC3CBC7.toInt()
    const val TEXT_LO = 0xFF8A948F.toInt()
    const val TEXT_DIM = 0xFF5D6863.toInt()
    const val TEXT_ON_MINT = 0xFF06110D.toInt()
    const val TITLE_EN = 0xFFCDD6D1.toInt()
    const val TITLE_CN = 0xFFF4F6F5.toInt()
    const val DANGER = 0xFFFF5454.toInt()

    const val PANEL_TOP = 0xD1141917.toInt()
    const val PANEL_BOTTOM = 0xE00A0D0C.toInt()
    const val FIELD_BG = 0xC7050706.toInt()
    const val BTN_SECONDARY = 0xE6141816.toInt()
    const val BTN_SECONDARY_PRESSED = 0xFF222A26.toInt()
    const val RIPPLE = 0x334ED8AF
}

/** 上游字体：标题用 Novecento Wide，数字/地址用 Bender。加载失败时回退系统字体，且不再重试。 */
internal object StrongholdFonts {
    private const val TAG = "StrongholdStyle"

    @Volatile private var displayFace: Typeface? = null
    @Volatile private var numberFace: Typeface? = null

    fun display(context: Context): Typeface =
        displayFace ?: load(context, "fonts/novecento-wide-normal.otf").also { displayFace = it }

    fun number(context: Context): Typeface =
        numberFace ?: load(context, "fonts/bender-regular.otf").also { numberFace = it }

    private fun load(context: Context, path: String): Typeface = try {
        Typeface.createFromAsset(context.assets, path)
    } catch (e: RuntimeException) {
        Log.w(TAG, "Font unavailable: $path", e)
        Typeface.DEFAULT
    }
}

/** 对应上游中文标题的 font-weight: 900；API 28 以下只有粗体可用。 */
internal fun heavyTypeface(): Typeface =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        Typeface.create(Typeface.DEFAULT, 900, false)
    } else {
        Typeface.DEFAULT_BOLD
    }

/** 复刻上游 `.app-bg`：竖直渐变 + 底部薄荷色辉光 + 网格 + 暗角。 */
internal class TacticalBackgroundDrawable(
    private val gridStepPx: Float,
    private val linePx: Float,
) : Drawable() {

    private val basePaint = Paint()
    private val glowPaint = Paint()
    private val vignettePaint = Paint()
    private val gridPaint = Paint().apply {
        color = 0x0B4ED8AF
        strokeWidth = linePx
    }

    override fun onBoundsChange(bounds: Rect) {
        if (bounds.isEmpty) {
            basePaint.shader = null
            glowPaint.shader = null
            vignettePaint.shader = null
            return
        }
        val left = bounds.left.toFloat()
        val top = bounds.top.toFloat()
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        basePaint.shader = LinearGradient(
            0f, top, 0f, bounds.bottom.toFloat(),
            intArrayOf(Palette.BG_TOP, Palette.BG_MID, Palette.BG_BOTTOM),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        glowPaint.shader = RadialGradient(
            left + w * 0.5f, top + h * 1.1f, max(w, h) * 0.6f,
            0x1A17F9B7, 0x0017F9B7,
            Shader.TileMode.CLAMP,
        )
        vignettePaint.shader = RadialGradient(
            bounds.exactCenterX(), bounds.exactCenterY(), hypot(w, h) / 2f,
            intArrayOf(0x00000000, 0xA6000000.toInt()),
            floatArrayOf(0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        canvas.drawRect(b, basePaint)
        canvas.drawRect(b, glowPaint)
        var x = b.left.toFloat()
        while (x <= b.right) {
            canvas.drawLine(x, b.top.toFloat(), x, b.bottom.toFloat(), gridPaint)
            x += gridStepPx
        }
        var y = b.top.toFloat()
        while (y <= b.bottom) {
            canvas.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, gridPaint)
            y += gridStepPx
        }
        canvas.drawRect(b, vignettePaint)
    }

    /** 不支持整体透明度。 */
    override fun setAlpha(alpha: Int) = Unit

    /** 不支持颜色滤镜。 */
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

/** 复刻上游 `.brackets::after`：四角 L 形描边，贴齐 bounds 外沿。 */
internal class BracketsDrawable(
    color: Int,
    private val lengthPx: Float,
    private val strokePx: Float,
) : Drawable() {

    private val paint = Paint().apply { this.color = color }

    var color: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidateSelf()
        }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val l = b.left.toFloat()
        val t = b.top.toFloat()
        val r = b.right.toFloat()
        val bt = b.bottom.toFloat()
        val len = lengthPx
        val s = strokePx
        // 左上
        canvas.drawRect(l, t, l + len, t + s, paint)
        canvas.drawRect(l, t, l + s, t + len, paint)
        // 右上
        canvas.drawRect(r - len, t, r, t + s, paint)
        canvas.drawRect(r - s, t, r, t + len, paint)
        // 左下
        canvas.drawRect(l, bt - s, l + len, bt, paint)
        canvas.drawRect(l, bt - len, l + s, bt, paint)
        // 右下
        canvas.drawRect(r - len, bt - s, r, bt, paint)
        canvas.drawRect(r - s, bt - len, r, bt, paint)
    }

    /** 不支持整体透明度；颜色自带 alpha。 */
    override fun setAlpha(alpha: Int) = Unit

    /** 不支持颜色滤镜。 */
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** 复刻上游主按钮右端的 -55° 斜纹，只画在 bounds 右侧 [bandWidthPx] 宽的区域内。 */
internal class StripesDrawable(
    private val bandWidthPx: Float,
    private val stripePx: Float,
    private val gapPx: Float,
) : Drawable() {

    private val paint = Paint().apply {
        color = 0x29000000
        strokeWidth = stripePx
        isAntiAlias = true
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val top = b.top.toFloat()
        val bottom = b.bottom.toFloat()
        val right = b.right.toFloat()
        val bandLeft = max(b.left.toFloat(), right - bandWidthPx)
        // 斜线跨越整个高度时的水平位移，以及保证垂直间距为 stripe + gap 的水平步长。
        val run = b.height() / tan(ANGLE)
        val step = (stripePx + gapPx) / sin(ANGLE)
        canvas.save()
        canvas.clipRect(bandLeft, top, right, bottom)
        var x = bandLeft - run
        while (x < right) {
            canvas.drawLine(x, bottom, x + run, top, paint)
            x += step
        }
        canvas.restore()
    }

    /** 不支持整体透明度。 */
    override fun setAlpha(alpha: Int) = Unit

    /** 不支持颜色滤镜。 */
    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        val ANGLE = Math.toRadians(55.0).toFloat()
    }
}

private fun Context.box(fill: Int, stroke: Int) = GradientDrawable().apply {
    setColor(fill)
    setStroke(dp(1), stroke)
}

internal fun Context.primaryButtonBackground(): Drawable {
    fun layers(fill: Int, stroke: Int) = LayerDrawable(
        arrayOf(
            box(fill, stroke),
            StripesDrawable(dpf(14f), dpf(2f), dpf(4f)),
            GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x2EFFFFFF, 0x00FFFFFF, 0x00FFFFFF),
            ),
        )
    )
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), layers(Palette.MINT_400, Palette.MINT_BORDER_HOVER))
        addState(intArrayOf(), layers(Palette.MINT_500, Palette.MINT_BORDER))
    }
}

internal fun Context.secondaryButtonBackground(): Drawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), box(Palette.BTN_SECONDARY_PRESSED, Palette.MINT_700))
    addState(intArrayOf(), box(Palette.BTN_SECONDARY, Palette.LINE_2))
}

internal fun ghostBackground(): Drawable =
    RippleDrawable(ColorStateList.valueOf(Palette.RIPPLE), null, ColorDrawable(Color.WHITE))

internal fun Context.panelBackground(): Drawable {
    val body = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(Palette.PANEL_TOP, Palette.PANEL_BOTTOM),
    ).apply { setStroke(dp(1), Palette.LINE_2) }
    val topBar = GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT,
        intArrayOf(Palette.MINT_500, 0x004ED8AF, 0x004ED8AF),
    )
    val brackets = BracketsDrawable((Palette.MINT_500 and 0x00FFFFFF) or (0x99 shl 24), dpf(10f), dpf(2f))
    return LayerDrawable(arrayOf(body, topBar, brackets)).apply {
        setLayerGravity(1, Gravity.TOP or Gravity.FILL_HORIZONTAL)
        setLayerHeight(1, dp(2))
    }
}

/**
 * 去掉系统按钮的默认外观（阴影动画、最小尺寸、全大写、字体内边距、旧背景留下的 padding）。
 * 必须在设置 background 之后调用；需要的 padding 在此之后再设。
 */
internal fun Button.flat() {
    stateListAnimator = null
    minWidth = 0
    minHeight = 0
    minimumWidth = 0
    minimumHeight = 0
    isAllCaps = false
    includeFontPadding = false
    setPadding(0, 0, 0, 0)
}
