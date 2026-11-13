package app.poppy.ui

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import org.matrix.rustcomponents.sdk.MediaSource

@Immutable
sealed interface Screen {
    val key: String

    data object Rooms : Screen { override val key = "rooms" }
    data class Room(val roomId: String, val focusEventId: String? = null) : Screen { override val key = "room/$roomId" }
    data class Thread(val roomId: String, val rootEventId: String) : Screen { override val key = "thread/$roomId/$rootEventId" }
    data class Threads(val roomId: String) : Screen { override val key = "threads/$roomId" }
    data object Search : Screen { override val key = "search" }
    data object Devices : Screen { override val key = "devices" }
    data class RoomDetails(val roomId: String) : Screen { override val key = "details/$roomId" }
    data class Members(val roomId: String) : Screen { override val key = "members/$roomId" }
    data object StartChat : Screen { override val key = "start-chat" }
    data object CreateRoom : Screen { override val key = "create-room" }
    data object Explore : Screen { override val key = "explore" }
    data class Space(val spaceId: String) : Screen { override val key = "space/$spaceId" }
    data object Settings : Screen { override val key = "settings" }
    data object Security : Screen { override val key = "security" }
    data object Verification : Screen { override val key = "verification" }
    data object Notifications : Screen { override val key = "notifications" }
    data object Appearance : Screen { override val key = "appearance" }
    data object About : Screen { override val key = "about" }
    data class Media(val source: MediaSource, val filename: String, val mimeType: String?) : Screen { override val key = "media/${source.url()}" }
    data class SharePicker(val text: String?, val uris: List<android.net.Uri>) : Screen { override val key = "share" }
}

/** A tiny back stack. Compose state, zero reflection, no serialisation step. */
@Stable
class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Rooms)

    val current: Screen get() = stack.last()

    fun push(screen: Screen) {
        if (stack.lastOrNull()?.key == screen.key) return
        stack.add(screen)
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun replace(screen: Screen) {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
        push(screen)
    }

    fun popTo(screen: Screen) {
        while (stack.size > 1 && stack.last() != screen) stack.removeAt(stack.lastIndex)
    }

    fun reset(vararg screens: Screen) {
        stack.clear()
        stack.add(Screen.Rooms)
        screens.forEach { push(it) }
    }
}
