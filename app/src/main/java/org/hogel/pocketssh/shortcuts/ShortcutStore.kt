package org.hogel.pocketssh.shortcuts

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences-backed persistence for [ContextGroup]s. Each group bundles
 * a shortcut bar slice, optional swipe payloads, and an optional FAB row;
 * matching is driven by foreground command name and tmux state. See
 * [ContextGroup] for the schema and [List.resolve] for the cascade rules.
 */
class ShortcutStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        // Purge stale pre-rewrite keys; their data now lives in context groups.
        if (prefs.contains(LEGACY_KEY_AUX) || prefs.contains(LEGACY_KEY_SWIPE)) {
            prefs.edit {
                remove(LEGACY_KEY_AUX)
                remove(LEGACY_KEY_SWIPE)
            }
        }
    }

    fun loadContextGroups(): List<ContextGroup> {
        val raw = prefs.getString(KEY_CONTEXT, null) ?: return defaultContextGroups()
        return runCatching { decodeContextGroups(JSONArray(raw)) }
            .getOrElse { defaultContextGroups() }
    }

    fun saveContextGroups(groups: List<ContextGroup>) {
        prefs.edit { putString(KEY_CONTEXT, encodeContextGroups(groups).toString()) }
    }

    /** Drop all stored groups so the next read returns the bundled defaults. */
    fun resetToDefaults() {
        prefs.edit { remove(KEY_CONTEXT) }
    }

    companion object {
        private const val PREFS_NAME = "shortcuts"
        private const val KEY_CONTEXT = "context_groups"

        // Pre-rewrite keys, kept here only so the init block can purge them.
        private const val LEGACY_KEY_AUX = "aux"
        private const val LEGACY_KEY_SWIPE = "swipe"

        fun defaultContextGroups(): List<ContextGroup> = listOf(
            // Empty contexts + null useTmux means it matches every state at
            // specifity 0, so it acts as the baseline that everything else
            // stacks on top of.
            ContextGroup(
                name = "always",
                shortcuts = listOf(
                    Shortcut("TAB", "{TAB}"),
                    Shortcut("^C", "^C"),
                    Shortcut("↓", "{DOWN}"),
                    Shortcut("↑", "{UP}"),
                ),
                fabItems = listOf(
                    Shortcut("\uD83D\uDD12", "{SECURE-INPUT}"), // 🔒 secure input toggle
                    Shortcut("⌨️", "{CTRL-INPUT}"), // control-input dialog (^C/^D/^L/^R/…)
                    Shortcut("\uD83D\uDDBC", "{IMAGE-PASTE}"), // 🖼 image picker
                    Shortcut("\uD83D\uDCCB", "{COPY}"),        // 📋 selection mode
                    Shortcut("\uD83D\uDCC1", "{SCP}"),         // 📁 remote file browser
                ),
            ),
            // Swipe direction mirrors the FAB arrows: ⬅️ / swipe-left =
            // previous window, ➡️ / swipe-right = next window.
            ContextGroup(
                name = "tmux",
                useTmux = true,
                fabItems = listOf(
                    Shortcut("➕", "{TMUX-PREFIX}c"),
                    Shortcut("⬅️", "{TMUX-PREFIX}p"),
                    Shortcut("➡️", "{TMUX-PREFIX}n"),
                ),
                swipeLeft = Shortcut("⬅️", "{TMUX-PREFIX}p"),
                swipeRight = Shortcut("➡️", "{TMUX-PREFIX}n"),
            ),
            // ESC / Shift-Tab / ^J are control-byte payloads the bigram tracker
            // can't observe (it poisons the line on the first byte `< 0x20`), so
            // they have to stay as fixed buttons. `/` opens the slash-command
            // menu and is pinned too so it's one tap from a fresh prompt.
            // Other plain-text tokens are surfaced by the tracker from real use.
            ContextGroup(
                name = "claude",
                contexts = listOf("claude"),
                shortcuts = listOf(
                    Shortcut("/", "/"),
                    Shortcut("ESC", "\\e"),
                    Shortcut("⇧Tab", "{S-TAB}"),
                    Shortcut("^J", "^J"),
                ),
            ),
            // Same fixed buttons as claude — codex's TUI uses ESC to interrupt,
            // Shift-Tab to cycle approval modes, ^J for a newline in multi-line
            // input, and `/` to open its slash-command menu.
            ContextGroup(
                name = "codex",
                contexts = listOf("codex"),
                shortcuts = listOf(
                    Shortcut("/", "/"),
                    Shortcut("ESC", "\\e"),
                    Shortcut("⇧Tab", "{S-TAB}"),
                    Shortcut("^J", "^J"),
                ),
            ),
        )

        internal fun encodeContextGroups(groups: List<ContextGroup>): JSONArray {
            val arr = JSONArray()
            for (g in groups) arr.put(encodeContextGroup(g))
            return arr
        }

        private fun encodeContextGroup(group: ContextGroup): JSONObject {
            val obj = JSONObject()
            obj.put("name", group.name)
            val ctxArr = JSONArray()
            for (c in group.contexts) ctxArr.put(c)
            obj.put("contexts", ctxArr)
            // useTmux is omitted when null so the JSON stays minimal — `false`
            // is never written because we don't model "only outside tmux".
            if (group.useTmux == true) obj.put("useTmux", true)
            obj.put("shortcuts", encodeShortcutArray(group.shortcuts))
            group.swipeLeft?.let { obj.put("swipeLeft", encodeShortcut(it)) }
            group.swipeRight?.let { obj.put("swipeRight", encodeShortcut(it)) }
            obj.put("fabItems", encodeShortcutArray(group.fabItems))
            return obj
        }

        internal fun decodeContextGroups(arr: JSONArray): List<ContextGroup> = buildList {
            for (i in 0 until arr.length()) {
                add(decodeContextGroup(arr.getJSONObject(i)))
            }
        }

        private fun decodeContextGroup(obj: JSONObject): ContextGroup {
            val name = obj.optString("name").orEmpty()
            val contexts = parseStringArray(obj.optJSONArray("contexts"))
            // Treat any non-true value (missing, null, false) as "don't care".
            val useTmux = if (obj.optBoolean("useTmux", false)) true else null
            val shortcuts = parseShortcutArray(obj.optJSONArray("shortcuts") ?: JSONArray())
            val swipeLeft = obj.optJSONObject("swipeLeft")?.let { decodeShortcut(it) }
            val swipeRight = obj.optJSONObject("swipeRight")?.let { decodeShortcut(it) }
            val fabItems = parseShortcutArray(obj.optJSONArray("fabItems") ?: JSONArray())
            return ContextGroup(name, contexts, useTmux, shortcuts, swipeLeft, swipeRight, fabItems)
        }

        private fun parseShortcutArray(arr: JSONArray): List<Shortcut> = buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val s = decodeShortcut(obj)
                if (s.label.isNotEmpty()) add(s)
            }
        }

        private fun decodeShortcut(obj: JSONObject): Shortcut =
            Shortcut(obj.optString("label").orEmpty(), obj.optString("payload").orEmpty())

        private fun encodeShortcutArray(shortcuts: List<Shortcut>): JSONArray {
            val arr = JSONArray()
            for (s in shortcuts) arr.put(encodeShortcut(s))
            return arr
        }

        private fun encodeShortcut(s: Shortcut): JSONObject =
            JSONObject().put("label", s.label).put("payload", s.payload)

        private fun parseStringArray(arr: JSONArray?): List<String> = buildList {
            if (arr == null) return@buildList
            for (i in 0 until arr.length()) {
                val s = arr.optString(i)
                if (s.isNotEmpty()) add(s)
            }
        }
    }
}
