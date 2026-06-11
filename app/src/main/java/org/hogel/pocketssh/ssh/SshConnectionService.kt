package org.hogel.pocketssh.ssh

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import org.hogel.pocketssh.BuildConfig
import org.hogel.pocketssh.R
import org.hogel.pocketssh.ui.TerminalActivity
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Foreground service that owns the SSH session so the connection survives
 * the UI being backgrounded or destroyed. A rolling buffer of all SSH
 * output is kept (bounded by [MAX_BUFFER_BYTES]) and replayed to the
 * listener on every attach, so a freshly recreated TerminalActivity can
 * reconstruct the previous screen state by feeding the bytes back into
 * the terminal emulator.
 */
class SshConnectionService : Service() {

    enum class State { IDLE, CONNECTING, CONNECTED, FAILED, DISCONNECTED }

    interface StatusListener {
        fun onSshConnected() {}
        fun onSshDisconnected(error: Throwable?) {}
    }

    inner class LocalBinder : Binder() {
        fun getService(): SshConnectionService = this@SshConnectionService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var state: State = State.IDLE
        private set

    @Volatile
    var lastError: Throwable? = null
        private set

    @Volatile
    var serviceStartedAt: Long = 0L
        private set

    @Volatile
    var lastConnectedAt: Long = 0L
        private set

    @Volatile
    var lastReadAt: Long = 0L
        private set

    @Volatile
    var totalReadBytes: Long = 0L
        private set

    @Volatile
    var lastKeepaliveAt: Long = 0L
        private set

    private var session: SshSession? = null
    private var readThread: Thread? = null

    @Volatile
    var connectionLabel: String = ""
        private set

    // Whether the active session was opened with tmux. Mirrors
    // [ConnectionParams.useTmux] so a TerminalActivity that resumes onto an
    // already-connected service (e.g., from the notification, or from
    // MainActivity's "open" button after the previous activity instance was
    // destroyed) can rebuild its tmux-only UI without relying on intent extras.
    @Volatile
    var useTmux: Boolean = false
        private set

    // Last OSC window title observed by an attached TerminalActivity. Cached
    // here so a freshly created activity can restore its app-context
    // shortcuts even when the title bytes have rolled out of [outputHistory].
    @Volatile
    var lastTitle: String? = null

    private val outputLock = Any()
    private val outputHistory = ByteArrayOutputStream()
    private var outputListener: ((ByteArray) -> Unit)? = null

    private val statusListeners = CopyOnWriteArrayList<StatusListener>()

    private val sshWriteExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ssh-write").apply { isDaemon = true }
    }

    private val probeExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ssh-probe").apply { isDaemon = true }
    }

    // SCP uploads run on a separate executor so they don't block keystroke
    // writes on `sshWriteExecutor` while a multi-MB image is being streamed.
    private val scpExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ssh-scp").apply { isDaemon = true }
    }

    // The in-flight upload, so its notification's cancel action can interrupt
    // it without tearing down the SSH connection.
    @Volatile
    private var uploadFuture: Future<*>? = null

    private val keepaliveExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ssh-keepalive").apply { isDaemon = true }
    }
    private var keepaliveTask: ScheduledFuture<*>? = null

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        serviceStartedAt = System.currentTimeMillis()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL_UPLOAD) {
            cancelUpload()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notification_text_connecting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        return START_NOT_STICKY
    }

    /**
     * Start a new SSH connection. No-op if a connection is already running.
     *
     * [authenticator] and [hostKeyPrompt] are invoked on a background thread
     * during the SSH handshake; both must post to the UI thread to show
     * their dialog and block until the user completes or cancels. Passing
     * them in here avoids coupling the foreground service to any UI.
     */
    fun connect(
        params: ConnectionParams,
        authenticator: BiometricAuthenticator,
        hostKeyPrompt: HostKeyPrompt,
        columns: Int,
        rows: Int,
    ) {
        synchronized(this) {
            if (state == State.CONNECTING || state == State.CONNECTED) return
            state = State.CONNECTING
            lastError = null
            connectionLabel = "${params.username}@${params.host}:${params.port}"
            useTmux = params.useTmux
            lastTitle = null
            trace { "state -> CONNECTING (useTmux=${params.useTmux})" }
            updateNotification(getString(R.string.notification_text_connecting))
            readThread = thread(name = "ssh-read") {
                runReadLoop(params, authenticator, hostKeyPrompt, columns, rows)
            }
        }
    }

    private fun runReadLoop(
        params: ConnectionParams,
        authenticator: BiometricAuthenticator,
        hostKeyPrompt: HostKeyPrompt,
        columns: Int,
        rows: Int,
    ) {
        var caught: Throwable? = null
        // Held outside the try so the finally can disconnect a connection that
        // was opened but never published to [session] (e.g. openShell threw
        // after connect succeeded). Without this the live Connection and its
        // trilead receiver thread would leak on every failed retry.
        var ssh: SshSession? = null
        try {
            val keyManager = SshKeyManager()
            val publicKey = keyManager.loadPublicKey()
                ?: throw SshAuthenticationException("No SSH key found")
            val signatureProxy = KeystoreSignatureProxy(publicKey, keyManager, authenticator)
            val hostKeyVerifier = TofuHostKeyVerifier(HostKeyStore(this), hostKeyPrompt)
            ssh = SshSession(
                params.host,
                params.port,
                params.username,
                signatureProxy,
                hostKeyVerifier,
            )
            ssh.connect()
            trace { "ssh connected, opening shell" }
            ssh.openShell(
                columns.coerceAtLeast(1),
                rows.coerceAtLeast(1),
                params.useTmux,
            )
            session = ssh
            state = State.CONNECTED
            lastConnectedAt = System.currentTimeMillis()
            trace { "state -> CONNECTED" }
            updateNotification(getString(R.string.notification_text_connected, connectionLabel))
            startKeepalive(ssh)
            notifyStatus { it.onSshConnected() }

            val buffer = ByteArray(8192)
            val input = ssh.stdout
            var lastReadTrace = 0L
            while (true) {
                val n = input.read(buffer)
                if (n == -1) break
                val now = System.currentTimeMillis()
                lastReadAt = now
                totalReadBytes += n
                if (BuildConfig.DEBUG && now - lastReadTrace >= READ_TRACE_INTERVAL_MS) {
                    Log.d(TAG, "read total=$totalReadBytes bytes")
                    lastReadTrace = now
                }
                deliverOutput(buffer.copyOf(n))
            }
            trace { "read loop EOF (total=$totalReadBytes)" }
        } catch (e: Throwable) {
            caught = e
            Log.e(TAG, "SSH session error", e)
        } finally {
            stopKeepalive()
            // disconnect() sends an SSH_MSG_DISCONNECT; on a half-open socket
            // the TCP write can block for the OS keepalive window. Detach it
            // so state teardown and onSshDisconnected fire immediately —
            // otherwise the service looks alive (state CONNECTED, session
            // non-null) while every write fails with "channel is closed".
            // Use the worker-local [ssh], not the [session] field: on an
            // openShell failure the field is still null but the connection is
            // already live, and only [ssh] holds it. After a successful
            // connect the two reference the same instance.
            val dying = ssh
            Thread({ runCatching { dying?.disconnect() } }, "ssh-shutdown-finally")
                .apply { isDaemon = true }
                .start()
            session = null
            lastError = caught
            state = if (caught != null) State.FAILED else State.DISCONNECTED
            trace { "state -> $state" }
            notifyStatus { it.onSshDisconnected(caught) }
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun startKeepalive(ssh: SshSession) {
        // OpenSSH's `ServerAliveInterval 120` equivalent: an SSH_MSG_IGNORE
        // every 120s keeps NAT/firewall mappings warm. A failure here means
        // the link is dead; the read loop will then unblock with EOF.
        keepaliveTask = keepaliveExecutor.scheduleWithFixedDelay({
            try {
                ssh.sendKeepalive()
                lastKeepaliveAt = System.currentTimeMillis()
                trace { "keepalive ok" }
            } catch (e: Exception) {
                Log.w(TAG, "SSH keepalive failed", e)
            }
        }, KEEPALIVE_INTERVAL_SECONDS, KEEPALIVE_INTERVAL_SECONDS, TimeUnit.SECONDS)
    }

    private fun stopKeepalive() {
        keepaliveTask?.cancel(false)
        keepaliveTask = null
    }

    private fun deliverOutput(data: ByteArray) {
        val current: ((ByteArray) -> Unit)?
        synchronized(outputLock) {
            outputHistory.write(data)
            if (outputHistory.size() > MAX_BUFFER_BYTES) {
                val all = outputHistory.toByteArray()
                val keep = all.copyOfRange(all.size - MAX_BUFFER_BYTES, all.size)
                outputHistory.reset()
                outputHistory.write(keep)
            }
            current = outputListener
        }
        current?.let { listener ->
            mainHandler.post { listener(data) }
        }
    }

    /**
     * Register an output listener. The full output history accumulated so
     * far is delivered on the main thread as backlog; the history is not
     * cleared so future attaches can replay it again.
     *
     * The backlog is delivered in [BACKLOG_REPLAY_CHUNK_BYTES] chunks across
     * separate `Handler.post` calls so the UI thread can interleave input
     * events between chunks. With a single 256 KB post the emulator's
     * synchronous `append` (plus every OSC title fan-out) saturates the
     * main thread long enough that taps land after the terminal has
     * already gone unresponsive — and worse, blocking SSH reads back up
     * tmux on the server side, which then can't service new keystrokes
     * either.
     */
    fun attachOutputListener(listener: (ByteArray) -> Unit) {
        val backlogSize: Int
        // Hold the lock across both the listener install and the backlog
        // posts. `mainHandler.post` is non-blocking, so the lock is held only
        // long enough to enqueue the chunks — but that window is enough to
        // keep any concurrent `deliverOutput` from posting newer bytes
        // between two backlog chunks, which would replay out of order. The
        // backlog is still split into [BACKLOG_REPLAY_CHUNK_BYTES] posts so
        // the UI thread can interleave input events between chunks.
        synchronized(outputLock) {
            val backlog = if (outputHistory.size() > 0) outputHistory.toByteArray() else null
            backlogSize = backlog?.size ?: 0
            backlog?.let { full ->
                var offset = 0
                while (offset < full.size) {
                    val end = minOf(offset + BACKLOG_REPLAY_CHUNK_BYTES, full.size)
                    val chunk = full.copyOfRange(offset, end)
                    mainHandler.post { listener(chunk) }
                    offset = end
                }
            }
            outputListener = listener
        }
        trace { "attachOutputListener backlog=$backlogSize" }
    }

    fun detachOutputListener() {
        synchronized(outputLock) { outputListener = null }
        trace { "detachOutputListener" }
    }

    fun addStatusListener(listener: StatusListener) {
        statusListeners += listener
        when (state) {
            State.CONNECTED -> mainHandler.post { listener.onSshConnected() }
            State.FAILED, State.DISCONNECTED ->
                mainHandler.post { listener.onSshDisconnected(lastError) }
            else -> Unit
        }
    }

    fun removeStatusListener(listener: StatusListener) {
        statusListeners.remove(listener)
    }

    private fun notifyStatus(action: (StatusListener) -> Unit) {
        mainHandler.post { statusListeners.forEach(action) }
    }

    fun writeToSsh(data: ByteArray) {
        val out = session?.stdin ?: return
        sshWriteExecutor.execute {
            try {
                out.write(data)
                out.flush()
            } catch (e: Exception) {
                Log.e(TAG, "SSH write error", e)
            }
        }
    }

    /**
     * Stream-upload to [remoteDir]/[filename] over SFTP in the background,
     * surfacing progress, success, and failure through a dedicated
     * notification so the terminal stays usable for the duration. The stream
     * is opened by [openInput] on the upload thread (so opening a large source
     * does not block the UI); [totalBytes] (<= 0 if unknown) drives the
     * progress bar. The transfer can be cancelled from its notification action
     * ([ACTION_CANCEL_UPLOAD]). [onResult] is posted on the main thread for the
     * caller to run any in-app follow-up (`null` = success); UI feedback itself
     * is the notification's job. Posts a not-connected error and returns
     * without starting if there is no live session.
     */
    fun uploadFile(
        openInput: () -> java.io.InputStream,
        filename: String,
        remoteDir: String,
        totalBytes: Long,
        onResult: (Throwable?) -> Unit,
    ) {
        val ssh = session
        if (ssh == null || state != State.CONNECTED) {
            mainHandler.post { onResult(IllegalStateException("Not connected")) }
            return
        }
        showUploadProgress(filename, 0, totalBytes)
        uploadFuture = scpExecutor.submit {
            var lastPct = -1
            val error = try {
                ssh.uploadFile(openInput(), filename, remoteDir) { sent ->
                    if (totalBytes > 0) {
                        val pct = ((sent * 100) / totalBytes).toInt().coerceIn(0, 100)
                        if (pct != lastPct) {
                            lastPct = pct
                            showUploadProgress(filename, pct, totalBytes)
                        }
                    }
                }
                null
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                cancelUploadNotification()
                return@submit
            } catch (e: Throwable) {
                if (Thread.currentThread().isInterrupted) {
                    cancelUploadNotification()
                    return@submit
                }
                Log.e(TAG, "SFTP upload failed", e)
                e
            }
            uploadFuture = null
            showUploadDone(filename, error)
            mainHandler.post { onResult(error) }
        }
    }

    /** Cancel the in-flight upload (if any) and clear its notification. */
    private fun cancelUpload() {
        uploadFuture?.cancel(true)
        uploadFuture = null
        cancelUploadNotification()
    }

    /**
     * List remote directory [path] via SFTP for the file browser. [onResult] is
     * invoked on the main thread with the resolved absolute path and entries on
     * success, or the thrown error on failure. Runs on the same single-threaded
     * [scpExecutor] as uploads/downloads, so it serializes with an in-flight
     * transfer (acceptable: the browser is modal during a transfer).
     */
    fun listRemoteDir(path: String, onResult: (Result<RemoteListing>) -> Unit) {
        val ssh = session
        if (ssh == null || state != State.CONNECTED) {
            mainHandler.post { onResult(Result.failure(IllegalStateException("Not connected"))) }
            return
        }
        scpExecutor.submit {
            val result = try {
                val resolved = ssh.canonicalPath(path)
                Result.success(RemoteListing(resolved, ssh.listDir(resolved)))
            } catch (e: Throwable) {
                // Do NOT log [path]: a remote path is user-controlled target data.
                Log.e(TAG, "SFTP list failed")
                Result.failure(e)
            }
            mainHandler.post { onResult(result) }
        }
    }

    /**
     * Download remote file [remotePath] via SFTP into [out], reporting `null` on
     * success or the thrown error on failure on the main thread. Returns a
     * [Future] the caller can `cancel(true)` to interrupt a stuck download. The
     * caller owns [out] and must close it after [onResult] fires. Returns `null`
     * if not connected (in which case [onResult] is still posted).
     */
    fun downloadFile(
        remotePath: String,
        out: java.io.OutputStream,
        onResult: (Throwable?) -> Unit,
    ): Future<*>? {
        val ssh = session
        if (ssh == null || state != State.CONNECTED) {
            mainHandler.post { onResult(IllegalStateException("Not connected")) }
            return null
        }
        return scpExecutor.submit {
            val error = try {
                ssh.downloadFile(remotePath, out)
                null
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return@submit
            } catch (e: Throwable) {
                if (Thread.currentThread().isInterrupted) return@submit
                Log.e(TAG, "SFTP download failed")
                e
            }
            mainHandler.post { onResult(error) }
        }
    }

    fun resizeWindow(columns: Int, rows: Int) {
        // resize sends a packet, so it must not run on the main thread.
        runCatching {
            sshWriteExecutor.execute {
                runCatching { session?.resizeWindow(columns, rows) }
            }
        }
    }

    /**
     * Run `tmux select-window -t pocketssh:<window>` over a separate SSH
     * session and report success/failure on the main thread. The interactive
     * shell session is unaffected. Used by the `pss://open?window=...`
     * deeplink path to land on the requested tmux window. A failure (window
     * not found, network error, etc.) leaves the user on whatever window the
     * shell session was already showing.
     */
    fun execTmuxSelectWindow(window: String, onResult: (Boolean) -> Unit) {
        val ssh = session
        if (ssh == null || state != State.CONNECTED) {
            mainHandler.post { onResult(false) }
            return
        }
        Thread({
            val ok = runCatching {
                val target = shellQuote("pocketssh:$window")
                ssh.execCommand("tmux select-window -t $target") == 0
            }.getOrElse {
                Log.e(TAG, "tmux select-window failed for '$window'", it)
                false
            }
            mainHandler.post { onResult(ok) }
        }, "tmux-select-window").apply { isDaemon = true }.start()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /** Tear down the SSH connection; the service will stop itself. */
    fun shutdown() {
        // disconnect() sends an SSH-level message and must not run on the
        // main thread. Use a dedicated thread so a stuck writer can't delay
        // the teardown. The read loop will then exit and run its finally.
        Thread({ runCatching { session?.disconnect() } }, "ssh-shutdown")
            .apply { isDaemon = true }
            .start()
    }

    /**
     * Round-trip liveness check. Blocks the calling thread for up to
     * [timeoutMs] waiting on `Connection.ping()` (a global request with
     * want_reply). Returns false on timeout, exception, or when the service
     * is not currently in [State.CONNECTED]. A half-open socket leaves the
     * underlying call blocked forever; the timeout path returns without
     * cancelling the probe thread because the next [shutdown] will close the
     * connection and unblock it.
     */
    fun probeLiveness(timeoutMs: Long): Boolean {
        if (state != State.CONNECTED) return false
        val ssh = session ?: return false
        val future = probeExecutor.submit<Boolean> {
            try {
                ssh.ping()
                true
            } catch (e: Exception) {
                Log.w(TAG, "probeLiveness ping failed", e)
                false
            }
        }
        val result = try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "probeLiveness wait failed", e)
            false
        }
        trace { "probeLiveness result=$result (timeoutMs=$timeoutMs)" }
        return result
    }

    override fun onDestroy() {
        sshWriteExecutor.shutdownNow()
        scpExecutor.shutdownNow()
        keepaliveExecutor.shutdownNow()
        probeExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, TerminalActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, SshConnectionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_terminal)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notification_action_disconnect), stopIntent)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun showUploadProgress(name: String, pct: Int, totalBytes: Long) {
        val cancelIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, SshConnectionService::class.java).setAction(ACTION_CANCEL_UPLOAD),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_terminal)
            .setContentTitle(getString(R.string.upload_notification_uploading, name))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, getString(android.R.string.cancel), cancelIntent)
        if (totalBytes > 0) {
            builder.setContentText("$pct%").setProgress(100, pct, false)
        } else {
            builder.setProgress(0, 0, true)
        }
        getSystemService(NotificationManager::class.java)
            .notify(UPLOAD_NOTIFICATION_ID, builder.build())
    }

    private fun showUploadDone(name: String, error: Throwable?) {
        val text = if (error == null) {
            getString(R.string.upload_notification_done, name)
        } else {
            getString(R.string.upload_notification_failed, error.message ?: "")
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_terminal)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify(UPLOAD_NOTIFICATION_ID, notification)
    }

    private fun cancelUploadNotification() {
        getSystemService(NotificationManager::class.java).cancel(UPLOAD_NOTIFICATION_ID)
    }

    fun snapshot(): Snapshot {
        val bufferBytes: Int
        val attached: Boolean
        synchronized(outputLock) {
            bufferBytes = outputHistory.size()
            attached = outputListener != null
        }
        return Snapshot(
            state = state,
            lastError = lastError,
            connectionLabel = connectionLabel,
            useTmux = useTmux,
            lastTitle = lastTitle,
            outputBufferBytes = bufferBytes,
            listenerAttached = attached,
            serviceStartedAt = serviceStartedAt,
            lastConnectedAt = lastConnectedAt,
            lastReadAt = lastReadAt,
            totalReadBytes = totalReadBytes,
            lastKeepaliveAt = lastKeepaliveAt,
        )
    }

    data class Snapshot(
        val state: State,
        val lastError: Throwable?,
        val connectionLabel: String,
        val useTmux: Boolean,
        val lastTitle: String?,
        val outputBufferBytes: Int,
        val listenerAttached: Boolean,
        val serviceStartedAt: Long,
        val lastConnectedAt: Long,
        val lastReadAt: Long,
        val totalReadBytes: Long,
        val lastKeepaliveAt: Long,
    )

    data class ConnectionParams(
        val host: String,
        val port: Int,
        val username: String,
        val useTmux: Boolean = false,
    )

    private inline fun trace(message: () -> String) {
        if (BuildConfig.DEBUG) Log.d(TAG, message())
    }

    companion object {
        const val ACTION_STOP = "org.hogel.pocketssh.action.STOP_CONNECTION"
        const val ACTION_CANCEL_UPLOAD = "org.hogel.pocketssh.action.CANCEL_UPLOAD"
        private const val CHANNEL_ID = "ssh_connection"
        private const val NOTIFICATION_ID = 1001
        private const val UPLOAD_NOTIFICATION_ID = 1002
        private const val MAX_BUFFER_BYTES = 256 * 1024
        private const val BACKLOG_REPLAY_CHUNK_BYTES = 16 * 1024
        private const val KEEPALIVE_INTERVAL_SECONDS = 120L
        private const val READ_TRACE_INTERVAL_MS = 10_000L
        private const val TAG = "SshConnectionService"
    }
}
