package com.smartiptv.tv

import android.content.Context
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Space

/** Lightweight gateway: it never queries the catalog or requests artwork. */
class ContentSelectionView(context: Context, selected: String = "live", choose: (String) -> Unit) : LinearLayout(context) {
    private var compactStyle: Boolean? = null
    private val cards: LinearLayout
    val choices = linkedMapOf<String, Button>()
    init {
        orientation = VERTICAL; setBackgroundColor(Palette.background); pad(34, 25)
        addView(context.text("SmartIPTV", 23f, bold = true).apply {
            setCompoundDrawablesWithIntrinsicBounds(Glyph("tv", Palette.green, context.dp(30)), null, null, null)
            compoundDrawablePadding = context.dp(9)
        })
        addView(Space(context), LayoutParams(1, 0, 1f))
        addView(context.text("O que você quer assistir?", 30f, bold = true).apply { gravity = Gravity.CENTER }, LayoutParams(-1, -2))
        gap(12)
        addView(context.text("Escolha uma opção para continuar.", 16f, Palette.muted).apply { gravity = Gravity.CENTER }, LayoutParams(-1, -2))
        gap(34)
        cards = context.row()
        listOf("live" to "TV ao vivo", "series" to "Séries", "movie" to "Filmes").forEachIndexed { index, (kind, title) ->
            val button = context.button(title) { choose(kind) }.apply {
                textSize = 24f; gravity = Gravity.CENTER; id = View.generateViewId(); contentDescription = title
                setCompoundDrawablesWithIntrinsicBounds(null, Glyph(kind, Palette.green, context.dp(64)), null, null)
                compoundDrawablePadding = context.dp(24); pad(12, 28)
            }
            cards.addView(button, LayoutParams(0, context.dp(195), 1f).apply { if (index > 0) leftMargin = context.dp(16) })
            choices[kind] = button
        }
        addView(cards, LayoutParams(-1, -2))
        addView(Space(context), LayoutParams(1, 0, 1.4f))
        post { choices[selected]?.requestFocus() }
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = MeasureSpec.getSize(heightMeasureSpec)
        val compact = height < context.dp(450)
        for (button in choices.values) {
            val targetHeight = minOf(context.dp(195), height * 35 / 100)
            if (button.layoutParams.height != targetHeight) button.layoutParams = button.layoutParams.apply { this.height = targetHeight }
            if (compactStyle == compact) continue
            button.textSize = if (compact) 19f else 24f
            button.setCompoundDrawablesWithIntrinsicBounds(null, Glyph(choices.entries.first { it.value === button }.key, Palette.green, context.dp(if (compact) 42 else 64)), null, null)
            button.compoundDrawablePadding = context.dp(if (compact) 12 else 24)
            button.pad(10, if (compact) 12 else 28)
        }
        compactStyle = compact
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode in listOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN)) {
            val buttons = choices.values.toList(); val index = buttons.indexOfFirst { it.hasFocus() }
            if (index >= 0) {
                val step = when (event.keyCode) { KeyEvent.KEYCODE_DPAD_LEFT -> -1; KeyEvent.KEYCODE_DPAD_RIGHT -> 1; else -> 0 }
                buttons.getOrNull(index + step)?.requestFocus()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }
}
