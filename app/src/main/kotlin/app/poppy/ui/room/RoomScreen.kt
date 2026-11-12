package app.poppy.ui.room

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Poll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.poppy.core.MatrixSession
import app.poppy.core.timeline.DayDivider
import app.poppy.core.timeline.MessageBody
import app.poppy.core.timeline.MessageEntry
import app.poppy.core.timeline.ReadMarker
import app.poppy.core.timeline.RoomTimeline
import app.poppy.core.timeline.SendStatus
import app.poppy.core.timeline.StateEntry
import app.poppy.core.timeline.TextKind
import app.poppy.core.timeline.TimelineStart
import app.poppy.graph
import app.poppy.ui.Navigator
import app.poppy.ui.Screen
import app.poppy.ui.components.Avatar
import app.poppy.ui.components.Time
import app.poppy.ui.components.avatarColor
import app.poppy.util.PickedFile
import app.poppy.util.copyToClipboard
import app.poppy.util.importUri
import app.poppy.util.shareText
import app.poppy.util.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.ComposerDraft
import org.matrix.rustcomponents.sdk.ComposerDraftType
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomInfo
import org.matrix.rustcomponents.sdk.RoomInfoListener
import uniffi.matrix_sdk_base.EncryptionState
import kotlin.math.roundToInt

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🎉", "🐶")

private val MORE_EMOJI = (
    "😀 😃 😄 😁 😆 😅 🤣 😊 😇 🙂 😉 😌 😍 🥰 😘 " +
        "😋 😜 🤪 🤓 😎 🤩 🥳 😏 😒 😔 😟 😕 🙁 😣 😫 " +
        "😩 🥺 😭 😤 😠 😡 🤯 😳 🥵 🥶 😱 😨 🤔 🤫 🤥 " +
        "😶 😐 🙄 😴 🤤 🤒 🤕 🤢 🤧 😷 👋 ✋ 👌 ✌️ 🤞 " +
        "👏 🙌 🙏 💪 👀 🔥 ✨ ⭐ 🌟 💯 ✅ ❌ ❗ ❓ 💤 " +
        "🐕 🐾 🦴 🌺 🌹 🌻 🎂 🍺 ☕ 🍕 🚀 🏆 🎵 💡 💀"
    ).split(' ')

@Immutable
private data class RoomHeader(
    val name: String,
    val avatar: String?,
    val members: Long,
    val encrypted: Boolean,
    val isDm: Boolean,
    val topic: String?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(session: MatrixSession, roomId: String, nav: Navigator) {
    val context = LocalContext.current
    val g = context.graph
    val room: Room? = remember(roomId) { session.client.getRoom(roomId) }
    if (room == null) {
        Scaffold(topBar = {
            TopAppBar(title = { Text("Room") }, navigationIcon = {
                IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            })
        }) { p -> Box(Modifier.padding(p).fillMaxSize(), contentAlignment = Alignment.Center) { Text("This room isn't available") } }
        return
    }

    val timeline = remember(roomId) { RoomTimeline(room, session.userId) { !g.settings.sendReadReceipts.value } }
    var header by remember(roomId) {
        mutableStateOf(RoomHeader(room.displayName() ?: roomId, room.avatarUrl(), room.joinedMembersCount().toLong(), false, false, room.topic()))
    }

    DisposableEffect(roomId) {
        timeline.open()
        g.visibleRoomId = roomId
        g.notifications.clearRoom(roomId)
        val infoHandle = room.subscribeToRoomInfoUpdates(object : RoomInfoListener {
            override fun call(roomInfo: RoomInfo) {
                header = roomInfo.toHeader(roomId)
            }
        })
        g.scope.launch { runCatching { header = room.roomInfo().toHeader(roomId) } }
        onDispose {
            if (g.visibleRoomId == roomId) g.visibleRoomId = null
            infoHandle.cancel()
            timeline.close()
        }
    }

    val entries by timeline.entries.collectAsState()
    val paginating by timeline.paginating.collectAsState()
    val typing by timeline.typing.collectAsState()
    val error by timeline.error.collectAsState()
    val reversed = remember(entries) { entries.asReversed() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var composer by remember(roomId) { mutableStateOf(TextFieldValue("")) }
    var replyTo by remember(roomId) { mutableStateOf<MessageEntry?>(null) }
    var editing by remember(roomId) { mutableStateOf<MessageEntry?>(null) }
    var actionsFor by remember { mutableStateOf<MessageEntry?>(null) }
    var confirmRedact by remember { mutableStateOf<MessageEntry?>(null) }
    var showPoll by remember { mutableStateOf(false) }

    // Drafts survive leaving the room (stored encrypted by the SDK).
    LaunchedEffect(roomId) {
        runCatching { room.loadComposerDraft(null) }.getOrNull()?.let { d ->
            if (composer.text.isEmpty()) composer = TextFieldValue(d.plainText, TextRange(d.plainText.length))
        }
    }
    DisposableEffect(roomId) {
        onDispose {
            val text = composer.text
            val type = editing?.eventId?.let { ComposerDraftType.Edit(it) }
                ?: replyTo?.eventId?.let { ComposerDraftType.Reply(it) }
                ?: ComposerDraftType.NewMessage
            g.scope.launch {
                runCatching {
                    if (text.isBlank()) room.clearComposerDraft(null)
                    else room.saveComposerDraft(ComposerDraft(text, null, type, emptyList()), null)
                }
            }
        }
    }

    // Back-pagination when the oldest loaded item comes into view.
    LaunchedEffect(listState) {
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to listState.layoutInfo.totalItemsCount }
            .distinctUntilChanged()
            .collect { (last, total) -> if (total == 0 || last >= total - 15) timeline.paginateBack() }
    }

    // Read receipts while the newest message is visible.
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    LaunchedEffect(entries.lastOrNull()?.key, atBottom) {
        if (atBottom && entries.isNotEmpty()) {
            delay(300)
            timeline.markAsRead()
        }
    }

    // Typing notifications, debounced.
    val sendTyping by g.settings.sendTyping.collectAsState()
    LaunchedEffect(composer.text.isNotEmpty(), sendTyping) {
        if (!sendTyping) return@LaunchedEffect
        if (composer.text.isNotEmpty()) {
            timeline.typing(true)
            delay(4_000)
            timeline.typing(false)
        } else {
            timeline.typing(false)
        }
    }

    LaunchedEffect(error) {
        error?.let {
            context.toast(it)
            timeline.clearError()
        }
    }

    fun uploadPicked(uri: Uri) {
        val reply = replyTo?.eventId
        replyTo = null
        scope.launch {
            val picked: PickedFile = withContext(Dispatchers.IO) { context.importUri(uri) } ?: return@launch
            val path = picked.file.absolutePath
            when {
                picked.mime.startsWith("image/") -> timeline.sendImage(path, picked.name, picked.mime, picked.width, picked.height, picked.size, null, reply)
                picked.mime.startsWith("video/") -> timeline.sendVideo(path, picked.name, picked.mime, picked.width, picked.height, picked.durationMs, picked.size, null, reply)
                picked.mime.startsWith("audio/") -> timeline.sendAudio(path, picked.name, picked.mime, picked.durationMs, picked.size, null, reply)
                else -> timeline.sendFile(path, picked.name, picked.mime, picked.size, null, reply)
            }
        }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { uris -> uris.forEach(::uploadPicked) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> uris.forEach(::uploadPicked) }

    fun send() {
        val raw = composer.text.trim()
        if (raw.isEmpty()) return
        val edit = editing
        when {
            edit != null -> timeline.edit(edit.id, raw)
            raw.startsWith("/me ") -> timeline.sendText(raw.removePrefix("/me "), replyTo?.eventId, emote = true)
            raw.startsWith("/shrug") -> timeline.sendText(("${raw.removePrefix("/shrug").trim()} ¯\\\\\\_(ツ)\\_/¯").trim(), replyTo?.eventId)
            raw.startsWith("/plain ") -> timeline.sendText(raw.removePrefix("/plain ").replace(Regex("([*_`~\\[\\]#>])"), "\\\\$1"), replyTo?.eventId)
            else -> timeline.sendText(raw, replyTo?.eventId)
        }
        composer = TextFieldValue("")
        replyTo = null
        editing = null
        scope.launch { listState.animateScrollToItem(0) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        Modifier.clip(RoundedCornerShape(12.dp)).clickable { nav.push(Screen.RoomDetails(roomId)) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Avatar(header.avatar, header.name, roomId, 38.dp)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(header.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val subtitle = when {
                                typing.isNotEmpty() -> typingText(typing)
                                header.isDm -> if (header.encrypted) "Encrypted" else "Direct message"
                                else -> "${header.members} members"
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (header.encrypted && typing.isEmpty()) Icon(Icons.Filled.Lock, null, Modifier.size(12.dp).padding(end = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    subtitle,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (typing.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        bottomBar = {
            Composer(
                value = composer,
                onValueChange = { composer = it },
                replyTo = replyTo,
                editing = editing,
                onCancel = {
                    if (editing != null) composer = TextFieldValue("")
                    replyTo = null
                    editing = null
                },
                onSend = ::send,
                onPickMedia = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                onPickFile = { pickFile.launch(arrayOf("*/*")) },
                onPoll = { showPoll = true },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
            ) {
                items(
                    count = reversed.size,
                    key = { reversed[it].key },
                    contentType = { i ->
                        when (val e = reversed[i]) {
                            is MessageEntry -> if (e.isOwn) "own" else "other"
                            else -> e::class.simpleName
                        }
                    },
                ) { i ->
                    when (val e = reversed[i]) {
                        is MessageEntry -> MessageRow(
                            entry = e,
                            showSender = !header.isDm,
                            onLongPress = { actionsFor = e },
                            onReply = { if (e.canReply) { replyTo = e; editing = null } },
                            onReact = { key -> timeline.toggleReaction(e.id, key) },
                            onOpenImage = { img -> nav.push(Screen.Media(img.source, img.filename, img.mimeType)) },
                            onVote = { answer -> e.eventId?.let { timeline.vote(it, answer) } },
                            onEndPoll = { e.eventId?.let { timeline.endPoll(it) } },
                            onRetry = { timeline.retry(e.id) },
                            onJumpToReply = { eventId ->
                                val idx = reversed.indexOfFirst { (it as? MessageEntry)?.eventId == eventId }
                                if (idx >= 0) scope.launch { listState.animateScrollToItem(idx) }
                            },
                        )
                        is DayDivider -> DayRow(e.timestamp)
                        is ReadMarker -> if (i > 0) NewMessagesRow() else Spacer(Modifier.height(0.dp))
                        is StateEntry -> StateRow(e.text)
                        is TimelineStart -> StartRow(header.name)
                    }
                }
                if (paginating) item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
            AnimatedVisibility(
                visible = !atBottom,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                SmallFloatingActionButton(onClick = { scope.launch { listState.animateScrollToItem(0) } }) {
                    Icon(Icons.Filled.KeyboardArrowDown, "Jump to latest")
                }
            }
        }
    }

    actionsFor?.let { e ->
        MessageActionsSheet(
            entry = e,
            onDismiss = { actionsFor = null },
            onReact = { timeline.toggleReaction(e.id, it) },
            onReply = { replyTo = e; editing = null },
            onEdit = {
                editing = e
                replyTo = null
                composer = TextFieldValue(e.body.plain, TextRange(e.body.plain.length))
            },
            onCopy = {
                context.copyToClipboard(e.body.plain)
                context.toast("Copied")
            },
            onShare = { context.shareText(e.body.plain) },
            onPin = { e.eventId?.let { timeline.pin(it) } },
            onRedact = { confirmRedact = e },
            onRetry = { timeline.retry(e.id) },
            onCancelSend = { timeline.abort(e.id) },
            onCopyLink = {
                scope.launch {
                    e.eventId?.let { id ->
                        runCatching { room.matrixToEventPermalink(id) }.getOrNull()?.let {
                            context.copyToClipboard(it)
                            context.toast("Link copied")
                        }
                    }
                }
            },
        )
    }

    confirmRedact?.let { e ->
        AlertDialog(
            onDismissRequest = { confirmRedact = null },
            title = { Text("Remove message?") },
            text = { Text("This removes the message for everyone in the room.") },
            confirmButton = {
                TextButton(onClick = {
                    timeline.redact(e.id)
                    confirmRedact = null
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRedact = null }) { Text("Cancel") } },
        )
    }

    if (showPoll) PollDialog(onDismiss = { showPoll = false }) { q, answers, disclosed -> timeline.createPoll(q, answers, disclosed) }
}

private fun RoomInfo.toHeader(roomId: String) = RoomHeader(
    name = displayName ?: rawName ?: canonicalAlias ?: roomId,
    avatar = avatarUrl ?: if (isDm || isDirect) heroes.firstOrNull()?.avatarUrl else null,
    members = joinedMembersCount.toLong(),
    encrypted = encryptionState == EncryptionState.ENCRYPTED,
    isDm = isDm || isDirect,
    topic = topic,
)

private fun typingText(names: List<String>) = when (names.size) {
    1 -> "${names[0]} is typing…"
    2 -> "${names[0]} and ${names[1]} are typing…"
    else -> "${names.size} people are typing…"
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun MessageRow(
    entry: MessageEntry,
    showSender: Boolean,
    onLongPress: () -> Unit,
    onReply: () -> Unit,
    onReact: (String) -> Unit,
    onOpenImage: (MessageBody.Image) -> Unit,
    onVote: (String) -> Unit,
    onEndPoll: () -> Unit,
    onRetry: () -> Unit,
    onJumpToReply: (String) -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val own = entry.isOwn
    val scheme = MaterialTheme.colorScheme
    val bubbleColor = if (own) scheme.primaryContainer else scheme.surfaceContainerHigh
    val textColor = if (own) scheme.onPrimaryContainer else scheme.onSurface
    val isMedia = entry.body is MessageBody.Image || entry.body is MessageBody.Video
    val bareEmoji = (entry.body as? MessageBody.Text)?.emojiOnly == true
    val bubbleless = (isMedia && entry.replyTo == null) || bareEmoji || (entry.body as? MessageBody.Image)?.isSticker == true
    val big = 20.dp
    val small = 6.dp
    val shape = if (own) {
        RoundedCornerShape(big, if (entry.groupedWithPrevious) small else big, if (entry.groupedWithNext) small else big, big)
    } else {
        RoundedCornerShape(if (entry.groupedWithPrevious) small else big, big, big, if (entry.groupedWithNext) small else big)
    }
    var dragX by remember { mutableFloatStateOf(0f) }
    val trigger = with(density) { 64.dp.toPx() }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = if (entry.groupedWithPrevious) 2.dp else 8.dp)
            .pointerInput(entry.key) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragX > trigger) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onReply()
                        }
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                ) { _, delta -> dragX = (dragX + delta).coerceIn(0f, trigger * 1.4f) }
            },
        horizontalAlignment = if (own) Alignment.End else Alignment.Start,
    ) {
        if (!own && showSender && !entry.groupedWithPrevious) {
            Text(
                entry.senderName,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = avatarColor(entry.senderId),
                modifier = Modifier.padding(start = 48.dp, bottom = 2.dp),
            )
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.offset { IntOffset(dragX.roundToInt(), 0) },
        ) {
            if (!own) {
                if (!entry.groupedWithNext) Avatar(entry.senderAvatar, entry.senderName, entry.senderId, 32.dp)
                else Spacer(Modifier.width(32.dp))
                Spacer(Modifier.width(8.dp))
            }
            Column(
                Modifier
                    .widthIn(max = 320.dp)
                    .clip(shape)
                    .background(if (bubbleless) Color.Transparent else bubbleColor)
                    .combinedClickable(
                        onClick = { if (entry.status == SendStatus.FAILED) onRetry() },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress()
                        },
                    )
                    .padding(if (bubbleless) 0.dp else 10.dp),
            ) {
                entry.replyTo?.let { r -> ReplyQuote(r, textColor, scheme.primary) { onJumpToReply(r.eventId) } }
                MessageBodyView(
                    body = entry.body,
                    color = textColor,
                    linkColor = if (own) scheme.onPrimaryContainer else scheme.primary,
                    onOpenImage = onOpenImage,
                    onVote = onVote,
                    canEndPoll = own,
                    onEndPoll = onEndPoll,
                )
                Row(
                    Modifier.align(Alignment.End).padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val meta = if (bubbleless) scheme.onSurfaceVariant else textColor.copy(alpha = 0.65f)
                    if (entry.isEdited) Text("edited", style = MaterialTheme.typography.labelSmall, color = meta)
                    Text(remember(entry.timestamp) { Time.clock(context, entry.timestamp) }, style = MaterialTheme.typography.labelSmall, color = meta)
                    if (own) {
                        when (entry.status) {
                            SendStatus.SENDING -> Icon(Icons.Filled.Schedule, "Sending", Modifier.size(13.dp), tint = meta)
                            SendStatus.FAILED -> Icon(Icons.Filled.ErrorOutline, "Failed – tap to retry", Modifier.size(14.dp), tint = scheme.error)
                            else -> Icon(if (entry.readBy > 0) Icons.Filled.DoneAll else Icons.Filled.Done, "Sent", Modifier.size(14.dp), tint = if (entry.readBy > 0) scheme.primary else meta)
                        }
                    }
                }
            }
        }
        if (entry.threadReplies > 0) {
            Text(
                "${entry.threadReplies} ${if (entry.threadReplies == 1) "reply" else "replies"} in thread",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
                modifier = Modifier.padding(start = if (own) 0.dp else 48.dp, top = 2.dp),
            )
        }
        if (entry.reactions.isNotEmpty()) {
            FlowRow(
                Modifier.padding(start = if (own) 0.dp else 40.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                entry.reactions.forEach { r ->
                    Surface(
                        onClick = { onReact(r.key) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (r.mine) scheme.primaryContainer else scheme.surfaceContainerHighest,
                        border = if (r.mine) androidx.compose.foundation.BorderStroke(1.dp, scheme.primary) else null,
                    ) {
                        Text(
                            "${r.key.take(16)} ${r.count}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayRow(ts: Long) {
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Text(
            remember(ts) { Time.day(context, ts) },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun NewMessagesRow() {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
        Text("New", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp))
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun StateRow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp),
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

@Composable
private fun StartRow(name: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("This is the beginning of", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(name, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Composer(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    replyTo: MessageEntry?,
    editing: MessageEntry?,
    onCancel: () -> Unit,
    onSend: () -> Unit,
    onPickMedia: () -> Unit,
    onPickFile: () -> Unit,
    onPoll: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(Modifier.navigationBarsPadding().imePadding()) {
            val ctx = editing ?: replyTo
            if (ctx != null) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (editing != null) Icons.Filled.Edit else Icons.AutoMirrored.Filled.Reply, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(
                            if (editing != null) "Editing" else "Replying to ${ctx.senderName}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(ctx.body.plain, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(onClick = onCancel) { Icon(Icons.Filled.Close, "Cancel") }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.AddCircleOutline, "Attach") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Photos & videos") }, leadingIcon = { Icon(Icons.Filled.Photo, null) }, onClick = { menu = false; onPickMedia() })
                        DropdownMenuItem(text = { Text("File") }, leadingIcon = { Icon(Icons.Filled.AttachFile, null) }, onClick = { menu = false; onPickFile() })
                        DropdownMenuItem(text = { Text("Poll") }, leadingIcon = { Icon(Icons.Outlined.Poll, null) }, onClick = { menu = false; onPoll() })
                    }
                }
                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    placeholder = { Text("Message") },
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    ),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences),
                    modifier = Modifier.weight(1f),
                )
                val canSend = value.text.isNotBlank()
                IconButton(
                    onClick = onSend,
                    enabled = canSend,
                    modifier = Modifier.padding(start = 4.dp).size(48.dp).clip(CircleShape)
                        .background(if (canSend) MaterialTheme.colorScheme.primary else Color.Transparent),
                ) {
                    Icon(
                        if (editing != null) Icons.Filled.Done else Icons.AutoMirrored.Filled.Send,
                        "Send",
                        tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MessageActionsSheet(
    entry: MessageEntry,
    onDismiss: () -> Unit,
    onReact: (String) -> Unit,
    onReply: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
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
    ModalBottomSheet(onDismissRequest = onDismiss) {
        if (isContent && entry.status != SendStatus.FAILED) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                QUICK_REACTIONS.forEach { emoji ->
                    Text(
                        emoji,
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.clip(CircleShape).clickable { act { onReact(emoji) } }.padding(8.dp),
                    )
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
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }
        if (entry.status == SendStatus.FAILED) {
            ActionItem("Retry sending", Icons.Filled.Refresh) { act(onRetry) }
            ActionItem("Cancel sending", Icons.Filled.Close) { act(onCancelSend) }
        }
        if (entry.canReply && isContent) ActionItem("Reply", Icons.AutoMirrored.Filled.Reply) { act(onReply) }
        if (entry.isOwn && entry.isEditable && entry.body is MessageBody.Text) ActionItem("Edit", Icons.Filled.Edit) { act(onEdit) }
        if (isContent) {
            ActionItem("Copy text", Icons.Filled.ContentCopy) { act(onCopy) }
            ActionItem("Share", Icons.Filled.Share) { act(onShare) }
        }
        if (entry.eventId != null) {
            ActionItem("Copy link", Icons.Filled.AttachFile) { act(onCopyLink) }
            ActionItem("Pin", Icons.Filled.PushPin) { act(onPin) }
        }
        if (entry.body !is MessageBody.Redacted && entry.eventId != null) {
            ActionItem("Remove", Icons.Outlined.DeleteOutline, MaterialTheme.colorScheme.error) { act(onRedact) }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ActionItem(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label, color = tint) },
        leadingContent = { Icon(icon, null, tint = tint) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun PollDialog(onDismiss: () -> Unit, onCreate: (String, List<String>, Boolean) -> Unit) {
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

