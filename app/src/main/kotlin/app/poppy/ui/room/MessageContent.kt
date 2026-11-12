package app.poppy.ui.room

import android.content.Intent
import android.net.Uri
import android.util.Patterns
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Poll
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.poppy.core.media.MediaRequest
import app.poppy.core.media.downloadToCache
import app.poppy.core.timeline.MessageBody
import app.poppy.core.timeline.ReplyPreview
import app.poppy.core.timeline.TextKind
import app.poppy.graph
import app.poppy.util.errorMessage
import app.poppy.util.formatBytes
import app.poppy.util.formatDuration
import app.poppy.util.openFile
import app.poppy.util.toast
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

private val urlRegex = Patterns.WEB_URL.toRegex()

/** Plain text → AnnotatedString with tappable links. */
fun linkify(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val styles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    urlRegex.findAll(text).forEach { m ->
        val raw = m.value
        if (!raw.contains('.')) return@forEach
        val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
        addLink(LinkAnnotation.Url(url, styles), m.range.first, m.range.last + 1)
    }
}

@Composable
fun TextContent(body: MessageBody.Text, color: Color, linkColor: Color) {
    val annotated = remember(body.plain, body.html, linkColor) {
        val html = body.html
        if (html != null) {
            runCatching {
                AnnotatedString.fromHtml(
                    html.replace("\n", "<br>").replace("<br><br>", "<br>"),
                    linkStyles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                )
            }.getOrElse { linkify(body.plain, linkColor) }
        } else {
            linkify(body.plain, linkColor)
        }
    }
    val style = when {
        body.emojiOnly && body.plain.length <= 24 -> MaterialTheme.typography.displaySmall
        else -> MaterialTheme.typography.bodyLarge
    }
    Text(
        annotated,
        color = if (body.kind == TextKind.NOTICE) color.copy(alpha = 0.8f) else color,
        style = style,
        fontStyle = if (body.kind == TextKind.EMOTE) FontStyle.Italic else FontStyle.Normal,
    )
}

@Composable
fun ImageContent(body: MessageBody.Image, onOpen: () -> Unit) {
    val density = LocalDensity.current
    val maxW = if (body.isSticker) 160.dp else 260.dp
    val ratio = if (body.width > 0 && body.height > 0) (body.width.toFloat() / body.height).coerceIn(0.5f, 2.2f) else 1.3f
    val req = remember(body.source, body.thumbnail) {
        val px = with(density) { maxW.roundToPx() }
        MediaRequest.thumbnail(body.thumbnail ?: body.source, px, (px / ratio).toInt())
    }
    AsyncImage(
        model = req,
        contentDescription = body.plain,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .widthIn(max = maxW)
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(14.dp))
            .background(if (body.isSticker) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen),
    )
}

@Composable
fun VideoContent(body: MessageBody.Video) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val ratio = if (body.width > 0 && body.height > 0) (body.width.toFloat() / body.height).coerceIn(0.5f, 2.2f) else 16f / 9f
    Box(
        Modifier
            .widthIn(max = 260.dp)
            .fillMaxWidth()
            .aspectRatio(ratio)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black)
            .clickable(enabled = !busy) {
                busy = true
                scope.launch {
                    try {
                        val client = context.graph.matrix.currentClient() ?: return@launch
                        context.openFile(client.downloadToCache(context, body.source, body.filename, body.mimeType), body.mimeType ?: "video/*")
                    } catch (t: Throwable) {
                        context.toast(errorMessage(t))
                    } finally {
                        busy = false
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        body.thumbnail?.let { thumb ->
            val px = with(density) { 260.dp.roundToPx() }
            AsyncImage(MediaRequest.thumbnail(thumb, px, (px / ratio).toInt()), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        Box(Modifier.size(52.dp).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(26.dp), color = Color.White, strokeWidth = 2.dp)
            else Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(32.dp))
        }
        if (body.durationMs > 0) {
            Text(
                formatDuration(body.durationMs),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun FileContent(name: String, subtitle: String, isAudio: Boolean, isVoice: Boolean, source: org.matrix.rustcomponents.sdk.MediaSource, mime: String?, color: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Row(
        Modifier
            .widthIn(min = 180.dp, max = 280.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !busy) {
                busy = true
                scope.launch {
                    try {
                        val client = context.graph.matrix.currentClient() ?: return@launch
                        context.openFile(client.downloadToCache(context, source, name, mime), mime)
                    } catch (t: Throwable) {
                        context.toast(errorMessage(t))
                    } finally {
                        busy = false
                    }
                }
            }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(42.dp).background(color.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = color)
            else Icon(
                when {
                    isVoice -> Icons.Filled.Mic
                    isAudio -> Icons.Filled.AudioFile
                    else -> Icons.Filled.Description
                },
                null,
                tint = color,
            )
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(if (isVoice) "Voice message" else name, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, color = color.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun LocationContent(body: MessageBody.Location, color: Color) {
    val context = LocalContext.current
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable {
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(body.geoUri))) }
                .onFailure { context.toast("No maps app installed") }
        }.padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Place, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
        Column(Modifier.padding(start = 8.dp)) {
            Text("Location", color = color, fontWeight = FontWeight.Medium)
            Text(body.plain.removePrefix("Location was shared at "), color = color.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
    }
}

@Composable
fun PollContent(body: MessageBody.Poll, color: Color, onVote: (String) -> Unit, canEnd: Boolean, onEnd: () -> Unit) {
    Column(Modifier.widthIn(min = 220.dp, max = 300.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Poll, null, tint = color, modifier = Modifier.size(18.dp))
            Text(body.question, color = color, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
        }
        val showResults = body.ended || body.disclosed || body.answers.any { it.mine }
        body.answers.forEach { a ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !body.ended) { onVote(a.id) }
                    .padding(vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (a.mine) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp).padding(end = 2.dp))
                    Text(a.text, color = color, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    if (showResults) Text(a.votes.toString(), color = color.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
                }
                if (showResults) {
                    LinearProgressIndicator(
                        progress = { if (body.totalVotes == 0) 0f else a.votes.toFloat() / body.totalVotes },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                    )
                }
            }
        }
        Text(
            when {
                body.ended -> "Final results · ${body.totalVotes} votes"
                else -> "${body.totalVotes} votes"
            },
            color = color.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (canEnd && !body.ended) TextButton(onClick = onEnd) { Text("End poll") }
    }
}

@Composable
fun PlaceholderContent(body: MessageBody, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (body is MessageBody.Encrypted) Icons.Filled.Lock else Icons.Outlined.DeleteOutline,
            null,
            tint = color.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp),
        )
        Text(
            body.plain,
            color = color.copy(alpha = 0.6f),
            fontStyle = FontStyle.Italic,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
fun ReplyQuote(reply: ReplyPreview, color: Color, accent: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .heightIn(min = 36.dp),
    ) {
        Box(Modifier.width(3.dp).height(40.dp).background(accent))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Text(reply.senderName ?: "Reply", color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(reply.text ?: "…", color = color.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun MessageBodyView(
    body: MessageBody,
    color: Color,
    linkColor: Color,
    onOpenImage: (MessageBody.Image) -> Unit,
    onVote: (String) -> Unit,
    canEndPoll: Boolean,
    onEndPoll: () -> Unit,
) {
    when (body) {
        is MessageBody.Text -> TextContent(body, color, linkColor)
        is MessageBody.Image -> ImageContent(body) { onOpenImage(body) }
        is MessageBody.Video -> VideoContent(body)
        is MessageBody.Audio -> FileContent(body.filename, formatDuration(body.durationMs), true, body.isVoice, body.source, body.mimeType, color)
        is MessageBody.File -> FileContent(body.filename, formatBytes(body.size), false, false, body.source, body.mimeType, color)
        is MessageBody.Location -> LocationContent(body, color)
        is MessageBody.Poll -> PollContent(body, color, onVote, canEndPoll, onEndPoll)
        is MessageBody.Redacted, is MessageBody.Encrypted -> PlaceholderContent(body, color)
        is MessageBody.Unsupported -> Text(body.plain, color = color.copy(alpha = 0.7f), fontSize = 14.sp)
    }
}
