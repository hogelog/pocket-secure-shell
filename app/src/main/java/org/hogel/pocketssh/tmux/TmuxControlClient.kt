package org.hogel.pocketssh.tmux

import java.io.ByteArrayOutputStream

/**
 * Parser and input encoder for tmux control mode (`tmux -CC`).
 *
 * tmux emits a line-based protocol on stdout. Each line is terminated by a
 * newline; the only `\n` bytes on the wire are line terminators, because all
 * pane output bytes below 0x20 are octal-escaped (see below). The lines we act
 * on:
 *
 *   `%begin <ts> <num> <flags>` … `%end`/`%error` — a command reply block; its
 *       body is the raw reply text, which we swallow (M1 issues no commands
 *       whose reply we read).
 *   `%output %<pane> <payload>` — pane output. tmux escapes a byte as `\ooo`
 *       (octal) when it is `< 0x20` or a backslash; every other byte (printable
 *       ASCII and 0x80-0xFF UTF-8 continuation bytes) is written literally.
 *       [decodeOutput] reverses this to the exact original bytes.
 *   `%exit [reason]` — tmux is detaching or exiting.
 *
 * Input flows the other way: [encodeInput] wraps a raw byte stream in
 * `send-keys -t %<pane> -lH <hex…>` commands. tmux's `-H` reads each argument
 * as a raw byte value (0-255) marked `KEYC_LITERAL`, so any byte sequence —
 * UTF-8, control bytes, mouse reports — round-trips unchanged to the pane.
 */
class TmuxControlClient(
    private val onPaneOutput: (ByteArray) -> Unit,
    private val onExit: (reason: String?) -> Unit,
) {
    /** Pane id (`%0`, `%1`, …) most recently seen on `%output`, used as the
     *  `send-keys` target. Read on the write thread, written on the read
     *  thread. */
    @Volatile
    private var activePaneId: String? = null

    private val lineBuffer = ByteArrayOutputStream()
    private var inReplyBlock = false

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
            if (line.startsWith("%end") || line.startsWith("%error")) inReplyBlock = false
            return
        }
        when {
            line.startsWith("%begin") -> inReplyBlock = true
            line.startsWith("%output ") -> handleOutput(line)
            line.startsWith("%exit") ->
                onExit(line.removePrefix("%exit").trim().ifEmpty { null })
            // %window-add / %layout-change / %session-changed / … land in M2+.
        }
    }

    /** `%output %<pane> <payload>` */
    private fun handleOutput(line: String) {
        val rest = line.substring(OUTPUT_PREFIX.length)
        val sp = rest.indexOf(' ')
        val pane = if (sp < 0) rest else rest.substring(0, sp)
        activePaneId = pane
        if (sp < 0) return
        val payload = rest.substring(sp + 1)
        if (payload.isNotEmpty()) onPaneOutput(decodeOutput(payload))
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
            offset = end
        }
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    companion object {
        private const val NL = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
        private const val OUTPUT_PREFIX = "%output "
        private const val MAX_BYTES_PER_COMMAND = 512

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

        private val EMPTY = ByteArray(0)
    }
}
