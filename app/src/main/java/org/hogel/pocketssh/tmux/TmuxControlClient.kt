package org.hogel.pocketssh.tmux

import java.io.ByteArrayOutputStream

/** One tmux window and its (single, M2 scope) pane, parsed from a control-mode
 *  `list-windows` reply. */
data class TmuxControlWindow(
    val id: String,      // window id, e.g. "@13"
    val index: Int,      // window index within the session
    val name: String,
    val active: Boolean,
    val paneId: String,  // pane id, e.g. "%19"
    val command: String, // pane foreground command, e.g. "node"
)

/**
 * Parser and command encoder for tmux control mode (`tmux -CC`).
 *
 * tmux emits a line-based protocol on stdout. The lines we act on:
 *
 *   `%begin <ts> <num> <flags>` … `%end`/`%error` — a command reply block. When
 *       `flags` is 1 the block answers a command the client queued (see
 *       [pendingCommands]): a `capture-pane` body goes to [onCaptureReply], a
 *       `list-windows` body is parsed and sent to [onWindowsChanged]; other
 *       replies are dropped. `flags` 0 marks a server-spontaneous block, dropped
 *       without consuming the FIFO.
 *   `%output %<pane> <payload>` — pane output. tmux octal-escapes bytes `< 0x20`
 *       and backslash; [decodeOutput] reverses it to the exact original bytes.
 *   `%exit [reason]` — tmux is detaching or exiting.
 *
 * Input flows the other way: [encodeInput] wraps a raw byte stream in
 * `send-keys -t %<pane> -lH <hex…>` (tmux's `-H` reads each arg as a raw byte
 * value marked `KEYC_LITERAL`, so any byte sequence round-trips unchanged).
 * [requestCapture] / [requestWindows] build the diagnostic/state commands.
 */
class TmuxControlClient(
    private val onPaneOutput: (paneId: String, bytes: ByteArray) -> Unit,
    private val onCaptureReply: (paneId: String, body: ByteArray) -> Unit,
    private val onWindowsChanged: (windows: List<TmuxControlWindow>) -> Unit,
    private val onWindowsDirty: () -> Unit,
    private val onExit: (reason: String?) -> Unit,
) {
    /** Pane that keystrokes target — the window the user is viewing, set via
     *  [setInputPane]. NOT the last pane to emit `%output`: a background pane
     *  can emit output, and input must still go to the viewed window. */
    @Volatile
    private var activePaneId: String? = null

    private val lineBuffer = ByteArrayOutputStream()

    /** FIFO of the commands the client has sent, one entry per command, used to
     *  identify each `%begin` reply block. Only consumed for blocks whose
     *  `%begin` flags mark them as client-command replies (see [handleLine]);
     *  server-spontaneous blocks (flags 0) never touch it, so a single stray
     *  out-of-band block can no longer shift the queue permanently out of step.
     *  Touched on both the read and write threads. */
    private val pendingCommands = ArrayDeque<Pending>()

    private var inReplyBlock = false
    /** `<num>` of the open `%begin` block; the closing `%end`/`%error` must
     *  carry the same number so a body line starting with `%end` cannot close
     *  the block early. */
    private var replyNum: String? = null
    /** Which command the open reply block belongs to, or null when out-of-band. */
    private var currentCommand: Pending? = null
    private val replyBody = ByteArrayOutputStream()

    /** Feed raw stdout bytes. Lines may split across calls. */
    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        for (i in 0 until length) {
            val b = bytes[i]
            if (b == NL) {
                val line = lineBuffer.toByteArray()
                lineBuffer.reset()
                val end = if (line.isNotEmpty() && line.last() == CR) line.size - 1 else line.size
                handleLine(String(line, 0, end, Charsets.ISO_8859_1))
            } else {
                lineBuffer.write(b.toInt())
            }
        }
    }

    private fun handleLine(line: String) {
        if (inReplyBlock) {
            handleReplyLine(line)
            return
        }
        when {
            line.startsWith("%begin") -> {
                inReplyBlock = true
                replyNum = line.numberField()
                // tmux prints `%begin <ts> <num> <flags>` with flags = 1 only for
                // replies to commands the control client itself queued, and 0 for
                // server-spontaneous blocks (hooks, the attach-time blocks). Match
                // a queued command only for flags == 1; drop flags == 0 blocks as
                // out-of-band without consuming the FIFO, so a stray spontaneous
                // block can't desync command↔reply pairing for the rest of the
                // session. (tmux cmdq_guard: flags = !!(state & CMDQ_STATE_CONTROL),
                // stable across tmux 3.1–3.5.)
                currentCommand = if (line.flagsField() == "1") dequeueCommand() else null
                replyBody.reset()
            }
            line.startsWith("%output ") -> handleOutput(line)
            // Any window-state change re-requests the full window list (the
            // caller diffs to avoid redundant work). `%session-changed` also
            // fires right after the attach-time `%begin` blocks, so it doubles
            // as the initial trigger — by then a spontaneous `%begin` can no
            // longer steal the list-windows reply via the command FIFO.
            line.startsWith("%session-changed") -> onWindowsDirty()
            line.startsWith("%session-window-changed") -> onWindowsDirty()
            line.startsWith("%window-add") -> onWindowsDirty()
            line.startsWith("%window-close") -> onWindowsDirty()
            line.startsWith("%window-renamed") -> onWindowsDirty()
            line.startsWith("%layout-change") -> onWindowsDirty()
            line.startsWith("%exit") ->
                onExit(line.removePrefix("%exit").trim().ifEmpty { null })
        }
    }

    /** A line inside a `%begin`…`%end`/`%error` reply block. Closes on the
     *  matching terminator (its number must equal [replyNum]); otherwise the
     *  line is body, kept for a tracked capture-pane or list-windows. Captured
     *  lines are rejoined with CRLF — for capture so the emulator advances and
     *  returns to column 0, for list-windows the parser splits on it. */
    private fun handleReplyLine(line: String) {
        if ((line.startsWith("%end") || line.startsWith("%error")) &&
            line.numberField() == replyNum
        ) {
            if (line.startsWith("%end")) {
                when (val cmd = currentCommand) {
                    is Pending.Capture -> onCaptureReply(cmd.paneId, replyBody.toByteArray())
                    Pending.Windows -> onWindowsChanged(parseWindows(replyBody.toByteArray()))
                    Pending.Other, null -> {}
                }
            }
            inReplyBlock = false
            replyNum = null
            currentCommand = null
            replyBody.reset()
            return
        }
        when (currentCommand) {
            is Pending.Capture, Pending.Windows -> {
                if (replyBody.size() > 0) {
                    replyBody.write(CR.toInt())
                    replyBody.write(NL.toInt())
                }
                replyBody.write(line.toByteArray(Charsets.ISO_8859_1))
            }
            else -> {}
        }
    }

    /** The `<num>` token of `%begin`/`%end`/`%error` (`%begin <ts> <num> …`). */
    private fun String.numberField(): String? = split(' ').getOrNull(2)

    /** The `<flags>` token of `%begin` (`%begin <ts> <num> <flags>`). */
    private fun String.flagsField(): String? = split(' ').getOrNull(3)

    /** `%output %<pane> <payload>` */
    private fun handleOutput(line: String) {
        val rest = line.substring(OUTPUT_PREFIX.length)
        val sp = rest.indexOf(' ')
        val pane = if (sp < 0) rest else rest.substring(0, sp)
        if (sp < 0) return
        val payload = rest.substring(sp + 1)
        if (payload.isNotEmpty()) onPaneOutput(pane, decodeOutput(payload))
    }

    /**
     * Encode [data] as one or more `send-keys` command lines. Long inputs are
     * split into [MAX_BYTES_PER_COMMAND]-byte commands so a paste can't produce
     * an over-long control line.
     */
    fun encodeInput(data: ByteArray): ByteArray {
        if (data.isEmpty()) return EMPTY
        val pane = activePaneId
        val out = StringBuilder()
        var offset = 0
        while (offset < data.size) {
            val end = minOf(offset + MAX_BYTES_PER_COMMAND, data.size)
            out.append("send-keys")
            if (pane != null) out.append(" -t ").append(pane)
            out.append(" -lH")
            for (i in offset until end) {
                out.append(' ').append(Integer.toHexString(data[i].toInt() and 0xFF))
            }
            out.append('\n')
            enqueueCommand(Pending.Other)
            offset = end
        }
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    /**
     * Build a `capture-pane` command line for [paneId] and remember that the
     * reply body belongs to it. `-p` writes to stdout, `-e` keeps SGR colours,
     * `-J` joins wrapped lines, `-S -N` reaches N lines back into history.
     */
    fun requestCapture(paneId: String): ByteArray {
        enqueueCommand(Pending.Capture(paneId))
        return "capture-pane -peJ -t $paneId -S -$CAPTURE_LINES\n".toByteArray(Charsets.US_ASCII)
    }

    /**
     * Build a `list-windows` command whose reply enumerates each window and its
     * pane (tab-separated), routed to [onWindowsChanged]. Needed because tmux
     * does not push window/pane state on attach — the client must ask.
     */
    fun requestWindows(): ByteArray {
        enqueueCommand(Pending.Windows)
        return (
            "list-windows -F " +
                "'#{window_id}\t#{window_index}\t#{window_name}\t#{window_active}\t#{pane_id}\t#{pane_current_command}'\n"
            ).toByteArray(Charsets.US_ASCII)
    }

    /** Build a `select-window` command for [windowId] (e.g. `@13`). tmux replies
     *  with `%session-window-changed`, which re-requests the window list. */
    fun selectWindow(windowId: String): ByteArray {
        enqueueCommand(Pending.Other)
        return "select-window -t $windowId\n".toByteArray(Charsets.US_ASCII)
    }

    /** Build a `new-window` command (prefix keys don't work in control mode). */
    fun newWindow(): ByteArray {
        enqueueCommand(Pending.Other)
        return "new-window\n".toByteArray(Charsets.US_ASCII)
    }

    /** Build a `refresh-client -C` reporting the client size. A control client
     *  has no usable tty size, so without this tmux keeps the session at its
     *  80x24 default and every pane renders for the wrong width. */
    fun refreshClientSize(columns: Int, rows: Int): ByteArray {
        enqueueCommand(Pending.Other)
        return "refresh-client -C ${columns}x$rows\n".toByteArray(Charsets.US_ASCII)
    }

    /** Point keystrokes at [paneId] — the window the user is viewing. */
    fun setInputPane(paneId: String) {
        activePaneId = paneId
    }

    @Synchronized
    private fun enqueueCommand(cmd: Pending) {
        pendingCommands.addLast(cmd)
    }

    @Synchronized
    private fun dequeueCommand(): Pending? =
        if (pendingCommands.isEmpty()) null else pendingCommands.removeFirst()

    private sealed interface Pending {
        data class Capture(val paneId: String) : Pending
        data object Windows : Pending
        data object Other : Pending
    }

    companion object {
        private const val NL = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
        private const val OUTPUT_PREFIX = "%output "
        private const val MAX_BYTES_PER_COMMAND = 512
        private const val CAPTURE_LINES = 2000

        /** Reverse tmux's `\ooo` octal escaping back to raw bytes. */
        fun decodeOutput(payload: String): ByteArray {
            val out = ByteArrayOutputStream(payload.length)
            var i = 0
            while (i < payload.length) {
                val c = payload[i]
                if (c == '\\' && i + 3 < payload.length &&
                    payload[i + 1].isOctal() && payload[i + 2].isOctal() && payload[i + 3].isOctal()
                ) {
                    out.write(payload.substring(i + 1, i + 4).toInt(8))
                    i += 4
                } else {
                    out.write(c.code and 0xFF)
                    i++
                }
            }
            return out.toByteArray()
        }

        private fun Char.isOctal() = this in '0'..'7'

        /** Parse a `list-windows` reply body (one tab-separated record per line). */
        private fun parseWindows(body: ByteArray): List<TmuxControlWindow> =
            String(body, Charsets.UTF_8).split("\r\n", "\n").mapNotNull { line ->
                if (line.isEmpty()) return@mapNotNull null
                val f = line.split('\t')
                if (f.size < 6) return@mapNotNull null
                val index = f[1].toIntOrNull() ?: return@mapNotNull null
                TmuxControlWindow(
                    id = f[0],
                    index = index,
                    name = f[2],
                    active = f[3] == "1",
                    paneId = f[4],
                    command = f[5],
                )
            }

        private val EMPTY = ByteArray(0)
    }
}
