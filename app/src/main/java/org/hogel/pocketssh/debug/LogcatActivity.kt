package org.hogel.pocketssh.debug

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Process
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.hogel.pocketssh.R
import org.hogel.pocketssh.databinding.ActivityLogcatBinding
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Viewer for the app's own logcat output, reached through the hidden menu
 * that MainActivity reveals after seven taps on the version label. Android
 * since 4.1 scopes third-party logcat reads to the calling process, so
 * `logcat -d` here only returns this app's log entries.
 */
class LogcatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLogcatBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLogcatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener { finish() }

        binding.btnRefresh.setOnClickListener { reload() }
        binding.btnCopy.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("PocketSecureShell logcat", binding.textLogcat.text),
            )
            Toast.makeText(this, R.string.logcat_copied, Toast.LENGTH_SHORT).show()
        }

        reload()
    }

    private fun reload() {
        binding.textLogcat.text = readLogcat()
    }

    private fun readLogcat(): String = try {
        val pid = Process.myPid().toString()
        val process = ProcessBuilder(
            "logcat",
            "-d",
            "-v",
            "threadtime",
            "--pid",
            pid,
        ).redirectErrorStream(true).start()
        BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
    } catch (e: Exception) {
        getString(R.string.logcat_unavailable, e.message ?: e.javaClass.simpleName)
    }
}
