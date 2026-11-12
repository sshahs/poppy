package app.poppy.ui.room

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.poppy.core.media.VoiceRecorder
import app.poppy.core.timeline.MessageEntry
import app.poppy.ui.components.Avatar
import app.poppy.util.formatDuration

@Immutable
data class MemberLite(val userId: String, val name: String, val avatar: String?)

private val mentionQuery = Regex("(?:^|\\s)@([^\\s@]{0,40})$")

/** The "@query" being typed right before the cursor, if any. */
fun mentionQueryAt(value: TextFieldValue): String? {
    if (!value.selection.collapsed) return null
    val before = value.text.substring(0, value.selection.start.coerceIn(0, value.text.length))
    return mentionQuery.find(before)?.groupValues?.get(1)
}

/** Replaces the "@query" before the cursor with "@Name " and returns the new value. */
fun insertMention(value: TextFieldValue, label: String): TextFieldValue {
    val cursor = value.selection.start
    val before = value.text.substring(0, cursor)
    val at = before.lastIndexOf('@')
    if (at < 0) return value
    val inserted = "$label "
    val text = value.text.substring(0, at) + inserted + value.text.substring(cursor)
    return TextFieldValue(text, TextRange(at + inserted.length))
}

@Composable
fun MentionSuggestions(query: String, members: List<MemberLite>, canMentionRoom: Boolean, onPick: (MemberLite?) -> Unit) {
    val q = query.lowercase()
    val matches = remember(query, members) {
        members.asSequence()
            .filter { q.isEmpty() || it.name.lowercase().contains(q) || it.userId.lowercase().contains(q) }
            .sortedBy { if (it.name.lowercase().startsWith(q)) 0 else 1 }
            .take(6)
            .toList()
    }
    val showRoom = canMentionRoom && "room".startsWith(q)
    if (matches.isEmpty() && !showRoom) return
    Surface(tonalElevation = 3.dp, shadowElevation = 2.dp, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
        LazyColumn(Modifier.heightIn(max = 260.dp)) {
            if (showRoom) item {
                ListItem(
                    headlineContent = { Text("@room") },
                    supportingContent = { Text("Notify everyone in this room") },
                    leadingContent = { Icon(Icons.Filled.Groups, null) },
                    modifier = Modifier.clickable { onPick(null) },
                )
            }
            items(matches, key = { it.userId }) { m ->
                ListItem(
                    headlineContent = { Text(m.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(m.userId, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = { Avatar(m.avatar, m.name, m.userId, 32.dp) },
                    modifier = Modifier.clickable { onPick(m) },
                )
            }
        }
    }
}

@Composable
fun Composer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    replyTo: MessageEntry?,
    editing: MessageEntry?,
    editingCaption: Boolean,
    recorder: VoiceRecorder,
    recording: Boolean,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    onStartRecording: () -> Unit,
    onStopRecording: (send: Boolean) -> Unit,
    onPickMedia: () -> Unit,
    onPickFile: () -> Unit,
    onPoll: () -> Unit,
    onLocation: () -> Unit,
    suggestions: @Composable () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.navigationBarsPadding().imePadding()) {
        suggestions()
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
            Column {
                val ctx = editing ?: replyTo
                AnimatedVisibility(ctx != null) {
                    if (ctx != null) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (editing != null) Icons.Filled.Edit else Icons.AutoMirrored.Filled.Reply, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                                Text(
                                    when {
                                        editingCaption -> "Editing caption"
                                        editing != null -> "Editing"
                                        else -> "Replying to ${ctx.senderName}"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text(ctx.body.plain, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Cancel") }
                        }
                    }
                }
                if (recording) {
                    RecordingBar(recorder, onStopRecording)
                } else {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.AddCircleOutline, "Attach") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Photos & videos") }, leadingIcon = { Icon(Icons.Filled.Photo, null) }, onClick = { menu = false; onPickMedia() })
                                DropdownMenuItem(text = { Text("File") }, leadingIcon = { Icon(Icons.Filled.AttachFile, null) }, onClick = { menu = false; onPickFile() })
                                DropdownMenuItem(text = { Text("Location") }, leadingIcon = { Icon(Icons.Filled.Place, null) }, onClick = { menu = false; onLocation() })
                                DropdownMenuItem(text = { Text("Poll") }, leadingIcon = { Icon(Icons.Outlined.Poll, null) }, onClick = { menu = false; onPoll() })
                            }
                        }
                        TextField(
                            value = value,
                            onValueChange = onValueChange,
                            placeholder = { Text(if (editingCaption) "Caption" else "Message") },
                            maxLines = 6,
                            shape = RoundedCornerShape(24.dp),
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                            ),
                            textStyle = MessageTextStyle,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                            modifier = Modifier.weight(1f),
                        )
                        val canSend = value.text.isNotBlank() || editingCaption
                        val showMic = !canSend && editing == null
                        IconButton(
                            onClick = { if (showMic) onStartRecording() else onSend() },
                            modifier = Modifier.padding(start = 4.dp).size(48.dp).clip(CircleShape)
                                .background(if (canSend) MaterialTheme.colorScheme.primary else Color.Transparent),
                        ) {
                            Icon(
                                when {
                                    showMic -> Icons.Filled.Mic
                                    editing != null -> Icons.Filled.Done
                                    else -> Icons.AutoMirrored.Filled.Send
                                },
                                if (showMic) "Record voice message" else "Send",
                                tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordingBar(recorder: VoiceRecorder, onStop: (Boolean) -> Unit) {
    val levels by recorder.level.collectAsState()
    val elapsed by recorder.elapsed.collectAsState()
    val accent = MaterialTheme.colorScheme.primary
    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { onStop(false) }) { Icon(Icons.Filled.Delete, "Discard", tint = MaterialTheme.colorScheme.error) }
        Box(Modifier.size(10.dp).background(MaterialTheme.colorScheme.error, CircleShape))
        Text(formatDuration(elapsed).ifEmpty { "0:00" }, modifier = Modifier.padding(horizontal = 8.dp), style = MaterialTheme.typography.labelLarge)
        Canvas(Modifier.weight(1f).height(32.dp)) {
            val n = 48
            val gap = 2.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            val padded = List(n - levels.size) { 0f } + levels
            padded.forEachIndexed { i, v ->
                val h = (size.height * v).coerceAtLeast(3.dp.toPx())
                drawRoundRect(accent, Offset(i * (w + gap), (size.height - h) / 2), Size(w, h), CornerRadius(w / 2, w / 2))
            }
        }
        IconButton(
            onClick = { onStop(true) },
            modifier = Modifier.padding(start = 4.dp).size(48.dp).clip(CircleShape).background(accent),
        ) { Icon(Icons.AutoMirrored.Filled.Send, "Send voice message", tint = MaterialTheme.colorScheme.onPrimary) }
    }
}
