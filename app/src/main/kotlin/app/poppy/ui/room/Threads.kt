package app.poppy.ui.room

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.poppy.core.Formatter
import app.poppy.core.MatrixSession
import app.poppy.core.avatar
import app.poppy.core.name
import app.poppy.ui.Navigator
import app.poppy.ui.Screen
import app.poppy.ui.components.Avatar
import app.poppy.ui.components.Time
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.ThreadListEntriesListener
import org.matrix.rustcomponents.sdk.ThreadListItem
import org.matrix.rustcomponents.sdk.ThreadListPaginationStateListener
import org.matrix.rustcomponents.sdk.ThreadListUpdate
import uniffi.matrix_sdk_ui.ThreadListPaginationState

@Immutable
private data class ThreadRow(
    val rootId: String,
    val sender: String,
    val senderId: String,
    val avatar: String?,
    val text: String,
    val latest: String?,
    val replies: Int,
    val ts: Long,
)

private fun ThreadListItem.toRow(): ThreadRow {
    val root = rootEvent
    val sender = root.senderProfile.name() ?: root.sender
    val latestEv = latestEvent
    return ThreadRow(
        rootId = root.eventId,
        sender = sender,
        senderId = root.sender,
        avatar = root.senderProfile.avatar(),
        text = root.content?.let { Formatter.preview(it, sender, includeSender = false) } ?: "Message",
        latest = latestEv?.content?.let { c -> Formatter.preview(c, latestEv.senderProfile.name() ?: latestEv.sender, includeSender = true) },
        replies = numReplies.toInt(),
        ts = (latestEv?.timestamp ?: root.timestamp).toLong(),
    )
}

/** All threads in a room, newest activity first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadsScreen(session: MatrixSession, roomId: String, nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val room = remember(roomId) { session.client.getRoom(roomId) } ?: return
    val service = remember(roomId) { room.threadListService() }
    val rows = remember { mutableStateListOf<ThreadRow>() }
    var loading by remember { mutableStateOf(true) }
    var endReached by remember { mutableStateOf(false) }

    DisposableEffect(roomId) {
        val handles = mutableListOf<TaskHandle>()
        rows.clear()
        rows.addAll(service.items().map { it.toRow() })
        handles += service.subscribeToItemsUpdates(object : ThreadListEntriesListener {
            override fun onUpdate(diff: List<ThreadListUpdate>) {
                scope.launch {
                    diff.forEach { u ->
                        when (u) {
                            is ThreadListUpdate.Append -> rows.addAll(u.values.map { it.toRow() })
                            is ThreadListUpdate.Clear -> rows.clear()
                            is ThreadListUpdate.PushFront -> rows.add(0, u.value.toRow())
                            is ThreadListUpdate.PushBack -> rows.add(u.value.toRow())
                            is ThreadListUpdate.PopFront -> if (rows.isNotEmpty()) rows.removeAt(0)
                            is ThreadListUpdate.PopBack -> if (rows.isNotEmpty()) rows.removeAt(rows.lastIndex)
                            is ThreadListUpdate.Insert -> rows.add(u.index.toInt().coerceAtMost(rows.size), u.value.toRow())
                            is ThreadListUpdate.Set -> if (u.index.toInt() in rows.indices) rows[u.index.toInt()] = u.value.toRow()
                            is ThreadListUpdate.Remove -> if (u.index.toInt() in rows.indices) rows.removeAt(u.index.toInt())
                            is ThreadListUpdate.Truncate -> while (rows.size > u.length.toInt()) rows.removeAt(rows.lastIndex)
                            is ThreadListUpdate.Reset -> {
                                rows.clear()
                                rows.addAll(u.values.map { it.toRow() })
                            }
                        }
                    }
                }
            }
        })
        handles += service.subscribeToPaginationStateUpdates(object : ThreadListPaginationStateListener {
            override fun onUpdate(state: ThreadListPaginationState) {
                loading = state is ThreadListPaginationState.Loading
                endReached = (state as? ThreadListPaginationState.Idle)?.endReached == true
            }
        })
        scope.launch { runCatching { service.paginate() }; loading = false }
        onDispose { handles.forEach { it.cancel() } }
    }

    Scaffold(topBar = {
        Column {
            TopAppBar(
                title = { Text("Threads") },
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(rows, key = { it.rootId }) { t ->
                ListItem(
                    leadingContent = { Avatar(t.avatar, t.sender, t.senderId, 40.dp) },
                    headlineContent = {
                        Text(t.text, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    },
                    overlineContent = { Text("${t.sender} · ${Time.short(context, t.ts)}") },
                    supportingContent = {
                        Column {
                            t.latest?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            Text(
                                "${t.replies} ${if (t.replies == 1) "reply" else "replies"}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                    modifier = Modifier.clickable { nav.push(Screen.Thread(roomId, t.rootId)) },
                )
            }
            if (!endReached && rows.isNotEmpty()) item(key = "more") {
                LaunchedEffect(rows.size) { runCatching { service.paginate() } }
            }
            if (!loading && rows.isEmpty()) item {
                Text(
                    "No threads in this room yet.",
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
