package app.poppy.ui.rooms

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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.SearchServicePaginationStateListener
import org.matrix.rustcomponents.sdk.SearchServiceResult
import org.matrix.rustcomponents.sdk.SearchServiceResultsListener
import org.matrix.rustcomponents.sdk.SearchServiceResultsUpdate
import org.matrix.rustcomponents.sdk.TaskHandle
import uniffi.matrix_sdk_ui.SearchServicePaginationState

@Immutable
private data class Hit(
    val roomId: String,
    val roomName: String,
    val eventId: String,
    val sender: String,
    val senderId: String,
    val avatar: String?,
    val text: String,
    val ts: Long,
)

/** Searches all rooms, including encrypted ones, using the SDK's on-device index. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(session: MatrixSession, nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val service = remember { session.client.searchService() }
    val hits = remember { mutableStateListOf<Hit>() }
    var query by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var endReached by remember { mutableStateOf(true) }
    val roomNames = remember { HashMap<String, String>() }
    val focus = remember { FocusRequester() }

    fun toHit(r: SearchServiceResult): Hit? = when (r) {
        is SearchServiceResult.Message -> {
            val m = r.result
            val sender = m.senderProfile.name() ?: m.sender
            Hit(
                roomId = r.roomId,
                roomName = roomNames.getOrPut(r.roomId) { session.client.getRoom(r.roomId)?.displayName() ?: r.roomId },
                eventId = m.eventId,
                sender = sender,
                senderId = m.sender,
                avatar = m.senderProfile.avatar(),
                text = Formatter.preview(m.content, sender, includeSender = false),
                ts = m.timestamp.toLong(),
            )
        }
    }

    DisposableEffect(Unit) {
        val handles = mutableListOf<TaskHandle>()
        scope.launch {
            handles += service.subscribeToResults(object : SearchServiceResultsListener {
                override fun onUpdate(updates: List<SearchServiceResultsUpdate>) {
                    scope.launch {
                        updates.forEach { u ->
                            when (u) {
                                is SearchServiceResultsUpdate.Append -> hits.addAll(u.values.mapNotNull(::toHit))
                                is SearchServiceResultsUpdate.Clear -> hits.clear()
                                is SearchServiceResultsUpdate.PushFront -> toHit(u.value)?.let { hits.add(0, it) }
                                is SearchServiceResultsUpdate.PushBack -> toHit(u.value)?.let { hits.add(it) }
                                is SearchServiceResultsUpdate.PopFront -> if (hits.isNotEmpty()) hits.removeAt(0)
                                is SearchServiceResultsUpdate.PopBack -> if (hits.isNotEmpty()) hits.removeAt(hits.lastIndex)
                                is SearchServiceResultsUpdate.Insert -> toHit(u.value)?.let { hits.add(u.index.toInt().coerceAtMost(hits.size), it) }
                                is SearchServiceResultsUpdate.Set -> toHit(u.value)?.let { if (u.index.toInt() in hits.indices) hits[u.index.toInt()] = it }
                                is SearchServiceResultsUpdate.Remove -> if (u.index.toInt() in hits.indices) hits.removeAt(u.index.toInt())
                                is SearchServiceResultsUpdate.Truncate -> while (hits.size > u.length.toInt()) hits.removeAt(hits.lastIndex)
                                is SearchServiceResultsUpdate.Reset -> {
                                    hits.clear()
                                    hits.addAll(u.values.mapNotNull(::toHit))
                                }
                            }
                        }
                    }
                }
            })
        }
        handles += service.subscribeToPaginationStateUpdates(object : SearchServicePaginationStateListener {
            override fun onUpdate(paginationState: SearchServicePaginationState) {
                loading = paginationState is SearchServicePaginationState.Loading
                endReached = (paginationState as? SearchServicePaginationState.Idle)?.endReached != false
            }
        })
        onDispose { handles.forEach { it.cancel() } }
    }

    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            hits.clear()
            return@LaunchedEffect
        }
        delay(300)
        runCatching {
            service.setQuery(q)
            service.paginate()
        }
    }

    Scaffold(topBar = {
        Column {
            TopAppBar(
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search messages") },
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
                navigationIcon = { IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(hits, key = { it.roomId + it.eventId }) { h ->
                ListItem(
                    leadingContent = { Avatar(h.avatar, h.sender, h.senderId, 40.dp) },
                    overlineContent = { Text("${h.roomName} · ${Time.short(context, h.ts)}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    headlineContent = { Text(h.sender, maxLines = 1) },
                    supportingContent = { Text(h.text, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.clickable { nav.push(Screen.Room(h.roomId, h.eventId)) },
                )
            }
            if (!endReached && hits.isNotEmpty()) item(key = "more") {
                LaunchedEffect(hits.size) { runCatching { service.paginate() } }
            }
            if (query.trim().length >= 2 && !loading && hits.isEmpty()) item {
                Text(
                    "No messages found. Older encrypted history is indexed in the background while Poppy is open.",
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
