package app.poppy.ui.rooms

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.poppy.core.MatrixSession
import app.poppy.core.SpaceChild
import app.poppy.core.SpaceUi
import app.poppy.core.media.MediaRequest
import app.poppy.core.toChild
import app.poppy.ui.Navigator
import app.poppy.ui.Screen
import app.poppy.ui.components.Avatar
import app.poppy.ui.components.avatarColor
import app.poppy.ui.components.initialOf
import app.poppy.util.errorMessage
import app.poppy.util.toast
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.SpaceListUpdate
import org.matrix.rustcomponents.sdk.SpaceRoomListEntriesListener
import org.matrix.rustcomponents.sdk.SpaceRoomListPaginationStateListener
import org.matrix.rustcomponents.sdk.TaskHandle
import uniffi.matrix_sdk_ui.SpaceRoomListPaginationState

/** Rounded-square avatar used for spaces (circles are for people and rooms). */
@Composable
fun SpaceAvatar(url: String?, name: String, id: String, size: Dp, selected: Boolean = false) {
    val shape = RoundedCornerShape(size / 4)
    app.poppy.ui.components.AvatarImage(
        url, name, id, size, shape,
        if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier,
    )
}

/** Horizontal space switcher shown above the chat list. */
@Composable
fun SpaceStrip(spaces: List<SpaceUi>, selected: SpaceUi?, onSelect: (SpaceUi?) -> Unit, onBrowse: (SpaceUi) -> Unit) {
    if (spaces.isEmpty()) return
    // Top-level spaces, plus the selected space's own subspaces.
    val shown = remember(spaces, selected) {
        val top = spaces.filter { it.level == 0 }
        val subs = selected?.let { sel -> spaces.filter { it.level > 0 && it.id in sel.roomIds } }.orEmpty()
        top + subs.filter { s -> top.none { it.id == s.id } }
    }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(key = "home") {
            SpaceTile("All chats", selected == null, onClick = { onSelect(null) }) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(11.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Home, null, tint = MaterialTheme.colorScheme.onSecondaryContainer) }
            }
        }
        items(shown, key = { it.id }) { s ->
            val isSel = selected?.id == s.id
            SpaceTile(s.name, isSel, onClick = { if (isSel) onBrowse(s) else onSelect(s) }) {
                SpaceAvatar(s.avatarUrl, s.name, s.id, 44.dp, isSel)
            }
        }
    }
}

@Composable
private fun SpaceTile(label: String, selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Column(
        Modifier.width(64.dp).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon()
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

/** Browse a space's rooms and subspaces (hierarchy), joining any you're not in yet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpaceScreen(session: MatrixSession, spaceId: String, nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val children = remember { mutableStateListOf<SpaceChild>() }
    var title by remember { mutableStateOf(session.client.getRoom(spaceId)?.displayName() ?: "Space") }
    var loading by remember { mutableStateOf(true) }
    var endReached by remember { mutableStateOf(false) }
    var joining by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<org.matrix.rustcomponents.sdk.SpaceRoomList?>(null) }

    DisposableEffect(spaceId) {
        val handles = mutableListOf<TaskHandle>()
        scope.launch {
            try {
                val service = session.spaces.service ?: session.client.spaceService()
                val l = service.spaceRoomList(spaceId)
                list = l
                l.space()?.let { title = it.displayName }
                handles += l.subscribeToPaginationStateUpdates(object : SpaceRoomListPaginationStateListener {
                    override fun onUpdate(paginationState: SpaceRoomListPaginationState) {
                        loading = paginationState is SpaceRoomListPaginationState.Loading
                        endReached = (paginationState as? SpaceRoomListPaginationState.Idle)?.endReached == true
                    }
                })
                handles += l.subscribeToRoomUpdate(object : SpaceRoomListEntriesListener {
                    override fun onUpdate(rooms: List<SpaceListUpdate>) {
                        scope.launch {
                            rooms.forEach { u ->
                                when (u) {
                                    is SpaceListUpdate.Append -> children.addAll(u.values.map { it.toChild() })
                                    is SpaceListUpdate.Clear -> children.clear()
                                    is SpaceListUpdate.PushFront -> children.add(0, u.value.toChild())
                                    is SpaceListUpdate.PushBack -> children.add(u.value.toChild())
                                    is SpaceListUpdate.PopFront -> if (children.isNotEmpty()) children.removeAt(0)
                                    is SpaceListUpdate.PopBack -> if (children.isNotEmpty()) children.removeAt(children.lastIndex)
                                    is SpaceListUpdate.Insert -> children.add(u.index.toInt().coerceAtMost(children.size), u.value.toChild())
                                    is SpaceListUpdate.Set -> if (u.index.toInt() in children.indices) children[u.index.toInt()] = u.value.toChild()
                                    is SpaceListUpdate.Remove -> if (u.index.toInt() in children.indices) children.removeAt(u.index.toInt())
                                    is SpaceListUpdate.Truncate -> while (children.size > u.length.toInt()) children.removeAt(children.lastIndex)
                                    is SpaceListUpdate.Reset -> {
                                        children.clear()
                                        children.addAll(u.values.map { it.toChild() })
                                    }
                                }
                            }
                        }
                    }
                })
                children.clear()
                children.addAll(l.rooms().map { it.toChild() })
                l.paginate()
            } catch (t: Throwable) {
                loading = false
                context.toast(errorMessage(t))
            }
        }
        onDispose { handles.forEach { it.cancel() } }
    }

    Scaffold(topBar = {
        Column {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(children, key = { it.id }) { c ->
                ListItem(
                    headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium) },
                    supportingContent = {
                        Text(
                            listOfNotNull(
                                if (c.isSpace) "Space · ${c.childCount} rooms" else "${c.members} members",
                                c.topic?.takeIf { it.isNotBlank() },
                            ).joinToString(" · "),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingContent = { if (c.isSpace) SpaceAvatar(c.avatarUrl, c.name, c.id, 44.dp) else Avatar(c.avatarUrl, c.name, c.id, 44.dp) },
                    trailingContent = {
                        when {
                            c.isSpace -> Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null)
                            c.joined -> Text("Joined", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            else -> Button(
                                enabled = joining == null,
                                contentPadding = PaddingValues(horizontal = 14.dp),
                                onClick = {
                                    joining = c.id
                                    scope.launch {
                                        runCatching { session.client.joinRoomByIdOrAlias(c.id, c.via) }
                                            .onSuccess { nav.push(Screen.Room(it.id())) }
                                            .onFailure { context.toast(errorMessage(it)) }
                                        joining = null
                                    }
                                },
                            ) { Text(if (joining == c.id) "Joining…" else "Join") }
                        }
                    },
                    modifier = Modifier.clickable {
                        when {
                            c.isSpace -> nav.push(Screen.Space(c.id))
                            c.joined -> nav.push(Screen.Room(c.id))
                        }
                    },
                )
            }
            if (!endReached && children.isNotEmpty()) item(key = "more") {
                androidx.compose.runtime.LaunchedEffect(children.size) { runCatching { list?.paginate() } }
            }
            if (!loading && children.isEmpty()) item {
                Text(
                    "This space has no rooms you can see.",
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
