package org.hogel.pocketssh.shortcuts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextGroupTest {

    @Test
    fun `specifity weights contexts twice useTmux flag`() {
        // 2 contexts → 4, useTmux=true → +1 = 5
        assertEquals(
            5,
            ContextGroup(name = "g", contexts = listOf("a", "b"), useTmux = true).specifity,
        )
        assertEquals(
            4,
            ContextGroup(name = "g", contexts = listOf("a", "b")).specifity,
        )
        assertEquals(1, ContextGroup(name = "g", useTmux = true).specifity)
        assertEquals(0, ContextGroup(name = "g").specifity)
    }

    @Test
    fun `matches returns true when contexts and useTmux are both unspecified`() {
        val g = ContextGroup(name = "always")
        assertTrue(g.matches(inTmux = false, currentContext = null))
        assertTrue(g.matches(inTmux = true, currentContext = "anything"))
    }

    @Test
    fun `matches with useTmux true requires inTmux true`() {
        val g = ContextGroup(name = "tmux", useTmux = true)
        assertFalse(g.matches(inTmux = false, currentContext = null))
        assertTrue(g.matches(inTmux = true, currentContext = null))
    }

    @Test
    fun `matches with non-empty contexts requires a context match`() {
        val g = ContextGroup(name = "claude", contexts = listOf("claude"))
        assertFalse(g.matches(inTmux = false, currentContext = null))
        assertFalse(g.matches(inTmux = false, currentContext = ""))
        assertFalse(g.matches(inTmux = false, currentContext = "vim"))
        assertTrue(g.matches(inTmux = false, currentContext = "claude"))
    }

    @Test
    fun `matches normalizes the current context with trim and lowercase`() {
        val g = ContextGroup(name = "claude", contexts = listOf("claude"))
        assertTrue(g.matches(inTmux = false, currentContext = "  CLAUDE  "))
        assertTrue(g.matches(inTmux = false, currentContext = "Claude"))
    }

    @Test
    fun `matches uses ignoreCase when scanning the contexts list`() {
        // Stored entries are matched case-insensitively too — the user can
        // type either case in the settings UI.
        val g = ContextGroup(name = "vim", contexts = listOf("VIM"))
        assertTrue(g.matches(inTmux = false, currentContext = "vim"))
    }

    @Test
    fun `matches returns true when any context in the list matches`() {
        val g = ContextGroup(name = "editors", contexts = listOf("vim", "nano", "emacs"))
        assertTrue(g.matches(inTmux = false, currentContext = "nano"))
        assertFalse(g.matches(inTmux = false, currentContext = "less"))
    }

    @Test
    fun `resolve filters out non-matching groups`() {
        val claude = ContextGroup(
            name = "claude",
            contexts = listOf("claude"),
            shortcuts = listOf(Shortcut("ESC", "\\e")),
        )
        val tmux = ContextGroup(
            name = "tmux",
            useTmux = true,
            shortcuts = listOf(Shortcut("➕", "{TMUX-PREFIX}c")),
        )
        val resolved = listOf(claude, tmux).resolve(inTmux = false, currentContext = "vim")
        assertEquals(0, resolved.shortcutRows.size)
        assertNull(resolved.swipeLeft)
        assertNull(resolved.swipeRight)
        assertEquals(0, resolved.fabRows.size)
    }

    @Test
    fun `resolve stacks shortcut rows by descending specifity`() {
        val always = ContextGroup(
            name = "always",
            shortcuts = listOf(Shortcut("TAB", "{TAB}")),
        )
        val tmux = ContextGroup(
            name = "tmux",
            useTmux = true,
            shortcuts = listOf(Shortcut("⬅️", "p")),
        )
        val claude = ContextGroup(
            name = "claude",
            contexts = listOf("claude"),
            shortcuts = listOf(Shortcut("⇧Tab", "{S-TAB}")),
        )

        val resolved = listOf(always, tmux, claude)
            .resolve(inTmux = true, currentContext = "claude")

        // claude (specifity 2) > tmux (1) > always (0).
        assertEquals(3, resolved.shortcutRows.size)
        assertEquals("⇧Tab", resolved.shortcutRows[0][0].label)
        assertEquals("⬅️", resolved.shortcutRows[1][0].label)
        assertEquals("TAB", resolved.shortcutRows[2][0].label)
    }

    @Test
    fun `resolve drops contributors that have no shortcuts`() {
        val empty = ContextGroup(name = "empty")
        val withRow = ContextGroup(
            name = "always",
            shortcuts = listOf(Shortcut("TAB", "{TAB}")),
        )
        val resolved = listOf(empty, withRow).resolve(inTmux = false, currentContext = null)
        assertEquals(1, resolved.shortcutRows.size)
        assertEquals("TAB", resolved.shortcutRows[0][0].label)
    }

    @Test
    fun `resolve picks swipes from the highest-specifity matching group`() {
        val tmuxSwipe = Shortcut("➡️", "{TMUX-PREFIX}n")
        val claudeSwipe = Shortcut("→claude", "x")
        val tmux = ContextGroup(
            name = "tmux",
            useTmux = true,
            swipeRight = tmuxSwipe,
        )
        val claude = ContextGroup(
            name = "claude",
            contexts = listOf("claude"),
            swipeRight = claudeSwipe,
        )
        val resolved = listOf(tmux, claude).resolve(inTmux = true, currentContext = "claude")
        assertSame(claudeSwipe, resolved.swipeRight)
    }

    @Test
    fun `resolve falls back to lower-specifity swipe when the top group has none`() {
        val tmuxSwipe = Shortcut("➡️", "{TMUX-PREFIX}n")
        val tmux = ContextGroup(
            name = "tmux",
            useTmux = true,
            swipeRight = tmuxSwipe,
        )
        val claude = ContextGroup(
            name = "claude",
            contexts = listOf("claude"),
            // claude has no swipeRight.
        )
        val resolved = listOf(tmux, claude).resolve(inTmux = true, currentContext = "claude")
        assertSame(tmuxSwipe, resolved.swipeRight)
    }

    @Test
    fun `resolve fabRows mirrors shortcutRows ordering`() {
        val always = ContextGroup(
            name = "always",
            fabItems = listOf(Shortcut("📋", "{COPY}")),
        )
        val tmux = ContextGroup(
            name = "tmux",
            useTmux = true,
            fabItems = listOf(Shortcut("➕", "{TMUX-PREFIX}c")),
        )
        val resolved = listOf(always, tmux).resolve(inTmux = true, currentContext = null)
        assertEquals(2, resolved.fabRows.size)
        assertEquals("➕", resolved.fabRows[0][0].label)
        assertEquals("📋", resolved.fabRows[1][0].label)
    }

    @Test
    fun `bundled defaults match expected groups when nothing is configured`() {
        val defaults = ShortcutStore.defaultContextGroups()
        val names = defaults.map { it.name }
        assertEquals(listOf("always", "tmux", "claude", "codex"), names)

        val resolvedTmuxClaude = defaults.resolve(inTmux = true, currentContext = "claude")
        // claude (specifity 2) + tmux (1) + always (0) all contribute somewhere.
        assertEquals(2, resolvedTmuxClaude.shortcutRows.size) // claude shortcuts + always shortcuts
        assertEquals(2, resolvedTmuxClaude.fabRows.size) // tmux fab + always fab

        val resolvedNoTmux = defaults.resolve(inTmux = false, currentContext = "vim")
        assertEquals(1, resolvedNoTmux.shortcutRows.size) // only always
        assertEquals(1, resolvedNoTmux.fabRows.size) // only always
        assertNull(resolvedNoTmux.swipeLeft)
        assertNull(resolvedNoTmux.swipeRight)
    }
}
