package app.poppy.ui.room

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em

data class HtmlColors(
    val link: Color,
    val codeBackground: Color,
    val quote: Color,
    val mention: Color,
    val mentionBackground: Color,
    val mentionMe: Color,
    val mentionMeBackground: Color,
    val spoiler: Color,
)

/**
 * A small, fast renderer for the HTML subset Matrix allows in `formatted_body`
 * (https://spec.matrix.org/latest/client-server-api/#mroommessage-msgtypes).
 *
 * Unlike a generic HTML renderer it keeps output compact: paragraphs are separated by a single
 * line break, inter-tag whitespace is ignored, lists get bullets/numbers and user/room links
 * become mention pills.
 */
object MatrixHtml {
    private val token = Regex("<!--.*?-->|<(/?)([a-zA-Z][a-zA-Z0-9-]*)([^>]*?)(/?)>|([^<]+)|<", RegexOption.DOT_MATCHES_ALL)
    private val attr = Regex("([a-zA-Z-]+)\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))")
    private val whitespace = Regex("\\s+")
    private val blockTags = setOf("p", "div", "ul", "ol", "li", "blockquote", "pre", "h1", "h2", "h3", "h4", "h5", "h6", "hr", "table", "tr", "details", "summary")
    private val voidTags = setOf("br", "hr", "img")

    private class Open(val tag: String, val pushed: Int, val listIndex: IntArray? = null)

    fun render(html: String, c: HtmlColors, ownUserId: String?): AnnotatedString = buildAnnotatedString {
        val stack = ArrayList<Open>()
        var pre = 0
        var skip = 0 // inside <mx-reply>
        var quoteDepth = 0
        val lists = ArrayList<IntArray>() // [-1] = unordered, else next number

        var last = '\n'
        fun put(t: String) {
            if (t.isEmpty()) return
            append(t)
            last = t[t.length - 1]
        }
        fun atLineStart() = last == '\n'
        fun newline() {
            if (length > 0 && !atLineStart()) put("\n")
        }

        for (m in token.findAll(html)) {
            val text = m.groups[5]?.value
            if (text != null) {
                if (skip > 0) continue
                var t = decode(text)
                if (pre == 0) {
                    t = t.replace(whitespace, " ")
                    if (atLineStart()) t = t.trimStart()
                    if (t.isEmpty()) continue
                }
                if (quoteDepth > 0 && atLineStart()) put("▎ ")
                put(t)
                continue
            }
            val name = m.groups[2]?.value?.lowercase() ?: continue
            val closing = m.groups[1]?.value == "/"
            val selfClosing = m.groups[4]?.value == "/" || name in voidTags
            val attrs = m.groups[3]?.value.orEmpty()

            if (name == "mx-reply") {
                if (closing) skip = (skip - 1).coerceAtLeast(0) else skip++
                continue
            }
            if (skip > 0) continue

            if (closing) {
                val idx = stack.indexOfLast { it.tag == name }
                if (idx < 0) continue
                while (stack.size > idx) {
                    val o = stack.removeAt(stack.lastIndex)
                    repeat(o.pushed) { pop() }
                    when (o.tag) {
                        "pre" -> pre--
                        "blockquote" -> quoteDepth--
                        "ul", "ol" -> if (lists.isNotEmpty()) lists.removeAt(lists.lastIndex)
                    }
                    if (o.tag in blockTags) newline()
                }
                continue
            }

            if (name == "br") {
                put("\n")
                continue
            }
            if (name == "hr") {
                newline()
                put("───────────")
                put("\n")
                continue
            }
            if (name == "img") {
                // Custom emoji / inline images: show the alt text (e.g. ":blobcat:").
                parseAttrs(attrs)["alt"]?.let { put(it) }
                continue
            }
            if (name in blockTags) newline()

            var pushed = 0
            fun style(s: SpanStyle) {
                pushStyle(s)
                pushed++
            }
            when (name) {
                "b", "strong" -> style(SpanStyle(fontWeight = FontWeight.Bold))
                "i", "em" -> style(SpanStyle(fontStyle = FontStyle.Italic))
                "u" -> style(SpanStyle(textDecoration = TextDecoration.Underline))
                "s", "del", "strike" -> style(SpanStyle(textDecoration = TextDecoration.LineThrough))
                "sup" -> style(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.8.em))
                "sub" -> style(SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.8.em))
                "code" -> style(SpanStyle(fontFamily = FontFamily.Monospace, background = c.codeBackground, fontSize = 0.92.em))
                "pre" -> {
                    pre++
                    style(SpanStyle(fontFamily = FontFamily.Monospace, background = c.codeBackground, fontSize = 0.9.em))
                }
                "h1", "h2" -> style(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.2.em))
                "h3", "h4", "h5", "h6" -> style(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 1.08.em))
                "blockquote" -> {
                    quoteDepth++
                    style(SpanStyle(color = c.quote))
                }
                "ul" -> lists += intArrayOf(-1)
                "ol" -> lists += intArrayOf(parseAttrs(attrs)["start"]?.toIntOrNull() ?: 1)
                "li" -> {
                    val list = lists.lastOrNull()
                    val indent = "  ".repeat((lists.size - 1).coerceAtLeast(0))
                    if (list == null || list[0] < 0) {
                        put("$indent• ")
                    } else {
                        put("$indent${list[0]}. ")
                        list[0]++
                    }
                }
                "a" -> {
                    val href = parseAttrs(attrs)["href"].orEmpty()
                    val target = mentionTarget(href)
                    if (target != null) {
                        val me = ownUserId != null && target == ownUserId
                        style(
                            SpanStyle(
                                color = if (me) c.mentionMe else c.mention,
                                background = if (me) c.mentionMeBackground else c.mentionBackground,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                        pushLink(LinkAnnotation.Url(href))
                        pushed++
                    } else if (href.isNotBlank()) {
                        pushLink(LinkAnnotation.Url(href, TextLinkStyles(SpanStyle(color = c.link, textDecoration = TextDecoration.Underline))))
                        pushed++
                    }
                }
                "font", "span" -> {
                    val a = parseAttrs(attrs)
                    if (a.containsKey("data-mx-spoiler")) {
                        style(SpanStyle(color = c.spoiler, background = c.spoiler))
                    } else {
                        parseColor(a["data-mx-color"] ?: a["color"])?.let { style(SpanStyle(color = it)) }
                        parseColor(a["data-mx-bg-color"])?.let { style(SpanStyle(background = it)) }
                    }
                }
            }
            if (!selfClosing) {
                stack += Open(name, pushed)
            } else {
                repeat(pushed) { pop() }
            }
        }
        // Close anything left open.
        for (o in stack.asReversed()) repeat(o.pushed) { pop() }
    }.trimEndNewlines()

    /** Returns the Matrix ID a matrix.to / matrix: link points at, if any. */
    fun mentionTarget(href: String): String? {
        val frag = when {
            href.startsWith("https://matrix.to/#/") -> href.removePrefix("https://matrix.to/#/")
            href.startsWith("matrix:u/") -> "@" + href.removePrefix("matrix:u/")
            else -> return null
        }
        val id = java.net.URLDecoder.decode(frag.substringBefore('?').substringBefore('/'), "UTF-8")
        return id.takeIf { it.startsWith("@") || it.startsWith("#") || it.startsWith("!") }
    }

    private fun AnnotatedString.trimEndNewlines(): AnnotatedString {
        var end = length
        while (end > 0 && (text[end - 1] == '\n' || text[end - 1] == ' ')) end--
        return if (end == length) this else subSequence(0, end)
    }

    private fun parseAttrs(s: String): Map<String, String> =
        if (s.isBlank()) emptyMap() else attr.findAll(s).associate { m ->
            m.groupValues[1].lowercase() to decode(m.groups[3]?.value ?: m.groups[4]?.value ?: m.groups[5]?.value ?: "")
        }

    private fun parseColor(v: String?): Color? {
        val hex = v?.trim()?.removePrefix("#") ?: return null
        return runCatching {
            when (hex.length) {
                6 -> Color(0xFF000000 or hex.toLong(16))
                3 -> Color(0xFF000000 or hex.map { "$it$it" }.joinToString("").toLong(16))
                else -> null
            }
        }.getOrNull()
    }

    private val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0", "mdash" to "—", "ndash" to "–", "hellip" to "…")

    fun decode(s: String): String {
        if ('&' !in s) return s
        return entity.replace(s) { m ->
            val e = m.groupValues[1]
            when {
                e.startsWith("#x") || e.startsWith("#X") -> e.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
                e.startsWith("#") -> e.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
                else -> named[e] ?: m.value
            }
        }
    }
}
