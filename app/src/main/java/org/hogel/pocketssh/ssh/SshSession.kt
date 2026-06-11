package org.hogel.pocketssh.ssh

import com.trilead.ssh2.ChannelCondition
import com.trilead.ssh2.Connection
import com.trilead.ssh2.SFTPv3Client
import com.trilead.ssh2.ServerHostKeyVerifier
import com.trilead.ssh2.Session
import com.trilead.ssh2.auth.SignatureProxy
import java.io.InputStream
import java.io.OutputStream
import org.hogel.pocketssh.tmux.TmuxTitle

/** One entry in a remote directory listing. */
data class RemoteEntry(val name: String, val isDirectory: Boolean, val size: Long)

/** A resolved remote directory ([path] is absolute/canonical) and its entries. */
data class RemoteListing(val path: String, val entries: List<RemoteEntry>)

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
        // kexTimeout = 0 disables the key-exchange watchdog. On a first
        // connection the TOFU verifier blocks the kex on the receiver thread
        // while the user confirms the host key fingerprint in a dialog; a
        // bounded kexTimeout would abort the handshake (and leave a half-stored
        // key) if they take too long. The TCP connect still has its own timeout.
        conn.connect(hostKeyVerifier, CONNECT_TIMEOUT_MS, NO_KEX_TIMEOUT)

        val authenticated = try {
            conn.authenticateWithPublicKey(username, signatureProxy)
        } catch (e: Throwable) {
            // authenticateWithPublicKey can throw (e.g. a cancelled biometric
            // surfaces as an IOException from SignatureProxy.sign). Close the
            // live connection before propagating so it does not leak.
            conn.close()
            throw e
        }
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
     * Upload [input] to the remote host as [remoteDir]/[filename] via SFTP,
     * streaming in chunks so a large file does not have to fit in memory.
     * Truncates any existing file at the target path (SCP-like overwrite).
     * Honors thread interruption (the caller's [java.util.concurrent.Future]
     * cancel) between chunks so a stuck upload can be abandoned without
     * tearing down the SSH connection. Closes [input]. Opens its own SFTP
     * channel over the existing [Connection], so it does not interfere with
     * the interactive shell. [onProgress] is invoked after each chunk with the
     * cumulative bytes written. Blocking; call from a background thread.
     *
     * Each [SFTPv3Client.write] blocks for the server's ack, so throughput is
     * round-trip-bound; the chunk size is the only knob. [UPLOAD_CHUNK_BYTES]
     * is kept well under OpenSSH's 256 KiB SFTP message limit while cutting the
     * round-trip count far below a conservative 32 KiB chunk.
     */
    fun uploadFile(
        input: InputStream,
        filename: String,
        remoteDir: String,
        onProgress: (Long) -> Unit = {},
    ) {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val remotePath = if (remoteDir.endsWith("/")) "$remoteDir$filename" else "$remoteDir/$filename"
        val sftp = SFTPv3Client(conn)
        try {
            val handle = sftp.createFileTruncate(remotePath)
            try {
                input.use {
                    val buf = ByteArray(UPLOAD_CHUNK_BYTES)
                    var offset = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val read = it.read(buf)
                        if (read <= 0) break
                        sftp.write(handle, offset, buf, 0, read)
                        offset += read
                        onProgress(offset)
                    }
                }
            } finally {
                sftp.closeFile(handle)
            }
        } finally {
            sftp.close()
        }
    }

    /**
     * Resolve [path] to an absolute canonical path on the remote (expands a
     * relative path or a bare `.` against the SFTP start directory, which is
     * the login home). Blocking; call from a background thread.
     */
    fun canonicalPath(path: String): String {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val sftp = SFTPv3Client(conn)
        try {
            return sftp.canonicalPath(path)
        } finally {
            sftp.close()
        }
    }

    /**
     * List the entries of remote directory [path] via SFTP, sorted directories
     * first then by name. The `.` self-entry is dropped; `..` is kept so the
     * caller can offer an "up" navigation. Opens its own SFTP channel over the
     * existing [Connection] and closes it before returning, so it does not
     * interfere with the interactive shell. Blocking; call from a background
     * thread.
     */
    fun listDir(path: String): List<RemoteEntry> {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val sftp = SFTPv3Client(conn)
        try {
            return sftp.ls(path)
                .filter { it.filename != "." }
                .map { RemoteEntry(it.filename, it.attributes.isDirectory, it.attributes.size ?: 0L) }
                .sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy { it.name })
        } finally {
            sftp.close()
        }
    }

    /**
     * Download remote file [remotePath] via SFTP, streaming its bytes into
     * [out]. Reads in chunks so a large file does not have to fit in memory.
     * Honors thread interruption (the caller's [java.util.concurrent.Future]
     * cancel) between chunks so a stuck download can be abandoned without
     * tearing down the SSH connection. The caller owns [out] and must close it.
     * Blocking; call from a background thread.
     */
    fun downloadFile(remotePath: String, out: OutputStream) {
        val conn = connection ?: throw IllegalStateException("Not connected")
        val sftp = SFTPv3Client(conn)
        try {
            val handle = sftp.openFileRO(remotePath)
            try {
                val buf = ByteArray(32 * 1024)
                var offset = 0L
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val read = sftp.read(handle, offset, buf, 0, buf.size)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    offset += read
                }
                out.flush()
            } finally {
                sftp.closeFile(handle)
            }
        } finally {
            sftp.close()
        }
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
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val NO_KEX_TIMEOUT = 0
        private const val TMUX_SESSION_NAME = "pocketssh"
        private const val UPLOAD_CHUNK_BYTES = 128 * 1024
    }
}

class SshAuthenticationException(message: String) : Exception(message)
