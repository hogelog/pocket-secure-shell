package org.hogel.pocketssh.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.hogel.pocketssh.BuildConfig
import org.hogel.pocketssh.MainActivity
import org.hogel.pocketssh.R
import org.hogel.pocketssh.databinding.ActivityDiagDumpBinding
import org.hogel.pocketssh.learning.BigramStore
import org.hogel.pocketssh.shortcuts.ShortcutStore
import org.hogel.pocketssh.ssh.HostKeyStore
import org.hogel.pocketssh.ssh.SshConnectionService
import org.hogel.pocketssh.ssh.SshKeyManager

/**
 * Diagnostic snapshot of app state. Reached through the hidden menu that
 * MainActivity reveals after seven taps on the version label. The dump is
 * shown on screen and copied to the clipboard on demand; nothing is sent
 * off-device.
 */
class DiagDumpActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDiagDumpBinding

    private var serviceSnapshot: SshConnectionService.Snapshot? = null
    private var serviceBound = false
    private var lastDump: String = ""

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as? SshConnectionService.LocalBinder)?.getService() ?: return
            serviceSnapshot = service.snapshot()
            render()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            serviceSnapshot = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagDumpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnCopy.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("PocketSecureShell diag", lastDump))
            Toast.makeText(this, R.string.diag_dump_copied, Toast.LENGTH_SHORT).show()
        }

        // Don't pass BIND_AUTO_CREATE: opening the diag screen alone must not
        // spin up an SSH service. If the service isn't already running we
        // render with a "not running" marker and skip the snapshot section.
        val intent = Intent(this, SshConnectionService::class.java)
        serviceBound = bindService(intent, serviceConnection, 0)
        render()
    }

    override fun onDestroy() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        super.onDestroy()
    }

    private fun render() {
        lastDump = buildDump()
        binding.textDump.text = lastDump
    }

    private fun buildDump(): String = buildString {
        appendLine("[App]")
        appendLine("applicationId = ${BuildConfig.APPLICATION_ID}")
        appendLine("versionName   = ${BuildConfig.VERSION_NAME}")
        appendLine("versionCode   = ${BuildConfig.VERSION_CODE}")
        appendLine("buildType     = ${BuildConfig.BUILD_TYPE}")
        appendLine("gitShortRev   = ${BuildConfig.GIT_SHORT_REV}")
        appendLine()

        appendLine("[Device]")
        appendLine("manufacturer  = ${Build.MANUFACTURER}")
        appendLine("model         = ${Build.MODEL}")
        appendLine("device        = ${Build.DEVICE}")
        appendLine("sdk           = ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})")
        appendLine()

        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        appendLine("[Connection prefs]")
        appendLine("host          = ${prefs.getString(MainActivity.KEY_HOST, "")}")
        appendLine("port          = ${prefs.getString(MainActivity.KEY_PORT, "")}")
        appendLine("username      = ${prefs.getString(MainActivity.KEY_USERNAME, "")}")
        appendLine("use_tmux      = ${prefs.getBoolean(MainActivity.KEY_USE_TMUX, true)}")
        appendLine("tmux_prefix   = ${prefs.getString(MainActivity.KEY_TMUX_PREFIX, "")}")
        appendLine()

        appendLine("[SSH key]")
        appendLine("hasKey        = ${SshKeyManager().hasKey()}")
        appendLine()

        appendLine("[Host keys]")
        appendLine("count         = ${HostKeyStore(this@DiagDumpActivity).list().size}")
        appendLine()

        appendLine("[Shortcuts]")
        appendLine("groups        = ${ShortcutStore(this@DiagDumpActivity).loadContextGroups().size}")
        appendLine()

        appendLine("[Bigram contexts]")
        val summaries = BigramStore(this@DiagDumpActivity).contextSummaries()
        if (summaries.isEmpty()) {
            appendLine("(none)")
        } else {
            summaries.forEach { appendLine("${it.context} = ${it.count}") }
        }
        appendLine()

        appendLine("[Service]")
        val snap = serviceSnapshot
        if (snap == null) {
            appendLine(if (serviceBound) "(snapshot pending)" else "(service not running)")
        } else {
            val now = System.currentTimeMillis()
            appendLine("state             = ${snap.state}")
            appendLine("connectionLabel   = ${snap.connectionLabel}")
            appendLine("useTmux           = ${snap.useTmux}")
            appendLine("lastTitle         = ${snap.lastTitle ?: "(none)"}")
            appendLine("lastError         = ${formatError(snap.lastError)}")
            appendLine("outputBufferBytes = ${snap.outputBufferBytes}")
            appendLine("listenerAttached  = ${snap.listenerAttached}")
            appendLine("serviceUptimeMs   = ${formatAge(snap.serviceStartedAt, now)}")
            appendLine("lastConnectedAt   = ${formatAge(snap.lastConnectedAt, now)}")
            appendLine("lastReadAt        = ${formatAge(snap.lastReadAt, now)}")
            appendLine("totalReadBytes    = ${snap.totalReadBytes}")
            appendLine("lastKeepaliveAt   = ${formatAge(snap.lastKeepaliveAt, now)}")
        }
        appendLine()

        appendLine("[Threads (ssh-*)]")
        val sshThreads = Thread.getAllStackTraces()
            .filterKeys { it.name.startsWith("ssh-") }
            .toSortedMap(compareBy { it.name })
        if (sshThreads.isEmpty()) {
            appendLine("(none)")
        } else {
            sshThreads.forEach { (thread, stack) ->
                appendLine("${thread.name} [${thread.state}]")
                stack.take(MAX_STACK_FRAMES).forEach { appendLine("  at $it") }
                if (stack.size > MAX_STACK_FRAMES) {
                    appendLine("  ... ${stack.size - MAX_STACK_FRAMES} more")
                }
            }
        }
    }

    private fun formatAge(timestamp: Long, now: Long): String {
        if (timestamp == 0L) return "(never)"
        val delta = now - timestamp
        return "$delta ms ago"
    }

    private fun formatError(error: Throwable?): String {
        if (error == null) return "(none)"
        val name = error.javaClass.simpleName
        val message = error.message
        return if (message.isNullOrBlank()) name else "$name: $message"
    }

    companion object {
        private const val MAX_STACK_FRAMES = 12
    }
}
