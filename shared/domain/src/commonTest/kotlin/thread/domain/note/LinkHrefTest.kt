package thread.domain.note

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkHrefTest {
    @Test
    fun allowsHttpHttpsAndMailto() {
        assertEquals("https://example.com/path?q=1#x", LinkHref.parse("https://example.com/path?q=1#x")?.value)
        assertEquals("http://localhost:8080", LinkHref.parse("http://localhost:8080")?.value)
        assertEquals("mailto:someone@example.com", LinkHref.parse("mailto:someone@example.com")?.value)
        assertEquals("HTTPS://Example.com", LinkHref.parse("HTTPS://Example.com")?.value)
    }

    @Test
    fun trimsSurroundingSpace() {
        assertEquals("https://example.com", LinkHref.parse("  https://example.com \n")?.value)
    }

    @Test
    fun refusesOtherSchemesAndBrokenAddresses() {
        listOf(
            "javascript:alert(1)",
            "JavaScript:alert(1)",
            "data:text/html,hi",
            "vbscript:x",
            "file:///etc/hosts",
            "ftp://example.com",
            "example.com",
            "www.example.com",
            "/relative/path",
            "#anchor",
            "",
            "https://",
            "mailto:",
            "https://exa mple.com",
            "https://example.com/\u0000",
            "java\nscript:alert(1)",
        ).forEach { assertNull(LinkHref.parse(it), "Expected \"$it\" to be refused") }
    }
}
