package app.poppy.ui.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.poppy.core.MatrixSession
import app.poppy.ui.Navigator
import app.poppy.ui.Screen
import app.poppy.ui.components.Avatar
import app.poppy.util.copyToClipboard
import app.poppy.util.errorMessage
import app.poppy.util.toast
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.MembershipState
import org.matrix.rustcomponents.sdk.PowerLevel
import org.matrix.rustcomponents.sdk.RoomInfo
import org.matrix.rustcomponents.sdk.RoomMember
import org.matrix.rustcomponents.sdk.RoomNotificationMode
import uniffi.matrix_sdk_base.EncryptionState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomDetailsScreen(session: MatrixSession, roomId: String, nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val room = remember(roomId) { session.client.getRoom(roomId) } ?: return
    var info by remember { mutableStateOf<RoomInfo?>(null) }
    var mode by remember { mutableStateOf<RoomNotificationMode?>(null) }
    var isDefaultMode by remember { mutableStateOf(true) }
    var showNotif by remember { mutableStateOf(false) }
    var showInvite by remember { mutableStateOf(false) }
    var showLeave by remember { mutableStateOf(false) }
    var editField by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }

    LaunchedEffect(roomId, refresh) {
        runCatching {
            val i = room.roomInfo()
            info = i
            val settings = session.client.getNotificationSettings()
            val s = settings.getRoomNotificationSettings(roomId, i.encryptionState == EncryptionState.ENCRYPTED, i.isDm || i.isDirect)
            mode = s.mode
            isDefaultMode = s.isDefault
        }
    }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure { context.toast(errorMessage(it)) }
            refresh++
            session.rooms.refresh(roomId)
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Room info") }, navigationIcon = {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        val i = info
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(i?.avatarUrl, i?.displayName ?: roomId, roomId, 96.dp)
                Spacer(Modifier.height(12.dp))
                Text(i?.displayName ?: "", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                i?.canonicalAlias?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                i?.topic?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 12.dp))
                }
            }
            HorizontalDivider()
            val encrypted = i?.encryptionState == EncryptionState.ENCRYPTED
            ListItem(
                headlineContent = { Text(if (encrypted) "Messages are end-to-end encrypted" else "Not encrypted") },
                supportingContent = { Text(if (encrypted) "Only people in this room can read messages." else "Enable encryption to protect new messages.") },
                leadingContent = { Icon(if (encrypted) Icons.Filled.Lock else Icons.Filled.LockOpen, null) },
                modifier = if (!encrypted && i != null) Modifier.clickable { act { room.enableEncryption() } } else Modifier,
            )
            ListItem(
                headlineContent = { Text("Notifications") },
                supportingContent = { Text(mode.label() + if (isDefaultMode) " (default)" else "") },
                leadingContent = { Icon(Icons.Filled.Notifications, null) },
                modifier = Modifier.clickable { showNotif = true },
            )
            ListItem(
                headlineContent = { Text("Favourite") },
                leadingContent = { Icon(Icons.Filled.Star, null) },
                trailingContent = { Switch(i?.isFavourite == true, { fav -> act { room.setIsFavourite(fav, null) } }) },
            )
            ListItem(
                headlineContent = { Text("People") },
                supportingContent = { Text("${i?.joinedMembersCount ?: 0} joined") },
                leadingContent = { Icon(Icons.Filled.Group, null) },
                modifier = Modifier.clickable { nav.push(Screen.Members(roomId)) },
            )
            ListItem(
                headlineContent = { Text("Invite people") },
                leadingContent = { Icon(Icons.Filled.PersonAdd, null) },
                modifier = Modifier.clickable { showInvite = true },
            )
            ListItem(
                headlineContent = { Text("Copy room link") },
                leadingContent = { Icon(Icons.Filled.Link, null) },
                modifier = Modifier.clickable {
                    scope.launch {
                        runCatching { room.matrixToPermalink() }.onSuccess {
                            context.copyToClipboard(it)
                            context.toast("Link copied")
                        }
                    }
                },
            )
            ListItem(
                headlineContent = { Text("Edit name") },
                leadingContent = { Icon(Icons.Filled.Edit, null) },
                modifier = Modifier.clickable { editField = "name" },
            )
            ListItem(
                headlineContent = { Text("Edit topic") },
                leadingContent = { Icon(Icons.Filled.Edit, null) },
                modifier = Modifier.clickable { editField = "topic" },
            )
            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Leave room", color = MaterialTheme.colorScheme.error) },
                leadingContent = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = MaterialTheme.colorScheme.error) },
                modifier = Modifier.clickable { showLeave = true },
            )
            Text(
                roomId,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp).clickable { context.copyToClipboard(roomId); context.toast("Room ID copied") },
            )
        }
    }

    if (showNotif) {
        ModalBottomSheet(onDismissRequest = { showNotif = false }) {
            Text("Notify me for", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            val options = listOf(null) + RoomNotificationMode.entries
            options.forEach { m ->
                val selected = if (m == null) isDefaultMode else !isDefaultMode && mode == m
                ListItem(
                    headlineContent = { Text(if (m == null) "Default" else m.label()) },
                    leadingContent = { RadioButton(selected, null) },
                    modifier = Modifier.clickable {
                        showNotif = false
                        act {
                            val settings = session.client.getNotificationSettings()
                            if (m == null) settings.restoreDefaultRoomNotificationMode(roomId) else settings.setRoomNotificationMode(roomId, m)
                        }
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showInvite) {
        TextPrompt("Invite someone", "Matrix ID", "@name:server", "", onDismiss = { showInvite = false }) { id ->
            act {
                room.inviteUserById(id.trim())
                context.toast("Invited ${id.trim()}")
            }
        }
    }

    editField?.let { field ->
        TextPrompt(
            if (field == "name") "Room name" else "Topic",
            if (field == "name") "Name" else "Topic",
            "",
            if (field == "name") info?.rawName ?: "" else info?.topic ?: "",
            onDismiss = { editField = null },
        ) { value -> act { if (field == "name") room.setName(value) else room.setTopic(value) } }
    }

    if (showLeave) {
        AlertDialog(
            onDismissRequest = { showLeave = false },
            title = { Text("Leave room?") },
            text = { Text(if (info?.joinRule is org.matrix.rustcomponents.sdk.JoinRule.Public) "You can rejoin later." else "You'll need a new invite to come back.") },
            confirmButton = {
                TextButton(onClick = {
                    showLeave = false
                    scope.launch {
                        runCatching { room.leave() }.onSuccess { nav.reset() }.onFailure { context.toast(errorMessage(it)) }
                    }
                }) { Text("Leave", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showLeave = false }) { Text("Cancel") } },
        )
    }
}

private fun RoomNotificationMode?.label() = when (this) {
    RoomNotificationMode.ALL_MESSAGES -> "All messages"
    RoomNotificationMode.MENTIONS_AND_KEYWORDS_ONLY -> "Mentions and keywords"
    RoomNotificationMode.MUTE -> "Mute"
    null -> "…"
}

@Composable
fun TextPrompt(title: String, label: String, placeholder: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value, { value = it }, label = { Text(label) }, placeholder = { Text(placeholder) }) },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(value)
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MembersScreen(session: MatrixSession, roomId: String, nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val room = remember(roomId) { session.client.getRoom(roomId) } ?: return
    val members = remember { mutableStateListOf<RoomMember>() }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<RoomMember?>(null) }

    LaunchedEffect(roomId) {
        runCatching {
            val it = room.members()
            members.clear()
            while (true) {
                val chunk = it.nextChunk(200u) ?: break
                members += chunk
            }
            members.sortWith(compareByDescending<RoomMember> { (it.powerLevel as? PowerLevel.Value)?.value ?: Long.MAX_VALUE }.thenBy { (it.displayName ?: it.userId).lowercase() })
        }.onFailure { context.toast(errorMessage(it)) }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("People") }, navigationIcon = {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        val filtered = remember(members.size, query) {
            members.filter { m -> m.membership !is MembershipState.Leave && (query.isBlank() || (m.displayName ?: "").contains(query, true) || m.userId.contains(query, true)) }
        }
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search people") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
            items(filtered, key = { it.userId }) { m ->
                ListItem(
                    headlineContent = { Text(m.displayName ?: m.userId) },
                    supportingContent = { Text(m.userId) },
                    leadingContent = { Avatar(m.avatarUrl, m.displayName ?: m.userId, m.userId, 40.dp) },
                    trailingContent = {
                        val role = when {
                            m.membership is MembershipState.Invite -> "Invited"
                            m.membership is MembershipState.Ban -> "Banned"
                            m.powerLevel is PowerLevel.Infinite -> "Owner"
                            ((m.powerLevel as? PowerLevel.Value)?.value ?: 0) >= 100 -> "Admin"
                            ((m.powerLevel as? PowerLevel.Value)?.value ?: 0) >= 50 -> "Moderator"
                            else -> null
                        }
                        role?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
                    },
                    modifier = Modifier.clickable { selected = m },
                )
            }
        }
    }

    selected?.let { m ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Avatar(m.avatarUrl, m.displayName ?: m.userId, m.userId, 56.dp)
                Column {
                    Text(m.displayName ?: m.userId, style = MaterialTheme.typography.titleMedium)
                    Text(m.userId, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (m.userId != session.userId) {
                ListItem(headlineContent = { Text("Send message") }, modifier = Modifier.clickable {
                    selected = null
                    scope.launch {
                        runCatching { openOrCreateDm(session, m.userId) }
                            .onSuccess { nav.push(Screen.Room(it)) }
                            .onFailure { context.toast(errorMessage(it)) }
                    }
                })
                ListItem(headlineContent = { Text("Remove from room") }, modifier = Modifier.clickable {
                    selected = null
                    scope.launch { runCatching { room.kickUser(m.userId, null) }.onFailure { context.toast(errorMessage(it)) } }
                })
                ListItem(headlineContent = { Text("Ban", color = MaterialTheme.colorScheme.error) }, modifier = Modifier.clickable {
                    selected = null
                    scope.launch { runCatching { room.banUser(m.userId, null) }.onFailure { context.toast(errorMessage(it)) } }
                })
                ListItem(headlineContent = { Text(if (m.isIgnored) "Unignore" else "Ignore") }, modifier = Modifier.clickable {
                    selected = null
                    scope.launch {
                        runCatching { if (m.isIgnored) session.client.unignoreUser(m.userId) else session.client.ignoreUser(m.userId) }
                            .onFailure { context.toast(errorMessage(it)) }
                    }
                })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Opens the existing DM with [userId] or creates a new encrypted one. */
suspend fun openOrCreateDm(session: MatrixSession, userId: String): String {
    session.client.getDmRoom(userId)?.let { return it.id() }
    return session.client.createRoom(
        org.matrix.rustcomponents.sdk.CreateRoomParameters(
            name = null,
            isEncrypted = true,
            isDirect = true,
            visibility = org.matrix.rustcomponents.sdk.RoomVisibility.Private,
            preset = org.matrix.rustcomponents.sdk.RoomPreset.TRUSTED_PRIVATE_CHAT,
            invite = listOf(userId),
        ),
    )
}
