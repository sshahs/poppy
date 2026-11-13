package app.poppy.ui.room

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.poppy.core.timeline.MessageBody
import app.poppy.core.timeline.MessageEntry
import app.poppy.core.timeline.SendStatus
import app.poppy.core.timeline.mediaCaption
import app.poppy.util.PickedFile
import app.poppy.util.formatBytes
import coil3.compose.AsyncImage

val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🎉", "🐶")

private val MORE_EMOJI = (
    "😀 😃 😄 😁 😆 😅 🤣 😊 😇 🙂 😉 😌 😍 🥰 😘 " +
        "😋 😜 🤪 🤓 😎 🤩 🥳 😏 😒 😔 😟 😕 🙁 😣 😫 " +
        "😩 🥺 😭 😤 😠 😡 🤯 😳 🥵 🥶 😱 😨 🤔 🤫 🤥 " +
        "😶 😐 🙄 😴 🤤 🤒 🤕 🤢 🤧 😷 👋 ✋ 👌 ✌️ 🤞 " +
        "👏 🙌 🙏 💪 👀 🔥 ✨ ⭐ 🌟 💯 ✅ ❌ ❗ ❓ 💤 " +
        "🐕 🐾 🦴 🌺 🌹 🌻 🎂 🍺 ☕ 🍕 🚀 🏆 🎵 💡 💀"
    ).split(' ')

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MessageActionsSheet(
    entry: MessageEntry,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onEditCaption: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    isPinned: Boolean,
    onPin: () -> Unit,
    onRedact: () -> Unit,
    onRetry: () -> Unit,
    onCancelSend: () -> Unit,
    onCopyLink: () -> Unit,
) {
    var more by remember { mutableStateOf(false) }
    fun act(block: () -> Unit) {
        block()
        onDismiss()
    }
    val isContent = entry.body !is MessageBody.Redacted && entry.body !is MessageBody.Encrypted
    val isMedia = entry.body is MessageBody.Image || entry.body is MessageBody.Video || entry.body is MessageBody.File ||
        entry.body is MessageBody.Audio || entry.body is MessageBody.Gallery
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (isContent && entry.status != SendStatus.FAILED) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                QUICK_REACTIONS.forEach { emoji ->
                    Text(emoji, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clip(CircleShape).clickable { act { onReact(emoji) } }.padding(8.dp))
                }
                Text("+", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clip(CircleShape).clickable { more = !more }.padding(8.dp))
            }
            if (more) {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                    MORE_EMOJI.forEach { emoji ->
                        Text(emoji, style = MaterialTheme.typography.titleLarge, modifier = Modifier.clip(CircleShape).clickable { act { onReact(emoji) } }.padding(8.dp))
                    }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
        }
        if (entry.status == SendStatus.FAILED) {
            ActionItem("Retry sending", Icons.Filled.Refresh) { act(onRetry) }
            ActionItem("Cancel sending", Icons.Filled.Close) { act(onCancelSend) }
        }
        if (entry.canReply && isContent) ActionItem("Reply", Icons.AutoMirrored.Filled.Reply) { act(onReply) }
        if (entry.isOwn && entry.isEditable && entry.body is MessageBody.Text) ActionItem("Edit", Icons.Filled.Edit) { act(onEdit) }
        if (entry.isOwn && entry.isEditable && isMedia) {
            ActionItem(if (entry.body.mediaCaption == null) "Add caption" else "Edit caption", Icons.Filled.Edit) { act(onEditCaption) }
        }
        if (isContent && (entry.body is MessageBody.Text || entry.body.mediaCaption != null)) ActionItem("Copy text", Icons.Filled.ContentCopy) { act(onCopy) }
        if (isContent) ActionItem("Share", Icons.Filled.Share) { act(onShare) }
        if (entry.eventId != null) {
            ActionItem("Copy link", Icons.Filled.Link) { act(onCopyLink) }
            ActionItem(if (isPinned) "Unpin" else "Pin", Icons.Filled.PushPin) { act(onPin) }
        }
        if (entry.body !is MessageBody.Redacted && entry.eventId != null) {
            ActionItem("Remove", Icons.Outlined.DeleteOutline, MaterialTheme.colorScheme.error) { act(onRedact) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ActionItem(label: String, icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, color = tint) },
        leadingContent = { Icon(icon, null, tint = tint) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
fun PollDialog(onDismiss: () -> Unit, onCreate: (String, List<String>, Boolean) -> Unit) {
    var question by remember { mutableStateOf("") }
    val answers = remember { mutableStateListOf("", "") }
    var disclosed by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create poll") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(question, { question = it }, label = { Text("Question") })
                answers.forEachIndexed { i, a ->
                    OutlinedTextField(a, { answers[i] = it }, label = { Text("Option ${i + 1}") }, singleLine = true)
                }
                if (answers.size < 10) TextButton(onClick = { answers.add("") }) { Text("Add option") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(disclosed, { disclosed = it })
                    Text("Show results while voting")
                }
            }
        },
        confirmButton = {
            val valid = question.isNotBlank() && answers.count { it.isNotBlank() } >= 2
            TextButton(enabled = valid, onClick = {
                onCreate(question.trim(), answers.filter { it.isNotBlank() }.map { it.trim() }, disclosed)
                onDismiss()
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Shown after picking attachments: previews, a caption, and "send as album" for several photos. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentSheet(files: List<PickedFile>, onDismiss: () -> Unit, onSend: (caption: String, asGallery: Boolean) -> Unit) {
    var caption by remember { mutableStateOf("") }
    val visual = files.all { it.mime.startsWith("image/") || it.mime.startsWith("video/") }
    var asGallery by remember { mutableStateOf(visual && files.size > 1) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(
                if (files.size == 1) "Send ${if (visual) "media" else "file"}" else "Send ${files.size} items",
                style = MaterialTheme.typography.titleMedium,
            )
            LazyRow(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files) { f ->
                    if (f.mime.startsWith("image/") || f.mime.startsWith("video/")) {
                        AsyncImage(
                            model = f.file,
                            contentDescription = f.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(if (files.size == 1) 200.dp else 110.dp).aspectRatio(1f).clip(RoundedCornerShape(12.dp)),
                        )
                    } else {
                        ListItem(
                            headlineContent = { Text(f.name) },
                            supportingContent = { Text(formatBytes(f.size)) },
                            leadingContent = { Icon(Icons.Filled.Description, null) },
                        )
                    }
                }
            }
            OutlinedTextField(
                caption, { caption = it },
                placeholder = { Text("Add a caption…") },
                maxLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            if (visual && files.size > 1) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Send as album")
                        Text("One message with all items. Some older apps show only the caption.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(asGallery, { asGallery = it })
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onSend(caption.trim(), asGallery) }) { Text("Send") }
            }
        }
    }
}

@Composable
fun LocationDialog(onDismiss: () -> Unit, onSendCurrent: () -> Unit, onShareLive: (Long) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Share location") },
        text = {
            Column {
                ListItem(
                    headlineContent = { Text("Send current location") },
                    leadingContent = { Icon(Icons.Filled.Place, null) },
                    modifier = Modifier.clickable { onDismiss(); onSendCurrent() },
                )
                Text("Share live location for", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
                listOf("15 minutes" to 15 * 60_000L, "1 hour" to 60 * 60_000L, "8 hours" to 8 * 60 * 60_000L).forEach { (label, ms) ->
                    ListItem(
                        headlineContent = { Text(label) },
                        leadingContent = { Icon(Icons.Filled.MyLocation, null) },
                        modifier = Modifier.clickable { onDismiss(); onShareLive(ms) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
