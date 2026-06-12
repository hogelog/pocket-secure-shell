package org.hogel.pocketssh.settings

import android.content.Context
import androidx.core.content.edit
import org.hogel.pocketssh.MainActivity
import org.hogel.pocketssh.learning.BigramStore
import org.hogel.pocketssh.shortcuts.ShortcutStore
import org.hogel.pocketssh.ui.TerminalActivity
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializes user-configurable settings to a single JSON document so users can
 * transfer them between devices. The bundle covers connection-target defaults
 * (host/port/username, tmux toggle and prefix), the terminal font size, and
 * the entire shortcut store (context groups, including their swipe and FAB
 * payloads).
 *
 * The learned bigram counts that drive the dynamic suggestion row are
 * effectively a partial history of typed shell commands, so they are excluded
 * from exports by default. Callers opt in by passing `includeLearning = true`
 * to [export]; [import] always accepts payloads with or without the field.
 *
 * Excluded:
 *  - SSH private key — keystore-bound (TEE), can't leave the device. Users
 *    regenerate on the new device and re-authorize on servers.
 *  - Host-key TOFU records — security anchors. Re-verifying on a fresh device
 *    is the point of the trust-on-first-use model.
 */
object SettingsBackup {
    /**
     * Schema version. Bump (and add a migration branch in [import]) on any
     * non-additive change to the JSON shape.
     */
    private const val VERSION = 1

    fun export(context: Context, includeLearning: Boolean = false): String {
        val prefs = context.getSharedPreferences(
            MainActivity.PREFS_NAME, Context.MODE_PRIVATE,
        )
        val store = ShortcutStore(context)

        val connectionJson = JSONObject().apply {
            prefs.getString(MainActivity.KEY_HOST, null)?.let { put("host", it) }
            prefs.getString(MainActivity.KEY_PORT, null)?.let { put("port", it) }
            prefs.getString(MainActivity.KEY_USERNAME, null)?.let { put("username", it) }
            put("use_tmux", prefs.getBoolean(MainActivity.KEY_USE_TMUX, true))
            prefs.getString(MainActivity.KEY_TMUX_PREFIX, null)?.let { put("tmux_prefix", it) }
            prefs.getString(MainActivity.KEY_VOICE_FILTER_COMMAND, null)
                ?.takeIf { it.isNotBlank() }?.let { put("voice_filter_command", it) }
        }
        val groupsJson = ShortcutStore.encodeContextGroups(store.loadContextGroups())

        val terminalPrefs = context.getSharedPreferences(
            TerminalActivity.PREFS_TERMINAL, Context.MODE_PRIVATE,
        )
        val terminalJson = JSONObject().apply {
            if (terminalPrefs.contains(TerminalActivity.KEY_FONT_SIZE_PX)) {
                put("font_size_px", terminalPrefs.getInt(TerminalActivity.KEY_FONT_SIZE_PX, 0))
            }
        }

        val root = JSONObject()
            .put("version", VERSION)
            .put("connection", connectionJson)
            .put("terminal", terminalJson)
            .put("context_groups", groupsJson)
        if (includeLearning) {
            root.put("bigrams", encodeBigrams(BigramStore(context).snapshot()))
        }
        return root.toString(2)
    }

    /**
     * Apply [json] to persisted settings. Throws if the input cannot be parsed
     * or has an unsupported version. Each section is fully decoded before any
     * write so a malformed payload won't leave settings half-overwritten.
     */
    fun import(context: Context, json: String) {
        val root = JSONObject(json)
        val version = root.optInt("version", -1)
        require(version == VERSION) { "Unsupported settings version: $version" }

        // Decode every section into typed locals before any write. A malformed
        // value (e.g. a non-integer font size or a duplicate bigram) throws here,
        // so the write phase below only ever puts already-validated values.
        val connection = root.optJSONObject("connection")?.let { decodeConnection(it) }
        val terminal = root.optJSONObject("terminal")?.let { decodeTerminal(it) }
        val groups = root.optJSONArray("context_groups")?.let { ShortcutStore.decodeContextGroups(it) }
        val bigrams = root.optJSONArray("bigrams")?.let { decodeBigrams(it) }

        if (connection != null) {
            val prefs = context.getSharedPreferences(
                MainActivity.PREFS_NAME, Context.MODE_PRIVATE,
            )
            prefs.edit {
                connection.host?.let { putString(MainActivity.KEY_HOST, it) }
                connection.port?.let { putString(MainActivity.KEY_PORT, it) }
                connection.username?.let { putString(MainActivity.KEY_USERNAME, it) }
                connection.useTmux?.let { putBoolean(MainActivity.KEY_USE_TMUX, it) }
                connection.tmuxPrefix?.let { putString(MainActivity.KEY_TMUX_PREFIX, it) }
                connection.voiceFilterCommand?.let {
                    putString(MainActivity.KEY_VOICE_FILTER_COMMAND, it)
                }
            }
        }
        if (terminal != null) {
            val terminalPrefs = context.getSharedPreferences(
                TerminalActivity.PREFS_TERMINAL, Context.MODE_PRIVATE,
            )
            terminalPrefs.edit {
                terminal.fontSizePx?.let { putInt(TerminalActivity.KEY_FONT_SIZE_PX, it) }
            }
        }
        if (groups != null) {
            ShortcutStore(context).saveContextGroups(groups)
        }
        if (bigrams != null) {
            BigramStore(context).replaceAll(bigrams)
        }
    }

    private class Connection(
        val host: String?,
        val port: String?,
        val username: String?,
        val useTmux: Boolean?,
        val tmuxPrefix: String?,
        val voiceFilterCommand: String?,
    )

    private fun decodeConnection(obj: JSONObject) = Connection(
        host = if (obj.has("host")) obj.getString("host") else null,
        port = if (obj.has("port")) obj.getString("port") else null,
        username = if (obj.has("username")) obj.getString("username") else null,
        useTmux = if (obj.has("use_tmux")) obj.getBoolean("use_tmux") else null,
        tmuxPrefix = if (obj.has("tmux_prefix")) obj.getString("tmux_prefix") else null,
        voiceFilterCommand = if (obj.has("voice_filter_command")) obj.getString("voice_filter_command") else null,
    )

    private class Terminal(val fontSizePx: Int?)

    private fun decodeTerminal(obj: JSONObject) = Terminal(
        fontSizePx = if (obj.has("font_size_px")) obj.getInt("font_size_px") else null,
    )

    private fun encodeBigrams(rows: List<BigramStore.Bigram>): JSONArray {
        val arr = JSONArray()
        for (r in rows) {
            arr.put(
                JSONObject()
                    .put("context", r.context)
                    .put("prev", r.prev)
                    .put("next", r.next)
                    .put("count", r.count),
            )
        }
        return arr
    }

    private fun decodeBigrams(arr: JSONArray): List<BigramStore.Bigram> = buildList {
        val seen = HashSet<Triple<String, String, String>>()
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val bigram = BigramStore.Bigram(
                context = obj.getString("context"),
                prev = obj.getString("prev"),
                next = obj.getString("next"),
                count = obj.getInt("count"),
            )
            // The bigram table's primary key is (context, prev, next); a duplicate
            // would blow up replaceAll's insert mid-transaction, so reject it here
            // before any section is written.
            require(seen.add(Triple(bigram.context, bigram.prev, bigram.next))) {
                "Duplicate bigram: ${bigram.context}/${bigram.prev}/${bigram.next}"
            }
            add(bigram)
        }
    }
}
