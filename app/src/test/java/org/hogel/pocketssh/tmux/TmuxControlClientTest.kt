package org.hogel.pocketssh.tmux

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxControlClientTest {

    private val output = mutableListOf<ByteArray>()
    private val exits = mutableListOf<String?>()
    private val client = TmuxControlClient(
        onPaneOutput = { output += it },
        onExit = { exits += it },
    )

    private fun feed(s: String) = client.feed(s.toByteArray(Charsets.ISO_8859_1))

    // --- decodeOutput ---

    @Test
    fun `decodeOutput passes printable ASCII through`() {
        assertArrayEquals("hello".toByteArray(), TmuxControlClient.decodeOutput("hello"))
    }

    @Test
    fun `decodeOutput unescapes control bytes and backslash`() {
        // \015\012 = CR LF, \\ = backslash
        assertArrayEquals(
            byteArrayOf(0x0D, 0x0A, '\\'.code.toByte()),
            TmuxControlClient.decodeOutput("\\015\\012\\134"),
        )
    }

    @Test
    fun `decodeOutput keeps high UTF-8 bytes literal`() {
        // tmux leaves bytes >= 0x80 unescaped; "あ" is E3 81 82.
        val payload = String(byteArrayOf(0xE3.toByte(), 0x81.toByte(), 0x82.toByte()), Charsets.ISO_8859_1)
        assertArrayEquals("あ".toByteArray(Charsets.UTF_8), TmuxControlClient.decodeOutput(payload))
    }

    @Test
    fun `decodeOutput leaves a trailing lone backslash literal`() {
        assertArrayEquals(byteArrayOf('\\'.code.toByte()), TmuxControlClient.decodeOutput("\\"))
    }

    // --- feed framing / dispatch ---

    @Test
    fun `output line emits decoded pane bytes`() {
        feed("%output %0 hi\\041\n") // \041 = '!'
        assertEquals(1, output.size)
        assertArrayEquals("hi!".toByteArray(), output[0])
    }

    @Test
    fun `lines split across feeds are reassembled`() {
        feed("%output %0 ab")
        assertTrue(output.isEmpty())
        feed("cd\n")
        assertArrayEquals("abcd".toByteArray(), output.single())
    }

    @Test
    fun `CRLF terminator is stripped`() {
        feed("%output %0 x\r\n")
        assertArrayEquals("x".toByteArray(), output.single())
    }

    @Test
    fun `reply block body is swallowed`() {
        feed("%begin 1 2 0\n")
        feed("some reply text\n")
        feed("%output %0 leaked\n") // inside the block: must be ignored
        feed("%end 1 2 0\n")
        feed("%output %0 real\n")
        assertArrayEquals("real".toByteArray(), output.single())
    }

    @Test
    fun `error block also closes the block`() {
        feed("%begin 1 2 0\n")
        feed("%error 1 2 0\n")
        feed("%output %0 after\n")
        assertArrayEquals("after".toByteArray(), output.single())
    }

    @Test
    fun `exit reports reason`() {
        feed("%exit server-exited\n")
        assertEquals(listOf("server-exited"), exits)
    }

    @Test
    fun `exit without reason reports null`() {
        feed("%exit\n")
        assertEquals(1, exits.size)
        assertNull(exits[0])
    }

    @Test
    fun `unknown notifications are ignored`() {
        feed("%window-add @1\n")
        feed("%layout-change @1 bf2e,80x24,0,0,0\n")
        assertTrue(output.isEmpty())
    }

    // --- encodeInput ---

    @Test
    fun `encodeInput before any pane omits target`() {
        val cmd = String(client.encodeInput("ab".toByteArray()), Charsets.US_ASCII)
        assertEquals("send-keys -lH 61 62\n", cmd)
    }

    @Test
    fun `encodeInput targets the active pane`() {
        feed("%output %3 \n") // sets active pane to %3
        val cmd = String(client.encodeInput(byteArrayOf(0x1B, 0x5B, 0x41)), Charsets.US_ASCII)
        assertEquals("send-keys -t %3 -lH 1b 5b 41\n", cmd)
    }

    @Test
    fun `encodeInput hex-encodes high bytes per byte`() {
        // "あ" -> E3 81 82, sent as raw bytes (tmux -H is byte-valued).
        val cmd = String(client.encodeInput("あ".toByteArray(Charsets.UTF_8)), Charsets.US_ASCII)
        assertEquals("send-keys -lH e3 81 82\n", cmd)
    }

    @Test
    fun `encodeInput empty is empty`() {
        assertArrayEquals(ByteArray(0), client.encodeInput(ByteArray(0)))
    }

    @Test
    fun `encodeInput splits long input into multiple commands`() {
        val data = ByteArray(600) { 'a'.code.toByte() }
        val cmd = String(client.encodeInput(data), Charsets.US_ASCII)
        assertEquals(2, cmd.trim().lines().size)
    }
}
