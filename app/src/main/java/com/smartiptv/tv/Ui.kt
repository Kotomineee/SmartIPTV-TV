package com.smartiptv.tv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

object Palette {
    val background = Color.rgb(11, 16, 17)
    val sidebar = Color.rgb(16, 24, 25)
    val panel = Color.rgb(18, 29, 26)
    val surface = Color.rgb(24, 37, 31)
    val line = Color.rgb(45, 64, 55)
    val green = Color.rgb(101, 223, 173)
    val text = Color.rgb(237, 245, 242)
    val muted = Color.rgb(145, 161, 158)
    val selected = Color.rgb(33, 61, 49)
    val error = Color.rgb(242, 170, 164)
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
fun View.pad(horizontal: Int, vertical: Int = horizontal) = setPadding(context.dp(horizontal), context.dp(vertical), context.dp(horizontal), context.dp(vertical))
fun shape(color: Int, radius: Float = 10f, border: Int = Palette.line, width: Int = 1): GradientDrawable = GradientDrawable().apply {
    setColor(color); cornerRadius = radius; if (width > 0) setStroke(width, border)
}
fun Context.focusBackground(primary: Boolean = false, selected: Boolean = false): Drawable {
    val radius = dp(8).toFloat()
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_focused), shape(if (primary) Color.rgb(139, 244, 199) else Palette.selected, radius, Palette.green, dp(2)))
        addState(intArrayOf(android.R.attr.state_pressed), shape(if (primary) Color.rgb(82, 201, 151) else Palette.selected, radius, Palette.green, dp(1)))
        addState(intArrayOf(), shape(if (primary) Palette.green else if (selected) Palette.selected else Palette.surface, radius,
            if (primary) Palette.green else Palette.line, dp(1)))
    }
}
fun Context.text(value: String, size: Float = 14f, color: Int = Palette.text, bold: Boolean = false): TextView = TextView(this).apply {
    text = value; textSize = size; setTextColor(color); typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
    includeFontPadding = false
}
fun Context.eyebrow(value: String): TextView = text(value, 10f, Palette.green, true).apply { letterSpacing = .16f }
fun Context.button(value: String, primary: Boolean = false, symbol: String? = null, action: () -> Unit): Button = Button(this).apply {
    text = value; textSize = 12f; isAllCaps = false; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    setTextColor(if (primary) Palette.background else Palette.text)
    background = focusBackground(primary); minWidth = 0; minimumWidth = 0; minHeight = dp(40); minimumHeight = dp(40)
    stateListAnimator = null; elevation = 0f
    pad(13, 8); isFocusable = true; isFocusableInTouchMode = true
    setOnClickListener { action() }
    if (symbol != null) { setCompoundDrawablesWithIntrinsicBounds(Glyph(symbol, if (primary) Palette.background else Palette.green, dp(17)), null, null, null); compoundDrawablePadding = dp(7) }
}
fun Context.input(name: String, password: Boolean = false): EditText = EditText(this).apply {
    contentDescription = name; setTextColor(Palette.text); setHintTextColor(Palette.muted); textSize = 13f
    background = focusBackground(); pad(12, 6); setSingleLine(); minimumHeight = dp(42)
    inputType = if (password) android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    if (name == "Servidor") inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
    isSaveEnabled = false
    showSoftInputOnFocus = false
    fun openKeyboard() { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT) }
    setOnClickListener { openKeyboard() }
    setOnKeyListener { _, key, event ->
        if (key in listOf(android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER)) {
            if (event.action == android.view.KeyEvent.ACTION_UP) openKeyboard()
            true
        } else false
    }
    imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
}
fun Context.column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
fun Context.row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isBaselineAligned = false }
fun LinearLayout.gap(height: Int) { addView(View(context), LinearLayout.LayoutParams(1, context.dp(height))) }
fun LinearLayout.rule() { addView(View(context).apply { setBackgroundColor(Palette.line) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(1))) }
fun TextView.singleLineEllipsis() { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
fun Context.poster(): ImageView = ImageView(this).apply {
    scaleType = ImageView.ScaleType.CENTER_CROP; background = shape(Color.rgb(29, 51, 37), dp(8).toFloat(), width = 0)
    clipToOutline = true; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
}

class Glyph(private val symbol: String, private val color: Int = Palette.green, private val size: Int = 24) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@Glyph.color; style = Paint.Style.STROKE; strokeWidth = 1.6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size
    override fun draw(canvas: Canvas) {
        canvas.save(); canvas.translate(bounds.left.toFloat(), bounds.top.toFloat()); canvas.scale(bounds.width() / 24f, bounds.height() / 24f)
        val path = Path()
        when (symbol) {
            "tv", "live", "film", "movie", "series" -> { canvas.drawRoundRect(3f, 4f, 21f, 18f, 2f, 2f, paint); canvas.drawLine(8f, 22f, 16f, 22f, paint); canvas.drawLine(12f, 18f, 12f, 22f, paint) }
            "home" -> { path.moveTo(3f, 11f); path.lineTo(12f, 3f); path.lineTo(21f, 11f); path.moveTo(5f, 10f); path.lineTo(5f, 21f); path.lineTo(19f, 21f); path.lineTo(19f, 10f); path.moveTo(10f, 21f); path.lineTo(10f, 15f); path.lineTo(14f, 15f); path.lineTo(14f, 21f) }
            "heart" -> { path.moveTo(12f, 21f); path.cubicTo(-7f, 8f, 7f, -3f, 12f, 6f); path.cubicTo(17f, -3f, 31f, 8f, 12f, 21f) }
            "play" -> { path.moveTo(8f, 4f); path.lineTo(20f, 12f); path.lineTo(8f, 20f); path.close() }
            "pause" -> { canvas.drawLine(8f, 5f, 8f, 19f, paint); canvas.drawLine(16f, 5f, 16f, 19f, paint) }
            "rewind", "forward" -> {
                canvas.save()
                if (symbol == "forward") canvas.scale(-1f, 1f, 12f, 12f)
                canvas.drawArc(RectF(4f, 4f, 20f, 20f), -80f, 300f, false, paint)
                path.moveTo(4f, 2f); path.lineTo(4f, 9f); path.lineTo(11f, 9f)
                canvas.drawPath(path, paint); path.reset(); canvas.restore()
                paint.style = Paint.Style.FILL; paint.textSize = 8f; paint.textAlign = Paint.Align.CENTER
                canvas.drawText("10", 12f, 16f, paint); paint.style = Paint.Style.STROKE
            }
            "back" -> { path.moveTo(14f, 5f); path.lineTo(7f, 12f); path.lineTo(14f, 19f); path.moveTo(7f, 12f); path.lineTo(22f, 12f) }
            "close" -> { canvas.drawLine(6f, 6f, 18f, 18f, paint); canvas.drawLine(18f, 6f, 6f, 18f, paint) }
            "clock" -> { canvas.drawCircle(12f, 12f, 9f, paint); path.moveTo(12f, 6f); path.lineTo(12f, 12f); path.lineTo(16f, 15f) }
            "search" -> { canvas.drawCircle(10f, 10f, 6f, paint); canvas.drawLine(15f, 15f, 21f, 21f, paint) }
            "settings" -> { canvas.drawCircle(12f, 12f, 4f, paint); canvas.drawCircle(12f, 12f, 9f, paint); for (a in 0..7) { canvas.save(); canvas.rotate(a * 45f, 12f, 12f); canvas.drawLine(12f, 1f, 12f, 4f, paint); canvas.restore() } }
            "volume", "muted" -> { path.moveTo(3f, 9f); path.lineTo(7f, 9f); path.lineTo(12f, 4f); path.lineTo(12f, 20f); path.lineTo(7f, 15f); path.lineTo(3f, 15f); path.close(); if (symbol == "volume") { canvas.drawArc(RectF(10f, 5f, 23f, 19f), -70f, 140f, false, paint) } else { canvas.drawLine(17f, 9f, 22f, 15f, paint); canvas.drawLine(22f, 9f, 17f, 15f, paint) } }
            "full" -> { path.moveTo(3f, 9f); path.lineTo(3f, 3f); path.lineTo(9f, 3f); path.moveTo(15f, 3f); path.lineTo(21f, 3f); path.lineTo(21f, 9f); path.moveTo(21f, 15f); path.lineTo(21f, 21f); path.lineTo(15f, 21f); path.moveTo(9f, 21f); path.lineTo(3f, 21f); path.lineTo(3f, 15f) }
            "refresh" -> { canvas.drawArc(RectF(4f, 4f, 20f, 20f), 45f, 285f, false, paint); path.moveTo(20f, 3f); path.lineTo(20f, 10f); path.lineTo(13f, 10f) }
            "subtitle" -> { canvas.drawRoundRect(2f, 5f, 22f, 19f, 2f, 2f, paint); canvas.drawLine(5f, 11f, 10f, 11f, paint); canvas.drawLine(14f, 11f, 19f, 11f, paint); canvas.drawLine(5f, 15f, 19f, 15f, paint) }
            "music" -> { path.moveTo(9f, 18f); path.lineTo(9f, 5f); path.lineTo(20f, 3f); path.lineTo(20f, 16f); canvas.drawOval(3f, 16f, 9f, 21f, paint); canvas.drawOval(14f, 14f, 20f, 19f, paint) }
            "eye" -> { canvas.drawOval(2f, 6f, 22f, 18f, paint); canvas.drawCircle(12f, 12f, 3f, paint) }
            else -> { paint.style = Paint.Style.FILL; paint.textSize = 13f; paint.textAlign = Paint.Align.CENTER; canvas.drawText(symbol, 12f, 17f, paint); paint.style = Paint.Style.STROKE }
        }
        canvas.drawPath(path, paint); canvas.restore()
    }
    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}

class HeroArtwork(context: Context) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save(); canvas.translate(width / 2f - context.dp(140), height / 2f - context.dp(85)); canvas.scale(context.dp(1).toFloat(), context.dp(1).toFloat())
        p.color = Color.rgb(29, 54, 49); p.style = Paint.Style.STROKE; p.strokeWidth = 1f; canvas.drawOval(-10f, -5f, 290f, 175f, p)
        p.style = Paint.Style.FILL; p.color = Color.rgb(20, 37, 34); canvas.drawRoundRect(10f, 10f, 270f, 155f, 12f, 12f, p)
        p.color = Color.rgb(36, 72, 58); canvas.drawRoundRect(17f, 17f, 263f, 148f, 8f, 8f, p)
        p.color = Color.rgb(45, 93, 72); canvas.drawCircle(198f, 75f, 54f, p)
        p.color = Palette.green; canvas.drawCircle(143f, 77f, 25f, p)
        p.color = Palette.background; val triangle = Path().apply { moveTo(138f, 66f); lineTo(154f, 77f); lineTo(138f, 88f); close() }; canvas.drawPath(triangle, p)
        p.color = Color.rgb(55, 89, 77); canvas.drawRoundRect(105f, 165f, 175f, 170f, 2f, 2f, p)
        canvas.restore()
    }
}
