package org.hogel.pocketssh.shortcuts

import android.view.KeyEvent
import com.termux.terminal.KeyHandler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortcutPayloadTest {

    @Test
    fun `plain text is emitted as a single SendBytes UTF-8 chunk`() {
        val actions = parseShortcutActions("ls -la")
        assertEquals(1, actions.size)
        val a = actions[0] as ShortcutAction.SendBytes
        assertArrayEquals("ls -la".toByteArray(Charsets.UTF_8), a.bytes)
    }

    @Test
    fun `multi-byte UTF-8 stays intact`() {
        val actions = parseShortcutActions("あいう")
        assertEquals(1, actions.size)
        val a = actions[0] as ShortcutAction.SendBytes
        assertArrayEquals("あいう".toByteArray(Charsets.UTF_8), a.bytes)
    }

    @Test
    fun `backslash escapes are decoded to single bytes`() {
        val actions = parseShortcutActions("\\e\\t\\r\\n")
        assertEquals(4, actions.size)
        assertArrayEquals(byteArrayOf(0x1B), (actions[0] as ShortcutAction.SendBytes).bytes)
        assertArrayEquals(byteArrayOf(0x09), (actions[1] as ShortcutAction.SendBytes).bytes)
        assertArrayEquals(byteArrayOf(0x0D), (actions[2] as ShortcutAction.SendBytes).bytes)
        assertArrayEquals(byteArrayOf(0x0A), (actions[3] as ShortcutAction.SendBytes).bytes)
    }

    @Test
    fun `uppercase backslash-E also decodes to ESC`() {
        val actions = parseShortcutActions("\\E")
        assertEquals(1, actions.size)
        assertArrayEquals(byteArrayOf(0x1B), (actions[0] as ShortcutAction.SendBytes).bytes)
    }

    @Test
    fun `double backslash is a literal backslash, not an escape`() {
        val actions = parseShortcutActions("\\\\n")
        assertEquals(1, actions.size)
        assertArrayEquals(
            "\\n".toByteArray(Charsets.UTF_8),
            (actions[0] as ShortcutAction.SendBytes).bytes,
        )
    }

    @Test
    fun `hex escape decodes a single byte`() {
        val actions = parseShortcutActions("\\x1B[A")
        // \x1B then literal "[A"
        assertEquals(2, actions.size)
        assertArrayEquals(byteArrayOf(0x1B), (actions[0] as ShortcutAction.SendBytes).bytes)
        assertArrayEquals("[A".toByteArray(Charsets.UTF_8), (actions[1] as ShortcutAction.SendBytes).bytes)
    }

    @Test
    fun `uppercase hex escape decodes a single byte`() {
        val actions = parseShortcutActions("\\Xff")
        assertEquals(1, actions.size)
        assertArrayEquals(byteArrayOf(0xFF.toByte()), (actions[0] as ShortcutAction.SendBytes).bytes)
    }

    @Test
    fun `malformed hex escape falls through as literal text`() {
        val actions = parseShortcutActions("\\xZZ")
        assertEquals(1, actions.size)
        assertArrayEquals(
            "\\xZZ".toByteArray(Charsets.UTF_8),
            (actions[0] as ShortcutAction.SendBytes).bytes,
        )
    }

    @Test
    fun `truncated hex escape falls through as literal text`() {
        val actions = parseShortcutActions("\\x1")
        assertEquals(1, actions.size)
        assertArrayEquals(
            "\\x1".toByteArray(Charsets.UTF_8),
            (actions[0] as ShortcutAction.SendBytes).bytes,
        )
    }

    @Test
    fun `unknown backslash escape falls through verbatim`() {
        val actions = parseShortcutActions("\\q")
        assertEquals(1, actions.size)
        assertArrayEquals(
            "\\q".toByteArray(Charsets.UTF_8),
            (actions[0] as ShortcutAction.SendBytes).bytes,
        )
    }

    @Test
    fun `caret-Ctrl produces C0 control bytes for letters`() {
        // ^A..^Z map to 0x01..0x1A, both cases.
        val cases = listOf(
            "^A" to 0x01,
            "^L" to 0x0C,
            "^Z" to 0x1A,
            "^c" to 0x03,
            "^d" to 0x04,
        )
        for ((src, expected) in cases) {
            val actions = parseShortcutActions(src)
            assertEquals("payload=$src", 1, actions.size)
            val bytes = (actions[0] as ShortcutAction.SendBytes).bytes
            assertEquals("payload=$src", 1, bytes.size)
            assertEquals("payload=$src", expected.toByte(), bytes[0])
        }
    }

    @Test
    fun `caret with bracket and other punctuation maps to C0 control bytes`() {
        val cases = listOf(
            "^[" to 0x1B,
            "^\\" to 0x1C,
            "^]" to 0x1D,
            "^^" to 0x1E,
            "^_" to 0x1F,
            "^?" to 0x7F,
        )
        for ((src, expected) in cases) {
            val actions = parseShortcutActions(src)
            assertEquals("payload=$src", 1, actions.size)
            val bytes = (actions[0] as ShortcutAction.SendBytes).bytes
            assertEquals("payload=$src", 1, bytes.size)
            assertEquals("payload=$src", expected.toByte(), bytes[0])
        }
    }

    @Test
    fun `unknown caret token falls through and the caret is kept as a literal`() {
        // `^!` has no Ctrl mapping; the parser keeps the caret in the literal
        // buffer and continues scanning at the next character.
        val actions = parseShortcutActions("^!")
        assertEquals(1, actions.size)
        assertArrayEquals(
            "^!".toByteArray(Charsets.UTF_8),
            (actions[0] as ShortcutAction.SendBytes).bytes,
        )
    }

    @Test
    fun `key tokens map to SendKey actions`() {
        assertEquals(
            ShortcutAction.SendKey(KeyEvent.KEYCODE_DPAD_UP),
            parseShortcutActions("{UP}").single(),
        )
        assertEquals(
            ShortcutAction.SendKey(KeyEvent.KEYCODE_DPAD_DOWN),
            parseShortcutActions("{DOWN}").single(),
        )
        assertEquals(
            ShortcutAction.SendKey(KeyEvent.KEYCODE_DPAD_LEFT),
            parseShortcutActions("{LEFT}").single(),
        )
        assertEquals(
            ShortcutAction.SendKey(KeyEvent.KEYCODE_DPAD_RIGHT),
            parseShortcutActions("{RIGHT}").single(),
        )
        assertEquals(
            ShortcutAction.SendKey(KeyEvent.KEYCODE_TAB),
            parseShortcutActions("{TAB}").single(),
        )
    }

    @Test
    fun `key tokens are case-insensitive`() {
        assertEquals(
            ShortcutAction.SendKey(KeyEvent.KEYCODE_DPAD_UP),
            parseShortcutActions("{up}").single(),
        )
    }

    @Test
    fun `S-TAB and SHIFT-TAB both map to back-tab`() {
        val expected = ShortcutAction.SendKey(KeyEvent.KEYCODE_TAB, KeyHandler.KEYMOD_SHIFT)
        assertEquals(expected, parseShortcutActions("{S-TAB}").single())
        assertEquals(expected, parseShortcutActions("{SHIFT-TAB}").single())
    }

    @Test
    fun `dynamic tokens map to their object actions`() {
        assertTrue(parseShortcutActions("{TMUX-PREFIX}").single() === ShortcutAction.SendTmuxPrefix)
        assertTrue(parseShortcutActions("{TMUX_PREFIX}").single() === ShortcutAction.SendTmuxPrefix)
        assertTrue(parseShortcutActions("{COPY}").single() === ShortcutAction.Copy)
        assertTrue(parseShortcutActions("{PASTE}").single() === ShortcutAction.Paste)
        assertTrue(parseShortcutActions("{IMAGE-PASTE}").single() === ShortcutAction.ImagePaste)
        assertTrue(parseShortcutActions("{IMAGE_PASTE}").single() === ShortcutAction.ImagePaste)
        assertTrue(parseShortcutActions("{CTRL-INPUT}").single() === ShortcutAction.CtrlInput)
        assertTrue(parseShortcutActions("{CTRL_INPUT}").single() === ShortcutAction.CtrlInput)
    }

    @Test
    fun `unknown brace token falls through as a literal opening brace`() {
        // `{NOPE}` is not recognised, so the parser emits the brace as a
        // literal and resumes scanning at the next character — there is no
        // silent dropping of the token text.
        val actions = parseShortcutActions("{NOPE}")
        assertEquals(1, actions.size)
        assertArrayEquals(
            "{NOPE}".toByteArray(Charsets.UTF_8),
            (actions[0] as ShortcutAction.SendBytes).bytes,
        )
    }

    @Test
    fun `unmatched opening brace is treated as a literal`() {
        val actions = parseShortcutActions("{UP")
        assertEquals(1, actions.size)
        assertArrayEquals(
            "{UP".toByteArray(Charsets.UTF_8),
            (actions[0] as ShortcutAction.SendBytes).bytes,
        )
    }

    @Test
    fun `lone trailing backslash is kept as a literal`() {
        val actions = parseShortcutActions("hi\\")
        assertEquals(1, actions.size)
        assertArrayEquals(
            "hi\\".toByteArray(Charsets.UTF_8),
            (actions[0] as ShortcutAction.SendBytes).bytes,
        )
    }

    @Test
    fun `lone trailing caret is kept as a literal`() {
        val actions = parseShortcutActions("hi^")
        assertEquals(1, actions.size)
        assertArrayEquals(
            "hi^".toByteArray(Charsets.UTF_8),
            (actions[0] as ShortcutAction.SendBytes).bytes,
        )
    }

    @Test
    fun `empty payload produces no actions`() {
        assertEquals(0, parseShortcutActions("").size)
    }

    @Test
    fun `mixed payload preserves order and groups adjacent literals`() {
        // git status\n
        val actions = parseShortcutActions("git status\\r")
        assertEquals(2, actions.size)
        assertArrayEquals(
            "git status".toByteArray(Charsets.UTF_8),
            (actions[0] as ShortcutAction.SendBytes).bytes,
        )
        assertArrayEquals(byteArrayOf(0x0D), (actions[1] as ShortcutAction.SendBytes).bytes)
    }

    @Test
    fun `caret then literal then key token yields three ordered actions`() {
        val actions = parseShortcutActions("^Cabc{UP}")
        assertEquals(3, actions.size)
        assertArrayEquals(byteArrayOf(0x03), (actions[0] as ShortcutAction.SendBytes).bytes)
        assertArrayEquals(
            "abc".toByteArray(Charsets.UTF_8),
            (actions[1] as ShortcutAction.SendBytes).bytes,
        )
        assertEquals(
            ShortcutAction.SendKey(KeyEvent.KEYCODE_DPAD_UP),
            actions[2],
        )
    }

    @Test
    fun `tmux compound payload mixes dynamic token, literal, and CR`() {
        val actions = parseShortcutActions("{TMUX-PREFIX}:clear history\\r")
        assertEquals(3, actions.size)
        assertTrue(actions[0] === ShortcutAction.SendTmuxPrefix)
        assertArrayEquals(
            ":clear history".toByteArray(Charsets.UTF_8),
            (actions[1] as ShortcutAction.SendBytes).bytes,
        )
        assertArrayEquals(byteArrayOf(0x0D), (actions[2] as ShortcutAction.SendBytes).bytes)
    }
}
