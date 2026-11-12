package app.poppy.ui.room

import android.content.Intent
import android.net.Uri
import android.util.Patterns
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.poppy.core.location.LiveLocationService
import app.poppy.core.media.MediaRequest
import app.poppy.core.media.VoicePlayer
import app.poppy.core.media.downloadToCache
import app.poppy.core.timeline.GalleryItem
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
import org.matrix.rustcomponents.sdk.MediaSource

private val urlRegex = Patterns.WEB_URL.toRegex()

/** Compact message text: 16sp with a tighter line height than the M3 default. */
val MessageTextStyle = TextStyle(fontSize = 16.sp, lineHeight = 21.sp)

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
fun htmlColors(onBubble: Color): HtmlColors {
    val s = MaterialTheme.colorScheme
    return remember(s, onBubble) {
        HtmlColors(
            link = s.primary,
            codeBackground = onBubble.copy(alpha = 0.08f),
            quote = onBubble.copy(alpha = 0.72f),
            mention = s.primary,
            mentionBackground = s.primary.copy(alpha = 0.12f),
            mentionMe = s.onTertiaryContainer,
            mentionMeBackground = s.tertiaryContainer,
            spoiler = onBubble.copy(alpha = 0.6f),
        )
    }
}

/**
 * Message text. [trailing] reserves invisible space on the last line so the timestamp overlay
 * (drawn by the bubble) sits inline, chat-app style, instead of on its own row.
 */
@Composable
fun TextContent(body: MessageBody.Text, color: Color, linkColor: Color, ownUserId: String?, trailing: String) {
    val colors = htmlColors(color)
    val annotated = remember(body.plain, body.html, colors, trailing) {
        val base = body.html?.let { html ->
            runCatching { MatrixHtml.render(html, colors, ownUserId) }.getOrNull()
        } ?: linkify(body.plain, linkColor)
        if (trailing.isEmpty()) base else buildAnnotatedString {
            append(base)
            withStyle(SpanStyle(color = Color.Transparent, fontSize = 11.sp)) { append(trailing) }
        }
    }
    val big = body.emojiOnly && body.plain.length <= 24
    Text(
        annotated,
        color = if (body.kind == TextKind.NOTICE) color.copy(alpha = 0.8f) else color,
        style = if (big) MaterialTheme.typography.displaySmall else MessageTextStyle,
        fontStyle = if (body.kind == TextKind.EMOTE) FontStyle.Italic else FontStyle.Normal,
    )
}

@Composable
fun CaptionText(caption: String, color: Color, trailing: String) {
    TextContent(MessageBody.Text(caption, null, TextKind.NORMAL, false), color, MaterialTheme.colorScheme.primary, null, trailing)
}

@Composable
private fun MediaThumb(
    source: MediaSource,
    thumbnail: MediaSource?,
    width: Int,
    height: Int,
    maxWidth: androidx.compose.ui.unit.Dp,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    crop: Boolean = true,
) {
    val density = LocalDensity.current
    val ratio = if (width > 0 && height > 0) (width.toFloat() / height).coerceIn(0.5f, 2.2f) else 1.3f
    val req = remember(source, thumbnail) {
        val px = with(density) { maxWidth.roundToPx() }
        MediaRequest.thumbnail(thumbnail ?: source, px, (px / ratio).toInt())
    }
    AsyncImage(
        model = req,
        contentDescription = contentDescription,
        contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
        modifier = modifier.widthIn(max = maxWidth).fillMaxWidth().aspectRatio(ratio),
    )
}

@Composable
fun ImageContent(body: MessageBody.Image, onOpen: () -> Unit) {
    val maxW = if (body.isSticker) 160.dp else 260.dp
    MediaThumb(
        body.source, body.thumbnail, body.width, body.height, maxW, body.plain,
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (body.isSticker) Color.Transparent else MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onOpen),
    )
}

@Composable
private fun PlayBadge(busy: Boolean, size: androidx.compose.ui.unit.Dp = 48.dp) {
    Box(Modifier.size(size).background(Color.Black.copy(alpha = 0.55f), CircleShape), contentAlignment = Alignment.Center) {
        if (busy) CircularProgressIndicator(Modifier.size(size / 2), color = Color.White, strokeWidth = 2.dp)
        else Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(size * 0.6f))
    }
}

@Composable
private fun rememberOpener(): (MediaSource, String, String?) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember {
        { source, name, mime ->
            scope.launch {
                try {
                    val client = context.graph.matrix.currentClient() ?: return@launch
                    context.openFile(client.downloadToCache(context, source, name, mime), mime)
                } catch (t: Throwable) {
                    context.toast(errorMessage(t))
                }
            }
        }
    }
}

@Composable
fun VideoContent(body: MessageBody.Video) {
    val open = rememberOpener()
    var busy by remember { mutableStateOf(false) }
    Box(
        Modifier.widthIn(max = 260.dp).clip(RoundedCornerShape(14.dp)).background(Color.Black).clickable {
            busy = true
            open(body.source, body.filename, body.mimeType ?: "video/*")
            busy = false
        },
        contentAlignment = Alignment.Center,
    ) {
        if (body.thumbnail != null) {
            MediaThumb(body.source, body.thumbnail, body.width, body.height, 260.dp, body.plain)
        } else {
            Box(Modifier.width(260.dp).aspectRatio(16f / 9f))
        }
        PlayBadge(busy)
        if (body.durationMs > 0) {
            Text(
                formatDuration(body.durationMs),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** Up to 4 tiles in a 2-column grid; "+N" on the last tile when there are more. */
@Composable
fun GalleryContent(body: MessageBody.Gallery, onOpen: (GalleryItem) -> Unit) {
    val open = rememberOpener()
    val shown = body.items.take(4)
    val extra = body.items.size - shown.size
    Column(Modifier.width(264.dp).clip(RoundedCornerShape(14.dp)), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        shown.chunked(2).forEachIndexed { rowIdx, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                row.forEachIndexed { colIdx, item ->
                    val isLast = rowIdx * 2 + colIdx == shown.lastIndex
                    Box(
                        Modifier.weight(1f).aspectRatio(if (shown.size == 1) 1.3f else 1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { if (item.isVideo) open(item.source, item.filename, item.mimeType) else onOpen(item) },
                        contentAlignment = Alignment.Center,
                    ) {
                        MediaThumb(item.source, item.thumbnail, 1, 1, 132.dp, item.filename)
                        if (item.isVideo) PlayBadge(false, 36.dp)
                        if (isLast && extra > 0) {
                            Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                                Text("+$extra", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                            }
                        }
                    }
                }
                if (row.size == 1 && shown.size > 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun VoiceContent(key: String, body: MessageBody.Audio, color: Color, accent: Color) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by VoicePlayer.state.collectAsState()
    val active = state.key == key
    var busy by remember { mutableStateOf(false) }
    val progress = if (active && state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
    val bars = remember(body.waveform) {
        val src = body.waveform.ifEmpty { List(40) { 0.3f } }
        // Normalise to ~40 bars regardless of how many samples the sender included.
        List(40) { i -> src[(i * src.size / 40).coerceAtMost(src.lastIndex)].coerceIn(0.08f, 1f) }
    }
    fun toggle() {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val client = context.graph.matrix.currentClient() ?: return@launch
                val file = client.downloadToCache(context, body.source, body.filename, body.mimeType)
                VoicePlayer.toggle(key, file)
            } catch (t: Throwable) {
                context.toast(errorMessage(t))
            } finally {
                busy = false
            }
        }
    }
    Row(Modifier.widthIn(min = 220.dp, max = 280.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(accent).clickable(onClick = ::toggle),
            contentAlignment = Alignment.Center,
        ) {
            when {
                busy -> CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                active && state.playing -> Icon(Icons.Filled.Pause, "Pause", tint = Color.White)
                else -> Icon(Icons.Filled.PlayArrow, "Play", tint = Color.White)
            }
        }
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Canvas(
                Modifier.fillMaxWidth().height(28.dp).pointerInput(key) {
                    detectTapGestures { pos -> if (active) VoicePlayer.seek(key, pos.x / size.width) }
                },
            ) {
                val gap = 2.dp.toPx()
                val w = (size.width - gap * (bars.size - 1)) / bars.size
                bars.forEachIndexed { i, v ->
                    val h = (size.height * v).coerceAtLeast(3.dp.toPx())
                    val played = (i + 0.5f) / bars.size <= progress
                    drawRoundRect(
                        color = if (played) accent else color.copy(alpha = 0.35f),
                        topLeft = Offset(i * (w + gap), (size.height - h) / 2),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(w / 2, w / 2),
                    )
                }
            }
            Text(
                formatDuration(if (active && state.positionMs > 0) state.positionMs else body.durationMs).ifEmpty { "0:00" },
                color = color.copy(alpha = 0.7f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
fun FileContent(name: String, subtitle: String, isAudio: Boolean, source: MediaSource, mime: String?, color: Color) {
    val open = rememberOpener()
    Row(
        Modifier
            .widthIn(min = 180.dp, max = 280.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { open(source, name, mime) }
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).background(color.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (isAudio) Icons.Filled.AudioFile else Icons.Filled.Description, null, tint = color)
        }
        Column(Modifier.padding(start = 10.dp)) {
            Text(name, color = color, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, color = color.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private val geoRegex = Regex("geo:(-?[0-9.]+),(-?[0-9.]+)")

@Composable
fun LocationContent(body: MessageBody.Location, color: Color, isOwn: Boolean, roomId: String) {
    val context = LocalContext.current
    val now = remember(body.updatedAt) { System.currentTimeMillis() }
    val live = body.isLive && body.liveUntil > now
    val sharing by LiveLocationService.sharing.collectAsState()
    val coords = geoRegex.find(body.geoUri)?.let { "%.5f, %.5f".format(it.groupValues[1].toDouble(), it.groupValues[2].toDouble()) }
    Column(Modifier.widthIn(min = 220.dp, max = 280.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = body.geoUri.isNotEmpty()) {
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(body.geoUri.substringBefore(';')))) }
                    .onFailure { context.toast("No maps app installed") }
            }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(44.dp).background((if (live) MaterialTheme.colorScheme.primary else color).copy(alpha = 0.14f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(if (live) Icons.Filled.MyLocation else Icons.Filled.Place, null, tint = if (live) MaterialTheme.colorScheme.primary else color)
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text(
                    when {
                        live -> "Live location"
                        body.isLive -> "Live location ended"
                        else -> "Location"
                    },
                    color = color,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    buildString {
                        append(coords ?: "Waiting for first update…")
                        if (live) append(" · until ").append(android.text.format.DateFormat.getTimeFormat(context).format(body.liveUntil))
                    },
                    color = color.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        if (live && isOwn && roomId in sharing) {
            TextButton(onClick = { LiveLocationService.stop(context) }) { Text("Stop sharing") }
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
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = !body.ended) { onVote(a.id) }
                    .padding(vertical = 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (a.mine) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
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
            if (body.ended) "Final results · ${body.totalVotes} votes" else "${body.totalVotes} votes",
            color = color.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 6.dp),
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
            modifier = Modifier.size(15.dp),
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
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.07f))
            .clickable(onClick = onClick)
            .heightIn(min = 32.dp),
    ) {
        Box(Modifier.width(3.dp).height(38.dp).background(accent))
        Column(Modifier.padding(horizontal = 8.dp, vertical = 3.dp)) {
            Text(reply.senderName ?: "Reply", color = accent, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(reply.text ?: "…", color = color.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Whether the body should be drawn without a bubble background (media without caption, jumbo emoji). */
fun MessageBody.isBubbleless(hasReply: Boolean): Boolean = when (this) {
    is MessageBody.Image -> isSticker || (caption == null && !hasReply)
    is MessageBody.Video -> caption == null && !hasReply
    is MessageBody.Gallery -> caption == null && !hasReply
    is MessageBody.Text -> emojiOnly && plain.length <= 24
    else -> false
}

/** Whether the inline timestamp should be reserved at the end of text (vs. drawn on its own row). */
fun MessageBody.hasInlineMeta(): Boolean = when (this) {
    is MessageBody.Text -> !(emojiOnly && plain.length <= 24)
    is MessageBody.Image -> caption != null
    is MessageBody.Video -> caption != null
    is MessageBody.Gallery -> caption != null
    is MessageBody.File -> caption != null
    is MessageBody.Audio -> caption != null
    else -> false
}

@Composable
fun MessageBodyView(
    entryKey: String,
    body: MessageBody,
    color: Color,
    linkColor: Color,
    accent: Color,
    ownUserId: String,
    roomId: String,
    isOwn: Boolean,
    trailing: String,
    onOpenImage: (MediaSource, String, String?) -> Unit,
    onVote: (String) -> Unit,
    onEndPoll: () -> Unit,
) {
    when (body) {
        is MessageBody.Text -> TextContent(body, color, linkColor, ownUserId, trailing)
        is MessageBody.Image -> Column {
            ImageContent(body) { onOpenImage(body.source, body.filename, body.mimeType) }
            body.caption?.let { Box(Modifier.padding(top = 6.dp)) { CaptionText(it, color, trailing) } }
        }
        is MessageBody.Video -> Column {
            VideoContent(body)
            body.caption?.let { Box(Modifier.padding(top = 6.dp)) { CaptionText(it, color, trailing) } }
        }
        is MessageBody.Gallery -> Column {
            GalleryContent(body) { onOpenImage(it.source, it.filename, it.mimeType) }
            body.caption?.let { Box(Modifier.padding(top = 6.dp)) { CaptionText(it, color, trailing) } }
        }
        is MessageBody.Audio -> Column {
            if (body.isVoice) VoiceContent(entryKey, body, color, accent)
            else FileContent(body.filename, formatDuration(body.durationMs), true, body.source, body.mimeType, color)
            body.caption?.let { Box(Modifier.padding(top = 4.dp)) { CaptionText(it, color, trailing) } }
        }
        is MessageBody.File -> Column {
            FileContent(body.filename, formatBytes(body.size), false, body.source, body.mimeType, color)
            body.caption?.let { Box(Modifier.padding(top = 4.dp)) { CaptionText(it, color, trailing) } }
        }
        is MessageBody.Location -> LocationContent(body, color, isOwn, roomId)
        is MessageBody.Poll -> PollContent(body, color, onVote, isOwn, onEndPoll)
        is MessageBody.Redacted, is MessageBody.Encrypted -> PlaceholderContent(body, color)
        is MessageBody.Unsupported -> Text(body.plain, color = color.copy(alpha = 0.7f), fontSize = 14.sp)
    }
}
