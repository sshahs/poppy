package app.poppy.ui.room

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.poppy.core.MatrixSession
import app.poppy.core.location.LiveLocationService
import app.poppy.core.location.Locations
import app.poppy.core.media.VoicePlayer
import app.poppy.core.media.VoiceRecorder
import app.poppy.core.timeline.DayDivider
import app.poppy.core.timeline.MessageBody
import app.poppy.core.timeline.MessageEntry
import app.poppy.core.timeline.ReadMarker
import app.poppy.core.timeline.RoomTimeline
import app.poppy.core.timeline.SendStatus
import app.poppy.core.timeline.StateEntry
import app.poppy.core.timeline.TimelineStart
import app.poppy.core.timeline.mediaCaption
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
import org.matrix.rustcomponents.sdk.Mentions
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomInfo
import org.matrix.rustcomponents.sdk.RoomInfoListener
import uniffi.matrix_sdk_base.EncryptionState
import kotlin.math.roundToInt

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
            VoicePlayer.stop()
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
    var editingCaption by remember(roomId) { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<MessageEntry?>(null) }
    var confirmRedact by remember { mutableStateOf<MessageEntry?>(null) }
    var showPoll by remember { mutableStateOf(false) }
    var showLocation by remember { mutableStateOf(false) }
    var pendingFiles by remember { mutableStateOf<List<PickedFile>?>(null) }
    var importing by remember { mutableStateOf(false) }
    val recorder = remember { VoiceRecorder(context) }
    var recording by remember { mutableStateOf(false) }

    // Mentions: "@Name" label in the composer → user id, resolved into pills on send.
    val mentionLabels = remember(roomId) { mutableStateMapOf<String, String>() }
    var members by remember(roomId) { mutableStateOf<List<MemberLite>>(emptyList()) }
    LaunchedEffect(roomId) {
        members = withContext(Dispatchers.Default) {
            runCatching {
                val it = room.membersNoSync()
                buildList {
                    while (true) {
                        val chunk = it.nextChunk(500u) ?: break
                        chunk.filter { m -> m.userId != session.userId && m.membership is org.matrix.rustcomponents.sdk.MembershipState.Join }
                            .forEach { m -> add(MemberLite(m.userId, m.displayName?.takeIf { n -> n.isNotBlank() } ?: m.userId, m.avatarUrl)) }
                    }
                }
            }.getOrDefault(emptyList())
        }
    }

    // Drafts survive leaving the room (stored encrypted by the SDK).
    LaunchedEffect(roomId) {
        runCatching { room.loadComposerDraft(null) }.getOrNull()?.let { d ->
            if (composer.text.isEmpty()) composer = TextFieldValue(d.plainText, TextRange(d.plainText.length))
        }
    }
    DisposableEffect(roomId) {
        onDispose {
            recorder.cancel()
            val text = composer.text
            val type = editing?.eventId?.let { ComposerDraftType.Edit(it) }
                ?: replyTo?.eventId?.let { ComposerDraftType.Reply(it) }
                ?: ComposerDraftType.NewMessage
            g.scope.launch {
                runCatching {
                    if (text.isBlank() || editingCaption) room.clearComposerDraft(null)
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

    // Stick to the newest message while the user is at the bottom, and always after we send.
    val atBottom by remember { derivedStateOf { listState.firstVisibleItemIndex <= 1 } }
    var stickToBottom by remember { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.isScrollInProgress }.collect { (idx, scrolling) ->
            // Only user (or our own animated) scrolling changes stickiness; new items arriving don't.
            if (scrolling) stickToBottom = idx <= 1
        }
    }
    val newest = entries.lastOrNull { it is MessageEntry } as? MessageEntry
    LaunchedEffect(newest?.key) {
        if (newest != null && (stickToBottom || newest.isOwn) && listState.firstVisibleItemIndex > 0) {
            listState.animateScrollToItem(0)
        }
    }

    // Read receipts while the newest message is visible.
    LaunchedEffect(entries.lastOrNull()?.key, atBottom) {
        if (atBottom && entries.isNotEmpty()) {
            delay(300)
            timeline.markAsRead()
        }
    }

    // Typing notifications, debounced.
    val sendTyping by g.settings.sendTyping.collectAsState()
    LaunchedEffect(composer.text.isNotEmpty(), sendTyping) {
        if (!sendTyping || editingCaption) return@LaunchedEffect
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

    fun importAll(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importing = true
        scope.launch {
            val files = withContext(Dispatchers.IO) { uris.mapNotNull { runCatching { context.importUri(it) }.getOrNull() } }
            importing = false
            if (files.isNotEmpty()) pendingFiles = files
        }
    }

    fun sendFiles(files: List<PickedFile>, caption: String, asGallery: Boolean) {
        val reply = replyTo?.eventId
        replyTo = null
        val cap = caption.ifBlank { null }
        if (asGallery && files.size > 1) {
            timeline.sendGallery(files, cap, reply)
            return
        }
        files.forEachIndexed { i, f ->
            val c = if (i == files.lastIndex) cap else null
            val path = f.file.absolutePath
            when {
                f.mime.startsWith("image/") -> timeline.sendImage(path, f.name, f.mime, f.width, f.height, f.size, c, reply)
                f.mime.startsWith("video/") -> timeline.sendVideo(path, f.name, f.mime, f.width, f.height, f.durationMs, f.size, c, reply)
                f.mime.startsWith("audio/") -> timeline.sendAudio(path, f.name, f.mime, f.durationMs, f.size, c, reply)
                else -> timeline.sendFile(path, f.name, f.mime, f.size, c, reply)
            }
        }
    }

    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(20)) { importAll(it) }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importAll(it) }

    fun startRecording() {
        try {
            VoicePlayer.stop()
            recorder.start()
            recording = true
        } catch (t: Throwable) {
            context.toast("Couldn't start recording: ${t.message}")
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startRecording() else context.toast("Microphone permission is needed for voice messages")
    }

    var pendingLocationAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) pendingLocationAction?.invoke() else context.toast("Location permission is needed to share your location")
        pendingLocationAction = null
    }
    fun withLocation(action: () -> Unit) {
        if (Locations.hasPermission(context)) action() else {
            pendingLocationAction = action
            locationPermission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }

    fun send() {
        val raw = composer.text.trim()
        val edit = editing
        if (edit != null && editingCaption) {
            timeline.editCaption(edit.id, raw)
        } else {
            if (raw.isEmpty()) return
            // Resolve "@Name" labels into matrix.to pills + intentional mentions.
            var md = raw
            val userIds = LinkedHashSet<String>()
            mentionLabels.forEach { (label, userId) ->
                val idx = md.indexOf(label)
                if (idx >= 0) {
                    val name = label.removePrefix("@").replace("[", "\\[").replace("]", "\\]")
                    md = md.substring(0, idx) + "[$name](https://matrix.to/#/$userId)" + md.substring(idx + label.length)
                    userIds += userId
                }
            }
            val roomMention = Regex("(^|\\s)@room\\b").containsMatchIn(raw)
            val mentions = Mentions(userIds.toList(), roomMention)
            when {
                edit != null -> timeline.edit(edit.id, md, mentions)
                md.startsWith("/me ") -> timeline.sendText(md.removePrefix("/me "), replyTo?.eventId, emote = true, mentions = mentions)
                md.startsWith("/shrug") -> timeline.sendText(("${md.removePrefix("/shrug").trim()} ¯\\\\\\_(ツ)\\_/¯").trim(), replyTo?.eventId, mentions = mentions)
                md.startsWith("/plain ") -> timeline.sendText(md.removePrefix("/plain ").replace(Regex("([*_`~\\[\\]#>])"), "\\\\$1"), replyTo?.eventId, mentions = mentions)
                else -> timeline.sendText(md, replyTo?.eventId, mentions = mentions)
            }
        }
        composer = TextFieldValue("")
        mentionLabels.clear()
        replyTo = null
        editing = null
        editingCaption = false
        stickToBottom = true
    }

    val query = remember(composer) { if (editingCaption) null else mentionQueryAt(composer) }

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
                editingCaption = editingCaption,
                recorder = recorder,
                recording = recording,
                onCancel = {
                    if (editing != null) composer = TextFieldValue("")
                    replyTo = null
                    editing = null
                    editingCaption = false
                },
                onSend = ::send,
                onStartRecording = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
                    else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                },
                onStopRecording = { sendIt ->
                    recording = false
                    if (sendIt) {
                        val clip = recorder.stop()
                        if (clip == null) context.toast("Hold on a little longer to record") else {
                            timeline.sendVoice(clip.file.absolutePath, clip.mime, clip.durationMs, clip.file.length(), clip.waveform, replyTo?.eventId)
                            replyTo = null
                            stickToBottom = true
                        }
                    } else {
                        recorder.cancel()
                    }
                },
                onPickMedia = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) },
                onPickFile = { pickFile.launch(arrayOf("*/*")) },
                onPoll = { showPoll = true },
                onLocation = { showLocation = true },
                suggestions = {
                    if (query != null && members.isNotEmpty()) {
                        MentionSuggestions(query, members, canMentionRoom = !header.isDm) { picked ->
                            if (picked == null) {
                                composer = insertMention(composer, "@room")
                            } else {
                                val label = "@${picked.name}"
                                mentionLabels[label] = picked.userId
                                composer = insertMention(composer, label)
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
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
                            ownUserId = session.userId,
                            roomId = roomId,
                            showSender = !header.isDm,
                            onLongPress = { actionsFor = e },
                            onReply = {
                                if (e.canReply) {
                                    replyTo = e
                                    editing = null
                                    editingCaption = false
                                }
                            },
                            onReact = { key -> timeline.toggleReaction(e.id, key) },
                            onOpenImage = { source, name, mime -> nav.push(Screen.Media(source, name, mime)) },
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
            if (importing) {
                Surface(Modifier.align(Alignment.TopCenter).padding(8.dp), shape = RoundedCornerShape(16.dp), tonalElevation = 3.dp) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text("  Preparing attachments…", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            AnimatedVisibility(
                visible = !atBottom,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                SmallFloatingActionButton(onClick = {
                    stickToBottom = true
                    scope.launch { listState.animateScrollToItem(0) }
                }) { Icon(Icons.Filled.KeyboardArrowDown, "Jump to latest") }
            }
        }
    }

    actionsFor?.let { e ->
        MessageActionsSheet(
            entry = e,
            onDismiss = { actionsFor = null },
            onReact = { timeline.toggleReaction(e.id, it) },
            onReply = { replyTo = e; editing = null; editingCaption = false },
            onEdit = {
                editing = e
                editingCaption = false
                replyTo = null
                composer = TextFieldValue(e.body.plain, TextRange(e.body.plain.length))
            },
            onEditCaption = {
                editing = e
                editingCaption = true
                replyTo = null
                val c = e.body.mediaCaption.orEmpty()
                composer = TextFieldValue(c, TextRange(c.length))
            },
            onCopy = {
                context.copyToClipboard(e.body.mediaCaption ?: e.body.plain)
                context.toast("Copied")
            },
            onShare = { context.shareText(e.body.mediaCaption ?: e.body.plain) },
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

    pendingFiles?.let { files ->
        AttachmentSheet(files, onDismiss = { pendingFiles = null }) { caption, asGallery ->
            pendingFiles = null
            stickToBottom = true
            sendFiles(files, caption, asGallery)
        }
    }

    if (showPoll) PollDialog(onDismiss = { showPoll = false }) { q, answers, disclosed -> timeline.createPoll(q, answers, disclosed) }

    if (showLocation) {
        LocationDialog(
            onDismiss = { showLocation = false },
            onSendCurrent = {
                withLocation {
                    scope.launch {
                        context.toast("Getting your location…")
                        val loc = Locations.current(context)
                        if (loc == null) context.toast("Couldn't get your location. Is location turned on?")
                        else timeline.sendLocation(Locations.geoUri(loc), Locations.describe(loc))
                    }
                }
            },
            onShareLive = { ms -> withLocation { LiveLocationService.start(context, roomId, ms) } },
        )
    }
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
    ownUserId: String,
    roomId: String,
    showSender: Boolean,
    onLongPress: () -> Unit,
    onReply: () -> Unit,
    onReact: (String) -> Unit,
    onOpenImage: (org.matrix.rustcomponents.sdk.MediaSource, String, String?) -> Unit,
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
    val bubbleColor = when {
        own -> scheme.primaryContainer
        entry.mentionsMe -> scheme.tertiaryContainer
        else -> scheme.surfaceContainerHigh
    }
    val textColor = when {
        own -> scheme.onPrimaryContainer
        entry.mentionsMe -> scheme.onTertiaryContainer
        else -> scheme.onSurface
    }
    val bubbleless = entry.body.isBubbleless(entry.replyTo != null)
    val inlineMeta = !bubbleless && entry.body.hasInlineMeta()
    val big = 18.dp
    val small = 5.dp
    val shape = if (own) {
        RoundedCornerShape(big, if (entry.groupedWithPrevious) small else big, if (entry.groupedWithNext) small else big, big)
    } else {
        RoundedCornerShape(if (entry.groupedWithPrevious) small else big, big, big, if (entry.groupedWithNext) small else big)
    }
    var dragX by remember { mutableFloatStateOf(0f) }
    val trigger = with(density) { 64.dp.toPx() }
    val time = remember(entry.timestamp) { Time.clock(context, entry.timestamp) }
    // Invisible run appended to text so the inline timestamp never overlaps the last line.
    val trailing = if (inlineMeta) " " + (if (entry.isEdited) "edited " else "") + time + (if (own) "   " else " ") else ""

    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = if (entry.groupedWithPrevious) 2.dp else 6.dp)
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
                modifier = Modifier.padding(start = 46.dp, bottom = 2.dp),
            )
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.offset { IntOffset(dragX.roundToInt(), 0) }) {
            if (!own) {
                if (!entry.groupedWithNext) Avatar(entry.senderAvatar, entry.senderName, entry.senderId, 32.dp)
                else Spacer(Modifier.width(32.dp))
                Spacer(Modifier.width(6.dp))
            }
            Box(
                Modifier
                    .widthIn(max = 320.dp)
                    .clip(shape)
                    .background(if (bubbleless) Color.Transparent else bubbleColor)
                    .then(if (entry.mentionsMe && !bubbleless) Modifier.border(1.dp, scheme.tertiary.copy(alpha = 0.5f), shape) else Modifier)
                    .combinedClickable(
                        onClick = { if (entry.status == SendStatus.FAILED) onRetry() },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress()
                        },
                    )
                    .padding(if (bubbleless) PaddingValues(0.dp) else PaddingValues(horizontal = 11.dp, vertical = 7.dp)),
            ) {
                Column {
                    entry.replyTo?.let { r -> ReplyQuote(r, textColor, scheme.primary) { onJumpToReply(r.eventId) } }
                    MessageBodyView(
                        entryKey = entry.key,
                        body = entry.body,
                        color = textColor,
                        linkColor = if (own) scheme.onPrimaryContainer else scheme.primary,
                        accent = scheme.primary,
                        ownUserId = ownUserId,
                        roomId = roomId,
                        isOwn = own,
                        trailing = trailing,
                        onOpenImage = onOpenImage,
                        onVote = onVote,
                        onEndPoll = onEndPoll,
                    )
                    if (!inlineMeta) {
                        MetaRow(entry, time, if (bubbleless) scheme.onSurfaceVariant else textColor.copy(alpha = 0.6f), Modifier.align(Alignment.End).padding(top = 2.dp), bubbleless)
                    }
                }
                if (inlineMeta) {
                    MetaRow(entry, time, textColor.copy(alpha = 0.6f), Modifier.align(Alignment.BottomEnd), false)
                }
            }
        }
        if (entry.threadReplies > 0) {
            Text(
                "${entry.threadReplies} ${if (entry.threadReplies == 1) "reply" else "replies"} in thread",
                style = MaterialTheme.typography.labelMedium,
                color = scheme.primary,
                modifier = Modifier.padding(start = if (own) 0.dp else 46.dp, top = 2.dp),
            )
        }
        if (entry.reactions.isNotEmpty()) {
            FlowRow(
                Modifier.padding(start = if (own) 0.dp else 38.dp, top = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                entry.reactions.forEach { r ->
                    Surface(
                        onClick = { onReact(r.key) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (r.mine) scheme.primaryContainer else scheme.surfaceContainerHighest,
                        border = if (r.mine) BorderStroke(1.dp, scheme.primary) else null,
                    ) {
                        Text(
                            if (r.count > 1) "${r.key.take(16)} ${r.count}" else r.key.take(16),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetaRow(entry: MessageEntry, time: String, color: Color, modifier: Modifier, pill: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier.then(
            if (pill) Modifier.background(scheme.surfaceContainerHigh.copy(alpha = 0.85f), RoundedCornerShape(8.dp)).padding(horizontal = 6.dp, vertical = 1.dp) else Modifier,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        if (entry.isEdited) Text("edited", style = MaterialTheme.typography.labelSmall, color = color)
        Text(time, style = MaterialTheme.typography.labelSmall, color = color)
        if (entry.isOwn) {
            when (entry.status) {
                SendStatus.SENDING -> Icon(Icons.Filled.Schedule, "Sending", Modifier.size(12.dp), tint = color)
                SendStatus.FAILED -> Icon(Icons.Filled.ErrorOutline, "Failed – tap to retry", Modifier.size(13.dp), tint = scheme.error)
                else -> Icon(if (entry.readBy > 0) Icons.Filled.DoneAll else Icons.Filled.Done, "Sent", Modifier.size(13.dp), tint = if (entry.readBy > 0) scheme.primary else color)
            }
        }
    }
}

@Composable
private fun DayRow(ts: Long) {
    val context = LocalContext.current
    Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(
            remember(ts) { Time.day(context, ts) },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun NewMessagesRow() {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
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
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 3.dp),
        textAlign = TextAlign.Center,
    )
}

@Composable
private fun StartRow(name: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("This is the beginning of", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(name, style = MaterialTheme.typography.titleMedium)
    }
}
