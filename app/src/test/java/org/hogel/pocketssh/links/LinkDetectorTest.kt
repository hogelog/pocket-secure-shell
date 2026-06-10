package org.hogel.pocketssh.links

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkDetectorTest {

    @Test
    fun `plain text without urls returns empty`() {
        assertEquals(emptyList<String>(), LinkDetector.extractUrls("nothing to see here"))
    }

    @Test
    fun `extracts a single http url`() {
        assertEquals(
            listOf("http://example.com/path"),
            LinkDetector.extractUrls("see http://example.com/path for details"),
        )
    }

    @Test
    fun `extracts a single https url`() {
        assertEquals(
            listOf("https://example.com"),
            LinkDetector.extractUrls("https://example.com"),
        )
    }

    @Test
    fun `extracts multiple urls in order`() {
        assertEquals(
            listOf("https://a.example", "http://b.example/x"),
            LinkDetector.extractUrls("first https://a.example then http://b.example/x"),
        )
    }

    @Test
    fun `trims trailing sentence punctuation`() {
        assertEquals(
            listOf("https://example.com/path"),
            LinkDetector.extractUrls("visit https://example.com/path."),
        )
        assertEquals(
            listOf("https://example.com"),
            LinkDetector.extractUrls("visit https://example.com, ok?"),
        )
    }

    @Test
    fun `preserves balanced parens inside url`() {
        assertEquals(
            listOf("https://en.wikipedia.org/wiki/Foo_(disambiguation)"),
            LinkDetector.extractUrls(
                "see https://en.wikipedia.org/wiki/Foo_(disambiguation) for details",
            ),
        )
    }

    @Test
    fun `trims a trailing close paren that is not balanced`() {
        assertEquals(
            listOf("https://example.com/path"),
            LinkDetector.extractUrls("(visit https://example.com/path)"),
        )
    }

    @Test
    fun `keeps query string and fragment`() {
        assertEquals(
            listOf("https://example.com/x?q=1&r=2#frag"),
            LinkDetector.extractUrls("link https://example.com/x?q=1&r=2#frag end"),
        )
    }

    @Test
    fun `does not match bare hostname without scheme`() {
        assertEquals(emptyList<String>(), LinkDetector.extractUrls("go to example.com please"))
    }

    @Test
    fun `does not match ftp or other schemes`() {
        assertEquals(emptyList<String>(), LinkDetector.extractUrls("ftp://example.com/file"))
    }

    @Test
    fun `stops at whitespace`() {
        assertEquals(
            listOf("https://example.com"),
            LinkDetector.extractUrls("https://example.com next word"),
        )
    }

    @Test
    fun `stops at quote characters`() {
        assertEquals(
            listOf("https://example.com"),
            LinkDetector.extractUrls("\"https://example.com\""),
        )
    }

    @Test
    fun `reports the span of each match`() {
        val text = "see https://a.example then http://b.example/x."
        assertEquals(
            listOf(
                LinkDetector.UrlMatch("https://a.example", 4, 21),
                LinkDetector.UrlMatch("http://b.example/x", 27, 45),
            ),
            LinkDetector.extractUrlMatches(text),
        )
    }

    @Test
    fun `span end excludes trimmed trailing punctuation`() {
        val match = LinkDetector.extractUrlMatches("visit https://example.com/path.").single()
        assertEquals("https://example.com/path", match.url)
        assertEquals(6, match.start)
        assertEquals("https://example.com/path".length + 6, match.endExclusive)
    }
}
