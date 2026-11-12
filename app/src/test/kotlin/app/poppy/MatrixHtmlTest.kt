package app.poppy

import androidx.compose.ui.graphics.Color
import app.poppy.ui.room.HtmlColors
import app.poppy.ui.room.MatrixHtml
import org.junit.Assert.assertEquals
import org.junit.Test

class MatrixHtmlTest {
    private val c = HtmlColors(Color.Blue, Color.Gray, Color.Gray, Color.Blue, Color.LightGray, Color.Red, Color.Yellow, Color.Black)

    private fun text(html: String) = MatrixHtml.render(html, c, "@me:example.org").text

    @Test
    fun paragraphsAndListsAreCompact() {
        val html = "<p><strong>Las Vegas GP — Sunday 22 November.</strong> 🏎️</p>\n<ul>\n<li><strong>Race start: 04:00</strong> (Sunday)</li>\n" +
            "<li>That's 20:00 Saturday</li>\n</ul>\n<p>Qualifying is the night before.</p>\n"
        assertEquals(
            "Las Vegas GP — Sunday 22 November. 🏎️\n• Race start: 04:00 (Sunday)\n• That's 20:00 Saturday\nQualifying is the night before.",
            text(html),
        )
    }

    @Test
    fun orderedListsNumberFromStart() {
        assertEquals("3. a\n4. b", text("<ol start=\"3\"><li>a</li><li>b</li></ol>"))
    }

    @Test
    fun replyFallbackIsDropped() {
        assertEquals("hello", text("<mx-reply><blockquote>quoted</blockquote></mx-reply>hello"))
    }

    @Test
    fun brAndEntities() {
        assertEquals("a & b\nc <d>", text("a &amp; b<br/>c &lt;d&gt;"))
    }

    @Test
    fun preKeepsWhitespace() {
        assertEquals("x\n  indented\ny", text("<p>x</p><pre><code>  indented</code></pre><p>y</p>"))
    }

    @Test
    fun mentionTargets() {
        assertEquals("@me:example.org", MatrixHtml.mentionTarget("https://matrix.to/#/%40me%3Aexample.org"))
        assertEquals("#room:x.org", MatrixHtml.mentionTarget("https://matrix.to/#/#room:x.org"))
        assertEquals(null, MatrixHtml.mentionTarget("https://example.org"))
    }
}
