package org.hogel.pocketssh.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import java.io.ByteArrayOutputStream

/**
 * Read-only overlay that renders a `tmux capture-pane` snapshot in its own
 * [TerminalView], stacked opaquely over the live terminal. The capture is a
 * plain line stream (+SGR), so the overlay emulator builds a normal transcript
 * and TerminalView's native `mTopRow` scrollback drives the drag — the same
 * smooth phone scroll, with tmux's persistence behind it.
 *
 * Nothing typed here reaches the remote: the view client swallows all key /
 * code-point input and a single tap dismisses the overlay.
 */
class ScrollbackOverlay(private val terminalView: TerminalView) {

    val isShowing: Boolean get() = terminalView.visibility == View.VISIBLE

    /**
     * Render [captureBytes] and bring the overlay up. [fontSizePx] mirrors the
     * live terminal so the emulator resolves to the same column count. Each
     * call clears the previous snapshot, so re-opening shows a fresh capture.
     */
    fun show(captureBytes: ByteArray, fontSizePx: Int) {
        terminalView.setTextSize(fontSizePx)
        val emulator = terminalView.mEmulator
        if (emulator == null) {
            Log.w(TAG, "overlay emulator not ready; skipping show")
            return
        }
        // Clear screen + scrollback + home the cursor, then feed the snapshot.
        emulator.append(RESET, RESET.size)
        val crlf = expandLfToCrlf(captureBytes)
        emulator.append(crlf, crlf.size)
        // onScreenUpdated() snaps mTopRow back to the bottom (latest row).
        terminalView.onScreenUpdated()
        terminalView.visibility = View.VISIBLE
        terminalView.invalidate()
    }

    fun hide() {
        terminalView.visibility = View.INVISIBLE
    }

    /**
     * tmux `capture-pane` separates lines with bare LF. A terminal emulator
     * treats LF as line-feed only (no carriage return), so without this the
     * snapshot would stairstep diagonally. Operating at the byte level is safe:
     * 0x0A never appears inside a UTF-8 multi-byte sequence or an SGR escape.
     */
    private fun expandLfToCrlf(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 64)
        for (b in data) {
            if (b == LF) out.write(CR)
            out.write(b.toInt())
        }
        return out.toByteArray()
    }

    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            terminalView.invalidate()
        }
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag ?: TAG, message, e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(tag ?: TAG, "Stack trace", e)
        }
    }

    private val viewClient = object : TerminalViewClient {
        override fun onScale(scale: Float): Float = scale
        override fun onSingleTapUp(e: MotionEvent?) { hide() }
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = false
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = false
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean = false
        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
        override fun onLongPress(event: MotionEvent?): Boolean = true
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean = true
        override fun onEmulatorSet() {}
        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag ?: TAG, message, e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(tag ?: TAG, "Stack trace", e)
        }
    }

    init {
        terminalView.setTypeface(Typeface.MONOSPACE)
        terminalView.isFocusable = false
        terminalView.isFocusableInTouchMode = false
        // The dummy process never produces output; all content arrives via
        // append() in show(). It exists only to let TerminalView build its
        // emulator (see TerminalActivity.setupTerminalView for the rationale).
        val session = TerminalSession(
            "/system/bin/sleep", "/",
            arrayOf("sleep", "86400"),
            arrayOf("TERM=xterm-256color"),
            null,
            sessionClient,
        )
        terminalView.attachSession(session)
        terminalView.setTerminalViewClient(viewClient)
        wireTouchClose()
    }

    /**
     * Drive the overlay's own scroll via [TerminalView.onTouchEvent], then close
     * it when the user releases at the bottom (`topRow == 0`) — the live stream
     * never stopped, so the live view is already current. A single tap closes
     * too, via [viewClient]'s onSingleTapUp.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun wireTouchClose() {
        terminalView.setOnTouchListener { v, event ->
            // Drive the native scroll, then always consume so the framework
            // doesn't re-dispatch to onTouchEvent and nothing leaks past the
            // overlay to the live view underneath.
            v.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP && terminalView.topRow >= 0) {
                hide()
            }
            true
        }
    }

    companion object {
        private const val TAG = "ScrollbackOverlay"
        private const val LF: Byte = 0x0A
        private const val CR: Int = 0x0D
        private val RESET = "[H[2J[3J".toByteArray(Charsets.US_ASCII)
    }
}
