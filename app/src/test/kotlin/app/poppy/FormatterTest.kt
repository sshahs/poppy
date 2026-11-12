package app.poppy

import app.poppy.core.Formatter
import app.poppy.util.formatBytes
import app.poppy.util.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatterTest {
    @Test
    fun stripsPlainReplyFallback() {
        val body = "> <@alice:example.org> hello there\n> second line\n\nmy reply"
        assertEquals("my reply", Formatter.stripReplyFallback(body))
    }

    @Test
    fun leavesNormalQuotesWithoutFallbackAlone() {
        assertEquals("no quote here", Formatter.stripReplyFallback("no quote here"))
    }

    @Test
    fun stripsHtmlReplyFallback() {
        val html = "<mx-reply><blockquote>quoted <b>text</b></blockquote></mx-reply>actual <i>reply</i>"
        assertEquals("actual <i>reply</i>", Formatter.stripHtmlReplyFallback(html))
    }

    @Test
    fun formatsBytes() {
        assertEquals("", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("2.0 MB", formatBytes(2L * 1024 * 1024))
    }

    @Test
    fun formatsDurations() {
        assertEquals("0:05", formatDuration(5_000))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("1:01:01", formatDuration(3_661_000))
    }
}
