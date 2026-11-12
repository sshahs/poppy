package app.poppy.core.media

import androidx.compose.runtime.Immutable
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import okio.Buffer
import okio.FileSystem
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.MediaSource

/**
 * Coil model for Matrix media. Encrypted attachments are decrypted by the Rust SDK, which also
 * keeps its own media cache, so Coil only needs a memory cache on top.
 */
@Immutable
class MediaRequest private constructor(
    val url: String,
    private val json: String?,
    val thumbWidth: Int,
    val thumbHeight: Int,
) {
    fun source(): MediaSource = if (json != null) MediaSource.fromJson(json) else MediaSource.fromUrl(url)

    val isThumbnail: Boolean get() = thumbWidth > 0

    override fun equals(other: Any?) =
        other is MediaRequest && other.url == url && other.thumbWidth == thumbWidth && other.thumbHeight == thumbHeight

    override fun hashCode() = (url.hashCode() * 31 + thumbWidth) * 31 + thumbHeight

    companion object {
        fun avatar(mxc: String?, size: Int = 96): MediaRequest? =
            mxc?.takeIf { it.startsWith("mxc://") }?.let { MediaRequest(it, null, size, size) }

        fun thumbnail(source: MediaSource, width: Int, height: Int) =
            MediaRequest(source.url(), source.toJson(), width, height)

        fun full(source: MediaSource) = MediaRequest(source.url(), source.toJson(), 0, 0)
    }
}

class MediaKeyer : Keyer<MediaRequest> {
    override fun key(data: MediaRequest, options: Options): String =
        if (data.isThumbnail) "${data.url}@${data.thumbWidth}x${data.thumbHeight}" else data.url
}

class MediaFetcher(
    private val request: MediaRequest,
    private val client: Client,
) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val source = request.source()
        val bytes = try {
            if (request.isThumbnail) {
                client.getMediaThumbnail(source, request.thumbWidth.toULong(), request.thumbHeight.toULong())
            } else {
                client.getMediaContent(source)
            }
        } catch (t: Throwable) {
            // Some servers don't thumbnail (or the media is encrypted): fall back to the original.
            if (request.isThumbnail) client.getMediaContent(source) else throw t
        } finally {
            source.destroy()
        }
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), FileSystem.SYSTEM),
            mimeType = null,
            dataSource = DataSource.NETWORK,
        )
    }

    class Factory(private val clientProvider: () -> Client?) : Fetcher.Factory<MediaRequest> {
        override fun create(data: MediaRequest, options: Options, imageLoader: ImageLoader): Fetcher? {
            val client = clientProvider() ?: return null
            return MediaFetcher(data, client)
        }
    }
}
