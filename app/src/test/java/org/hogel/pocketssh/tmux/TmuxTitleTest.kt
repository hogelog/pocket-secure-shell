package org.hogel.pocketssh.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val US = ''
private const val RS = ''
private const val GS = ''

class TmuxTitleTest {

    @Test
    fun `null and empty produce EMPTY`() {
        assertEquals(TmuxTitle.EMPTY, TmuxTitle.parse(null))
        assertEquals(TmuxTitle.EMPTY, TmuxTitle.parse(""))
    }

    @Test
    fun `legacy title without separator becomes command-only`() {
        val parsed = TmuxTitle.parse("claude")
        assertEquals("claude", parsed.command)
        assertTrue(parsed.windows.isEmpty())
    }

    @Test
    fun `single window record`() {
        val raw = "vim${US}W:0${GS}vim${GS}1${RS}"
        val parsed = TmuxTitle.parse(raw)
        assertEquals("vim", parsed.command)
        assertEquals(1, parsed.windows.size)
        assertEquals(TmuxWindow(0, "vim", true), parsed.windows[0])
    }

    @Test
    fun `multiple windows with one active`() {
        val raw = "claude${US}W:" +
            "0${GS}vim${GS}0${RS}" +
            "1${GS}claude${GS}1${RS}" +
            "2${GS}bash${GS}0${RS}"
        val parsed = TmuxTitle.parse(raw)
        assertEquals("claude", parsed.command)
        assertEquals(
            listOf(
                TmuxWindow(0, "vim", false),
                TmuxWindow(1, "claude", true),
                TmuxWindow(2, "bash", false),
            ),
            parsed.windows,
        )
    }

    @Test
    fun `empty command with windows`() {
        val raw = "${US}W:0${GS}bash${GS}1${RS}"
        val parsed = TmuxTitle.parse(raw)
        assertNull(parsed.command)
        assertEquals(1, parsed.windows.size)
    }

    @Test
    fun `malformed records are skipped`() {
        // Missing active field; only two GS-separated fields → skipped.
        val raw = "x${US}W:0${GS}bad${RS}1${GS}good${GS}1${RS}"
        val parsed = TmuxTitle.parse(raw)
        assertEquals(listOf(TmuxWindow(1, "good", true)), parsed.windows)
    }

    @Test
    fun `non-integer index is skipped`() {
        val raw = "x${US}W:abc${GS}bad${GS}0${RS}3${GS}ok${GS}0${RS}"
        val parsed = TmuxTitle.parse(raw)
        assertEquals(listOf(TmuxWindow(3, "ok", false)), parsed.windows)
    }

    @Test
    fun `alternate-screen flag parsed from new format`() {
        val raw = "vim${US}1${US}W:0${GS}vim${GS}1${RS}"
        val parsed = TmuxTitle.parse(raw)
        assertEquals("vim", parsed.command)
        assertTrue(parsed.alternateOn)
        assertEquals(listOf(TmuxWindow(0, "vim", true)), parsed.windows)
    }

    @Test
    fun `normal-screen flag parsed from new format`() {
        val raw = "bash${US}0${US}W:0${GS}bash${GS}1${RS}"
        val parsed = TmuxTitle.parse(raw)
        assertEquals("bash", parsed.command)
        assertFalse(parsed.alternateOn)
        assertEquals(listOf(TmuxWindow(0, "bash", true)), parsed.windows)
    }

    @Test
    fun `legacy format without flag degrades to normal screen`() {
        val parsed = TmuxTitle.parse("vim${US}W:0${GS}vim${GS}1${RS}")
        assertFalse(parsed.alternateOn)
        assertEquals(listOf(TmuxWindow(0, "vim", true)), parsed.windows)
    }

    @Test
    fun `format template references expected tmux variables`() {
        val fmt = TmuxTitle.TMUX_TITLE_FORMAT
        assertTrue(fmt.contains("#{pane_current_command}"))
        assertTrue(fmt.contains("#{?alternate_on,1,0}"))
        assertTrue(fmt.contains("#{W:"))
        assertTrue(fmt.contains("#{window_index}"))
        assertTrue(fmt.contains("#{window_name}"))
        assertTrue(fmt.contains("#{?window_active,1,0}"))
        assertTrue(fmt.contains(US))
        assertTrue(fmt.contains(GS))
        assertTrue(fmt.contains(RS))
    }
}
