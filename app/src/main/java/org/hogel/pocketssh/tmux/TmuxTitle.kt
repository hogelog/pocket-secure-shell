package org.hogel.pocketssh.tmux

/**
 * Parsed representation of the OSC window title emitted by our tmux
 * `set-titles-string` template.
 *
 * tmux only has one client-bound side channel to the SSH terminal that the
 * bundled termux `TerminalEmulator` exposes to the app — the OSC 0/1/2 title.
 * Other OSC codes are silently dropped (only color/clipboard/title cases are
 * handled; everything else hits `unknownParameter`). To surface the window
 * list without forking the vendor emulator, we smuggle it inside the title
 * itself, separated from the foreground command name and the active pane's
 * alternate-screen flag by control bytes:
 *
 *   `<pane_current_command>${US}<alternate_on>${US}W:<idx>${GS}<name>${GS}<active>${RS}...`
 *
 * `<alternate_on>` is `1` while the active pane runs a full-screen app on the
 * alternate screen (vim/less/…), `0` on the normal screen (a shell, or a TUI
 * that prints into the scrollback). It gates the native scrollback overlay,
 * which can only reproduce history that lives in tmux's pane buffer.
 *
 * `US`, `RS`, `GS` are the ASCII unit/record/group separators (0x1F, 0x1E,
 * 0x1D) — non-printable and not BEL/ESC, so they pass cleanly through tmux's
 * format expansion and the OSC payload.
 */
data class TmuxTitle(
    val command: String?,
    val alternateOn: Boolean,
    val windows: List<TmuxWindow>,
) {
    companion object {
        const val US = ''
        const val RS = ''
        const val GS = ''
        private const val WINDOWS_PREFIX = "W:"

        val EMPTY = TmuxTitle(command = null, alternateOn = false, windows = emptyList())

        /**
         * Parse a raw OSC title. The alternate-screen flag and windows section
         * are both optional: a payload that jumps straight to "W:" (or carries
         * no separators at all) degrades to `alternateOn = false`, so older or
         * partial inputs keep parsing.
         */
        fun parse(raw: String?): TmuxTitle {
            if (raw.isNullOrEmpty()) return EMPTY
            val sep = raw.indexOf(US)
            if (sep < 0) {
                return TmuxTitle(raw.ifEmpty { null }, alternateOn = false, windows = emptyList())
            }
            val command = raw.substring(0, sep).ifEmpty { null }
            var rest = raw.substring(sep + 1)
            // The alternate-screen flag sits between the command and the
            // windows section. A payload that goes straight to "W:" carries no
            // flag (treated as normal screen).
            var alternateOn = false
            if (!rest.startsWith(WINDOWS_PREFIX)) {
                val flagSep = rest.indexOf(US)
                if (flagSep >= 0) {
                    alternateOn = rest.substring(0, flagSep) == "1"
                    rest = rest.substring(flagSep + 1)
                }
            }
            if (!rest.startsWith(WINDOWS_PREFIX)) {
                return TmuxTitle(command, alternateOn, windows = emptyList())
            }
            val windowsBlob = rest.substring(WINDOWS_PREFIX.length)
            val windows = windowsBlob.split(RS)
                .mapNotNull { record ->
                    if (record.isEmpty()) return@mapNotNull null
                    val fields = record.split(GS)
                    if (fields.size != 3) return@mapNotNull null
                    val index = fields[0].toIntOrNull() ?: return@mapNotNull null
                    val name = fields[1]
                    val active = fields[2] == "1"
                    TmuxWindow(index = index, name = name, active = active)
                }
            return TmuxTitle(command, alternateOn, windows)
        }

        /**
         * tmux `set-titles-string` template that produces a payload parseable
         * by [parse]. The template uses the `#{W:...}` per-window iterator
         * (tmux >= 3.2), so window-list updates appear in the title whenever
         * any window in the active session is added, removed, renamed, or
         * made active.
         */
        val TMUX_TITLE_FORMAT: String = buildString {
            append("#{pane_current_command}")
            append(US)
            append("#{?alternate_on,1,0}")
            append(US)
            append(WINDOWS_PREFIX)
            append("#{W:#{window_index}")
            append(GS)
            append("#{window_name}")
            append(GS)
            append("#{?window_active,1,0}")
            append(RS)
            append("}")
        }
    }
}

data class TmuxWindow(
    val index: Int,
    val name: String,
    val active: Boolean,
)
