package app.poppy.ui.rooms

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.MarkChatUnread
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.poppy.core.MatrixSession
import app.poppy.core.RoomFilter
import app.poppy.core.RoomSummary
import app.poppy.graph
import app.poppy.ui.Navigator
import app.poppy.ui.Screen
import app.poppy.ui.components.Avatar
import app.poppy.ui.components.EmptyState
import app.poppy.ui.components.Time
import app.poppy.util.errorMessage
import app.poppy.util.toast
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.RecoveryState
import org.matrix.rustcomponents.sdk.VerificationState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomListScreen(session: MatrixSession, nav: Navigator) {
    val rooms by session.rooms.rooms.collectAsState()
    val loaded by session.rooms.loaded.collectAsState()
    val filter by session.rooms.filter.collectAsState()
    val syncing by session.syncIndicator.collectAsState()
    val profile by session.profile.collectAsState()
    val verification by session.verificationState.collectAsState()
    val recovery by session.recoveryState.collectAsState()
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var fabMenu by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<RoomSummary?>(null) }
    var showJoin by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    // Infinite scrolling of the sliding-sync window.
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { last -> if (last > listState.layoutInfo.totalItemsCount - 20) session.rooms.loadMore() }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            Column {
                if (searching) {
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    TopAppBar(
                        title = {
                            TextField(
                                value = query,
                                onValueChange = {
                                    query = it
                                    session.rooms.setQuery(it)
                                },
                                placeholder = { Text("Search chats") },
                                singleLine = true,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = Color.Transparent,
                                    unfocusedIndicatorColor = Color.Transparent,
                                ),
                                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            )
                        },
                        navigationIcon = {
                            IconButton(onClick = {
                                searching = false
                                query = ""
                                session.rooms.setQuery("")
                            }) { Icon(Icons.Filled.Close, "Close search") }
                        },
                    )
                } else {
                    TopAppBar(
                        title = { Text("Chats", fontWeight = FontWeight.SemiBold) },
                        navigationIcon = {
                            IconButton(onClick = { nav.push(Screen.Settings) }) {
                                Avatar(profile.avatarUrl, profile.displayName ?: profile.userId, profile.userId, 34.dp)
                            }
                        },
                        actions = {
                            IconButton(onClick = { searching = true }) { Icon(Icons.Filled.Search, "Search") }
                        },
                        scrollBehavior = scrollBehavior,
                    )
                }
                if (syncing || !loaded) LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp)) else Spacer(Modifier.height(2.dp))
            }
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { fabMenu = true }) { Icon(Icons.Filled.Edit, "New chat") }
                DropdownMenu(expanded = fabMenu, onDismissRequest = { fabMenu = false }) {
                    DropdownMenuItem(text = { Text("Start a chat") }, leadingIcon = { Icon(Icons.Filled.PersonAdd, null) }, onClick = {
                        fabMenu = false
                        nav.push(Screen.StartChat)
                    })
                    DropdownMenuItem(text = { Text("New room") }, leadingIcon = { Icon(Icons.Filled.GroupAdd, null) }, onClick = {
                        fabMenu = false
                        nav.push(Screen.CreateRoom)
                    })
                    DropdownMenuItem(text = { Text("Join by address") }, leadingIcon = { Icon(Icons.Filled.Add, null) }, onClick = {
                        fabMenu = false
                        showJoin = true
                    })
                    DropdownMenuItem(text = { Text("Explore public rooms") }, leadingIcon = { Icon(Icons.Filled.Explore, null) }, onClick = {
                        fabMenu = false
                        nav.push(Screen.Explore)
                    })
                }
            }
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
        ) {
            item(key = "banner", contentType = "banner") {
                SecurityBanner(verification, recovery) { nav.push(Screen.Security) }
            }
            item(key = "filters", contentType = "filters") {
                LazyRow(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(RoomFilter.entries, key = { it.name }) { f ->
                        FilterChip(
                            selected = f == filter,
                            onClick = { session.rooms.setFilter(if (f == filter) RoomFilter.ALL else f) },
                            label = { Text(f.label()) },
                        )
                    }
                }
            }
            if (loaded && rooms.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = if (filter == RoomFilter.ALL && query.isEmpty()) "No chats yet" else "Nothing here",
                        body = if (filter == RoomFilter.ALL && query.isEmpty()) "Start a conversation or join a room to get going." else "Try another filter.",
                    ) {
                        if (filter == RoomFilter.ALL) Button(onClick = { nav.push(Screen.StartChat) }) { Text("Start a chat") }
                    }
                }
            }
            items(rooms, key = { it.id }, contentType = { if (it.isInvite) "invite" else "room" }) { room ->
                if (room.isInvite) {
                    InviteRow(room, session)
                } else {
                    RoomRow(room, onClick = { nav.push(Screen.Room(room.id)) }, onLongClick = { actionsFor = room })
                }
            }
            item(key = "bottom-space") { Spacer(Modifier.height(88.dp)) }
        }
    }

    actionsFor?.let { room -> RoomActionsSheet(room, session, onDismiss = { actionsFor = null }) }
    if (showJoin) JoinRoomDialog(session, onDismiss = { showJoin = false }, onJoined = { nav.push(Screen.Room(it)) })
}

private fun RoomFilter.label() = when (this) {
    RoomFilter.ALL -> "All"
    RoomFilter.UNREAD -> "Unread"
    RoomFilter.PEOPLE -> "People"
    RoomFilter.GROUPS -> "Rooms"
    RoomFilter.FAVOURITES -> "Favourites"
    RoomFilter.INVITES -> "Invites"
}

@Composable
private fun SecurityBanner(verification: VerificationState, recovery: RecoveryState, onClick: () -> Unit) {
    val (title, body) = when {
        verification == VerificationState.UNVERIFIED -> "Verify this device" to "Confirm it's you to read your encrypted message history."
        recovery == RecoveryState.DISABLED -> "Set up recovery" to "Protect your encrypted messages in case you lose all your devices."
        recovery == RecoveryState.INCOMPLETE -> "Finish recovery setup" to "Enter your recovery key to restore your message keys."
        else -> return
    }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.VerifiedUser, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RoomRow(room: RoomSummary, onClick: () -> Unit, onLongClick: () -> Unit) {
    val context = LocalContext.current
    val time = remember(room.lastMessageTs) { Time.short(context, room.lastMessageTs) }
    val highlight = room.hasUnread && !room.isMuted
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(room.avatarUrl, room.name, room.id, 52.dp)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    room.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (highlight) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (room.isFavourite) Icon(Icons.Filled.Star, null, Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.tertiary)
                if (room.hasCall) Icon(Icons.Filled.Call, null, Modifier.padding(start = 4.dp).size(14.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.weight(1f))
                Text(
                    time,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    room.lastMessage ?: room.topic ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (highlight) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (room.isMuted) Icon(Icons.Filled.NotificationsOff, "Muted", Modifier.padding(start = 6.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                when {
                    room.unreadMentions > 0 -> Badge(Modifier.padding(start = 6.dp), containerColor = MaterialTheme.colorScheme.primary) {
                        Icon(Icons.Filled.AlternateEmail, null, Modifier.size(12.dp))
                    }
                    room.unreadNotifications > 0 -> Badge(
                        Modifier.padding(start = 6.dp),
                        containerColor = if (room.isMuted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                    ) { Text(if (room.unreadNotifications > 99) "99+" else room.unreadNotifications.toString()) }
                    room.hasUnread -> Box(
                        Modifier.padding(start = 8.dp).size(10.dp).background(
                            if (room.isMuted) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                            CircleShape,
                        ),
                    )
                }
            }
        }
    }
}

@Composable
private fun InviteRow(room: RoomSummary, session: MatrixSession) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(room.avatarUrl, room.name, room.id, 52.dp)
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(room.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    room.inviter?.let { "$it invited you" } ?: "You were invited",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(Modifier.padding(start = 66.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching { session.client.getRoom(room.id)?.leave() }.onFailure { context.toast(errorMessage(it)) }
                    busy = false
                }
            }, shape = RoundedCornerShape(12.dp)) { Text("Decline") }
            Button(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    runCatching { session.client.getRoom(room.id)?.join() }.onFailure { context.toast(errorMessage(it)) }
                    busy = false
                }
            }, shape = RoundedCornerShape(12.dp)) { Text("Accept") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomActionsSheet(room: RoomSummary, session: MatrixSession, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun run(block: suspend (org.matrix.rustcomponents.sdk.Room) -> Unit) {
        onDismiss()
        scope.launch {
            val r = session.client.getRoom(room.id) ?: return@launch
            runCatching { block(r) }.onFailure { context.toast(errorMessage(it)) }
            session.rooms.refresh(room.id)
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(room.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        ListItem(
            headlineContent = { Text(if (room.hasUnread) "Mark as read" else "Mark as unread") },
            leadingContent = { Icon(if (room.hasUnread) Icons.Filled.DoneAll else Icons.Filled.MarkChatUnread, null) },
            modifier = Modifier.clickable {
                run {
                    if (room.hasUnread) {
                        it.markAsRead(org.matrix.rustcomponents.sdk.ReceiptType.READ)
                        it.setUnreadFlag(false)
                        context.graph.notifications.clearRoom(room.id)
                    } else {
                        it.setUnreadFlag(true)
                    }
                }
            },
        )
        ListItem(
            headlineContent = { Text(if (room.isFavourite) "Remove from favourites" else "Add to favourites") },
            leadingContent = { Icon(if (room.isFavourite) Icons.Filled.StarBorder else Icons.Filled.Star, null) },
            modifier = Modifier.clickable { run { it.setIsFavourite(!room.isFavourite, null) } },
        )
        ListItem(
            headlineContent = { Text("Leave") },
            leadingContent = { Icon(Icons.AutoMirrored.Filled.ExitToApp, null, tint = MaterialTheme.colorScheme.error) },
            modifier = Modifier.clickable { run { it.leave() } },
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
fun JoinRoomDialog(session: MatrixSession, onDismiss: () -> Unit, onJoined: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Join a room") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Room address") },
                    placeholder = { Text("#room:example.org") },
                    singleLine = true,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(enabled = !busy && address.isNotBlank(), onClick = {
                busy = true
                scope.launch {
                    try {
                        val room = session.client.joinRoomByIdOrAlias(address.trim(), emptyList())
                        onDismiss()
                        onJoined(room.id())
                    } catch (t: Throwable) {
                        error = errorMessage(t)
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Join") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
