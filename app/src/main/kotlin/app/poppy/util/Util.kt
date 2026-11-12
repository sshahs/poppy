package app.poppy.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import org.matrix.rustcomponents.sdk.ClientException
import java.io.File

fun errorMessage(t: Throwable): String = when (t) {
    is ClientException.MatrixApi -> t.msg
    is ClientException.Generic -> t.msg
    else -> t.message ?: t.javaClass.simpleName
}

fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

fun Context.copyToClipboard(text: String, label: String = "Poppy") {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}

fun Context.shareText(text: String) {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
}

/** A local copy of user-picked content, ready for upload. */
data class PickedFile(
    val file: File,
    val name: String,
    val mime: String,
    val size: Long,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
)

/** Copies a content:// Uri into our cache so the Rust SDK can stream it from disk. */
fun Context.importUri(uri: Uri): PickedFile? {
    val resolver = contentResolver
    var name = "file"
    var size = 0L
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getString(0)?.let { name = it }
            size = c.getLong(1)
        }
    }
    val mime = resolver.getType(uri)
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        ?: "application/octet-stream"
    val dir = File(cacheDir, "share").apply { mkdirs() }
    val out = File(dir, "${System.nanoTime()}-${name.replace('/', '_')}")
    resolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return null
    if (size <= 0) size = out.length()
    var w = 0
    var h = 0
    var dur = 0L
    when {
        mime.startsWith("image/") -> {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(out.absolutePath, opts)
            w = opts.outWidth.coerceAtLeast(0)
            h = opts.outHeight.coerceAtLeast(0)
        }
        mime.startsWith("video/") || mime.startsWith("audio/") -> runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(out.absolutePath)
                dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            }
        }
    }
    return PickedFile(out, name, mime, size, w, h, dur)
}

fun Context.uriForFile(file: File): Uri = FileProvider.getUriForFile(this, "$packageName.files", file)

fun Context.openFile(file: File, mime: String?) {
    val uri = uriForFile(file)
    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        startActivity(Intent.createChooser(intent, null))
    } catch (t: Throwable) {
        toast("No app can open this file")
    }
}

fun Context.shareFile(file: File, mime: String?) {
    val uri = uriForFile(file)
    val intent = Intent(Intent.ACTION_SEND).setType(mime ?: "*/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    startActivity(Intent.createChooser(intent, null))
}

fun formatBytes(bytes: Long): String = when {
    bytes <= 0 -> ""
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}

fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Parses matrix.to / matrix: links into a room/user identifier. */
object Permalinks {
    data class Target(val id: String, val eventId: String?, val via: List<String>)

    fun parse(uri: Uri): Target? = when {
        uri.scheme == "matrix" -> {
            // matrix:r/alias:server, matrix:roomid/id:server, matrix:u/user:server
            val parts = uri.schemeSpecificPart.substringBefore('?').split('/')
            val via = uri.getQueryParameters("via")
            when (parts.getOrNull(0)) {
                "r" -> parts.getOrNull(1)?.let { Target("#$it", null, via) }
                "roomid" -> parts.getOrNull(1)?.let { Target("!$it", parts.getOrNull(3)?.let { e -> "$$e" }, via) }
                "u" -> parts.getOrNull(1)?.let { Target("@$it", null, via) }
                else -> null
            }
        }
        uri.host == "matrix.to" -> {
            val frag = Uri.decode(uri.fragment ?: "").removePrefix("/")
            val path = frag.substringBefore('?').split('/')
            val via = Uri.parse("https://x/?" + frag.substringAfter('?', "")).getQueryParameters("via")
            path.getOrNull(0)?.takeIf { it.isNotEmpty() }?.let { Target(it, path.getOrNull(1), via) }
        }
        else -> null
    }
}
