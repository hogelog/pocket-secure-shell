package org.hogel.pocketssh.ui

import android.content.Context
import android.net.Uri
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.core.view.ContentInfoCompat
import androidx.core.view.ViewCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat

/**
 * Transparent focusable View that owns the IME connection on the user's behalf.
 *
 * Termux's [com.termux.view.TerminalView] is `final` and its built-in
 * [BaseInputConnection] discards composing text without rendering it, so
 * Japanese kana-to-kanji preedit is invisible. Putting this view at the same
 * focus slot as the terminal lets us intercept [setComposingText] /
 * [commitText] / [finishComposingText] and surface the composing string to a
 * preedit overlay while still routing committed text and hardware key events
 * through the existing terminal pipeline.
 *
 * The view also declares image MIME support via [EditorInfoCompat.setContentMimeTypes]
 * so Gboard's clipboard suggestion bar offers recent screenshots/images as
 * paste chips; received content is forwarded to [onImageContent].
 */
class ImeProxyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Called whenever the IME's composing text changes (empty when cleared). */
    var onComposingTextChanged: (CharSequence) -> Unit = {}

    /** Called when the IME commits text (kana-to-kanji confirmation, etc.). */
    var onCommitText: (CharSequence) -> Unit = {}

    /**
     * Called when a hardware key (or a key event synthesised by the IME via
     * [InputConnection.sendKeyEvent], e.g. Enter on the soft keyboard) reaches
     * this view. Return true to mark it consumed.
     */
    var onHardwareKey: (Int, KeyEvent) -> Boolean = { _, _ -> false }

    /**
     * Called when the IME inserts image content (e.g. tapping a clipboard
     * screenshot chip in Gboard's suggestion bar). The handler must read the
     * URI synchronously — the framework releases the temporary URI permission
     * as soon as the receive callback returns.
     */
    var onImageContent: (Uri) -> Unit = {}

    /**
     * When true, [onCreateInputConnection] advertises the editor as a password
     * field with personalised learning disabled, so the system IME does not
     * persist typed bytes into its prediction/auto-correct dictionaries.
     * Setter triggers an IME restart so the new EditorInfo takes effect.
     */
    var secureMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.restartInput(this)
        }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // Background must be drawable for focus on some devices but we want it
        // visually invisible — a fully transparent colour is sufficient.
        setBackgroundColor(0x00000000)
        ViewCompat.setOnReceiveContentListener(this, SUPPORTED_IMAGE_MIME_TYPES) { _, payload ->
            handleReceivedContent(payload)
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        if (secureMode) {
            // Password variation + NO_PERSONALIZED_LEARNING tells the IME to
            // disable predictive text and prevent the typed bytes from being
            // added to the device's auto-correct/learning dictionary, which
            // is the whole point of the secure-input mode.
            outAttrs.inputType =
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        } else {
            // TYPE_CLASS_TEXT lets the IME run composition (Japanese kana →
            // kanji), word suggestions, etc. NO_FULLSCREEN keeps the keyboard
            // docked instead of taking over the screen in landscape.
            outAttrs.inputType =
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
            outAttrs.imeOptions =
                EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        }
        EditorInfoCompat.setContentMimeTypes(outAttrs, SUPPORTED_IMAGE_MIME_TYPES)
        val ic: InputConnection = ProxyInputConnection(this, true)
        return InputConnectionCompat.createWrapper(this, ic, outAttrs)
    }

    private fun handleReceivedContent(payload: ContentInfoCompat): ContentInfoCompat? {
        val parts = payload.partition { item -> item.uri != null }
        val images = parts.first
        if (images != null) {
            val clip = images.clip
            for (i in 0 until clip.itemCount) {
                val uri = clip.getItemAt(i).uri ?: continue
                onImageContent(uri)
            }
        }
        return parts.second
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (onHardwareKey(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Most terminal-relevant work happens on key down; consume up events
        // for keys we already handled to avoid IME defaults reacting to them.
        return false
    }

    private inner class ProxyInputConnection(
        target: View,
        fullEditor: Boolean,
    ) : BaseInputConnection(target, fullEditor) {

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            // Mirror to overlay first so the user sees the preedit immediately;
            // the editable bookkeeping in super is what lets the IME continue
            // editing the same composing region on the next call.
            onComposingTextChanged(text)
            return super.setComposingText(text, newCursorPosition)
        }

        override fun setComposingRegion(start: Int, end: Int): Boolean {
            // Defer to super; we don't reflect partial-region edits in the
            // overlay because the next setComposingText call (which IMEs
            // always issue right after) gives us the full new string.
            return super.setComposingRegion(start, end)
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            // Hand off to SSH instead of letting the editable accumulate; the
            // terminal screen is the source of truth for what the user sees.
            onCommitText(text)
            onComposingTextChanged("")
            editable?.clear()
            return true
        }

        override fun finishComposingText(): Boolean {
            // Flush whatever is still composing as if the user confirmed it,
            // then drop the overlay. Mirrors how Termux's BaseInputConnection
            // wrapper sends pending text on focus loss.
            val current = editable
            val pending = current?.toString().orEmpty()
            if (pending.isNotEmpty()) {
                onCommitText(pending)
            }
            onComposingTextChanged("")
            current?.clear()
            return super.finishComposingText()
        }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            // Some IMEs ask the editor to delete characters before the cursor
            // (e.g. Samsung auto-correct). Translate that into BS keystrokes
            // so the remote shell sees the same edit it would for hardware
            // backspace.
            val deleteEvent = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
            repeat(beforeLength) { onHardwareKey(KeyEvent.KEYCODE_DEL, deleteEvent) }
            return super.deleteSurroundingText(beforeLength, afterLength)
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            // The soft keyboard's Enter / Backspace arrive here as synthetic
            // key events. Route them through the same hardware-key handler so
            // they reach the existing TerminalActivity logic.
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (onHardwareKey(event.keyCode, event)) return true
            }
            return super.sendKeyEvent(event)
        }
    }

    private companion object {
        // Mirrors the MIME branches in TerminalActivity.extensionForMime so any
        // chip Gboard surfaces resolves to a known remote-side extension.
        val SUPPORTED_IMAGE_MIME_TYPES = arrayOf(
            "image/png",
            "image/jpeg",
            "image/webp",
            "image/heic",
            "image/heif",
            "image/gif",
        )
    }
}
