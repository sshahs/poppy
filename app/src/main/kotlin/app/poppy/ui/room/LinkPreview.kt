package app.poppy.ui.room

import android.content.Intent
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.poppy.core.media.MediaRequest
import app.poppy.graph
import coil3.compose.AsyncImage
import org.json.JSONObject

@Immutable
data class UrlPreview(val url: String, val title: String?, val description: String?, val image: String?, val site: String?)

/** Small in-memory cache so scrolling doesn't refetch previews. */
private object PreviewCache {
    val cache = LruCache<String, UrlPreview>(200)
    val misses = HashSet<String>()
}

private val firstUrl = Regex("https?://[^\\s<>\"')\\]]+")

fun firstLink(text: String): String? = firstUrl.find(text)?.value?.trimEnd('.', ',', '!', '?', ':', ';')

@Composable
fun LinkPreviewCard(text: String, color: Color) {
    val context = LocalContext.current
    val g = context.graph
    val enabled by g.settings.linkPreviews.collectAsState()
    if (!enabled) return
    val url = remember(text) { firstLink(text) } ?: return
    var preview by remember(url) { mutableStateOf(PreviewCache.cache.get(url)) }
    LaunchedEffect(url) {
        if (preview != null || url in PreviewCache.misses) return@LaunchedEffect
        val client = g.matrix.currentClient() ?: return@LaunchedEffect
        val json = runCatching { client.getUrlPreview(url, null) }.getOrNull()?.let { runCatching { JSONObject(it) }.getOrNull() }
        val p = json?.let {
            UrlPreview(
                url = url,
                title = it.optString("og:title").ifBlank { null },
                description = it.optString("og:description").ifBlank { null },
                image = it.optString("og:image").takeIf { img -> img.startsWith("mxc://") },
                site = it.optString("og:site_name").ifBlank { null },
            )
        }?.takeIf { it.title != null || it.description != null }
        if (p == null) PreviewCache.misses += url else {
            PreviewCache.cache.put(url, p)
            preview = p
        }
    }
    val p = preview ?: return
    Row(
        Modifier
            .padding(bottom = 6.dp)
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.07f))
            .clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url))) } },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(64.dp).background(MaterialTheme.colorScheme.primary))
        Column(Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Text(p.site ?: Uri.parse(p.url).host.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            p.title?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            p.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = color.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
        p.image?.let { mxc ->
            AsyncImage(
                model = remember(mxc) { MediaRequest.avatar(mxc, 160) },
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.padding(end = 6.dp).size(56.dp).clip(RoundedCornerShape(8.dp)),
            )
        }
    }
}
