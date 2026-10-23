package com.smartiptv.tv

import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class LoginView(context: Context, vault: SessionVault, private val login: (Credentials, Boolean) -> Unit) : FrameLayout(context) {
    val server = context.input("Servidor")
    val username = context.input("Usuário").apply { hint = "Seu usuário" }
    val password = context.input("Senha", true).apply { hint = "Sua senha" }
    val remember = CheckBox(context).apply {
        text = "Manter conectado neste aparelho"; textSize = 12f; setTextColor(Palette.text)
        buttonTintList = ColorStateList.valueOf(Palette.green); minHeight = context.dp(36)
        background = context.focusBackground(); pad(4, 1)
    }
    val feedback = context.text("", 11f, Palette.error)
    val submit = context.button("Entrar", true, "play") { validate() }
    private val reveal = context.button("", symbol = "eye") {
        val visible = password.inputType and InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        password.inputType = InputType.TYPE_CLASS_TEXT or if (visible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
        password.setSelection(password.text.length)
    }.apply { contentDescription = "Mostrar ou ocultar senha" }

    init {
        setBackgroundColor(Palette.background)
        val shell = context.column().apply { pad(26, 18) }
        addView(shell, LayoutParams(-1, -1))
        val header = context.row()
        header.addView(context.text("SmartIPTV", 21f, bold = true).apply { setCompoundDrawablesWithIntrinsicBounds(Glyph("tv", Palette.green, context.dp(27)), null, null, null); compoundDrawablePadding = context.dp(9) }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(context.text("v${BuildConfig.VERSION_NAME}", 11f, Palette.muted))
        shell.addView(header)
        val body = context.row()
        shell.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val hero = context.column().apply { gravity = Gravity.CENTER_VERTICAL; pad(12, 4) }
        hero.addView(context.eyebrow("SUA PRÓXIMA EXPERIÊNCIA")); hero.gap(14)
        hero.addView(context.text("Sua playlist.\nSeu espaço.", 35f, bold = true)); hero.gap(12)
        hero.addView(context.text("Um novo lugar para seus canais,\nfilmes e séries favoritos.", 14f, Palette.muted))
        hero.addView(HeroArtwork(context), LinearLayout.LayoutParams(-1, context.dp(175)))
        body.addView(hero, LinearLayout.LayoutParams(0, -1, 1f))
        val scroll = ScrollView(context).apply { isFillViewport = false; clipToPadding = false }
        val card = context.column().apply { pad(24, 20); background = shape(Palette.panel, context.dp(14).toFloat()) }
        scroll.addView(card, FrameLayout.LayoutParams(-1, -2))
        val formLayout = LinearLayout.LayoutParams(context.dp(365), -2).apply { gravity = Gravity.CENTER_VERTICAL }
        body.addView(scroll, formLayout)
        card.addView(context.eyebrow("ACESSO XTREAM")); card.gap(10)
        card.addView(context.text("Bem-vindo ao SmartIPTV", 24f, bold = true)); card.gap(7)
        card.addView(context.text("Preencha os dados do seu provedor.", 12f, Palette.muted)); card.gap(18)
        fun field(caption: String, field: View) {
            card.addView(context.text(caption, 12f)); card.gap(6)
            card.addView(field, LinearLayout.LayoutParams(-1, context.dp(42))); card.gap(12)
        }
        field("Servidor", server); field("Usuário", username)
        val passwordRow = context.row()
        passwordRow.addView(password, LinearLayout.LayoutParams(0, -1, 1f))
        passwordRow.addView(reveal, LinearLayout.LayoutParams(context.dp(42), -1))
        field("Senha", passwordRow)
        card.addView(remember, LinearLayout.LayoutParams(-1, context.dp(35))); card.gap(10)
        card.addView(submit, LinearLayout.LayoutParams(-1, context.dp(42))); card.gap(8)
        card.addView(feedback, LinearLayout.LayoutParams(-1, -2)); card.gap(10); card.rule(); card.gap(10)
        card.addView(context.text("Use os dados de acesso fornecidos pelo seu provedor.", 10f, Palette.muted))
        server.setText(vault.lastServer); username.setText(vault.lastUser)
        password.imeOptions = EditorInfo.IME_ACTION_GO
        password.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_GO) { validate(); true } else false }
        listOf(server, username, password, reveal, remember, submit).forEach { it.id = View.generateViewId() }
        server.nextFocusDownId = username.id; username.nextFocusDownId = password.id
        password.nextFocusDownId = remember.id; password.nextFocusRightId = reveal.id; reveal.nextFocusLeftId = password.id
        remember.nextFocusDownId = submit.id; submit.nextFocusUpId = remember.id
        addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left == oldRight - oldLeft) return@addOnLayoutChangeListener
            val wide = right - left >= context.dp(800)
            val visibility = if (wide) VISIBLE else GONE
            if (hero.visibility != visibility) hero.visibility = visibility
            val gravity = if (wide) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
            if (body.gravity != gravity) body.gravity = gravity
            val desired = minOf(context.dp(if (wide) 365 else 410), body.width.coerceAtLeast(1))
            if (scroll.layoutParams.width != desired) {
                scroll.layoutParams = scroll.layoutParams.apply { width = desired }
            }
        }
        server.requestFocus()
    }

    fun validate() {
        if (!submit.isEnabled) return
        try {
            val value = Credentials.validated(server.text.toString(), username.text.toString(), password.text.toString())
            feedback.text = "Conectando ao servidor…"; feedback.setTextColor(Palette.muted)
            login(value, remember.isChecked)
        } catch (error: ProviderFailure) {
            error(error.message ?: "Confira os dados.")
            when { server.text.isBlank() -> server.requestFocus(); username.text.isBlank() -> username.requestFocus(); password.text.isBlank() -> password.requestFocus(); else -> server.requestFocus() }
        }
    }
    fun busy(value: Boolean) {
        listOf(server, username, password, reveal, remember, submit).forEach { it.isEnabled = !value }
        submit.text = if (value) "Conectando…" else "Entrar"
    }
    fun error(message: String) { busy(false); feedback.setTextColor(Palette.error); feedback.text = message }
}
