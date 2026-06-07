package org.hogel.pocketssh.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ArrayAdapter
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.atomic.AtomicBoolean
import org.hogel.pocketssh.R
import org.hogel.pocketssh.databinding.ActivityTerminalBinding
import org.hogel.pocketssh.learning.BigramStore
import org.hogel.pocketssh.learning.BigramTracker
import org.hogel.pocketssh.links.LinkDetector
import org.hogel.pocketssh.shortcuts.ResolvedContext
import org.hogel.pocketssh.shortcuts.Shortcut
import org.hogel.pocketssh.shortcuts.ShortcutAction
import org.hogel.pocketssh.shortcuts.ShortcutStore
import org.hogel.pocketssh.shortcuts.parseShortcutActions
import org.hogel.pocketssh.shortcuts.resolve
import org.hogel.pocketssh.ssh.BiometricAuthenticationException
import org.hogel.pocketssh.ssh.BiometricAuthenticator
import org.hogel.pocketssh.ssh.HostKeyPrompt
import org.hogel.pocketssh.ssh.RemoteListing
import org.hogel.pocketssh.ssh.SshConnectionService
import org.hogel.pocketssh.ssh.SshKeyManager
import org.hogel.pocketssh.tmux.TmuxTitle
import org.hogel.pocketssh.tmux.TmuxWindow
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import com.termux.view.TerminalViewClient
import kotlin.math.abs
import kotlin.math.max

/**
 * Terminal UI activity.
 *
 * The SSH session lives in [SshConnectionService] (a foreground service), not
 * here, so the connection survives backgrounding and process death of the UI.
 * This activity binds to the service to feed SSH stdout into the
 * TerminalEmulator and forward keyboard input to ssh stdin.
 */
class TerminalActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTerminalBinding

    private var service: SshConnectionService? = null
    private var bound = false

    private var pendingParams: SshConnectionService.ConnectionParams? = null
    // Pending tmux window from a deeplink intent. Set in onCreate / onNewIntent,
    // cleared after execTmuxSelectWindow fires (whether cold-start once SSH
    // connects, or warm-start immediately on onNewIntent).
    private var pendingTmuxWindow: String? = null

    @Volatile
    private var probeInFlight: Boolean = false

    @Volatile
    private var staleConnectionHandled: Boolean = false

    // BiometricPrompt callbacks run on this executor; we keep the UI thread
    // free and post prompt-show calls explicitly via runOnUiThread below.
    private val biometricExecutor = Executors.newSingleThreadExecutor()

    private val biometricAuthenticator = object : BiometricAuthenticator {
        override fun authenticate() {
            // sshlib's auth handshake calls us on the ssh-read thread; block it
            // on this queue until the biometric callback fires on the UI side.
            // No CryptoObject is involved — the keystore key is gated by a
            // time-based validity window, so a successful prompt is enough to
            // unlock signing for the configured period.
            val resultQueue = SynchronousQueue<Result<Unit>>()
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    deliver(Result.success(Unit))
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    deliver(Result.failure(
                        BiometricAuthenticationException("Biometric error $errorCode: $errString"),
                    ))
                }

                override fun onAuthenticationFailed() {
                    // Keep the prompt open; the user can retry. The prompt only
                    // dismisses on success or error (e.g., too many attempts).
                }

                private fun deliver(r: Result<Unit>) {
                    // Offer may be called before the consumer thread parks; use
                    // put so we block until the SSH thread picks it up.
                    resultQueue.put(r)
                }
            }

            runOnUiThread {
                val prompt = BiometricPrompt(
                    this@TerminalActivity,
                    biometricExecutor,
                    callback,
                )
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(getString(R.string.biometric_prompt_title))
                    .setSubtitle(getString(R.string.biometric_prompt_subtitle))
                    .setNegativeButtonText(getString(android.R.string.cancel))
                    .setAllowedAuthenticators(
                        androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG,
                    )
                    .build()
                prompt.authenticate(info)
            }

            resultQueue.take().getOrThrow()
        }
    }

    private val hostKeyPrompt = object : HostKeyPrompt {
        override fun confirmNewHostKey(
            host: String,
            port: Int,
            algorithm: String,
            fingerprint: String,
        ): Boolean {
            // Block the ssh-read thread on this queue while the dialog runs on
            // the UI thread. Mirrors the biometric pattern above. setCancelable
            // is false so a stray back-press cannot silently accept the key.
            val resultQueue = SynchronousQueue<Boolean>()
            runOnUiThread {
                AlertDialog.Builder(this@TerminalActivity)
                    .setTitle(R.string.host_key_verify_title)
                    .setMessage(
                        getString(
                            R.string.host_key_verify_message,
                            host,
                            port,
                            algorithm,
                            fingerprint,
                        ),
                    )
                    .setCancelable(false)
                    .setPositiveButton(R.string.host_key_accept) { _, _ ->
                        resultQueue.put(true)
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        resultQueue.put(false)
                    }
                    .show()
            }
            return resultQueue.take()
        }
    }

    // Sticky modifier state: applies to the next single key input, then resets.
    private var stickyCtrl = false
    private var ctrlButton: Button? = null

    // Whether this connection is using tmux. Routes into ContextGroup.matches
    // alongside the active foreground command name so that groups gated on
    // [ContextGroup.useTmux] activate. Authoritative value lives on
    // [SshConnectionService.useTmux]; this field is populated from there once
    // the service binds, so the UI reflects the active session even when the
    // activity was recreated without intent extras (resumed from the
    // notification or from MainActivity's "open" button).
    private var useTmux = false

    // FAB speed dial expansion state. Toggled by tapping the main FAB; child
    // actions are gone-by-default and animated in/out.
    private var fabExpanded = false

    // Secure-input mode: when active, the IME proxy advertises a password
    // editor (so the keyboard suppresses prediction/learning) and bytes sent
    // to ssh are kept out of the bigram tracker. Auto-flips off on the first
    // CR/LF in `writeToSsh`, or the user can tap the on-screen badge.
    private var secureInputActive = false

    // Rolling tail of recent SSH output, scanned by `handleSshOutput` to
    // detect password prompts (`Password:`, `[sudo] password for ...:`) and
    // flip [secureInputActive] on silently. Bounded to a few hundred bytes —
    // any password prompt fits comfortably and a sliding window stops the
    // buffer from growing without bound across the session.
    private val recentOutputTail = java.io.ByteArrayOutputStream()


    // Active SCP file-browser and transfer-progress dialogs, plus the directory
    // a pending upload targets. Held so onDestroy can dismiss any open window
    // and the document picker callback knows where to put the file.
    private var scpBrowserDialog: AlertDialog? = null
    private var scpTransferDialog: AlertDialog? = null
    private var scpUploadTargetDir: String? = null
    private var scpBrowserListView: ListView? = null
    private var scpBrowserPathHeader: TextView? = null

    private var fontSizePx = DEFAULT_FONT_SIZE_PX
    private val terminalPrefs by lazy { getSharedPreferences(PREFS_TERMINAL, Context.MODE_PRIVATE) }
    private val shortcutStore by lazy { ShortcutStore(this) }
    private val bigramStore by lazy { BigramStore(this) }
    private val bigramTracker by lazy {
        BigramTracker(bigramStore) {
            // Tracker callbacks may arrive on the SSH write thread (whatever
            // thread `writeToSsh` runs on). Bounce to the UI thread before
            // touching views.
            runOnUiThread { rebuildShortcutBar() }
        }
    }
    // Cached for re-applying after returning from the shortcuts settings screen
    // without waiting for tmux to re-emit the title OSC.
    private var lastAppContext: String? = null

    // Most recent raw OSC title (whatever bytes tmux sent us, before
    // [TmuxTitle.parse]). Used to short-circuit [applyTitle] when the same
    // title is replayed during attach or when tmux re-emits an unchanged
    // title from one of our redraw hooks — both happen often enough that
    // rebuilding the shortcut bar and tab strip for each one stalls the UI
    // thread on resume.
    private var lastRawTitle: String? = null

    // Cached parse of [lastRawTitle].windows so [applyWindowList] can no-op
    // when only the command part of the title changed. Tab construction
    // (Button.inflate × N) is non-trivial; skipping when the list is
    // unchanged is the main win.
    private var lastWindows: List<TmuxWindow> = emptyList()

    // Coalesce title-driven UI rebuilds within a single UI tick. The
    // terminal emulator's `append` fires `onTitleChanged` synchronously
    // for every OSC title in the byte stream, and a backlog-replay chunk
    // can carry many of them; without coalescing each one would run the
    // full shortcut-bar + tab-strip rebuild on the main thread.
    // [pendingTitleHandler] schedules a single deferred [applyTitle]
    // against the latest cached title (via [SshConnectionService.lastTitle])
    // after the current chunk's `append` returns.
    private val pendingTitleHandler = Handler(Looper.getMainLooper())
    private val pendingTitleRunnable = Runnable {
        pendingTitleUpdate = false
        applyTitle(service?.lastTitle)
    }
    private var pendingTitleUpdate = false

    // Last (cols, rows) reported to the SSH peer. Used to suppress redundant
    // resize packets when a layout pass doesn't actually change the visible
    // cell grid.
    private var lastSentColumns = 0
    private var lastSentRows = 0

    // Vertical-drag accumulator for setupTerminalScrollRouting.
    private var scrollRemainderPx = 0f
    // True between onDown and ACTION_UP/CANCEL whenever we have claimed a
    // vertical drag. Used to swallow subsequent events from `TerminalView` so
    // its own `doScroll` doesn't emit a duplicate mouse/arrow sequence to the
    // dummy pty (which the kernel then echoes back into the screen buffer).
    private var handlingScrollGesture = false
    // Set by the detector's onSingleTapUp when the gesture resolved as a tap
    // (not a scroll / long-press). Consulted at ACTION_UP to decide whether to
    // swallow the up-event and run our own keyboard toggle.
    private var tappedThisGesture = false
    // Locked once a gesture's motion exceeds the slop threshold; after that it
    // stays on the same axis until the gesture ends. This prevents a drag that
    // started horizontal from flipping to vertical scroll partway through (and
    // vice versa), which otherwise produces a confusing mid-gesture handoff.
    private var gestureAxis = GestureAxis.UNDETERMINED
    // Sign of the pending swipe-gesture commit: -1 = left swipe, +1 = right
    // swipe, 0 = below the commit threshold so releasing now won't fire.
    // Drives the mid-gesture overlay and the ACTION_UP commit decision.
    private var pendingSwipeDirection = 0
    // Snapshot of the resolved ContextGroup cascade for the active state.
    // Populated by [applyContext] and consulted by every render path
    // (shortcut bar, FAB, swipe gesture handler) so they share a single
    // source of truth.
    private var resolved: ResolvedContext = ResolvedContext(
        shortcutRows = emptyList(),
        swipeLeft = null,
        swipeRight = null,
        fabRows = emptyList(),
    )

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { _ ->
        // The service is allowed to start even if the permission was denied;
        // the system simply suppresses the notification UI in that case.
        startAndBindService()
    }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) onImagePicked(uri) }

    // SAF file picker for SCP upload. The chosen file is uploaded into
    // [scpUploadTargetDir], the directory the browser was showing when the
    // user tapped "Upload here".
    private val scpUploadPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) onScpUploadFilePicked(uri) }

    private val outputListener: (ByteArray) -> Unit = ::handleSshOutput

    private fun handleSshOutput(data: ByteArray) {
        val emulator = binding.terminalView.mEmulator ?: return
        emulator.append(data, data.size)
        binding.terminalView.invalidate()
        detectPasswordPrompt(data)
    }

    /**
     * Append [data] to [recentOutputTail] (kept bounded to the last
     * [OUTPUT_TAIL_KEEP_BYTES] bytes) and silently flip secure-input mode on
     * when the tail ends with a password-prompt pattern. No-op once secure
     * input is already active so a multi-chunk prompt doesn't fire repeatedly.
     */
    private fun detectPasswordPrompt(data: ByteArray) {
        if (data.isEmpty()) return
        recentOutputTail.write(data)
        if (recentOutputTail.size() > OUTPUT_TAIL_KEEP_BYTES) {
            val all = recentOutputTail.toByteArray()
            val keep = all.copyOfRange(all.size - OUTPUT_TAIL_KEEP_BYTES, all.size)
            recentOutputTail.reset()
            recentOutputTail.write(keep)
        }
        if (secureInputActive) return
        // ASCII decode is enough — every password prompt token we care about
        // (`Password`, `password`, `for`, `[sudo]`) is plain ASCII, and stray
        // non-ASCII bytes upstream just become replacement characters that
        // can't accidentally match the regex.
        val ascii = String(recentOutputTail.toByteArray(), Charsets.US_ASCII)
        // Strip CSI escape sequences (`ESC [ ... <final>`) and bare ESCs that
        // typically wrap prompt drawing so the trailing match below sees the
        // visible text only.
        val stripped = ANSI_ESCAPE_REGEX.replace(ascii, "").replace("\u001B", "")
        val trimmed = stripped.trimEnd()
        if (PASSWORD_PROMPT_REGEX.containsMatchIn(trimmed)) {
            setSecureInput(true)
        }
    }

    /**
     * Centralised toggle for [secureInputActive]. Keeps the IME proxy, the
     * on-screen badge, and the bigram tracker's line state in sync so neither
     * the view layer nor the suggestion row holds stale state when secure
     * mode flips on or off.
     */
    private fun setSecureInput(active: Boolean) {
        if (secureInputActive == active) return
        secureInputActive = active
        binding.imeProxy.secureMode = active
        binding.btnPasswordBadge.visibility = if (active) View.VISIBLE else View.GONE
        if (!active) {
            // Bytes typed during secure mode were skipped by ingestSend, so
            // the tracker's `prev` may point at a stale token from before the
            // password. Reset to <BOL> so the next line's suggestions start
            // from a known state.
            bigramTracker.resetLine()
        }
    }

    private val statusListener = object : SshConnectionService.StatusListener {
        override fun onSshConnected() {
            service?.let { syncWindowSize(it) }
            flushPendingTmuxWindow()
        }

        override fun onSshDisconnected(error: Throwable?) {
            if (staleConnectionHandled) return
            val message = if (error != null) {
                "${getString(R.string.connection_failed)}: ${error.message}"
            } else {
                getString(R.string.disconnected)
            }
            Toast.makeText(this@TerminalActivity, message, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, ibinder: IBinder) {
            val svc = (ibinder as SshConnectionService.LocalBinder).getService()
            service = svc
            bound = true
            svc.attachOutputListener(outputListener)
            svc.addStatusListener(statusListener)
            val params = pendingParams
            when {
                svc.state == SshConnectionService.State.IDLE && params != null -> {
                    val emulator = binding.terminalView.mEmulator
                    val cols = emulator?.mColumns ?: DEFAULT_COLUMNS
                    val rows = emulator?.mRows ?: DEFAULT_ROWS
                    lastSentColumns = cols
                    lastSentRows = rows
                    svc.connect(params, biometricAuthenticator, hostKeyPrompt, cols, rows)
                }
                svc.state == SshConnectionService.State.IDLE -> {
                    // Resumed from notification but the service is no longer connected.
                    Toast.makeText(this@TerminalActivity, R.string.disconnected, Toast.LENGTH_SHORT).show()
                    return finish()
                }
                else -> syncWindowSize(svc)
            }
            // Resolve the ContextGroup cascade now that the service has
            // authoritative useTmux. Re-apply the cached app context so the
            // per-app shortcut row survives even when the title OSC has rolled
            // out of the buffer.
            useTmux = svc.useTmux
            applyTitle(svc.lastTitle)
            // Already-connected branch: a deeplink fired while the SSH session
            // was alive needs to switch windows now (no onSshConnected callback
            // will come). Cold-start instead waits for statusListener.
            if (svc.state == SshConnectionService.State.CONNECTED) {
                flushPendingTmuxWindow()
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding = ActivityTerminalBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val host = intent.getStringExtra(EXTRA_HOST)
        val username = intent.getStringExtra(EXTRA_USERNAME)
        val port = intent.getIntExtra(EXTRA_PORT, 22)
        if (host != null && username != null) {
            if (!SshKeyManager().hasKey()) {
                Toast.makeText(this, "No SSH key found", Toast.LENGTH_SHORT).show()
                return finish()
            }
            pendingParams = SshConnectionService.ConnectionParams(
                host, port, username,
                intent.getBooleanExtra(EXTRA_USE_TMUX, false),
            )
        }
        pendingTmuxWindow = intent.getStringExtra(EXTRA_TMUX_WINDOW)

        setupTerminalView()
        setupTerminalScrollRouting()
        // Render an initial pass with whatever defaults resolve at specifity 0
        // (the "always" group). The FAB and per-app rows fill in once the
        // service binds and reports useTmux / lastTitle.
        applyContext(null)
        applyWindowList(emptyList())
        binding.windowTabsNew.setOnClickListener { openNewTmuxWindow() }
        binding.fabMain.setOnClickListener { setFabExpanded(!fabExpanded) }
        setFabExpanded(false)
        binding.btnPasswordBadge.setOnClickListener { setSecureInput(false) }
        // Initial sync so the IME proxy / badge visibility match the
        // default-off state on a freshly created activity.
        binding.btnPasswordBadge.visibility = View.GONE
        binding.imeProxy.secureMode = false

        binding.btnDisconnect.setOnClickListener {
            service?.shutdown()
            finish()
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startAndBindService()
        }
    }

    override fun onResume() {
        super.onResume()
        // The shortcuts settings screen lives in another activity. Re-resolve
        // the cascade on every resume so edits take effect the moment we come
        // back, without waiting for tmux to re-emit the title OSC.
        applyContext(lastAppContext)
        maybeProbeLiveness()
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        // Warm-start path for the pss://open?window=... deeplink. The activity
        // is launchMode=singleTop, so MainActivity re-issues us with the new
        // window target instead of starting a fresh instance.
        setIntent(newIntent)
        newIntent.getStringExtra(EXTRA_TMUX_WINDOW)?.let { window ->
            pendingTmuxWindow = window
            flushPendingTmuxWindow()
        }
    }

    /**
     * Fire the deferred `tmux select-window` for [pendingTmuxWindow], if any.
     * Safe to call multiple times: clears the field on consumption so a later
     * onSshConnected does not re-fire. Toasts on failure.
     */
    private fun flushPendingTmuxWindow() {
        val window = pendingTmuxWindow ?: return
        val svc = service ?: return
        if (svc.state != SshConnectionService.State.CONNECTED) return
        pendingTmuxWindow = null
        svc.execTmuxSelectWindow(window) { ok ->
            if (!ok) {
                Toast.makeText(
                    this,
                    "tmux window '$window' not found",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    private fun maybeProbeLiveness() {
        if (staleConnectionHandled || probeInFlight) return
        val svc = service ?: return
        if (svc.state != SshConnectionService.State.CONNECTED) return
        probeInFlight = true
        kotlin.concurrent.thread(name = "ssh-probe-caller", isDaemon = true) {
            val alive = svc.probeLiveness(PROBE_TIMEOUT_MS)
            runOnUiThread {
                probeInFlight = false
                if (!alive) handleStaleConnection()
            }
        }
    }

    private fun handleStaleConnection() {
        if (staleConnectionHandled) return
        staleConnectionHandled = true
        Toast.makeText(this, R.string.terminal_freeze_detected_message, Toast.LENGTH_SHORT).show()
        service?.shutdown()
        finish()
    }

    private fun startAndBindService() {
        val serviceIntent = Intent(this, SshConnectionService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun syncWindowSize(svc: SshConnectionService) {
        val emulator = binding.terminalView.mEmulator ?: return
        val cols = emulator.mColumns
        val rows = emulator.mRows
        if (cols <= 0 || rows <= 0) return
        if (cols == lastSentColumns && rows == lastSentRows) return
        lastSentColumns = cols
        lastSentRows = rows
        svc.resizeWindow(cols, rows)
    }

    /**
     * Resolve the [ContextGroup] cascade for the current state and rebuild the
     * shortcut bar, the FAB rows, and the swipe payloads. Called whenever any
     * input to the resolution changes — the active foreground command name
     * (from the title OSC), the [useTmux] flag (once the service binds), or
     * a return from the settings activity that may have edited the groups.
     */
    private fun applyContext(app: String?) {
        lastAppContext = app
        bigramTracker.setContext(app)
        resolved = shortcutStore.loadContextGroups().resolve(useTmux, app)
        rebuildShortcutBar()
        rebuildFab(resolved.fabRows)
    }

    /**
     * Parse a raw OSC title and fan out to the per-app context path and the
     * native window tab strip. Called from [onTitleChanged] and from the
     * service-bind path on activity recreation. When the raw title is null
     * (service has nothing cached yet) the cached [lastAppContext] keeps the
     * shortcut bar populated and the tab strip stays as-is.
     *
     * Identical consecutive raw titles short-circuit immediately — that
     * matters because (a) backlog replay on resume hits this method many
     * times with the same bytes, and (b) our `refresh-client` hooks make
     * tmux re-emit titles even when neither the command nor the window list
     * actually moved. Both the shortcut bar rebuild and the tab strip
     * rebuild are expensive enough that doing them per-replay-chunk visibly
     * freezes the UI thread.
     */
    private fun applyTitle(rawTitle: String?) {
        if (rawTitle == lastRawTitle) return
        lastRawTitle = rawTitle
        val parsed = TmuxTitle.parse(rawTitle)
        val newCommand = parsed.command ?: lastAppContext
        if (newCommand != lastAppContext) applyContext(newCommand)
        applyWindowList(parsed.windows)
    }

    /**
     * Rebuild the native tmux window tab strip. Hidden when tmux is off or
     * the list is empty (initial state before tmux has emitted a title).
     * The active tab is highlighted via the `state_activated` branch of
     * `bg_aux_modifier`, the same drawable used by the sticky Ctrl button.
     *
     * Idempotent on an unchanged window list — [applyTitle] dedupes raw
     * titles, but a title where only the command changed (active pane's
     * `pane_current_command` flipped, window list intact) still lands here
     * with the same list, and rebuilding the buttons would be wasted work.
     */
    private fun applyWindowList(windows: List<TmuxWindow>) {
        if (windows == lastWindows) return
        lastWindows = windows
        val container = binding.windowTabs
        container.removeAllViews()
        val visible = useTmux && windows.isNotEmpty()
        binding.windowTabsBar.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return
        val tabHorizontalPaddingPx = dpToPx(6)
        val tabMinDimensionPx = dpToPx(32)
        for (window in windows) {
            val label = getString(R.string.window_tab_label, window.index, window.name)
            val tab = makeAuxButton(label) { selectTmuxWindow(window.index) }
            // Tabs are denser than the shortcut bar — smaller font, tighter
            // padding, and a 32dp minimum so a 10-window strip still fits
            // without horizontal scroll on a phone.
            tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            tab.setPadding(tabHorizontalPaddingPx, 0, tabHorizontalPaddingPx, 0)
            tab.minWidth = tabMinDimensionPx
            tab.minimumWidth = tabMinDimensionPx
            tab.minHeight = tabMinDimensionPx
            tab.minimumHeight = tabMinDimensionPx
            styleModifierButton(tab)
            tab.isActivated = window.active
            container.addView(tab, auxButtonLayoutParams())
        }
    }

    /**
     * Send the tmux key sequence that selects window [index]. Indices 0..9
     * map to `prefix + digit`; higher indices go through `prefix : select-
     * window -t N <Enter>` because tmux's default key table only binds
     * single digits.
     */
    private fun selectTmuxWindow(index: Int) {
        val prefix = readTmuxPrefixByte()
        if (index in 0..9) {
            writeToSsh(byteArrayOf(prefix, ('0'.code + index).toByte()))
        } else {
            writeToSsh(byteArrayOf(prefix))
            writeToSsh(":select-window -t $index\r".toByteArray(Charsets.UTF_8))
        }
    }

    /** Send `prefix c` to create a new tmux window. */
    private fun openNewTmuxWindow() {
        val prefix = readTmuxPrefixByte()
        writeToSsh(byteArrayOf(prefix, 'c'.code.toByte()))
    }

    /**
     * Rebuild the shortcut bar. The bottom row flattens every matching
     * [ContextGroup] into a single horizontally-scrolling line, ordered
     * specifity high → low so the active foreground command's deck sits
     * left of the always-on slice. The learned-suggestions row
     * stays above it when bigram counts yield candidates for the active
     * `(context, prev)`. Ctrl is the left-most button on the bottom row as
     * a sticky modifier toggle; it can't be expressed inside a
     * [ContextGroup] because it has no payload.
     */
    private fun rebuildShortcutBar() {
        val bar = binding.shortcutBar
        bar.removeAllViews()

        val learnedTokens = bigramStore.topNext(
            bigramTracker.currentContext(),
            bigramTracker.currentPrev(),
            LEARNED_SUGGESTION_LIMIT,
        )
        if (learnedTokens.isNotEmpty()) {
            val learnedLayout = addShortcutRow()
            for (token in learnedTokens) {
                learnedLayout.addView(makeLearnedButton(token), auxButtonLayoutParams())
            }
        }

        val mergedRow: List<Shortcut> = resolved.shortcutRows.flatten()
        val rowLayout = addShortcutRow()
        ctrlButton = makeAuxButton("Ctrl") { setCtrlSticky(!stickyCtrl) }
            .also { styleModifierButton(it); rowLayout.addView(it, auxButtonLayoutParams()) }
        for (shortcut in mergedRow) {
            rowLayout.addView(
                makeAuxButton(shortcut.label, runShortcutAction(shortcut.payload)),
                auxButtonLayoutParams(),
            )
        }
    }

    /**
     * A learned candidate button. Tap sends `<token> ` (or a literal CR for
     * the `<ENTER>` pseudo token) so the chain rolls into the next round of
     * suggestions; long-press confirms and deletes the (context, prev, token)
     * bigram so a stale or unwanted candidate can be evicted in place.
     *
     * Non-ENTER taps bypass [writeToSsh]'s [BigramTracker.ingestSend] path and
     * call [BigramTracker.commitToken] directly. Routing through `ingestSend`
     * loses the tap when the line was poisoned by a prior control byte (arrow
     * key, Ctrl-shortcut, Esc), which left the suggestion bar frozen on the
     * previous candidate set.
     */
    private fun makeLearnedButton(token: String): Button {
        val isEnter = token == BigramStore.ENTER
        val label = if (isEnter) "⏎" else token
        val action: () -> Unit = if (isEnter) {
            runShortcutAction("\\r")
        } else {
            {
                service?.writeToSsh("$token ".toByteArray(Charsets.UTF_8))
                bigramTracker.commitToken(token)
            }
        }
        val button = makeAuxButton(label, action)
        button.setOnLongClickListener {
            confirmDeleteLearnedCandidate(label, token)
            true
        }
        return button
    }

    private fun confirmDeleteLearnedCandidate(label: String, token: String) {
        val context = bigramTracker.currentContext()
        val prev = bigramTracker.currentPrev()
        AlertDialog.Builder(this)
            .setTitle(R.string.learned_delete_title)
            .setMessage(getString(R.string.learned_delete_message, label))
            .setPositiveButton(R.string.learned_delete_confirm) { _, _ ->
                bigramStore.delete(context, prev, token)
                rebuildShortcutBar()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Append a horizontally-scrollable row to [binding.shortcutBar] and
     * return the inner [LinearLayout] for caller-supplied buttons. Per-row
     * scrolling keeps an over-stuffed group from forcing the whole bar to
     * scroll sideways and stranding higher-priority rows.
     */
    private fun addShortcutRow(): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(4), 0, dpToPx(4), 0)
        }
        val scroll = HorizontalScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            isHorizontalScrollBarEnabled = false
            addView(
                row,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        binding.shortcutBar.addView(scroll)
        return row
    }

    /**
     * Rebuild the FAB speed-dial menu. Each [ContextGroup] that contributed a
     * non-empty `fabItems` list becomes one or more horizontal rows, wrapping
     * at [FAB_MAX_COLUMNS] buttons; rows are stacked specifity high → low
     * (closest match at the top, "always" at the bottom). The secure-input
     * toggle is an ordinary `{SECURE-INPUT}` item in the bundled defaults, so
     * everything here is just the resolved shortcuts.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun rebuildFab(rows: List<List<Shortcut>>) {
        val container = binding.fabActions
        container.removeAllViews()

        for (row in rows) {
            // Wrap a wide group into stacked rows of at most FAB_MAX_COLUMNS so
            // the buttons stay thumb-reachable instead of running off-screen.
            for (chunk in row.chunked(FAB_MAX_COLUMNS)) {
                val rowView = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ).apply { gravity = android.view.Gravity.END }
                    gravity = android.view.Gravity.END
                }
                for (shortcut in chunk) {
                    val btn = makeAuxButton(shortcut.label) {
                        runShortcutActions(parseShortcutActions(shortcut.payload))
                        setFabExpanded(false)
                    }
                    // Override the bar-button background with the FAB variant —
                    // same fill but with a 1dp stroke, so adjacent buttons render
                    // thin inter-cell borders without a divider mechanism.
                    btn.background = ContextCompat.getDrawable(this, R.drawable.bg_fab_button)
                    // Reveal the payload in the center overlay while pressed so the
                    // emoji label stays decipherable, hiding it as soon as the touch
                    // slides off the button (which also cancels the click). Returning
                    // false keeps the click handler firing on release, so a press can
                    // be a peek-only confirmation.
                    btn.setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN -> showPayloadFeedback(shortcut)
                            MotionEvent.ACTION_MOVE ->
                                if (event.x in 0f..v.width.toFloat() && event.y in 0f..v.height.toFloat()) {
                                    showPayloadFeedback(shortcut)
                                } else {
                                    hideSwipeFeedback()
                                }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> hideSwipeFeedback()
                        }
                        false
                    }
                    rowView.addView(btn)
                }
                container.addView(rowView)
            }
        }
    }

    /** Wraps a payload string into a click handler that runs its actions. */
    private fun runShortcutAction(payload: String): () -> Unit {
        val actions = parseShortcutActions(payload)
        return { runShortcutActions(actions) }
    }

    private fun runShortcutActions(actions: List<ShortcutAction>) {
        for (action in actions) when (action) {
            is ShortcutAction.SendBytes -> writeToSsh(action.bytes)
            is ShortcutAction.SendKey -> sendKeyCode(action.keyCode, action.keyMod)
            is ShortcutAction.SendTmuxPrefix -> writeToSsh(byteArrayOf(readTmuxPrefixByte()))
            is ShortcutAction.Copy -> startTextSelection()
            is ShortcutAction.ImagePaste -> launchImagePicker()
            is ShortcutAction.Scp -> launchScpBrowser()
            is ShortcutAction.SecureInput -> setSecureInput(!secureInputActive)
            is ShortcutAction.CtrlInput -> showControlInputDialog()
        }
        clearStickyModifiers()
    }

    private fun setFabExpanded(expanded: Boolean) {
        fabExpanded = expanded
        binding.fabActions.visibility = if (expanded) View.VISIBLE else View.GONE
        // Quarter turn gives the menu icon a subtle rotated cue when open
        // without needing a second drawable. Dim to 0.3 alpha while collapsed
        // so the FAB recedes from the terminal content until it's needed.
        binding.fabMain.animate()
            .rotation(if (expanded) 90f else 0f)
            .alpha(if (expanded) 1f else FAB_COLLAPSED_ALPHA)
            .setDuration(150)
            .start()
    }

    /**
     * Show the control-input dialog: a grid of common Ctrl sequences plus a
     * one-character field for any other letter. Every entry is sent by feeding
     * a `^X` payload back through [parseShortcutActions], so the C0 mapping
     * stays in one place ([ctrlByteFor]).
     */
    private fun showControlInputDialog() {
        val pad = dpToPx(16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        var dialog: AlertDialog? = null
        fun send(payload: String) {
            runShortcutActions(parseShortcutActions(payload))
            dialog?.dismiss()
        }

        CTRL_INPUT_PRESETS.chunked(CTRL_INPUT_COLUMNS).forEach { chunk ->
            val rowView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (preset in chunk) {
                val btn = makeAuxButton(preset) { send(preset) }
                btn.layoutParams = auxButtonLayoutParams()
                rowView.addView(btn)
            }
            root.addView(rowView)
        }

        val edit = EditText(this).apply {
            hint = getString(R.string.ctrl_input_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            filters = arrayOf(InputFilter.LengthFilter(1))
            minWidth = dpToPx(64)
        }
        fun sendTyped(): Boolean {
            val ch = edit.text.toString().firstOrNull() ?: return false
            send("^$ch")
            return true
        }
        edit.setOnEditorActionListener { _, _, _ -> sendTyped() }

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(2), dpToPx(12), dpToPx(2), 0)
            addView(TextView(this@TerminalActivity).apply { text = getString(R.string.ctrl_input_prefix) })
            addView(edit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                makeAuxButton(getString(R.string.ctrl_input_send)) { sendTyped() }
                    .apply { layoutParams = auxButtonLayoutParams() },
            )
        }
        root.addView(inputRow)

        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.ctrl_input_title)
            .setView(root)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.show()
    }

    private fun launchImagePicker() {
        imagePickerLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    /**
     * Upload the picked image to the remote host via SFTP and, on success,
     * type its `/tmp/...` path into the SSH stdin so the user can submit
     * it to Claude Code by pressing Enter.
     *
     * The transfer runs in the background (progress/result surface through the
     * service notification) so the terminal stays usable; the path is typed
     * on success only if this activity is still around to receive the result.
     */
    private fun onImagePicked(uri: Uri) {
        val svc = service
        if (svc == null || svc.state != SshConnectionService.State.CONNECTED) {
            Toast.makeText(this, R.string.image_upload_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        val resolver = contentResolver
        val mime = resolver.getType(uri).orEmpty()
        val ext = extensionForMime(mime)
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("Asia/Tokyo")
        }.format(Date())
        val filename = "pocketssh-$timestamp.$ext"

        svc.uploadFile(
            { resolver.openInputStream(uri) ?: throw IOException("Cannot open picked image") },
            filename,
            REMOTE_TMP_DIR,
            queryFileSize(uri),
        ) { error ->
            if (error == null) {
                val pathRef = "$REMOTE_TMP_DIR/$filename "
                writeToSsh(pathRef.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun extensionForMime(mime: String): String = when (mime.lowercase()) {
        "image/png" -> "png"
        "image/jpeg", "image/jpg" -> "jpg"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        "image/heic" -> "heic"
        "image/heif" -> "heif"
        else -> "png"
    }

    /**
     * Open the two-way SCP/SFTP file browser, starting at the login home (`.`
     * resolves to it server-side). Inside the browser the user navigates remote
     * directories, taps a file to download it to the device Downloads folder, or
     * taps "Upload here" to push a local file into the current directory.
     */
    private fun launchScpBrowser() {
        val svc = service
        if (svc == null || svc.state != SshConnectionService.State.CONNECTED) {
            Toast.makeText(this, R.string.image_upload_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        scpNavigate(".")
    }

    /** List [path] over SFTP and (re)render the browser dialog, or toast on failure. */
    private fun scpNavigate(path: String) {
        val svc = service ?: return
        svc.listRemoteDir(path) { result ->
            result.fold(
                onSuccess = { showScpListing(it) },
                onFailure = { e ->
                    // e.message is shown only in a local toast, never sent anywhere.
                    Toast.makeText(
                        this,
                        getString(R.string.scp_list_failed, e.message ?: ""),
                        Toast.LENGTH_LONG,
                    ).show()
                },
            )
        }
    }

    private fun showScpListing(listing: RemoteListing) {
        val path = listing.path
        val labels = mutableListOf<String>()
        val onClick = mutableListOf<() -> Unit>()
        if (path != "/") {
            labels.add("⬆  ..")
            onClick.add { scpNavigate(joinRemotePath(path, "..")) }
        }
        for (entry in listing.entries) {
            if (entry.name == "." || entry.name == "..") continue
            val target = joinRemotePath(path, entry.name)
            if (entry.isDirectory) {
                labels.add("📁  ${entry.name}/")
                onClick.add { scpNavigate(target) }
            } else {
                labels.add("📄  ${entry.name}")
                onClick.add { showScpFileActions(target, entry.name) }
            }
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)

        val existing = scpBrowserDialog
        if (existing != null && existing.isShowing) {
            scpBrowserPathHeader?.text = path
            scpBrowserListView?.let { list ->
                list.adapter = adapter
                list.setOnItemClickListener { _, _, pos, _ -> onClick[pos]() }
            }
            return
        }

        val pad = dpToPx(16)
        val header = TextView(this).apply {
            text = path
            setPadding(pad, pad, pad, dpToPx(8))
            setTypeface(typeface, Typeface.BOLD)
        }
        val list = ListView(this).apply {
            this.adapter = adapter
            setOnItemClickListener { _, _, pos, _ -> onClick[pos]() }
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(
                list,
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(360)),
            )
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.scp_browser_title)
            .setView(container)
            .setNeutralButton(R.string.scp_upload_here, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        scpBrowserListView = list
        scpBrowserPathHeader = header
        scpBrowserDialog = dialog
        dialog.setOnDismissListener {
            scpBrowserDialog = null
            scpBrowserListView = null
            scpBrowserPathHeader = null
        }
        dialog.show()
        // Override the neutral button after show() so it does NOT auto-dismiss
        // on the navigation taps; for upload it reads the *current* directory
        // (header text changes as the user navigates), then closes and opens
        // the document picker.
        dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
            scpUploadTargetDir = scpBrowserPathHeader?.text?.toString() ?: path
            dialog.dismiss()
            scpUploadPickerLauncher.launch(arrayOf("*/*"))
        }
    }

    /** Join a remote dir and a child name with `/`; `..` is resolved server-side. */
    private fun joinRemotePath(dir: String, name: String): String =
        if (dir.endsWith("/")) "$dir$name" else "$dir/$name"

    /** Offer Download (save to Downloads) or Copy (file contents to clipboard). */
    private fun showScpFileActions(remotePath: String, displayName: String) {
        val items = arrayOf(
            getString(R.string.scp_file_action_download),
            getString(R.string.scp_file_action_copy),
        )
        AlertDialog.Builder(this)
            .setTitle(displayName)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> confirmScpDownload(remotePath, displayName)
                    1 -> startScpCopyToClipboard(remotePath, displayName)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmScpDownload(remotePath: String, displayName: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.scp_download_confirm_title)
            .setMessage(getString(R.string.scp_download_confirm_message, displayName))
            .setPositiveButton(android.R.string.ok) { _, _ -> startScpDownload(remotePath, displayName) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /**
     * Download [remotePath] into memory and put its contents on the clipboard as
     * UTF-8 text. A cancelable progress dialog blocks until the SFTP worker
     * finishes; canceling interrupts the worker.
     */
    private fun startScpCopyToClipboard(remotePath: String, displayName: String) {
        val svc = service
        if (svc == null || svc.state != SshConnectionService.State.CONNECTED) {
            Toast.makeText(this, R.string.image_upload_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        val out = java.io.ByteArrayOutputStream()
        val cancelled = AtomicBoolean(false)
        val dialog = buildScpProgressDialog(R.string.scp_copying)
        val future = svc.downloadFile(remotePath, out) { error ->
            if (cancelled.get()) return@downloadFile
            scpTransferDialog = null
            dialog.dismiss()
            if (error == null) {
                val text = out.toString(Charsets.UTF_8.name())
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText(displayName, text))
                Toast.makeText(
                    this,
                    getString(R.string.scp_copy_done, displayName),
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                Toast.makeText(
                    this,
                    getString(R.string.scp_copy_failed, error.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        dialog.setButton(
            DialogInterface.BUTTON_NEGATIVE,
            getString(android.R.string.cancel),
        ) { _, _ ->
            cancelled.set(true)
            scpTransferDialog = null
            future?.cancel(true)
            Toast.makeText(this, R.string.scp_copy_cancelled, Toast.LENGTH_SHORT).show()
        }
        scpTransferDialog = dialog
        dialog.show()
    }

    /**
     * Download [remotePath] into a new MediaStore Downloads entry named
     * [displayName]. A cancelable progress dialog blocks until the SFTP worker
     * finishes; canceling interrupts the worker and removes the pending entry.
     */
    private fun startScpDownload(remotePath: String, displayName: String) {
        val svc = service
        if (svc == null || svc.state != SshConnectionService.State.CONNECTED) {
            Toast.makeText(this, R.string.image_upload_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        val resolver = contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val targetUri = resolver.insert(collection, values)
        if (targetUri == null) {
            Toast.makeText(this, getString(R.string.scp_download_failed, ""), Toast.LENGTH_LONG).show()
            return
        }
        val out = try {
            resolver.openOutputStream(targetUri)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open download sink")
            null
        }
        if (out == null) {
            resolver.delete(targetUri, null, null)
            Toast.makeText(this, getString(R.string.scp_download_failed, ""), Toast.LENGTH_LONG).show()
            return
        }

        val cancelled = AtomicBoolean(false)
        val dialog = buildScpProgressDialog(R.string.scp_downloading)
        val future = svc.downloadFile(remotePath, out) { error ->
            try { out.close() } catch (_: Exception) {}
            if (cancelled.get()) return@downloadFile
            scpTransferDialog = null
            dialog.dismiss()
            if (error == null) {
                val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                resolver.update(targetUri, done, null, null)
                Toast.makeText(
                    this,
                    getString(R.string.scp_download_done, displayName),
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                resolver.delete(targetUri, null, null)
                Toast.makeText(
                    this,
                    getString(R.string.scp_download_failed, error.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
        dialog.setButton(
            DialogInterface.BUTTON_NEGATIVE,
            getString(android.R.string.cancel),
        ) { _, _ ->
            cancelled.set(true)
            scpTransferDialog = null
            future?.cancel(true)
            try { out.close() } catch (_: Exception) {}
            resolver.delete(targetUri, null, null)
            Toast.makeText(this, R.string.scp_download_cancelled, Toast.LENGTH_SHORT).show()
        }
        scpTransferDialog = dialog
        dialog.show()
    }

    /** Upload the SAF-picked [uri] into [scpUploadTargetDir] captured at pick time. */
    private fun onScpUploadFilePicked(uri: Uri) {
        val remoteDir = scpUploadTargetDir
        scpUploadTargetDir = null
        if (remoteDir == null) return
        val svc = service
        if (svc == null || svc.state != SshConnectionService.State.CONNECTED) {
            Toast.makeText(this, R.string.image_upload_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        val displayName = queryDisplayName(uri) ?: "upload.bin"
        // Runs in the background; progress and result surface through the
        // service notification, so the terminal stays usable during the upload.
        svc.uploadFile(
            { contentResolver.openInputStream(uri) ?: throw IOException("Cannot open picked file") },
            displayName,
            remoteDir,
            queryFileSize(uri),
        ) {}
    }

    private fun queryDisplayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /** Picked-file size in bytes for the upload progress bar, or -1 if unknown. */
    private fun queryFileSize(uri: Uri): Long =
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L

    /** A non-cancelable-by-back, indeterminate progress dialog for an SCP transfer. */
    private fun buildScpProgressDialog(titleRes: Int): AlertDialog {
        val padding = dpToPx(24)
        val progressView = ProgressBar(this).apply { isIndeterminate = true }
        val container = FrameLayout(this).apply {
            setPadding(padding, padding, padding, padding)
            addView(
                progressView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
        return AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setView(container)
            .setCancelable(false)
            .create()
    }

    private fun auxButtonLayoutParams(): LinearLayout.LayoutParams {
        val marginPx = dpToPx(2)
        return LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { setMargins(marginPx, marginPx, marginPx, marginPx) }
    }

    private fun makeAuxButton(label: String, action: () -> Unit): Button = Button(this).apply {
        val minWidthPx = dpToPx(44)
        val minHeightPx = dpToPx(40)
        text = label
        isAllCaps = false
        minWidth = minWidthPx
        minimumWidth = minWidthPx
        minHeight = minHeightPx
        minimumHeight = minHeightPx
        setPadding(dpToPx(8), 0, dpToPx(8), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        isFocusable = false
        setOnClickListener {
            action()
            binding.imeProxy.requestFocus()
        }
    }

    /**
     * Read the tmux prefix letter saved by [MainActivity]. Falls back to `b`
     * on any unexpected value so the FAB shortcuts and swipe gesture stay
     * usable.
     */
    private fun readTmuxPrefixLetter(): Char {
        return getSharedPreferences(PREFS_CONNECTION, Context.MODE_PRIVATE)
            .getString(KEY_TMUX_PREFIX, DEFAULT_TMUX_PREFIX_LETTER)
            ?.trim()?.lowercase()
            ?.firstOrNull()
            ?.takeIf { it in 'a'..'z' }
            ?: DEFAULT_TMUX_PREFIX_LETTER[0]
    }

    /** Convert the configured prefix letter to its control byte (`a` → 0x01). */
    private fun readTmuxPrefixByte(): Byte {
        return (readTmuxPrefixLetter().code - 'a'.code + 1).toByte()
    }

    private fun styleModifierButton(button: Button) {
        button.background = ContextCompat.getDrawable(this, R.drawable.bg_aux_modifier)
        ContextCompat.getColorStateList(this, R.color.aux_modifier_text)?.let {
            button.setTextColor(it)
        }
    }

    private fun setCtrlSticky(on: Boolean) {
        stickyCtrl = on
        ctrlButton?.isActivated = on
    }

    private fun clearStickyModifiers() {
        if (stickyCtrl) setCtrlSticky(false)
    }

    /**
     * Enter termux's text selection mode (the Copy/Paste/More floating
     * toolbar) anchored at the centre of the terminal view. The user can
     * drag the selection handles from there. We trigger this from an aux
     * bar button instead of long-press because the GestureDetector's
     * long-press timer fires too easily during slow swipe starts.
     */
    private fun startTextSelection() {
        val terminalView = binding.terminalView
        val emulator = terminalView.mEmulator ?: return
        if (emulator.mColumns <= 0 || emulator.mRows <= 0) return
        if (terminalView.width <= 0 || terminalView.height <= 0) return
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            now, now,
            MotionEvent.ACTION_DOWN,
            terminalView.width / 2f,
            terminalView.height / 2f,
            0,
        )
        // termux's startTextSelectionMode aborts silently if requestFocus()
        // fails, which it always does here because TerminalView is set
        // non-focusable so the IME proxy view can own input focus. Flip
        // focusability on for the duration of the call. The selection
        // cursors are PopupWindows that handle their own touches once
        // shown, so restoring non-focusable afterwards is safe — the
        // aux button's post-click handler then hands focus back to the
        // IME proxy as usual.
        terminalView.isFocusable = true
        terminalView.isFocusableInTouchMode = true
        try {
            terminalView.startTextSelectionMode(event)
        } finally {
            terminalView.isFocusable = false
            terminalView.isFocusableInTouchMode = false
            event.recycle()
        }
    }

    /**
     * Translate a hardware (or IME-synthesised) key event into bytes on the
     * SSH stdin, honouring sticky modifiers and the emulator's current
     * cursor/keypad application modes. Returns true when the event was
     * consumed.
     *
     * Lifted from the original `viewClient.onKeyDown` body so it can be
     * driven by the IME proxy view as well as by Termux's terminal view.
     */
    private fun processHardwareKey(keyCode: Int, event: KeyEvent): Boolean {
        val emu = binding.terminalView.mEmulator ?: return false

        // Multi-character input (e.g., IME batch)
        if (event.action == KeyEvent.ACTION_MULTIPLE && keyCode == KeyEvent.KEYCODE_UNKNOWN) {
            event.characters?.let { writeToSsh(it.toByteArray(Charsets.UTF_8)) }
            clearStickyModifiers()
            return true
        }

        // Let system keys through (except back → escape)
        if (event.isSystem && keyCode != KeyEvent.KEYCODE_BACK) {
            return false
        }

        val metaState = event.metaState
        val controlDown = event.isCtrlPressed || stickyCtrl
        val leftAltDown = (metaState and KeyEvent.META_ALT_LEFT_ON) != 0

        var keyMod = 0
        if (controlDown) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        if (event.isAltPressed || leftAltDown) keyMod = keyMod or KeyHandler.KEYMOD_ALT
        if (event.isShiftPressed) keyMod = keyMod or KeyHandler.KEYMOD_SHIFT
        if (event.isNumLockOn) keyMod = keyMod or KeyHandler.KEYMOD_NUM_LOCK

        if (!event.isFunctionPressed) {
            val code = KeyHandler.getCode(
                keyCode, keyMod,
                emu.isCursorKeysApplicationMode,
                emu.isKeypadApplicationMode
            )
            if (code != null) {
                writeToSsh(code.toByteArray(Charsets.UTF_8))
                clearStickyModifiers()
                return true
            }
        }

        val rightAltDown = (metaState and KeyEvent.META_ALT_RIGHT_ON) != 0
        var bitsToClear = KeyEvent.META_CTRL_MASK
        if (!rightAltDown) {
            bitsToClear = bitsToClear or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        val effectiveMetaState = metaState and bitsToClear.inv()

        val result = event.getUnicodeChar(effectiveMetaState)
        if (result == 0) return false

        // Skip combining accents for now
        if ((result and KeyCharacterMap.COMBINING_ACCENT) != 0) {
            clearStickyModifiers()
            return true
        }

        var codePoint = fixBluetoothCodePoint(result)
        if (controlDown) codePoint = applyCtrl(codePoint)

        writeCodePointToSsh(codePoint, leftAltDown)
        clearStickyModifiers()
        return true
    }

    private fun sendKeyCode(keyCode: Int, extraMod: Int = 0) {
        val emu = binding.terminalView.mEmulator
        val cursorApp = emu?.isCursorKeysApplicationMode == true
        val keypadApp = emu?.isKeypadApplicationMode == true
        var keyMod = extraMod
        if (stickyCtrl) keyMod = keyMod or KeyHandler.KEYMOD_CTRL
        val code = KeyHandler.getCode(keyCode, keyMod, cursorApp, keypadApp) ?: return
        writeToSsh(code.toByteArray(Charsets.UTF_8))
        clearStickyModifiers()
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun changeFontSize(increase: Boolean) {
        val newSize = (fontSizePx + if (increase) FONT_SIZE_STEP_PX else -FONT_SIZE_STEP_PX)
            .coerceIn(MIN_FONT_SIZE_PX, MAX_FONT_SIZE_PX)
        if (newSize == fontSizePx) return
        fontSizePx = newSize
        binding.terminalView.setTextSize(newSize)
        terminalPrefs.edit { putInt(KEY_FONT_SIZE_PX, newSize) }
        service?.let { syncWindowSize(it) }
    }

    private fun setupTerminalView() {
        val terminalView = binding.terminalView
        fontSizePx = terminalPrefs.getInt(KEY_FONT_SIZE_PX, DEFAULT_FONT_SIZE_PX)
            .coerceIn(MIN_FONT_SIZE_PX, MAX_FONT_SIZE_PX)
        terminalView.setTextSize(fontSizePx)
        terminalView.setTypeface(Typeface.MONOSPACE)

        // Create a dummy TerminalSession to initialize TerminalView.
        // "sleep 86400" keeps the process alive without producing output.
        // All actual I/O goes through SSH, not this dummy process.
        // Termux's TerminalSession uses `args` as the full argv (args[0] becomes
        // argv[0]). Android's /system/bin/sleep is a toybox multicall binary that
        // dispatches on argv[0], so we must pass "sleep" as argv[0].
        val dummySession = TerminalSession(
            "/system/bin/sleep", "/",
            arrayOf("sleep", "86400"),
            arrayOf("TERM=xterm-256color"),
            null,
            sessionClient
        )
        terminalView.attachSession(dummySession)
        terminalView.setTerminalViewClient(viewClient)

        // IME focus lives on the proxy view (see ImeProxyView), not on the
        // terminal itself. The terminal still receives touches because the
        // proxy sits below it in the FrameLayout and is non-clickable.
        terminalView.isFocusable = false
        terminalView.isFocusableInTouchMode = false
        wireImeProxy()
        binding.imeProxy.requestFocus()
        binding.imeProxy.post { showSoftKeyboard() }

        // The activity uses windowSoftInputMode="adjustResize", so showing or
        // hiding the soft keyboard shrinks/grows the TerminalView, which in
        // turn updates the emulator's mRows / mColumns. Push the new size to
        // the SSH peer (SIGWINCH) so tmux/vim/less reflow to the visible area
        // instead of leaving rows hidden under the IME.
        terminalView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            service?.let { syncWindowSize(it) }
        }
    }

    private fun wireImeProxy() {
        val imeProxy = binding.imeProxy
        imeProxy.onComposingTextChanged = { text -> updatePreedit(text) }
        imeProxy.onCommitText = { text ->
            // Composition was committed — flush the bytes through the same
            // path used by hardware character input. When sticky Ctrl is
            // armed, apply it to the first code point only and pass the
            // remainder verbatim: soft IMEs sometimes batch consecutive
            // taps into a single commit (e.g. typing "op" arrives as one
            // 2-char commitText), and the user expects "Ctrl" + "op" to
            // send Ctrl-o followed by a plain "p".
            val str = text.toString()
            if (str.isNotEmpty() && stickyCtrl) {
                val firstCp = str.codePointAt(0)
                val cp = applyCtrl(fixBluetoothCodePoint(firstCp))
                writeCodePointToSsh(cp, false)
                val firstLen = Character.charCount(firstCp)
                if (str.length > firstLen) {
                    writeToSsh(str.substring(firstLen).toByteArray(Charsets.UTF_8))
                }
            } else {
                writeToSsh(str.toByteArray(Charsets.UTF_8))
            }
            clearStickyModifiers()
        }
        imeProxy.onHardwareKey = { keyCode, event -> processHardwareKey(keyCode, event) }
        imeProxy.onImageContent = ::onImagePicked
    }

    private fun updatePreedit(text: CharSequence) {
        val overlay = binding.preeditOverlay
        if (text.isEmpty()) {
            overlay.clear()
        } else {
            overlay.setComposing(text)
        }
    }

    /**
     * Route vertical swipes into bytes on the SSH stdin so scrolling works
     * inside tmux / vim / less. Without this the finger drag still feeds
     * `TerminalView.doScroll`, but every byte it emits is written to our dummy
     * "sleep 86400" [TerminalSession] and never reaches the remote shell — and
     * because that session's pty has echo on by default, bytes it writes come
     * straight back out as visible text in the screen buffer.
     *
     * Three cases, mirroring Termux's own `doScroll` dispatch:
     *
     * 1. Mouse tracking active (e.g. tmux `set -g mouse on`): emit an xterm
     *    classic mouse-wheel sequence (`\e[M<b+32><x+32><y+32>`) per
     *    [SCROLL_LINES_PER_WHEEL] rows of drag. We use the classic format
     *    instead of SGR because plain `set -g mouse on` only advertises
     *    DECSET 1000/1002 — SGR (1006) is opt-in, and if tmux is not in SGR
     *    mode it treats the `\e[<...M` bytes as literal text.
     * 2. Alt buffer active but mouse tracking off (tmux with mouse off, vim,
     *    less): emit `DPAD_UP` / `DPAD_DOWN` key codes — what Termux does
     *    natively for the same case.
     * 3. Otherwise: do nothing and let `TerminalView`'s native `mTopRow`
     *    scrollback path handle the gesture.
     *
     * Once we've claimed a vertical drag (case 1 or 2) the `OnTouchListener`
     * swallows the remaining events so `TerminalView` does not also run
     * `doScroll` into the dummy pty (which would echo the emulator-formatted
     * mouse bytes back to the screen).
     *
     * Taps are treated the same way while mouse tracking is active:
     * `TerminalView.onUp` would emit `MOUSE_LEFT_BUTTON` press/release via
     * `emulator.sendMouseEvent`, again echoing through the dummy pty as
     * `^[[<0;x;yM^[[<0;x;ym…`. We swallow the tap-up, then do
     * `requestFocus()` + `toggleSoftKeyboard()` ourselves so the user's
     * existing tap-to-toggle-keyboard behavior is preserved.
     *
     * For pinches and for taps in plain-shell mode we leave events untouched.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupTerminalScrollRouting() {
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scrollRemainderPx = 0f
                handlingScrollGesture = false
                tappedThisGesture = false
                gestureAxis = GestureAxis.UNDETERMINED
                pendingSwipeDirection = 0
                return false
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                tappedThisGesture = true
                return false
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                // Ignore pinch gestures — font-size zoom is driven by onScale.
                if (e2.pointerCount > 1) return false
                if (e1 == null) return false

                // Lock the primary axis once the drag clears the slop. Using
                // the cumulative delta (e2 - e1) instead of this frame's
                // distance lets the lock weather a brief jitter at gesture
                // start without flipping axis.
                val totalDx = e2.x - e1.x
                val totalDy = e2.y - e1.y
                val absDx = abs(totalDx)
                val absDy = abs(totalDy)
                if (gestureAxis == GestureAxis.UNDETERMINED) {
                    if (max(absDx, absDy) < dpToPx(GESTURE_AXIS_LOCK_SLOP_DP)) return false
                    // Horizontal lock requires at least one direction with a
                    // non-empty payload. If the user has cleared both, swipes
                    // fall through to TerminalView like any plain horizontal
                    // drag would.
                    val swipeBound = resolved.swipeLeft != null || resolved.swipeRight != null
                    gestureAxis = if (swipeBound && absDx > absDy * SWIPE_HORIZONTAL_RATIO) {
                        GestureAxis.HORIZONTAL
                    } else {
                        GestureAxis.VERTICAL
                    }
                }

                if (gestureAxis == GestureAxis.HORIZONTAL) {
                    // We own the gesture for the rest of the drag — even if
                    // the user retreats below the commit threshold — so
                    // TerminalView never sees the motion. Otherwise its native
                    // text-selection long-press could fire from a slow swipe.
                    handlingScrollGesture = true
                    val rawDirection = when {
                        absDx >= dpToPx(SWIPE_MIN_DISTANCE_DP) -> if (totalDx > 0) +1 else -1
                        else -> 0
                    }
                    // Treat "direction with empty payload" as "no commit" so
                    // the overlay only appears when releasing here would
                    // actually do something.
                    val direction = when (rawDirection) {
                        +1 -> if (resolved.swipeRight != null) +1 else 0
                        -1 -> if (resolved.swipeLeft != null) -1 else 0
                        else -> 0
                    }
                    if (direction != pendingSwipeDirection) {
                        pendingSwipeDirection = direction
                        if (direction == 0) hideSwipeFeedback() else showSwipeFeedback(direction)
                    }
                    return true
                }

                // VERTICAL axis: route the drag to the wheel/dpad scroll path.
                val emu = binding.terminalView.mEmulator ?: return false
                val rows = emu.mRows
                if (rows <= 0) return false
                // Leave the plain-shell scrollback path to `TerminalView`.
                if (!emu.isMouseTrackingActive && !emu.isAlternateBufferActive) return false

                val lineHeight = binding.terminalView.height.toFloat() / rows
                if (lineHeight <= 0f) return false
                val step = lineHeight * SCROLL_LINES_PER_WHEEL
                // From this point on we own the gesture even if we end up
                // emitting zero bytes this frame — otherwise TerminalView would
                // pick up the leftover motion and scroll the dummy pty.
                handlingScrollGesture = true
                val total = distanceY + scrollRemainderPx
                val deltaWheels = (total / step).toInt()
                scrollRemainderPx = total - deltaWheels * step
                if (deltaWheels == 0) return true

                val up = deltaWheels < 0
                val repeats = abs(deltaWheels)
                when {
                    emu.isMouseTrackingActive -> {
                        val cols = emu.mColumns
                        val charWidth = if (cols > 0) binding.terminalView.width.toFloat() / cols else 0f
                        val col = (if (charWidth > 0f) (e2.x / charWidth).toInt() + 1 else 1)
                            .coerceIn(1, MOUSE_CLASSIC_COORD_MAX.coerceAtMost(cols.coerceAtLeast(1)))
                        val row = ((e2.y / lineHeight).toInt() + 1)
                            .coerceIn(1, MOUSE_CLASSIC_COORD_MAX.coerceAtMost(rows))
                        val button = if (up) WHEEL_UP_BUTTON else WHEEL_DOWN_BUTTON
                        val bytes = byteArrayOf(
                            0x1B, '['.code.toByte(), 'M'.code.toByte(),
                            (32 + button).toByte(),
                            (32 + col).toByte(),
                            (32 + row).toByte(),
                        )
                        repeat(repeats) { writeToSsh(bytes) }
                    }
                    emu.isAlternateBufferActive -> {
                        val keyCode = if (up) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN
                        val code = KeyHandler.getCode(
                            keyCode, 0,
                            emu.isCursorKeysApplicationMode,
                            emu.isKeypadApplicationMode,
                        )
                        if (code != null) {
                            val bytes = code.toByteArray(Charsets.UTF_8)
                            repeat(repeats) { writeToSsh(bytes) }
                        }
                    }
                }
                return true
            }
        })
        binding.terminalView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            val inMouseTracking = binding.terminalView.mEmulator?.isMouseTrackingActive == true
            val tapInMouseTracking = tappedThisGesture && inMouseTracking
            var consume = handlingScrollGesture || tapInMouseTracking
            when (event.action) {
                MotionEvent.ACTION_UP -> {
                    // Tapped a URL? Open it (with confirmation) instead of
                    // forwarding the tap to the mouse-tracking sequence or
                    // toggling the keyboard. Has to run before the CANCEL
                    // replay below so we can still read the buffer at the
                    // tap coords.
                    val openedLink = tappedThisGesture && tryOpenLinkAt(event)
                    if (openedLink) consume = true
                    if (consume) {
                        // Replay the UP to TerminalView as a CANCEL so its
                        // gesture detector resets (clears the long-press timer)
                        // instead of being left hanging with a half-seen
                        // gesture. CANCEL also bypasses the onUp path that
                        // would otherwise emit MOUSE_LEFT_BUTTON press/release.
                        val cancel = MotionEvent.obtain(event).apply {
                            action = MotionEvent.ACTION_CANCEL
                        }
                        binding.terminalView.onTouchEvent(cancel)
                        cancel.recycle()
                    }
                    if (pendingSwipeDirection != 0) commitPendingSwipe()
                    if (tapInMouseTracking && !openedLink) {
                        binding.imeProxy.requestFocus()
                        toggleSoftKeyboard()
                    }
                    hideSwipeFeedback()
                    handlingScrollGesture = false
                    tappedThisGesture = false
                    gestureAxis = GestureAxis.UNDETERMINED
                    pendingSwipeDirection = 0
                }
                MotionEvent.ACTION_CANCEL -> {
                    hideSwipeFeedback()
                    handlingScrollGesture = false
                    tappedThisGesture = false
                    gestureAxis = GestureAxis.UNDETERMINED
                    pendingSwipeDirection = 0
                }
            }
            consume
        }
    }

    /**
     * Run the configured payload for the currently pending swipe direction.
     * Caller is responsible for clearing [pendingSwipeDirection] and the
     * overlay afterwards.
     */
    private fun commitPendingSwipe() {
        val shortcut = activeSwipeShortcut(pendingSwipeDirection) ?: return
        runShortcutActions(parseShortcutActions(shortcut.payload))
    }

    private fun showSwipeFeedback(direction: Int) {
        val shortcut = activeSwipeShortcut(direction) ?: return
        showPayloadFeedback(shortcut)
    }

    /** Show [shortcut]'s label and resolved payload in the center overlay. */
    private fun showPayloadFeedback(shortcut: Shortcut) {
        val preview = previewSwipePayload(shortcut.payload)
        binding.swipeFeedback.text = getString(R.string.swipe_feedback, shortcut.label, preview)
        binding.swipeFeedback.visibility = View.VISIBLE
    }

    private fun activeSwipeShortcut(direction: Int): Shortcut? = when {
        direction > 0 -> resolved.swipeRight
        direction < 0 -> resolved.swipeLeft
        else -> null
    }

    /**
     * Render a payload for the mid-gesture overlay. Substitutes the dynamic
     * `{TMUX-PREFIX}` token with the user's configured prefix so the user can
     * see the literal control sequence that will fire (`^B n` rather than
     * `{TMUX-PREFIX}n`); other tokens stay verbatim.
     */
    private fun previewSwipePayload(payload: String): String {
        val prefix = readTmuxPrefixLetter().uppercaseChar()
        return payload
            .replace("{TMUX-PREFIX}", "^$prefix")
            .replace("{TMUX_PREFIX}", "^$prefix")
    }

    private fun hideSwipeFeedback() {
        binding.swipeFeedback.visibility = View.GONE
    }

    private fun showSoftKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(binding.imeProxy, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun toggleSoftKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0)
    }

    private fun writeToSsh(data: ByteArray) {
        service?.writeToSsh(data)
        if (secureInputActive) {
            // Bytes during secure input are deliberately kept out of the
            // bigram tracker so passwords never feed the suggestion row.
            // CR/LF means the user submitted the password — auto-release
            // secure mode after a single submission, matching the spec that
            // a password entry is one-shot.
            if (data.any { it == 0x0D.toByte() || it == 0x0A.toByte() }) {
                setSecureInput(false)
            }
        } else {
            bigramTracker.ingestSend(data)
        }
    }

    private fun copySelectedTextToClipboard(text: String?) {
        if (text.isNullOrEmpty()) return
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("pocketssh", text))
    }

    /**
     * Map a tap on the terminal to a URL, if one sits at or near the tap.
     * Returns true (and shows the confirm dialog) when a URL is found, in
     * which case the caller should suppress the default tap behaviour.
     *
     * The whole wrapped line is scanned (not just the tapped word), so a tap
     * landing on prose next to a link still opens it, and the tapped row plus
     * the two rows above and below are checked so a slightly-off tap counts.
     *
     * Bounded to the visible screen rows so that scrollback (`mTopRow < 0`)
     * is not handled — the wrapped-line walk only follows line-wrap
     * continuations in the screen range, not the transcript.
     */
    private fun tryOpenLinkAt(event: MotionEvent): Boolean {
        val terminalView = binding.terminalView
        val emulator = terminalView.mEmulator ?: return false
        val coords = terminalView.getColumnAndRow(event, true)
        val column = coords[0]
        val row = coords[1]
        if (column < 0 || column >= emulator.mColumns) return false
        for (candidate in intArrayOf(row, row - 1, row + 1, row - 2, row + 2)) {
            if (candidate < 0 || candidate >= emulator.mRows) continue
            val url = findUrlNearTap(emulator, column, candidate) ?: continue
            showOpenLinkConfirmDialog(url)
            return true
        }
        return false
    }

    /**
     * Scan the whole wrapped line that `row` belongs to and return the URL
     * nearest the tapped column, or null if the line holds none.
     *
     * The wrap walk and offset arithmetic mirror
     * `TerminalBuffer.getWordAtLocation`: rows before the last one in a wrapped
     * line are full-width, so the flat text offset of the tap is
     * `(row - y1) * columns + column`.
     */
    private fun findUrlNearTap(emulator: TerminalEmulator, column: Int, row: Int): String? {
        val screen = emulator.screen
        val cols = emulator.mColumns
        val rows = emulator.mRows
        var y1 = row
        var y2 = row
        while (y1 > 0 && !screen.getSelectedText(0, y1 - 1, cols, row, true, true).contains('\n')) y1--
        while (y2 < rows && !screen.getSelectedText(0, row, cols, y2 + 1, true, true).contains('\n')) y2++
        val text = screen.getSelectedText(0, y1, cols, y2, true, true)
        val matches = LinkDetector.extractUrlMatches(text)
        if (matches.isEmpty()) return null
        val tapOffset = (row - y1) * cols + column
        return matches.minByOrNull { match ->
            when {
                tapOffset < match.start -> match.start - tapOffset
                tapOffset >= match.endExclusive -> tapOffset - match.endExclusive + 1
                else -> 0
            }
        }?.url
    }

    private fun showOpenLinkConfirmDialog(url: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.open_link_title)
            .setMessage(url)
            .setPositiveButton(R.string.open_link_open) { _, _ -> launchExternalUrl(url) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun launchExternalUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            startActivity(intent)
        } catch (_: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.open_link_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    // Bytes go to SSH stdin, not the dummy TerminalSession, so we can't
    // delegate to TerminalEmulator.paste() (it writes via mSession.write).
    // Sanitize and bracket-wrap the same way it does.
    private fun pasteClipboardToSsh() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return
        if (clip.itemCount == 0) return
        val raw = clip.getItemAt(0).coerceToText(this).toString()
        if (raw.isEmpty()) return

        val sanitized = raw
            .replace(Regex("[\u001B\u0080-\u009F]"), "")
            .replace(Regex("\r?\n"), "\r")
        if (sanitized.isEmpty()) return

        val bracketed = isBracketedPasteActive()
        if (bracketed) writeToSsh(BRACKETED_PASTE_START)
        writeToSsh(sanitized.toByteArray(Charsets.UTF_8))
        if (bracketed) writeToSsh(BRACKETED_PASTE_END)
    }

    // TerminalEmulator has no public getter for DECSET 2004; reach in via
    // reflection. Submodule is pinned, so the private name is stable.
    private fun isBracketedPasteActive(): Boolean {
        val emulator = binding.terminalView.mEmulator ?: return false
        return try {
            val method = TerminalEmulator::class.java
                .getDeclaredMethod("isDecsetInternalBitSet", Int::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(emulator, DECSET_BIT_BRACKETED_PASTE_MODE) as? Boolean == true
        } catch (_: ReflectiveOperationException) {
            false
        }
    }

    private fun writeCodePointToSsh(codePoint: Int, prependEscape: Boolean) {
        val buf = ByteArray(5)
        var pos = 0
        if (prependEscape) buf[pos++] = 27
        when {
            codePoint <= 0x7F -> buf[pos++] = codePoint.toByte()
            codePoint <= 0x7FF -> {
                buf[pos++] = (0xC0 or (codePoint shr 6)).toByte()
                buf[pos++] = (0x80 or (codePoint and 0x3F)).toByte()
            }
            codePoint <= 0xFFFF -> {
                buf[pos++] = (0xE0 or (codePoint shr 12)).toByte()
                buf[pos++] = (0x80 or ((codePoint shr 6) and 0x3F)).toByte()
                buf[pos++] = (0x80 or (codePoint and 0x3F)).toByte()
            }
            else -> {
                buf[pos++] = (0xF0 or (codePoint shr 18)).toByte()
                buf[pos++] = (0x80 or ((codePoint shr 12) and 0x3F)).toByte()
                buf[pos++] = (0x80 or ((codePoint shr 6) and 0x3F)).toByte()
                buf[pos++] = (0x80 or (codePoint and 0x3F)).toByte()
            }
        }
        writeToSsh(buf.copyOf(pos))
    }

    /** Apply Ctrl key mapping to a code point (a→1, b→2, etc.) */
    private fun applyCtrl(codePoint: Int): Int = when {
        codePoint in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
        codePoint in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
        codePoint == ' '.code || codePoint == '2'.code -> 0
        codePoint == '['.code || codePoint == '3'.code -> 27
        codePoint == '\\'.code || codePoint == '4'.code -> 28
        codePoint == ']'.code || codePoint == '5'.code -> 29
        codePoint == '^'.code || codePoint == '6'.code -> 30
        codePoint == '_'.code || codePoint == '7'.code || codePoint == '/'.code -> 31
        codePoint == '8'.code -> 127
        else -> codePoint
    }

    /** Fix Bluetooth keyboard Unicode quirks. */
    private fun fixBluetoothCodePoint(codePoint: Int): Int = when (codePoint) {
        0x02DC -> 0x007E // SMALL TILDE → TILDE
        0x02CB -> 0x0060 // MODIFIER GRAVE → GRAVE
        0x02C6 -> 0x005E // MODIFIER CIRCUMFLEX → CIRCUMFLEX
        else -> codePoint
    }

    override fun onDestroy() {
        scpBrowserDialog?.dismiss()
        scpBrowserDialog = null
        scpTransferDialog?.dismiss()
        scpTransferDialog = null
        pendingTitleHandler.removeCallbacks(pendingTitleRunnable)
        if (bound) {
            service?.detachOutputListener()
            service?.removeStatusListener(statusListener)
            unbindService(serviceConnection)
            bound = false
            service = null
        }
        biometricExecutor.shutdownNow()
        binding.terminalView.mTermSession?.finishIfRunning()
        super.onDestroy()
    }

    // --- TerminalSessionClient ---
    private val sessionClient = object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {
            binding.terminalView.invalidate()
        }
        override fun onTitleChanged(changedSession: TerminalSession) {
            val title = changedSession.title
            // Cache on the service so a subsequent activity instance can pick
            // up the active app context without waiting for tmux to re-emit
            // the title OSC (it only does so on changes). Cache eagerly so
            // the deferred runnable always sees the latest title.
            service?.lastTitle = title
            if (!pendingTitleUpdate) {
                pendingTitleUpdate = true
                pendingTitleHandler.post(pendingTitleRunnable)
            }
        }
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
            copySelectedTextToClipboard(text)
        }
        override fun onPasteTextFromClipboard(session: TerminalSession?) {
            pasteClipboardToSsh()
        }
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK
        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag ?: TAG, message, e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(tag ?: TAG, "Stack trace", e)
        }
    }

    // --- TerminalViewClient ---
    // Key input is intercepted here and sent to SSH instead of the dummy TerminalSession.
    private val viewClient = object : TerminalViewClient {
        // Return value becomes TerminalView.mScaleFactor: return 1.0f to reset
        // the accumulator after stepping, or the current scale to keep
        // accumulating until the next threshold.
        override fun onScale(scale: Float): Float {
            if (scale < 0.9f || scale > 1.1f) {
                changeFontSize(scale > 1.0f)
                return 1.0f
            }
            return scale
        }
        override fun onSingleTapUp(e: MotionEvent?) {
            // Tapping the terminal toggles the soft keyboard so it can be
            // re-summoned after the user dismisses it with the back gesture.
            // Focus stays on the IME proxy view (it's the IME target).
            binding.imeProxy.requestFocus()
            toggleSoftKeyboard()
        }
        override fun shouldBackButtonBeMappedToEscape(): Boolean = true
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        // Route TerminalView.onCreateInputConnection() into its
        // TYPE_CLASS_TEXT | TYPE_TEXT_VARIATION_NORMAL branch so the IME can
        // run composition (Japanese kana-to-kanji) and show word suggestions.
        override fun isTerminalViewSelected(): Boolean = false
        override fun copyModeChanged(copyMode: Boolean) {}
        override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean = false
        // Returning true suppresses termux's default
        // startTextSelectionMode() so the Copy/Paste/More floating toolbar
        // never appears from a misfired long-press timer (a 500 ms hold
        // with no movement, which is easy to hit at the start of a slow
        // swipe). Selection is started explicitly from the "Select" button on
        // the right end of the context row via startTextSelection().
        override fun onLongPress(event: MotionEvent?): Boolean = true
        // Sticky modifiers are consumed when read by the soft keyboard text path
        // (TerminalView.sendTextToTerminal / inputCodePoint). Hardware key events
        // go through our onKeyDown below, which clears sticky state explicitly.
        override fun readControlKey(): Boolean {
            val v = stickyCtrl
            if (v) setCtrlSticky(false)
            return v
        }
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onEmulatorSet() {}

        override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
            // The terminal view is not focusable in this activity (the IME
            // proxy view owns focus), so this callback is normally inert. It
            // stays wired up as a defensive forward in case some Termux code
            // path delivers a key event directly to the terminal view.
            val event = e ?: return false
            return processHardwareKey(keyCode, event)
        }

        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
            // All key input is handled in onKeyDown. This is a fallback for any edge cases.
            var cp = fixBluetoothCodePoint(codePoint)
            if (ctrlDown) cp = applyCtrl(cp)
            writeCodePointToSsh(cp, false)
            return true
        }

        override fun logError(tag: String?, message: String?) { Log.e(tag ?: TAG, message ?: "") }
        override fun logWarn(tag: String?, message: String?) { Log.w(tag ?: TAG, message ?: "") }
        override fun logInfo(tag: String?, message: String?) { Log.i(tag ?: TAG, message ?: "") }
        override fun logDebug(tag: String?, message: String?) { Log.d(tag ?: TAG, message ?: "") }
        override fun logVerbose(tag: String?, message: String?) { Log.v(tag ?: TAG, message ?: "") }
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) {
            Log.e(tag ?: TAG, message, e)
        }
        override fun logStackTrace(tag: String?, e: Exception?) {
            Log.e(tag ?: TAG, "Stack trace", e)
        }
    }

    /** Locked direction of an in-flight terminal drag. See [gestureAxis]. */
    private enum class GestureAxis { UNDETERMINED, HORIZONTAL, VERTICAL }

    companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_USE_TMUX = "use_tmux"
        // Deeplink (pss://open?window=...) target. When present, switch to
        // the named tmux window once the SSH session is connected.
        const val EXTRA_TMUX_WINDOW = "tmux_window"
        private const val DEFAULT_COLUMNS = 80
        private const val DEFAULT_ROWS = 24
        private const val DEFAULT_FONT_SIZE_PX = 16
        private const val MIN_FONT_SIZE_PX = 8
        private const val MAX_FONT_SIZE_PX = 80
        private const val FONT_SIZE_STEP_PX = 2
        // Terminal-prefs schema is also consumed by SettingsBackup, so these
        // are exposed module-internal rather than activity-private.
        internal const val PREFS_TERMINAL = "terminal"
        internal const val KEY_FONT_SIZE_PX = "font_size_px"
        // Mirrors MainActivity.PREFS_NAME / KEY_TMUX_PREFIX so we can read the
        // user's prefix without routing it through ConnectionParams.
        private const val PREFS_CONNECTION = "connection"
        private const val KEY_TMUX_PREFIX = "tmux_prefix"
        private const val DEFAULT_TMUX_PREFIX_LETTER = "b"
        private const val TAG = "TerminalActivity"
        private const val FAB_COLLAPSED_ALPHA = 0.3f
        private const val FAB_MAX_COLUMNS = 3
        private const val CTRL_INPUT_COLUMNS = 4
        private val CTRL_INPUT_PRESETS =
            listOf("^C", "^D", "^L", "^R", "^Z", "^A", "^E", "^U", "^K", "^W", "^[")
        private const val PROBE_TIMEOUT_MS = 4_000L

        // Rolling window of SSH output kept around for password-prompt
        // detection. A few hundred bytes is plenty — every prompt we look
        // for fits in well under 100 ASCII chars even with surrounding ANSI
        // escapes.
        private const val OUTPUT_TAIL_KEEP_BYTES = 256

        // Strips ANSI CSI sequences (`ESC [ ... <final>`) so prompt-tail
        // matching sees the visible characters only.
        private val ANSI_ESCAPE_REGEX = Regex("\\u001B\\[[0-9;?]*[a-zA-Z]")

        // Matches common shell/sudo password prompts at the end of the
        // visible output. Case-insensitive; covers bare `Password:` /
        // `password:` (ssh, su) and `[sudo] password for <user>:`.
        private val PASSWORD_PROMPT_REGEX =
            Regex("(?i)(?:\\[sudo\\] )?password( for [^:]+)?: ?$")

        // Mirrors termux's private DECSET_BIT_BRACKETED_PASTE_MODE (DECSET 2004).
        private const val DECSET_BIT_BRACKETED_PASTE_MODE = 1 shl 10
        private val BRACKETED_PASTE_START = byteArrayOf(0x1B, '['.code.toByte(), '2'.code.toByte(), '0'.code.toByte(), '0'.code.toByte(), '~'.code.toByte())
        private val BRACKETED_PASTE_END = byteArrayOf(0x1B, '['.code.toByte(), '2'.code.toByte(), '0'.code.toByte(), '1'.code.toByte(), '~'.code.toByte())

        // xterm mouse wheel button codes.
        private const val WHEEL_UP_BUTTON = 64
        private const val WHEEL_DOWN_BUTTON = 65
        // Classic (non-SGR) xterm mouse coords max out at (255 - 32) = 223.
        private const val MOUSE_CLASSIC_COORD_MAX = 223
        // Emit one wheel event per this many rows of finger travel. tmux's
        // default response to a wheel event is three lines, so stepping every
        // two rows works out to a comfortable ~1.5× amplification.
        private const val SCROLL_LINES_PER_WHEEL = 2f

        // Horizontal-swipe thresholds for the tmux window-switch gesture.
        // Distance is the commit threshold: once cumulative |dx| crosses it
        // the mid-gesture overlay appears, and releasing here triggers the
        // window switch. Ratio rejects steep diagonals so they stay vertical
        // scrolls. Slop is the cumulative motion needed before the gesture
        // commits to an axis — sized larger than the system touch slop so a
        // tap-with-jitter doesn't latch onto either axis.
        private const val SWIPE_MIN_DISTANCE_DP = 80
        private const val SWIPE_HORIZONTAL_RATIO = 1.5f
        private const val GESTURE_AXIS_LOCK_SLOP_DP = 16

        // Picked images are uploaded under /tmp on the remote host so they
        // are wiped automatically on reboot — no explicit cleanup is needed.
        private const val REMOTE_TMP_DIR = "/tmp"

        // Number of learned candidates to render in the suggestions row. Sized
        // a touch under the always row's eight buttons so the learned row
        // visually reads as "extra" rather than competing for the same space.
        private const val LEARNED_SUGGESTION_LIMIT = 6
    }
}
