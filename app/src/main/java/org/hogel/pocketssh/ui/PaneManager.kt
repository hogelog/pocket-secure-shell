package org.hogel.pocketssh.ui

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalView
import org.hogel.pocketssh.tmux.TmuxControlWindow

/**
 * Holds one [TerminalSession] per tmux pane and renders the active one in the
 * shared [TerminalView]. Each pane's history is seeded once via `capture-pane`
 * so history and live `%output` share one local transcript and scroll natively.
 *
 * Panes are learned from a `list-windows` reply ([setWindows]) — tmux does not
 * push window/pane state on attach — and the active one is attached to the view.
 * A pane's emulator is built up front (via [TerminalSession.updateSize], not by
 * attaching), so a background pane still accumulates its output.
 *
 * Live output that arrives before the capture seed lands is buffered, then
 * flushed after the history, so the transcript stays in order
 * (history → current screen → live) rather than live-then-history.
 */
class PaneManager(
    private val terminalView: TerminalView,
    private val sessionClient: TerminalSessionClient,
    private val requestCapture: (paneId: String) -> Unit,
    private val onActivePaneChanged: (paneId: String) -> Unit,
) {
    private class Pane(val session: TerminalSession) {
        var seeded = false
        val pending = ArrayDeque<ByteArray>()
    }

    private val panes = LinkedHashMap<String, Pane>()
    var activePaneId: String? = null
        private set

    /** Live `%output` for [paneId]; creates the pane (and seeds it) on first
     *  sight. Buffered until the seed lands so history stays ahead. */
    fun onOutput(paneId: String, bytes: ByteArray) {
        val pane = panes[paneId] ?: createPane(paneId)
        if (activePaneId == null) setActive(paneId)
        if (pane.seeded) {
            appendToPane(pane, bytes)
        } else {
            pane.pending.addLast(bytes)
        }
    }

    /** Capture-pane history reply for [paneId]: append history, then the live
     *  bytes buffered during the round-trip, in that order. */
    fun onCaptureReply(paneId: String, body: ByteArray) {
        val pane = panes[paneId] ?: return
        appendToPane(pane, body)
        pane.pending.forEach { appendToPane(pane, it) }
        pane.pending.clear()
        pane.seeded = true
    }

    /** Register each window's pane (seeding its history), drop panes whose
     *  window has closed, and attach the active one. */
    fun setWindows(windows: List<TmuxControlWindow>) {
        val liveIds = windows.map { it.paneId }.toSet()
        (panes.keys - liveIds).forEach { panes.remove(it)?.session?.finishIfRunning() }
        windows.forEach { w -> if (panes[w.paneId] == null) createPane(w.paneId) }
        windows.firstOrNull { it.active }?.let { setActive(it.paneId) }
    }

    fun setActive(paneId: String) {
        val pane = panes[paneId] ?: return
        if (activePaneId == paneId) return
        activePaneId = paneId
        terminalView.attachSession(pane.session)
        onActivePaneChanged(paneId)
    }

    fun finishAll() {
        panes.values.forEach { it.session.finishIfRunning() }
        panes.clear()
        activePaneId = null
    }

    private fun appendToPane(pane: Pane, bytes: ByteArray) {
        pane.session.emulator?.append(bytes, bytes.size)
        if (panes[activePaneId] === pane) terminalView.invalidate()
    }

    private fun createPane(paneId: String): Pane {
        val active = terminalView.mEmulator
        val cols = active?.mColumns ?: DEFAULT_COLUMNS
        val rows = active?.mRows ?: DEFAULT_ROWS
        val renderer = terminalView.mRenderer
        val session = TerminalSession(
            "/system/bin/sleep", "/",
            arrayOf("sleep", "86400"),
            arrayOf("TERM=xterm-256color"),
            TRANSCRIPT_ROWS,
            sessionClient,
        )
        session.updateSize(cols, rows, renderer.getFontWidth().toInt(), renderer.getFontLineSpacing())
        val pane = Pane(session)
        panes[paneId] = pane
        requestCapture(paneId)
        return pane
    }

    companion object {
        private const val DEFAULT_COLUMNS = 80
        private const val DEFAULT_ROWS = 24
        private const val TRANSCRIPT_ROWS = 2000
    }
}
