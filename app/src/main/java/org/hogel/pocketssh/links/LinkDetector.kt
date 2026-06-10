package org.hogel.pocketssh.links

/**
 * Extracts http(s) URLs from a text blob.
 *
 * Trailing punctuation commonly attached to a URL in prose (`.`, `,`, `)`,
 * `]`, `>`, `;`, `:`, `!`, `?`, `"`, `'`) is stripped because it almost never
 * belongs to the URL itself when the URL appears mid-sentence. A trailing
 * `)` is preserved when there is an unmatched `(` earlier in the URL, so
 * Wikipedia-style URLs like `Foo_(disambiguation)` round-trip.
 */
object LinkDetector {

    private val URL_PATTERN = Regex("""https?://[^\s<>"'`\\\[\]{}|^]+""")

    private const val TRAILING_PUNCT = ".,;:!?\"'>"

    /** A detected URL together with its half-open span in the source text. */
    data class UrlMatch(val url: String, val start: Int, val endExclusive: Int)

    fun extractUrls(text: String): List<String> =
        extractUrlMatches(text).map { it.url }

    fun extractUrlMatches(text: String): List<UrlMatch> =
        URL_PATTERN.findAll(text)
            .mapNotNull { match ->
                val url = trimTrailingPunctuation(match.value)
                if (url.isEmpty()) null
                else UrlMatch(url, match.range.first, match.range.first + url.length)
            }
            .toList()

    private fun trimTrailingPunctuation(raw: String): String {
        var end = raw.length
        while (end > 0) {
            val ch = raw[end - 1]
            when {
                ch in TRAILING_PUNCT -> end--
                ch == ')' && raw.substring(0, end).count { it == '(' } <
                    raw.substring(0, end).count { it == ')' } -> end--
                else -> return raw.substring(0, end)
            }
        }
        return ""
    }
}
