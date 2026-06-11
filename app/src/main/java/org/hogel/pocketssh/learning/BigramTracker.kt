package org.hogel.pocketssh.learning

import java.io.ByteArrayOutputStream

/**
 * Observes bytes the app sends over the SSH stdin and turns them into bigram
 * counts in a [BigramStore], plus a "what was the last completed token on the
 * current line" signal that drives live suggestion refreshes.
 *
 * State machine, byte by byte:
 * - **CR / LF**: line break. If a token is buffered and the line has not been
 *   poisoned, record `(prev, buffer)`. Then reset to `prev = <BOL>`, empty
 *   buffer, un-poison. A CRLF pair (CR immediately followed by LF, even across
 *   chunk boundaries) is a single line break: the LF is swallowed so it does
 *   not record a second, empty-line terminator.
 * - **space / tab**: token boundary. If a token is buffered and the line has
 *   not been poisoned, record `(prev, buffer)` and adopt that token as the new
 *   `prev`. Empty the buffer either way.
 * - **backspace / DEL**: discard the in-progress token. Don't try to rewind
 *   `prev` — the user could have backspaced past previous tokens and we'd
 *   guess wrong.
 * - **other control byte (`< 0x20`)**: poison the line. Once poisoned no more
 *   tokens are recorded until the next CR/LF. Used to skip escape sequences
 *   from arrow keys, ^C, etc., without needing a full ANSI parser.
 * - **printable byte (`>= 0x20`)**: append to the UTF-8 buffer.
 *
 * The tracker assumes the stream is roughly UTF-8 friendly; partial multi-byte
 * sequences at a token boundary (e.g. user types `あ` then space) decode fine
 * because all multi-byte UTF-8 continuation bytes are `>= 0x80`, well above the
 * byte values handled as separators here.
 *
 * Cursor moves and Backspace inside the remote shell are not observed — once
 * `prev` drifts away from the visible reality, the next CR/LF resyncs us.
 *
 * Thread safety: callers serialise against a single tracker instance. The SSH
 * write path in `TerminalActivity` is single-threaded (UI thread or service
 * dispatch), which satisfies that.
 */
class BigramTracker(
    private val store: BigramStore,
    private val onPrevChanged: () -> Unit,
) {
    private var context: String = BigramStore.UNKNOWN_CONTEXT
    private var prev: String = BigramStore.BOL
    private val buffer = ByteArrayOutputStream()
    private var poisoned: Boolean = false

    /**
     * True iff the immediately preceding byte was a CR (0x0D). Used to swallow
     * the LF of a CRLF pair as a single line break instead of recording a
     * spurious `(<BOL>, <ENTER>)` on the empty post-CR line. Kept as a field so
     * a CRLF split across two [ingestSend] chunks is still treated as one
     * terminator.
     */
    private var prevWasCr: Boolean = false

    /**
     * Switch the foreground context. Empty / null collapses to
     * [BigramStore.UNKNOWN_CONTEXT] so suggestions still flow when tmux is off
     * or has not yet emitted a title OSC.
     */
    fun setContext(app: String?) {
        val key = app?.trim()?.lowercase().orEmpty()
        val newContext = if (key.isEmpty()) BigramStore.UNKNOWN_CONTEXT else key
        if (newContext != context) {
            context = newContext
            // Cross-context line state would be misleading; reset the line so
            // suggestions on the new context start from <BOL>.
            resetLine()
            onPrevChanged()
        }
    }

    fun currentContext(): String = context

    fun currentPrev(): String = prev

    /** Feed every chunk written to ssh stdin. Order matters; do not buffer. */
    fun ingestSend(bytes: ByteArray) {
        var shouldNotify = false
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            // Swallow the LF of a CRLF pair (possibly split across chunks): the
            // CR already terminated the line, so the LF must not be processed
            // again as a fresh empty-line break.
            if (v == 0x0A && prevWasCr) {
                prevWasCr = false
                continue
            }
            prevWasCr = v == 0x0D
            when {
                v == 0x0A || v == 0x0D -> {
                    val tail = if (!poisoned && buffer.size() > 0) decodeAndReset() else null
                    if (tail != null) {
                        store.record(context, prev, tail)
                        shouldNotify = true
                    } else {
                        buffer.reset()
                    }
                    if (!poisoned) {
                        // Treat line termination as a bigram successor so
                        // frequent end-of-line tokens (e.g. `status` after
                        // `git`) — and bare Enter on an empty line, recorded
                        // as `(<BOL>, <ENTER>)` — eventually surface a
                        // one-tap Enter button in the suggestion row.
                        val terminator = tail ?: prev
                        store.record(context, terminator, BigramStore.ENTER)
                        shouldNotify = true
                    }
                    if (prev != BigramStore.BOL) shouldNotify = true
                    prev = BigramStore.BOL
                    poisoned = false
                }
                v == 0x20 || v == 0x09 -> {
                    if (!poisoned && buffer.size() > 0) {
                        val token = decodeAndReset()
                        store.record(context, prev, token)
                        shouldNotify = true
                        if (prev != token) {
                            prev = token
                        }
                    } else {
                        buffer.reset()
                    }
                }
                v == 0x08 || v == 0x7F -> {
                    buffer.reset()
                }
                v < 0x20 -> {
                    poisoned = true
                    buffer.reset()
                }
                else -> {
                    buffer.write(v)
                }
            }
        }
        if (shouldNotify) onPrevChanged()
    }

    /** Drop in-flight line state without touching the persisted store. */
    fun resetLine() {
        prev = BigramStore.BOL
        buffer.reset()
        poisoned = false
        prevWasCr = false
    }

    /**
     * Authoritatively adopt [token] as the most recent committed token on the
     * current line, regardless of whether the line was [poisoned]. Used when
     * the UI lets the user commit a token directly (e.g. a learned-completion
     * tap) — the byte-stream heuristic that marks the line poisoned after any
     * control byte would otherwise leave [prev] stuck at a stale value once
     * the user has pressed an arrow key, Ctrl-shortcut, or Esc since the last
     * Enter, and subsequent taps would fail to refresh the suggestion bar.
     */
    fun commitToken(token: String) {
        if (token.isEmpty()) return
        store.record(context, prev, token)
        if (prev != token) prev = token
        buffer.reset()
        poisoned = false
        prevWasCr = false
        onPrevChanged()
    }

    private fun decodeAndReset(): String {
        val s = String(buffer.toByteArray(), Charsets.UTF_8)
        buffer.reset()
        return s
    }
}
