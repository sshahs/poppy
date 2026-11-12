package app.poppy.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import app.poppy.R
import app.poppy.core.MatrixManager
import app.poppy.core.MatrixSession
import app.poppy.core.SessionState
import app.poppy.core.VerificationStep
import app.poppy.graph
import app.poppy.ui.create.CreateRoomScreen
import app.poppy.ui.create.ExploreScreen
import app.poppy.ui.create.StartChatScreen
import app.poppy.ui.details.MembersScreen
import app.poppy.ui.details.RoomDetailsScreen
import app.poppy.ui.details.openOrCreateDm
import app.poppy.ui.login.LoginScreen
import app.poppy.ui.media.MediaViewerScreen
import app.poppy.ui.media.SharePickerScreen
import app.poppy.ui.room.RoomScreen
import app.poppy.ui.rooms.RoomListScreen
import app.poppy.ui.settings.AboutScreen
import app.poppy.ui.settings.AppearanceScreen
import app.poppy.ui.settings.NotificationSettingsScreen
import app.poppy.ui.settings.SecurityScreen
import app.poppy.ui.settings.SettingsScreen
import app.poppy.ui.settings.VerificationScreen
import app.poppy.ui.theme.PoppyTheme
import app.poppy.util.Permalinks
import app.poppy.util.errorMessage
import app.poppy.util.toast
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val nav = Navigator()
    private var pendingLink by mutableStateOf<Permalinks.Target?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Poppy)
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { PoppyRoot(nav, pendingLink) { pendingLink = null } }
        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val data = intent.data
        when {
            intent.action == Intent.ACTION_VIEW && data?.scheme == MatrixManager.OAUTH_SCHEME -> lifecycleScope.launch {
                try {
                    graph.matrix.completeBrowserLogin(data.toString())
                } catch (t: Throwable) {
                    toast("Login failed: ${errorMessage(t)}")
                    graph.matrix.cancelBrowserLogin()
                }
            }
            intent.action == ACTION_OPEN_ROOM -> intent.getStringExtra(EXTRA_ROOM_ID)?.let { nav.reset(Screen.Room(it)) }
            intent.action == Intent.ACTION_VIEW && data != null -> pendingLink = Permalinks.parse(data)
            intent.action == Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                lifecycleScope.launch {
                    graph.matrix.state.first { it is SessionState.LoggedIn }
                    nav.push(Screen.SharePicker(text, listOfNotNull(stream)))
                }
            }
        }
    }

    companion object {
        const val ACTION_OPEN_ROOM = "app.poppy.OPEN_ROOM"
        const val EXTRA_ROOM_ID = "room_id"
    }
}

@Composable
private fun PoppyRoot(nav: Navigator, pendingLink: Permalinks.Target?, onLinkHandled: () -> Unit) {
    val g = LocalContext.current.graph
    val state by g.matrix.state.collectAsState()
    val theme by g.settings.themeMode.collectAsState()
    val dynamic by g.settings.dynamicColor.collectAsState()
    val amoled by g.settings.amoled.collectAsState()
    PoppyTheme(theme, dynamic, amoled) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (val s = state) {
                SessionState.Loading -> Splash()
                SessionState.LoggedOut -> {
                    LaunchedEffect(Unit) { nav.reset() }
                    LoginScreen()
                }
                is SessionState.LoggedIn -> {
                    MainContent(s.session, nav)
                    if (pendingLink != null) LinkDialog(s.session, pendingLink, nav, onLinkHandled)
                }
            }
        }
    }
}

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Image(painterResource(R.drawable.ic_poppy), contentDescription = null, modifier = Modifier.size(120.dp))
    }
}

@Composable
private fun MainContent(session: MatrixSession, nav: Navigator) {
    val holder = rememberSaveableStateHolder()
    val step by session.verification.step.collectAsState()
    LaunchedEffect(step) {
        if (step is VerificationStep.Incoming && nav.current != Screen.Verification) nav.push(Screen.Verification)
    }
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }
    val depth = nav.stack.size
    var lastDepth by remember { mutableStateOf(depth) }
    val forward = depth >= lastDepth
    LaunchedEffect(depth) { lastDepth = depth }

    AnimatedContent(
        targetState = nav.current,
        contentKey = { it.key },
        transitionSpec = {
            val dir = if (forward) 1 else -1
            (slideInHorizontally(tween(220)) { w -> dir * w / 4 } + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(tween(180)) { w -> -dir * w / 4 } + fadeOut(tween(160)))
        },
        label = "nav",
    ) { screen ->
        holder.SaveableStateProvider(screen.key) {
            when (screen) {
                Screen.Rooms -> RoomListScreen(session, nav)
                is Screen.Room -> RoomScreen(session, screen.roomId, nav)
                is Screen.RoomDetails -> RoomDetailsScreen(session, screen.roomId, nav)
                is Screen.Members -> MembersScreen(session, screen.roomId, nav)
                Screen.StartChat -> StartChatScreen(session, nav)
                Screen.CreateRoom -> CreateRoomScreen(session, nav)
                Screen.Explore -> ExploreScreen(session, nav)
                is Screen.Space -> app.poppy.ui.rooms.SpaceScreen(session, screen.spaceId, nav)
                Screen.Settings -> SettingsScreen(session, nav)
                Screen.Security -> SecurityScreen(session, nav)
                Screen.Verification -> VerificationScreen(session, nav)
                Screen.Notifications -> NotificationSettingsScreen(session, nav)
                Screen.Appearance -> AppearanceScreen(nav)
                Screen.About -> AboutScreen(nav)
                is Screen.Media -> MediaViewerScreen(session, screen.source, screen.filename, screen.mimeType, nav)
                is Screen.SharePicker -> SharePickerScreen(session, screen.text, screen.uris, nav)
            }
        }
    }
}

@Composable
private fun LinkDialog(session: MatrixSession, target: Permalinks.Target, nav: Navigator, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Already joined? Open directly.
    LaunchedEffect(target) {
        val roomId = when {
            target.id.startsWith("!") -> target.id
            target.id.startsWith("#") -> runCatching { session.client.resolveRoomAlias(target.id)?.roomId }.getOrNull()
            else -> null
        }
        val joined = roomId?.let { session.client.getRoom(it) }
        if (joined != null && joined.membership() == org.matrix.rustcomponents.sdk.Membership.JOINED) {
            nav.push(Screen.Room(joined.id()))
            onDone()
        }
    }
    val isUser = target.id.startsWith("@")
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (isUser) "Message ${target.id}?" else "Join ${target.id}?") },
        text = { Text(if (isUser) "Start a direct conversation." else "You'll join this room and see its messages.") },
        confirmButton = {
            TextButton(onClick = {
                onDone()
                scope.launch {
                    runCatching {
                        if (isUser) openOrCreateDm(session, target.id)
                        else session.client.joinRoomByIdOrAlias(target.id, target.via).id()
                    }.onSuccess { nav.push(Screen.Room(it)) }.onFailure { context.toast(errorMessage(it)) }
                }
            }) { Text(if (isUser) "Message" else "Join") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } },
    )
}
