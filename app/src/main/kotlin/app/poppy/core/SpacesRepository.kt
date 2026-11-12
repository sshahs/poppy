package app.poppy.core

import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.RoomType
import org.matrix.rustcomponents.sdk.SpaceFilter
import org.matrix.rustcomponents.sdk.SpaceFilterUpdate
import org.matrix.rustcomponents.sdk.SpaceRoom
import org.matrix.rustcomponents.sdk.SpaceService
import org.matrix.rustcomponents.sdk.SpaceServiceSpaceFiltersListener
import org.matrix.rustcomponents.sdk.TaskHandle

@Immutable
data class SpaceUi(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    /** 0 for top-level spaces, deeper for subspaces. */
    val level: Int,
    /** Every room inside this space (recursively), used to filter the chat list. */
    val roomIds: List<String>,
)

@Immutable
data class SpaceChild(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val topic: String?,
    val alias: String?,
    val members: Long,
    val isSpace: Boolean,
    val childCount: Long,
    val joined: Boolean,
    val via: List<String>,
)

fun SpaceRoom.toChild() = SpaceChild(
    id = roomId,
    name = displayName,
    avatarUrl = avatarUrl,
    topic = topic,
    alias = canonicalAlias,
    members = numJoinedMembers.toLong(),
    isSpace = roomType is RoomType.Space,
    childCount = childrenCount.toLong(),
    joined = state == Membership.JOINED,
    via = via,
)

/** Joined spaces (as chat-list filters), kept live by the SDK's SpaceService. */
class SpacesRepository {
    private val _spaces = MutableStateFlow<List<SpaceUi>>(emptyList())
    val spaces: StateFlow<List<SpaceUi>> = _spaces

    private val filters = ArrayList<SpaceFilter>()
    private var handle: TaskHandle? = null
    var service: SpaceService? = null
        private set

    suspend fun attach(client: Client) {
        if (service != null) return
        try {
            val s = client.spaceService()
            service = s
            filters.clear()
            filters += s.spaceFilters()
            publish()
            handle = s.subscribeToSpaceFilters(object : SpaceServiceSpaceFiltersListener {
                override fun onUpdate(filterUpdates: List<SpaceFilterUpdate>) {
                    synchronized(filters) { filterUpdates.forEach(::apply) }
                    publish()
                }
            })
        } catch (t: Throwable) {
            Log.w("Spaces", "space service unavailable", t)
        }
    }

    private fun apply(u: SpaceFilterUpdate) {
        when (u) {
            is SpaceFilterUpdate.Append -> filters += u.values
            is SpaceFilterUpdate.Clear -> filters.clear()
            is SpaceFilterUpdate.PushFront -> filters.add(0, u.value)
            is SpaceFilterUpdate.PushBack -> filters += u.value
            is SpaceFilterUpdate.PopFront -> if (filters.isNotEmpty()) filters.removeAt(0)
            is SpaceFilterUpdate.PopBack -> if (filters.isNotEmpty()) filters.removeAt(filters.lastIndex)
            is SpaceFilterUpdate.Insert -> filters.add(u.index.toInt().coerceAtMost(filters.size), u.value)
            is SpaceFilterUpdate.Set -> if (u.index.toInt() in filters.indices) filters[u.index.toInt()] = u.value
            is SpaceFilterUpdate.Remove -> if (u.index.toInt() in filters.indices) filters.removeAt(u.index.toInt())
            is SpaceFilterUpdate.Truncate -> while (filters.size > u.length.toInt()) filters.removeAt(filters.lastIndex)
            is SpaceFilterUpdate.Reset -> {
                filters.clear()
                filters += u.values
            }
        }
    }

    private fun publish() {
        _spaces.value = synchronized(filters) {
            filters.map { f ->
                SpaceUi(f.spaceRoom.roomId, f.spaceRoom.displayName, f.spaceRoom.avatarUrl, f.level.toInt(), f.descendants)
            }
        }
    }

    fun close() {
        handle?.cancel()
    }
}
