package org.hogel.pocketssh.ssh

import com.trilead.ssh2.ChannelCondition
import com.trilead.ssh2.Connection
import com.trilead.ssh2.SCPClient
import com.trilead.ssh2.ServerHostKeyVerifier
import com.trilead.ssh2.Session
import com.trilead.ssh2.auth.SignatureProxy
import java.io.InputStream
import java.io.OutputStream
import org.hogel.pocketssh.tmux.TmuxTitle

/** SSH connection backed by sshlib (Trilead SSH2). */
class SshSession(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val signatureProxy: SignatureProxy,
    private val hostKeyVerifier: ServerHostKeyVerifier,
) {
    private var connection: Connection? = null
    private var session: Session? = null

    val stdout: InputStream get() = session!!.stdout
    val stdin: OutputStream get() = session!!.stdin

    var isConnected: Boolean = false
        private set

    /** Blocking; call from a background thread. */
    fun connect() {
        val conn = Connection(host, port)
        conn.connect(hostKeyVerifier, 10_000, 10_000)

        val authenticated = conn.authenticateWithPublicKey(username, signatureProxy)
        if (!authenticated) {
            conn.close()
            throw SshAuthenticationException("Public key authentication failed")
        }

        connection = conn
        isConnected = true
    }

    /**
     * Open an interactive session.
     *
     * When [useTmux] is false, starts a normal login shell.
     *
     * When [useTmux] is true, runs `tmux new-session -A -s <name>` via
     * `bash -lc` so ~/.profile / ~/.bashrc are sourced (PATH, aliases, nvm,
     * etc. are available) and the session survives disconnects: reconnecting
     * attaches to the existing session, or creates a new one if none exists.
     * When the session command exits, the SSH channel closes.
     */
    fun openShell(
        columns: Int,
        rows: Int,
        useTmux: Boolean = false,
    ) {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val sess = conn.openSession()
        sess.requestPTY("xterm-256color", columns, rows, 0, 0, null)
        val remoteCommand = buildRemoteCommand(useTmux)
        if (remoteCommand == null) {
            sess.startShell()
        } else {
            sess.execCommand(remoteCommand)
        }
        session = sess
    }

    private fun buildRemoteCommand(useTmux: Boolean): String? {
        if (!useTmux) return null

        val inner = buildString {
            // Configure tmux to broadcast the active pane's foreground command
            // plus the session's window list as the OSC window title, so the
            // client can render a native tab strip without a second control
            // channel. See [TmuxTitle] for the wire format. Chained into one
            // tmux invocation so the options apply to the same server we then
            // attach to.
            append("tmux set-option -g set-titles on \\; ")
            append("set-option -g set-titles-string \"")
            append(TmuxTitle.TMUX_TITLE_FORMAT)
            append("\" \\; ")
            // Status bar is replaced by the native TabLayout, so hide tmux's
            // own one. Users with a customized status will notice this.
            append("set-option -g status off \\; ")
            // Deliberately no `set-hook ... refresh-client`: it dumps the
            // whole pane to the client on every title re-emit and floods the
            // SSH channel until tmux blocks on write and the terminal freezes.
            // Title updates ride the natural triggers instead — switching
            // window changes `pane_current_command`, which marks the title
            // dirty. Renaming a non-active window lags until the next redraw,
            // acceptable for a switch-oriented tab strip.
            append("new-session -A -s ")
            append(TMUX_SESSION_NAME)
        }
        return "bash -lc ${shellQuote(inner)}"
    }

    /** Wrap [value] in single quotes for safe use inside a POSIX shell command. */
    private fun shellQuote(value: String): String {
        val escaped = value.replace("'", "'\\''")
        return "'$escaped'"
    }

    fun resizeWindow(columns: Int, rows: Int) {
        session?.resizePTY(columns, rows, 0, 0)
    }

    /**
     * Upload [bytes] to [remoteDir] on the remote host as [filename] via SCP.
     * Opens its own SSH session over the existing [Connection], so it does
     * not interfere with the interactive shell session. Blocking; call from
     * a background thread.
     */
    fun uploadBytes(bytes: ByteArray, filename: String, remoteDir: String) {
        val conn = connection ?: throw IllegalStateException("Not connected")
        SCPClient(conn).put(bytes, filename, remoteDir)
    }

    /**
     * Run a one-shot command on a separate SSH session over the same connection.
     * Blocks until the remote command exits or [timeoutMs] elapses. Returns the
     * remote exit status (or -1 on timeout / missing status). The interactive
     * shell session is unaffected. Call from a background thread.
     */
    fun execCommand(command: String, timeoutMs: Long = 5_000): Int {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val sess = conn.openSession()
        try {
            sess.execCommand(command)
            // Drain stdout/stderr so the remote can flush and exit cleanly.
            drain(sess.stdout)
            drain(sess.stderr)
            sess.waitForCondition(
                ChannelCondition.EXIT_STATUS or ChannelCondition.CLOSED,
                timeoutMs,
            )
            return sess.exitStatus ?: -1
        } finally {
            try { sess.close() } catch (_: Exception) {}
        }
    }

    private fun drain(input: InputStream) {
        val buf = ByteArray(1024)
        try {
            while (input.read(buf) > 0) { /* discard */ }
        } catch (_: Exception) { /* channel closed */ }
    }

    /** Send an SSH_MSG_IGNORE packet to keep NAT/firewall mappings warm. */
    fun sendKeepalive() {
        connection?.sendIgnorePacket()
    }

    /**
     * Round-trip liveness check: sends `SSH_MSG_GLOBAL_REQUEST
     * keepalive@openssh.com` with want_reply=true and blocks until the server
     * answers (with REQUEST_FAILURE, which is the expected reply). On a
     * half-open socket this never returns, so the caller must invoke this on a
     * worker thread and time it out externally.
     */
    fun ping() {
        connection?.ping() ?: throw IllegalStateException("Not connected")
    }

    fun disconnect() {
        isConnected = false
        try { session?.close() } catch (_: Exception) {}
        try { connection?.close() } catch (_: Exception) {}
        session = null
        connection = null
    }

    companion object {
        private const val TAG = "SshSession"
        private const val TMUX_SESSION_NAME = "pocketssh"
    }
}

class SshAuthenticationException(message: String) : Exception(message)
