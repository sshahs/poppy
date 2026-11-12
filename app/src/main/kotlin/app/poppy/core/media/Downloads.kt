package app.poppy.core.media

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.MediaSource
import java.io.File
import java.security.MessageDigest

/** Fetches (and decrypts) an attachment into our cache, reusing earlier downloads. */
suspend fun Client.downloadToCache(context: Context, source: MediaSource, filename: String, mime: String?): File {
    val dir = File(context.cacheDir, "media").apply { mkdirs() }
    val hash = MessageDigest.getInstance("SHA-1").digest(source.url().toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    val safeName = filename.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "file" }
    val target = File(dir, "$hash-$safeName")
    if (target.exists() && target.length() > 0) return target
    val handle = getMediaFile(source, filename, mime ?: "application/octet-stream", true, dir.absolutePath)
    withContext(Dispatchers.IO) {
        if (!handle.persist(target.absolutePath)) File(handle.path()).copyTo(target, overwrite = true)
    }
    return target
}

/** Copies a cached file into the public Downloads collection. */
suspend fun saveToDownloads(context: Context, file: File, displayName: String, mime: String?): Boolean = withContext(Dispatchers.IO) {
    runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime ?: "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Poppy")
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
            context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            true
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Poppy").apply { mkdirs() }
            file.copyTo(File(dir, displayName), overwrite = true)
            true
        }
    }.getOrDefault(false)
}
