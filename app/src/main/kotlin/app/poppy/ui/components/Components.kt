package app.poppy.ui.components

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.poppy.core.media.MediaRequest
import coil3.compose.AsyncImage
import java.util.Calendar
import kotlin.math.abs

private val avatarColors = listOf(
    Color(0xFFD93A35), Color(0xFF2E7D6B), Color(0xFF5C6BC0), Color(0xFFB8641F),
    Color(0xFF8E44AD), Color(0xFF00838F), Color(0xFF6D4C41), Color(0xFF3949AB),
)

fun avatarColor(id: String): Color = avatarColors[abs(id.hashCode()) % avatarColors.size]

fun initialOf(name: String): String {
    val trimmed = name.trimStart('@', '#', '!', '+')
    val cp = trimmed.codePointAtOrNull(0) ?: return "?"
    return String(Character.toChars(cp)).uppercase()
}

private fun String.codePointAtOrNull(i: Int): Int? = if (isEmpty()) null else codePointAt(i)

/** Circular avatar: Matrix thumbnail if available, coloured initial otherwise. */
@Composable
fun Avatar(url: String?, name: String, id: String, size: Dp, modifier: Modifier = Modifier) {
    AvatarImage(url, name, id, size, CircleShape, modifier)
}

/**
 * Avatar with a fallback that never shows behind a real image: the coloured initial is only drawn
 * when there is no avatar or it fails to load, so transparent PNG avatars render as intended.
 */
@Composable
fun AvatarImage(url: String?, name: String, id: String, size: Dp, shape: androidx.compose.ui.graphics.Shape, modifier: Modifier = Modifier) {
    val px = with(androidx.compose.ui.platform.LocalDensity.current) { size.roundToPx() }
    val request = remember(url, px) { MediaRequest.avatar(url, px.coerceIn(48, 320)) }
    // NONE: no avatar / failed → initial. LOADING: neutral placeholder. LOADED: image only.
    var state by remember(request) { mutableStateOf(if (request == null) AvatarState.NONE else AvatarState.LOADING) }
    val background = when (state) {
        AvatarState.NONE -> avatarColor(id)
        AvatarState.LOADING -> MaterialTheme.colorScheme.surfaceVariant
        AvatarState.LOADED -> Color.Transparent
    }
    Box(modifier.size(size).clip(shape).background(background), contentAlignment = Alignment.Center) {
        if (state == AvatarState.NONE) {
            Text(initialOf(name), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.42f).sp)
        }
        if (request != null) {
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { state = AvatarState.LOADED },
                onError = { state = AvatarState.NONE },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private enum class AvatarState { NONE, LOADING, LOADED }

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, content: @Composable () -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        content()
    }
}

object Time {
    fun short(context: android.content.Context, ts: Long): String {
        if (ts <= 0) return ""
        val now = System.currentTimeMillis()
        return when {
            DateUtils.isToday(ts) -> DateFormat.getTimeFormat(context).format(ts)
            now - ts < 6 * DateUtils.DAY_IN_MILLIS -> DateUtils.formatDateTime(context, ts, DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY)
            sameYear(ts, now) -> DateUtils.formatDateTime(context, ts, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NO_YEAR or DateUtils.FORMAT_ABBREV_MONTH)
            else -> DateUtils.formatDateTime(context, ts, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_NUMERIC_DATE)
        }
    }

    fun clock(context: android.content.Context, ts: Long): String = DateFormat.getTimeFormat(context).format(ts)

    fun day(context: android.content.Context, ts: Long): String = when {
        DateUtils.isToday(ts) -> "Today"
        DateUtils.isToday(ts + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        else -> DateUtils.formatDateTime(
            context, ts,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or (if (sameYear(ts, System.currentTimeMillis())) DateUtils.FORMAT_NO_YEAR else 0),
        )
    }

    private fun sameYear(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
    }
}

@Composable
fun rememberContext() = LocalContext.current
