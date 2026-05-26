package org.hogel.pocketssh

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.text.InputFilter
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.hogel.pocketssh.databinding.ActivityMainBinding
import org.hogel.pocketssh.debug.DiagDumpActivity
import org.hogel.pocketssh.debug.LogcatActivity
import org.hogel.pocketssh.settings.SettingsBackup
import org.hogel.pocketssh.shortcuts.ShortcutStore
import org.hogel.pocketssh.ssh.SshConnectionService
import org.hogel.pocketssh.ssh.SshKeyManager
import org.hogel.pocketssh.ui.HostKeysSettingsActivity
import org.hogel.pocketssh.ui.LearningSettingsActivity
import org.hogel.pocketssh.ui.LicensesActivity
import org.hogel.pocketssh.ui.ShortcutsSettingsActivity
import org.hogel.pocketssh.ui.TerminalActivity

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var keyManager: SshKeyManager
    private lateinit var prefs: SharedPreferences

    // In-memory copy of the saved tmux prefix letter (a–z). The dialog updates
    // this and saveConnectionInput persists it on pause, mirroring how the
    // other connection fields are handled.
    private var tmuxPrefix: String = DEFAULT_TMUX_PREFIX

    private var debugMenuUnlocked: Boolean = false
    private var versionTapCount: Int = 0
    private var lastVersionTapAt: Long = 0

    private var service: SshConnectionService? = null
    private var bindRegistered = false

    private val statusListener = object : SshConnectionService.StatusListener {
        override fun onSshConnected() {
            updateConnectionStatus()
        }

        override fun onSshDisconnected(error: Throwable?) {
            updateConnectionStatus()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, ibinder: IBinder) {
            val svc = (ibinder as SshConnectionService.LocalBinder).getService()
            service = svc
            svc.addStatusListener(statusListener)
            updateConnectionStatus()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            updateConnectionStatus()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // pss://open?window=... deeplink. If we have saved credentials, forward
        // to TerminalActivity with the window target and finish() so the main
        // setup screen does not flash. Otherwise fall through to the normal UI
        // so the user can fill in the connection details.
        if (handleDeeplinkIntent(intent)) return

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Wire up the toolbar so the overflow menu (export/import settings) shows.
        setSupportActionBar(binding.toolbar)

        binding.textVersion.text = "${BuildConfig.VERSION_NAME}-${BuildConfig.GIT_SHORT_REV}"
        binding.textVersion.setOnClickListener { onVersionTapped() }

        keyManager = SshKeyManager()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        debugMenuUnlocked = getSharedPreferences(DEBUG_PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DEBUG_MENU_UNLOCKED, false)

        restoreConnectionInput()
        setupConnectionTargetToggle()
        setupSshKeyToggle()
        setupTmuxPrefixRow()
        setupTmuxToggle()
        setupShortcutsRow()

        updatePublicKeyDisplay()

        binding.btnGenerateKey.setOnClickListener {
            if (keyManager.hasKey()) {
                confirmOverwriteAndGenerateKey()
            } else {
                generateKey()
            }
        }

        binding.btnCopyKey.setOnClickListener {
            val pubKey = keyManager.getPublicKey() ?: return@setOnClickListener
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("SSH Public Key", pubKey))
            Toast.makeText(this, R.string.public_key_copied, Toast.LENGTH_SHORT).show()
        }

        binding.btnConnect.setOnClickListener {
            if (isSessionActive()) {
                resumeTerminal()
            } else {
                startConnection()
            }
        }

        binding.btnMainDisconnect.setOnClickListener { service?.shutdown() }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_diag_dump)?.isVisible = debugMenuUnlocked
        menu.findItem(R.id.action_logcat)?.isVisible = debugMenuUnlocked
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_host_keys -> {
            startActivity(Intent(this, HostKeysSettingsActivity::class.java))
            true
        }
        R.id.action_learning -> {
            startActivity(Intent(this, LearningSettingsActivity::class.java))
            true
        }
        R.id.action_export_settings -> {
            // Persist any in-flight edits before snapshotting so the export
            // matches what the user sees in the form.
            saveConnectionInput()
            showExportSettingsDialog()
            true
        }
        R.id.action_import_settings -> {
            showImportSettingsDialog()
            true
        }
        R.id.action_licenses -> {
            startActivity(Intent(this, LicensesActivity::class.java))
            true
        }
        R.id.action_open_repository -> {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.open_repository_url))))
            true
        }
        R.id.action_diag_dump -> {
            startActivity(Intent(this, DiagDumpActivity::class.java))
            true
        }
        R.id.action_logcat -> {
            startActivity(Intent(this, LogcatActivity::class.java))
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun onVersionTapped() {
        if (debugMenuUnlocked) {
            Toast.makeText(this, R.string.debug_menu_already_enabled, Toast.LENGTH_SHORT).show()
            return
        }
        val now = android.os.SystemClock.uptimeMillis()
        versionTapCount = if (now - lastVersionTapAt > VERSION_TAP_TIMEOUT_MS) 1 else versionTapCount + 1
        lastVersionTapAt = now
        if (versionTapCount >= VERSION_TAPS_TO_UNLOCK) {
            debugMenuUnlocked = true
            versionTapCount = 0
            getSharedPreferences(DEBUG_PREFS_NAME, Context.MODE_PRIVATE)
                .edit { putBoolean(KEY_DEBUG_MENU_UNLOCKED, true) }
            invalidateOptionsMenu()
            Toast.makeText(this, R.string.debug_menu_enabled, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showExportSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_export_settings, null)
        val checkbox = dialogView.findViewById<CheckBox>(R.id.check_include_learning)
        val text = dialogView.findViewById<TextView>(R.id.text_export_json)

        fun refresh() {
            text.text = SettingsBackup.export(this, includeLearning = checkbox.isChecked)
        }
        checkbox.setOnCheckedChangeListener { _, _ -> refresh() }
        refresh()

        AlertDialog.Builder(this)
            .setTitle(R.string.settings_export)
            .setView(dialogView)
            .setPositiveButton(R.string.settings_export_copy) { _, _ ->
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("PocketSecureShell settings", text.text),
                )
                Toast.makeText(this, R.string.settings_export_copied, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showImportSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_import_settings, null)
        val layout = dialogView.findViewById<TextInputLayout>(R.id.layout_import_json)
        val edit = dialogView.findViewById<TextInputEditText>(R.id.edit_import_json)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.settings_import)
            .setView(dialogView)
            .setPositiveButton(R.string.settings_import_apply, null)
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        // Override the positive button so a parse error keeps the dialog open
        // and surfaces inline through the TextInputLayout error slot.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val json = edit.text?.toString().orEmpty()
            try {
                SettingsBackup.import(this, json)
            } catch (e: Exception) {
                layout.error = getString(R.string.settings_import_invalid, e.message ?: "")
                return@setOnClickListener
            }
            applyImportedSettings()
            dialog.dismiss()
            Toast.makeText(this, R.string.settings_import_applied, Toast.LENGTH_SHORT).show()
        }
    }

    /** Reload all on-screen state from prefs after a successful import. */
    private fun applyImportedSettings() {
        restoreConnectionInput()
        binding.textTmuxPrefixValue.text = getString(R.string.tmux_prefix_value, tmuxPrefix)
        applyConnectionTargetExpanded(binding.containerConnectionTarget.visibility == View.VISIBLE)
        updateShortcutsSummary()
    }

    private fun isSessionActive(): Boolean = when (service?.state) {
        SshConnectionService.State.CONNECTING,
        SshConnectionService.State.CONNECTED -> true
        else -> false
    }

    private fun resumeTerminal() {
        startActivity(Intent(this, TerminalActivity::class.java))
    }

    /**
     * Handle a `pss://open?window=<tmux_window>` intent. Returns true if the
     * intent was a deeplink and was dispatched (caller should `return` from
     * onCreate to skip the main UI setup). Returns false for non-deeplink
     * intents (regular launcher / settings open) so onCreate proceeds normally.
     *
     * Saved credentials are required: a cold-start deeplink builds the
     * connection params from the prefs that the connect form would have used.
     * If no credentials are saved, the deeplink is logged via a Toast and the
     * main UI is shown so the user can configure them.
     */
    private fun handleDeeplinkIntent(intent: Intent?): Boolean {
        val uri = intent?.data ?: return false
        if (uri.scheme != "pss" || uri.host != "open") return false
        val window = uri.getQueryParameter("window")
        if (window.isNullOrBlank()) {
            Toast.makeText(this, R.string.deeplink_missing_window, Toast.LENGTH_LONG).show()
            return false
        }
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val host = prefs.getString(KEY_HOST, null)?.takeIf { it.isNotBlank() }
        val username = prefs.getString(KEY_USERNAME, null)?.takeIf { it.isNotBlank() }
        if (host == null || username == null) {
            Toast.makeText(this, R.string.deeplink_missing_credentials, Toast.LENGTH_LONG).show()
            return false
        }
        val port = prefs.getString(KEY_PORT, null)?.toIntOrNull() ?: 22
        val useTmux = prefs.getBoolean(KEY_USE_TMUX, true)
        val forward = Intent(this, TerminalActivity::class.java).apply {
            putExtra(TerminalActivity.EXTRA_HOST, host)
            putExtra(TerminalActivity.EXTRA_PORT, port)
            putExtra(TerminalActivity.EXTRA_USERNAME, username)
            putExtra(TerminalActivity.EXTRA_USE_TMUX, useTmux)
            putExtra(TerminalActivity.EXTRA_TMUX_WINDOW, window)
        }
        startActivity(forward)
        finish()
        return true
    }

    private fun startConnection() {
        val host = binding.editHost.text.toString().trim()
        val portStr = binding.editPort.text.toString().trim()
        val username = binding.editUsername.text.toString().trim()

        if (host.isEmpty() || username.isEmpty()) return

        val port = portStr.toIntOrNull() ?: 22

        val intent = Intent(this, TerminalActivity::class.java).apply {
            putExtra(TerminalActivity.EXTRA_HOST, host)
            putExtra(TerminalActivity.EXTRA_PORT, port)
            putExtra(TerminalActivity.EXTRA_USERNAME, username)
            putExtra(TerminalActivity.EXTRA_USE_TMUX, binding.switchUseTmux.isChecked)
        }
        startActivity(intent)
    }

    override fun onStart() {
        super.onStart()
        // Bind without BIND_AUTO_CREATE: only connect if the service is already
        // running (i.e., an SSH session is active). A dormant app shows no
        // status card.
        val intent = Intent(this, SshConnectionService::class.java)
        bindRegistered = bindService(intent, serviceConnection, 0)
        updateConnectionStatus()
    }

    override fun onStop() {
        super.onStop()
        if (bindRegistered) {
            service?.removeStatusListener(statusListener)
            unbindService(serviceConnection)
            bindRegistered = false
            service = null
        }
    }

    override fun onPause() {
        super.onPause()
        saveConnectionInput()
    }

    override fun onResume() {
        super.onResume()
        updateShortcutsSummary()
    }

    private fun updateConnectionStatus() {
        val svc = service
        val state = svc?.state ?: SshConnectionService.State.IDLE
        when (state) {
            SshConnectionService.State.CONNECTING -> {
                binding.textConnectionStatus.text =
                    getString(R.string.status_connecting_to, svc?.connectionLabel ?: "")
                binding.cardConnectionStatus.visibility = View.VISIBLE
                binding.btnMainDisconnect.visibility = View.VISIBLE
            }
            SshConnectionService.State.CONNECTED -> {
                binding.textConnectionStatus.text =
                    getString(R.string.status_connected_to, svc?.connectionLabel ?: "")
                binding.cardConnectionStatus.visibility = View.VISIBLE
                binding.btnMainDisconnect.visibility = View.VISIBLE
            }
            else -> {
                binding.cardConnectionStatus.visibility = View.GONE
                binding.btnMainDisconnect.visibility = View.GONE
            }
        }
    }

    private fun restoreConnectionInput() {
        prefs.getString(KEY_HOST, null)?.let { binding.editHost.setText(it) }
        prefs.getString(KEY_PORT, null)?.let { binding.editPort.setText(it) }
        prefs.getString(KEY_USERNAME, null)?.let { binding.editUsername.setText(it) }
        binding.switchUseTmux.isChecked = prefs.getBoolean(KEY_USE_TMUX, true)
        tmuxPrefix = normalizeTmuxPrefix(prefs.getString(KEY_TMUX_PREFIX, DEFAULT_TMUX_PREFIX))
    }

    private fun saveConnectionInput() {
        prefs.edit {
            putString(KEY_HOST, binding.editHost.text.toString())
            putString(KEY_PORT, binding.editPort.text.toString())
            putString(KEY_USERNAME, binding.editUsername.text.toString())
            putBoolean(KEY_USE_TMUX, binding.switchUseTmux.isChecked)
            putString(KEY_TMUX_PREFIX, tmuxPrefix)
        }
    }

    private fun setupConnectionTargetToggle() {
        // Auto-collapse when a target was already saved; expand on first run so
        // the user sees the input fields.
        val hasSavedTarget = !prefs.getString(KEY_HOST, null).isNullOrBlank() &&
            !prefs.getString(KEY_USERNAME, null).isNullOrBlank()
        applyConnectionTargetExpanded(!hasSavedTarget)
        binding.headerConnectionTarget.setOnClickListener {
            val expanded = binding.containerConnectionTarget.visibility == View.VISIBLE
            applyConnectionTargetExpanded(!expanded)
        }
    }

    private fun applyConnectionTargetExpanded(expanded: Boolean) {
        binding.containerConnectionTarget.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.textConnectionTargetSummary.visibility = if (expanded) View.GONE else View.VISIBLE
        binding.iconConnectionTargetChevron.rotation = if (expanded) 180f else 0f
        if (!expanded) {
            binding.textConnectionTargetSummary.text = buildConnectionTargetSummary()
        }
    }

    private fun buildConnectionTargetSummary(): String {
        val host = binding.editHost.text.toString().trim()
        val username = binding.editUsername.text.toString().trim()
        val port = binding.editPort.text.toString().trim().ifEmpty { "22" }
        if (host.isEmpty() || username.isEmpty()) {
            return getString(R.string.connection_target_summary_empty)
        }
        return "$username@$host:$port"
    }

    private fun setupSshKeyToggle() {
        // Auto-collapse when a key has already been generated.
        applySshKeyExpanded(!keyManager.hasKey())
        binding.headerSshKey.setOnClickListener {
            val expanded = binding.containerSshKey.visibility == View.VISIBLE
            applySshKeyExpanded(!expanded)
        }
    }

    private fun applySshKeyExpanded(expanded: Boolean) {
        binding.containerSshKey.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.textSshKeySummary.visibility = if (expanded) View.GONE else View.VISIBLE
        binding.iconSshKeyChevron.rotation = if (expanded) 180f else 0f
        if (!expanded) {
            binding.textSshKeySummary.setText(
                if (keyManager.hasKey()) R.string.ssh_key_summary_generated
                else R.string.ssh_key_summary_not_generated
            )
        }
    }

    private fun setupShortcutsRow() {
        binding.headerShortcuts.setOnClickListener {
            startActivity(Intent(this, ShortcutsSettingsActivity::class.java))
        }
        updateShortcutsSummary()
    }

    private fun updateShortcutsSummary() {
        val store = ShortcutStore(this)
        val groupCount = store.loadContextGroups().size
        binding.textShortcutsSummary.text =
            resources.getQuantityString(R.plurals.shortcuts_summary_format, groupCount, groupCount)
    }

    private fun setupTmuxPrefixRow() {
        binding.textTmuxPrefixValue.text = getString(R.string.tmux_prefix_value, tmuxPrefix)
        binding.rowTmuxPrefix.setOnClickListener { showTmuxPrefixDialog() }
    }

    private fun setupTmuxToggle() {
        // Auto-collapse when a connection target was already saved; first-run
        // users see the section open so the tmux switch and prefix are visible.
        val hasSavedTarget = !prefs.getString(KEY_HOST, null).isNullOrBlank() &&
            !prefs.getString(KEY_USERNAME, null).isNullOrBlank()
        applyTmuxExpanded(!hasSavedTarget)
        binding.headerTmux.setOnClickListener {
            val expanded = binding.containerTmux.visibility == View.VISIBLE
            applyTmuxExpanded(!expanded)
        }
    }

    private fun applyTmuxExpanded(expanded: Boolean) {
        binding.containerTmux.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.textTmuxSummary.visibility = if (expanded) View.GONE else View.VISIBLE
        binding.iconTmuxChevron.rotation = if (expanded) 180f else 0f
        if (!expanded) {
            binding.textTmuxSummary.text = buildTmuxSummary()
        }
    }

    private fun buildTmuxSummary(): String =
        if (binding.switchUseTmux.isChecked) {
            getString(R.string.tmux_summary_on, tmuxPrefix)
        } else {
            getString(R.string.tmux_summary_off)
        }

    private fun showTmuxPrefixDialog() {
        val edit = EditText(this).apply {
            setText(tmuxPrefix)
            setSelection(text.length)
            filters = arrayOf(InputFilter.LengthFilter(1))
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        val pad = (resources.displayMetrics.density * 24).toInt()
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(edit)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.tmux_prefix)
            .setMessage(R.string.tmux_prefix_dialog_message)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                tmuxPrefix = normalizeTmuxPrefix(edit.text.toString())
                binding.textTmuxPrefixValue.text = getString(R.string.tmux_prefix_value, tmuxPrefix)
                prefs.edit { putString(KEY_TMUX_PREFIX, tmuxPrefix) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun normalizeTmuxPrefix(input: String?): String {
        val trimmed = input?.trim()?.lowercase().orEmpty()
        if (trimmed.length == 1 && trimmed[0] in 'a'..'z') return trimmed
        return DEFAULT_TMUX_PREFIX
    }

    private fun confirmOverwriteAndGenerateKey() {
        AlertDialog.Builder(this)
            .setTitle(R.string.key_overwrite_title)
            .setMessage(R.string.key_overwrite_message)
            .setPositiveButton(R.string.key_overwrite_confirm) { _, _ -> generateKey() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun generateKey() {
        keyManager.generateKey()
        Toast.makeText(this, R.string.key_generated, Toast.LENGTH_SHORT).show()
        updatePublicKeyDisplay()
    }

    private fun updatePublicKeyDisplay() {
        val hasKey = keyManager.getPublicKey() != null
        binding.btnCopyKey.visibility = if (hasKey) View.VISIBLE else View.GONE
    }

    companion object {
        // Connection-prefs schema is also consumed by SettingsBackup, so these
        // are exposed module-internal rather than activity-private.
        internal const val PREFS_NAME = "connection"
        internal const val KEY_HOST = "host"
        internal const val KEY_PORT = "port"
        internal const val KEY_USERNAME = "username"
        internal const val KEY_USE_TMUX = "use_tmux"
        internal const val KEY_TMUX_PREFIX = "tmux_prefix"
        private const val DEFAULT_TMUX_PREFIX = "b"

        // Hidden diagnostic menu, unlocked the same way Android's Developer
        // options screen reveals itself. Kept in its own prefs file so it
        // never participates in SettingsBackup export/import.
        private const val DEBUG_PREFS_NAME = "debug"
        private const val KEY_DEBUG_MENU_UNLOCKED = "menu_unlocked"
        private const val VERSION_TAPS_TO_UNLOCK = 7
        private const val VERSION_TAP_TIMEOUT_MS = 3_000L
    }
}
