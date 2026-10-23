package com.smartiptv.tv

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Rect
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/** D-pad focus selects the field; OK explicitly starts the native TV keyboard. */
class TvTextInput(context: Context) : EditText(context) {
    private var keyboardPending = false
    private val keyboard = Runnable {
        if (keyboardPending && isAttachedToWindow && isFocused && hasWindowFocus()) {
            keyboardPending = false
            val manager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            // Fire OS must see an enabled editor and a current input connection before showing its IME.
            manager.restartInput(this)
            manager.showSoftInput(this, 0)
        }
    }

    init { showSoftInputOnFocus = false; isFocusableInTouchMode = true }

    override fun performClick(): Boolean {
        super.performClick()
        if (!isEnabled) return false
        showSoftInputOnFocus = true
        requestFocus()
        setSelection(text.length)
        var owner: Context = context
        while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
        (owner as? Activity)?.window?.let { window ->
            window.setSoftInputMode((window.attributes.softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE.inv()) or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED)
        }
        keyboardPending = true
        removeCallbacks(keyboard)
        post(keyboard)
        return true
    }

    private fun activation(key: Int) = key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = if (activation(keyCode)) true else super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (!activation(keyCode)) return super.onKeyUp(keyCode, event)
        if (!event.isCanceled) performClick()
        return true
    }
    private fun finishEditing() {
        keyboardPending = false
        removeCallbacks(keyboard)
        showSoftInputOnFocus = false
    }
    override fun onKeyPreIme(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) finishEditing()
        return super.onKeyPreIme(keyCode, event)
    }
    override fun onEditorAction(actionCode: Int) {
        finishEditing()
        super.onEditorAction(actionCode)
    }
    override fun onFocusChanged(focused: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        if (!focused) finishEditing()
        super.onFocusChanged(focused, direction, previouslyFocusedRect)
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus && keyboardPending) post(keyboard)
    }
    override fun onDetachedFromWindow() { finishEditing(); super.onDetachedFromWindow() }
}
