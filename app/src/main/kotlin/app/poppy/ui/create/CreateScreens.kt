package app.poppy.ui.create

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.poppy.core.MatrixSession
import app.poppy.ui.Navigator
import app.poppy.ui.Screen
import app.poppy.ui.components.Avatar
import app.poppy.ui.details.openOrCreateDm
import app.poppy.util.errorMessage
import app.poppy.util.toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.CreateRoomParameters
import org.matrix.rustcomponents.sdk.RoomDescription
import org.matrix.rustcomponents.sdk.RoomDirectorySearchEntriesListener
import org.matrix.rustcomponents.sdk.RoomDirectorySearchEntryUpdate
import org.matrix.rustcomponents.sdk.RoomPreset
import org.matrix.rustcomponents.sdk.RoomVisibility
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.UserProfile

private val mxid = Regex("^@[^:\\s]+:[^\\s]+$")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StartChatScreen(session: MatrixSession, nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<UserProfile>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var opening by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        searching = true
        val found = runCatching { session.client.searchUsers(q, 30u).results }.getOrDefault(emptyList())
        results = if (mxid.matches(q) && found.none { it.userId == q }) {
            listOf(runCatching { session.client.getProfile(q) }.getOrElse { UserProfile(q, null, null, null, null) }) + found
        } else found
        searching = false
    }

    fun open(userId: String) {
        if (opening) return
        opening = true
        scope.launch {
            runCatching { openOrCreateDm(session, userId) }
                .onSuccess { nav.replace(Screen.Room(it)) }
                .onFailure { context.toast(errorMessage(it)) }
            opening = false
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Start a chat") }, navigationIcon = {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                OutlinedTextField(
                    query, { query = it },
                    label = { Text("Name or Matrix ID") },
                    placeholder = { Text("@poppy:example.org") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
                if (searching || opening) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            items(results, key = { it.userId }) { u ->
                ListItem(
                    headlineContent = { Text(u.displayName ?: u.userId) },
                    supportingContent = { Text(u.userId) },
                    leadingContent = { Avatar(u.avatarUrl, u.displayName ?: u.userId, u.userId, 40.dp) },
                    modifier = Modifier.clickable { open(u.userId) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateRoomScreen(session: MatrixSession, nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var public by remember { mutableStateOf(false) }
    var encrypted by remember { mutableStateOf(true) }
    var alias by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("New room") }, navigationIcon = {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Room name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(topic, { topic = it }, label = { Text("Topic (optional)") }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            ListItem(
                headlineContent = { Text("Public room") },
                supportingContent = { Text("Anyone can find and join") },
                trailingContent = {
                    Switch(public, {
                        public = it
                        if (it) encrypted = false
                    })
                },
            )
            if (public) {
                OutlinedTextField(
                    alias, { alias = it.replace(" ", "-").lowercase() },
                    label = { Text("Address") },
                    prefix = { Text("#") },
                    suffix = { Text(":${session.client.userIdServerName()}") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ListItem(
                headlineContent = { Text("End-to-end encryption") },
                supportingContent = { Text("Can't be turned off later") },
                trailingContent = { Switch(encrypted, { encrypted = it }) },
            )
            Spacer(Modifier.height(24.dp))
            Button(
                enabled = !busy && name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val id = session.client.createRoom(
                                CreateRoomParameters(
                                    name = name.trim(),
                                    topic = topic.trim().ifBlank { null },
                                    isEncrypted = encrypted,
                                    isDirect = false,
                                    visibility = if (public) RoomVisibility.Public else RoomVisibility.Private,
                                    preset = if (public) RoomPreset.PUBLIC_CHAT else RoomPreset.PRIVATE_CHAT,
                                    canonicalAlias = alias.trim().ifBlank { null }?.takeIf { public },
                                ),
                            )
                            nav.replace(Screen.Room(id))
                        } catch (t: Throwable) {
                            context.toast(errorMessage(t))
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Create") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(session: MatrixSession, nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val search = remember { session.client.roomDirectorySearch() }
    val rooms = remember { mutableStateListOf<RoomDescription>() }
    var query by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf<TaskHandle?>(null) }

    DisposableEffect(Unit) {
        scope.launch {
            handle = search.results(object : RoomDirectorySearchEntriesListener {
                override fun onUpdate(roomEntriesUpdate: List<RoomDirectorySearchEntryUpdate>) {
                    scope.launch {
                        roomEntriesUpdate.forEach { u ->
                            when (u) {
                                is RoomDirectorySearchEntryUpdate.Append -> rooms.addAll(u.values)
                                is RoomDirectorySearchEntryUpdate.Clear -> rooms.clear()
                                is RoomDirectorySearchEntryUpdate.PushBack -> rooms.add(u.value)
                                is RoomDirectorySearchEntryUpdate.PushFront -> rooms.add(0, u.value)
                                is RoomDirectorySearchEntryUpdate.PopBack -> if (rooms.isNotEmpty()) rooms.removeAt(rooms.lastIndex)
                                is RoomDirectorySearchEntryUpdate.PopFront -> if (rooms.isNotEmpty()) rooms.removeAt(0)
                                is RoomDirectorySearchEntryUpdate.Insert -> rooms.add(u.index.toInt().coerceAtMost(rooms.size), u.value)
                                is RoomDirectorySearchEntryUpdate.Set -> if (u.index.toInt() in rooms.indices) rooms[u.index.toInt()] = u.value
                                is RoomDirectorySearchEntryUpdate.Remove -> if (u.index.toInt() in rooms.indices) rooms.removeAt(u.index.toInt())
                                is RoomDirectorySearchEntryUpdate.Truncate -> while (rooms.size > u.length.toInt()) rooms.removeAt(rooms.lastIndex)
                                is RoomDirectorySearchEntryUpdate.Reset -> {
                                    rooms.clear()
                                    rooms.addAll(u.values)
                                }
                            }
                        }
                    }
                }
            })
        }
        onDispose { handle?.cancel() }
    }

    LaunchedEffect(query, server) {
        delay(350)
        runCatching { search.search(query.trim().ifBlank { null }, 30u, server.trim().ifBlank { null }) }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Explore rooms") }, navigationIcon = {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                OutlinedTextField(query, { query = it }, placeholder = { Text("Search public rooms") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp))
                OutlinedTextField(server, { server = it }, placeholder = { Text("Server (optional, e.g. matrix.org)") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(16.dp))
            }
            items(rooms, key = { it.roomId }) { r ->
                ListItem(
                    headlineContent = { Text(r.name ?: r.alias ?: r.roomId, maxLines = 1) },
                    supportingContent = { Text(listOfNotNull(r.alias, "${r.joinedMembers} members", r.topic).joinToString(" · "), maxLines = 2, style = MaterialTheme.typography.bodySmall) },
                    leadingContent = { Avatar(r.avatarUrl, r.name ?: r.roomId, r.roomId, 44.dp) },
                    modifier = Modifier.clickable {
                        scope.launch {
                            runCatching { session.client.joinRoomByIdOrAlias(r.alias ?: r.roomId, listOfNotNull(server.trim().ifBlank { null })) }
                                .onSuccess { nav.replace(Screen.Room(it.id())) }
                                .onFailure { context.toast(errorMessage(it)) }
                        }
                    },
                )
            }
            item {
                if (rooms.isNotEmpty()) {
                    LaunchedEffect(rooms.size) { runCatching { if (!search.isAtLastPage()) search.nextPage() } }
                }
            }
        }
    }
}
