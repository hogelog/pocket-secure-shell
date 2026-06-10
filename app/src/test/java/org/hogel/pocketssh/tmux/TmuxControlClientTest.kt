package org.hogel.pocketssh.tmux

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxControlClientTest {

    private val output = mutableListOf<ByteArray>()
    private val captures = mutableListOf<Pair<String, ByteArray>>()
    private val windowsUpdates = mutableListOf<List<TmuxControlWindow>>()
    private val exits = mutableListOf<String?>()
    private val client = TmuxControlClient(
        onPaneOutput = { _, bytes -> output += bytes },
        onCaptureReply = { pane, body -> captures += pane to body },
        onWindowsChanged = { windowsUpdates += it },
        onWindowsDirty = { windowsDirtyCount++ },
        onExit = { exits += it },
    )

    private var windowsDirtyCount = 0

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
    fun `encodeInput targets the pane set via setInputPane`() {
        client.setInputPane("%3")
        val cmd = String(client.encodeInput(byteArrayOf(0x1B, 0x5B, 0x41)), Charsets.US_ASCII)
        assertEquals("send-keys -t %3 -lH 1b 5b 41\n", cmd)
    }

    @Test
    fun `encodeInput does not follow the last output pane`() {
        feed("%output %7 hi\n") // background pane emits; must not become the target
        val cmd = String(client.encodeInput("a".toByteArray()), Charsets.US_ASCII)
        assertEquals("send-keys -lH 61\n", cmd) // no -t, since no pane was set
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

    // --- capture-pane reply body ---

    @Test
    fun `requestCapture targets the pane and reaches into history`() {
        val cmd = String(client.requestCapture("%2"), Charsets.US_ASCII)
        assertEquals("capture-pane -peJ -t %2 -S -2000\n", cmd)
    }

    @Test
    fun `capture reply body routes to the requesting pane joined by CRLF`() {
        client.requestCapture("%2")
        feed("%begin 1 5 0\n")
        feed("line one\n")
        feed("line two\n")
        feed("%end 1 5 0\n")
        assertEquals("%2", captures.single().first)
        assertArrayEquals("line one\r\nline two".toByteArray(), captures.single().second)
    }

    @Test
    fun `capture reply keeps raw SGR escape bytes byte-exact`() {
        // Real tmux 3.5a wire shape: unlike %output, the reply body is NOT
        // octal-escaped — colour escapes arrive as raw ESC bytes and must
        // reach the emulator unchanged for the seeded history to keep colour.
        client.requestCapture("%0")
        feed("%begin 1781127830 284 1\r\n")
        feed("\u001B[31mRED\u001B[39m plain \u001B[1m\u001B[34mBOLDBLUE\r\n")
        feed("\u001B[0mline2 \u001B[42mGREENBG\r\n")
        feed("%end 1781127830 284 1\r\n")
        assertArrayEquals(
            ("\u001B[31mRED\u001B[39m plain \u001B[1m\u001B[34mBOLDBLUE\r\n" +
                "\u001B[0mline2 \u001B[42mGREENBG").toByteArray(Charsets.ISO_8859_1),
            captures.single().second,
        )
    }

    @Test
    fun `a body line that looks like a terminator does not close on a mismatched number`() {
        client.requestCapture("%0")
        feed("%begin 1 7 0\n")
        feed("%end 1 99 0\n") // captured content, not the terminator (number != 7)
        feed("real\n")
        feed("%end 1 7 0\n")
        assertArrayEquals("%end 1 99 0\r\nreal".toByteArray(), captures.single().second)
    }

    @Test
    fun `out-of-band begin block with no pending command is ignored`() {
        feed("%begin 1 1 0\n")
        feed("@1 state\n")
        feed("%end 1 1 0\n")
        assertTrue(captures.isEmpty())
    }

    @Test
    fun `replies are consumed in send order before the capture reply`() {
        client.encodeInput("ab".toByteArray()) // enqueues one send-keys command
        client.requestCapture("%1")
        feed("%begin 1 1 0\n%end 1 1 0\n") // send-keys reply: body dropped
        feed("%begin 1 2 0\nhi\n%end 1 2 0\n") // capture reply
        assertEquals("%1", captures.single().first)
        assertArrayEquals("hi".toByteArray(), captures.single().second)
    }

    // --- list-windows reply ---

    @Test
    fun `list-windows reply is parsed into windows`() {
        client.requestWindows()
        feed("%begin 1 9 0\n")
        feed("@0\t0\tbash\t0\t%0\tbash\n")
        feed("@13\t4\tclaude\t1\t%19\tnode\n")
        feed("%end 1 9 0\n")
        val windows = windowsUpdates.single()
        assertEquals(2, windows.size)
        assertEquals(TmuxControlWindow("@13", 4, "claude", true, "%19", "node"), windows[1])
    }

    @Test
    fun `requestWindows emits a tab-delimited list-windows format`() {
        val cmd = String(client.requestWindows(), Charsets.US_ASCII)
        assertTrue(cmd.startsWith("list-windows -F "))
        assertTrue(cmd.contains("#{window_id}\t#{window_index}"))
    }
}
