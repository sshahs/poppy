package app.poppy.ui.media

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.poppy.core.MatrixSession
import app.poppy.core.media.MediaRequest
import app.poppy.core.media.downloadToCache
import app.poppy.core.media.saveToDownloads
import app.poppy.ui.Navigator
import app.poppy.ui.Screen
import app.poppy.ui.components.Avatar
import app.poppy.util.errorMessage
import app.poppy.util.importUri
import app.poppy.util.shareFile
import app.poppy.util.toast
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown

/** Full-screen, pinch-to-zoom image viewer. */
@Composable
fun MediaViewerScreen(session: MatrixSession, source: MediaSource, filename: String, mime: String?, nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var chrome by remember { mutableStateOf(true) }
    val request = remember(source) { MediaRequest.full(source) }

    fun withFile(block: suspend (java.io.File) -> Unit) {
        scope.launch {
            runCatching { block(session.client.downloadToCache(context, source, filename, mime)) }.onFailure { context.toast(errorMessage(it)) }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { chrome = !chrome },
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else scale = 2.5f
                    },
                )
            },
    ) {
        AsyncImage(
            model = request,
            contentDescription = filename,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        )
        if (chrome) {
            Row(
                Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.4f)).statusBarsPadding().padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Text(filename, color = Color.White, maxLines = 1, modifier = Modifier.weight(1f))
                IconButton(onClick = { withFile { context.shareFile(it, mime) } }) { Icon(Icons.Filled.Share, "Share", tint = Color.White) }
                IconButton(onClick = {
                    withFile { f ->
                        val ok = saveToDownloads(context, f, filename, mime)
                        context.toast(if (ok) "Saved to Downloads/Poppy" else "Couldn't save")
                    }
                }) { Icon(Icons.Filled.Download, "Save", tint = Color.White) }
            }
        }
    }
}

/** Picks a room to send shared text/files to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SharePickerScreen(session: MatrixSession, text: String?, uris: List<Uri>, nav: Navigator) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rooms by session.rooms.rooms.collectAsState()
    Scaffold(topBar = {
        TopAppBar(title = { Text("Send to…") }, navigationIcon = {
            IconButton(onClick = { nav.pop() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        })
    }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            items(rooms.filter { !it.isInvite }, key = { it.id }) { room ->
                ListItem(
                    headlineContent = { Text(room.name) },
                    leadingContent = { Avatar(room.avatarUrl, room.name, room.id, 40.dp) },
                    modifier = Modifier.clickable {
                        scope.launch {
                            try {
                                val r = session.client.getRoom(room.id) ?: return@launch
                                val tl = r.timeline()
                                text?.takeIf { it.isNotBlank() }?.let { tl.send(messageEventContentFromMarkdown(it)) }
                                for (uri in uris) {
                                    val f = withContext(Dispatchers.IO) { context.importUri(uri) } ?: continue
                                    val params = UploadParameters(UploadSource.File(f.file.absolutePath), null, null, null, null)
                                    if (f.mime.startsWith("image/")) {
                                        tl.sendImage(params, null, org.matrix.rustcomponents.sdk.ImageInfo(f.height.toULong(), f.width.toULong(), f.mime, f.size.toULong(), null, null, null, null)).join()
                                    } else {
                                        tl.sendFile(params, org.matrix.rustcomponents.sdk.FileInfo(f.mime, f.size.toULong(), null, null)).join()
                                    }
                                }
                                nav.replace(Screen.Room(room.id))
                            } catch (t: Throwable) {
                                context.toast(errorMessage(t))
                            }
                        }
                    },
                )
            }
        }
    }
}
