package dev.mobileide.feature.terminal.components

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText

/**
 * Invisible editor that forwards keystrokes straight to the PTY, the way Termux
 * does. It never stores text: committed characters are written to the shell,
 * backspace sends DEL, Enter sends CR. The shell's own echo is what the user
 * sees in the terminal, so input and output stay in sync.
 */
class TerminalInputView(context: Context) : EditText(context) {

    var onText: ((String) -> Unit)? = null
    var onBackspace: ((Int) -> Unit)? = null
    var onEnter: (() -> Unit)? = null
    var onArrow: ((Int) -> Unit)? = null
    var onControlKey: ((Int) -> Unit)? = null

    init {
        background = null
        setPadding(0, 0, 0, 0)
        setTextColor(Color.TRANSPARENT)
        setHintTextColor(Color.TRANSPARENT)
        setCursorVisible(false)
        isSingleLine = true
        isFocusable = true
        isFocusableInTouchMode = true
        inputType = TYPE_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = inputType
        outAttrs.imeOptions = imeOptions
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        // Non-editable: the IME must not build a local text buffer.
        val base = BaseInputConnection(this, false)
        return object : InputConnectionWrapper(base, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val value = text?.toString().orEmpty()
                when {
                    value == "\n" || value == "\r" -> onEnter?.invoke()
                    value.isNotEmpty() -> onText?.invoke(value)
                }
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true

            override fun finishComposingText(): Boolean = true

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (beforeLength > 0) onBackspace?.invoke(beforeLength)
                return true
            }

            override fun deleteSurroundingTextInCodePoints(
                beforeLength: Int,
                afterLength: Int
            ): Boolean {
                if (beforeLength > 0) onBackspace?.invoke(beforeLength)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean =
                handleKey(event) || super.sendKeyEvent(event)

            override fun performEditorAction(editorAction: Int): Boolean {
                onEnter?.invoke()
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (handleKey(event)) return true
        val unicode = event.unicodeChar
        if (unicode != 0) {
            onText?.invoke(String(Character.toChars(unicode)))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) {
            return event.action == KeyEvent.ACTION_UP && isHandledCode(event.keyCode)
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                onEnter?.invoke()
                return true
            }

            KeyEvent.KEYCODE_DEL -> {
                onBackspace?.invoke(1)
                return true
            }

            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                onArrow?.invoke(event.keyCode)
                return true
            }
        }
        if (event.isCtrlPressed && event.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            onControlKey?.invoke(event.keyCode - KeyEvent.KEYCODE_A + 1)
            return true
        }
        return false
    }

    private fun isHandledCode(keyCode: Int): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DEL,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true

        else -> false
    }

    private companion object {
        const val TYPE_TEXT = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    }
}
