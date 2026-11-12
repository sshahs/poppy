package app.poppy.core

import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.LatestEventValue
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.ProfileDetails
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomList
import org.matrix.rustcomponents.sdk.RoomListDynamicEntriesController
import org.matrix.rustcomponents.sdk.RoomListEntriesDynamicFilterKind
import org.matrix.rustcomponents.sdk.RoomListEntriesListener
import org.matrix.rustcomponents.sdk.RoomListEntriesUpdate
import org.matrix.rustcomponents.sdk.RoomListEntriesWithDynamicAdaptersResult
import org.matrix.rustcomponents.sdk.RoomListLoadingState
import org.matrix.rustcomponents.sdk.RoomListLoadingStateListener
import org.matrix.rustcomponents.sdk.RoomListService
import org.matrix.rustcomponents.sdk.RoomNotificationMode
import org.matrix.rustcomponents.sdk.TaskHandle
import uniffi.matrix_sdk_base.EncryptionState
import uniffi.matrix_sdk_ui.RoomListFilterCategory
import uniffi.matrix_sdk_ui.RoomListFilterReadReceipts

@Immutable
data class RoomSummary(
    val id: String,
    val name: String,
    val avatarUrl: String?,
    val isDm: Boolean,
    val isEncrypted: Boolean,
    val isInvite: Boolean,
    val inviter: String?,
    val isFavourite: Boolean,
    val isLowPriority: Boolean,
    val isSpace: Boolean,
    val isMuted: Boolean,
    val isMarkedUnread: Boolean,
    val unreadMessages: Long,
    val unreadNotifications: Long,
    val unreadMentions: Long,
    val hasCall: Boolean,
    val lastMessage: String?,
    val lastMessageTs: Long,
    val topic: String?,
    val alias: String?,
) {
    val hasUnread: Boolean get() = isMarkedUnread || unreadNotifications > 0 || unreadMessages > 0
}

enum class RoomFilter { ALL, UNREAD, PEOPLE, GROUPS, FAVOURITES, INVITES }

/**
 * Drives the room list from sliding sync. Diffs from Rust are applied sequentially on a
 * background dispatcher; only rooms that actually changed are re-summarised.
 */
class RoomListRepository(private val scope: CoroutineScope) {
    private val _rooms = MutableStateFlow<List<RoomSummary>>(emptyList())
    val rooms: StateFlow<List<RoomSummary>> = _rooms

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    private val _filter = MutableStateFlow(RoomFilter.ALL)
    val filter: StateFlow<RoomFilter> = _filter

    private var query: String = ""

    private val _space = MutableStateFlow<SpaceUi?>(null)
    /** The space the chat list is narrowed to, or null for everything. */
    val space: StateFlow<SpaceUi?> = _space

    private val entries = ArrayList<Pair<Room, RoomSummary>>()
    private val updates = Channel<List<RoomListEntriesUpdate>>(Channel.UNLIMITED)

    // Strong references: dropping these would cancel the Rust streams.
    private var roomList: RoomList? = null
    private var result: RoomListEntriesWithDynamicAdaptersResult? = null
    private var controller: RoomListDynamicEntriesController? = null
    private var loadingHandle: TaskHandle? = null

    suspend fun attach(service: RoomListService) {
        if (roomList != null) return
        val list = service.allRooms()
        roomList = list
        scope.launch(Dispatchers.Default) {
            for (batch in updates) {
                try {
                    apply(batch)
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to apply room list diff", t)
                }
            }
        }
        val loading = list.loadingState(object : RoomListLoadingStateListener {
            override fun onUpdate(state: RoomListLoadingState) {
                if (state is RoomListLoadingState.Loaded) _loaded.value = true
            }
        })
        if (loading.state is RoomListLoadingState.Loaded) _loaded.value = true
        loadingHandle = loading.stateStream
        val res = list.entriesWithDynamicAdapters(PAGE_SIZE, object : RoomListEntriesListener {
            override fun onUpdate(roomEntriesUpdate: List<RoomListEntriesUpdate>) {
                updates.trySend(roomEntriesUpdate)
            }
        })
        result = res
        controller = res.controller()
        applyFilter()
    }

    fun setFilter(filter: RoomFilter) {
        _filter.value = filter
        applyFilter()
    }

    fun setSpace(space: SpaceUi?) {
        _space.value = space
        applyFilter()
    }

    fun setQuery(text: String) {
        query = text.trim()
        applyFilter()
    }

    fun loadMore() {
        runCatching { controller?.addOnePage() }
    }

    private fun applyFilter() {
        val base = mutableListOf<RoomListEntriesDynamicFilterKind>(
            RoomListEntriesDynamicFilterKind.NonLeft,
            RoomListEntriesDynamicFilterKind.NonSpace,
            RoomListEntriesDynamicFilterKind.DeduplicateVersions,
        )
        when (_filter.value) {
            RoomFilter.ALL -> Unit
            RoomFilter.UNREAD -> base += RoomListEntriesDynamicFilterKind.ReadReceipts(RoomListFilterReadReceipts.NOTIFICATIONS)
            RoomFilter.PEOPLE -> base += RoomListEntriesDynamicFilterKind.Category(RoomListFilterCategory.PEOPLE)
            RoomFilter.GROUPS -> base += RoomListEntriesDynamicFilterKind.Category(RoomListFilterCategory.GROUP)
            RoomFilter.FAVOURITES -> base += RoomListEntriesDynamicFilterKind.Favourite
            RoomFilter.INVITES -> base += RoomListEntriesDynamicFilterKind.Invite
        }
        if (query.isNotEmpty()) base += RoomListEntriesDynamicFilterKind.FuzzyMatchRoomName(query)
        _space.value?.let { base += RoomListEntriesDynamicFilterKind.Identifiers(it.roomIds) }
        runCatching { controller?.setFilter(RoomListEntriesDynamicFilterKind.All(base)) }
    }

    /** Re-summarise one room on demand (e.g. after toggling favourite). */
    fun refresh(roomId: String) {
        scope.launch(Dispatchers.Default) {
            val idx = entries.indexOfFirst { it.second.id == roomId }
            if (idx >= 0) {
                val room = entries[idx].first
                entries[idx] = room to summarise(room)
                publish()
            }
        }
    }

    private suspend fun apply(batch: List<RoomListEntriesUpdate>) {
        for (u in batch) {
            when (u) {
                is RoomListEntriesUpdate.Append -> u.values.forEach { entries += it to summarise(it) }
                is RoomListEntriesUpdate.Clear -> entries.clear()
                is RoomListEntriesUpdate.PushFront -> entries.add(0, u.value to summarise(u.value))
                is RoomListEntriesUpdate.PushBack -> entries += u.value to summarise(u.value)
                is RoomListEntriesUpdate.PopFront -> if (entries.isNotEmpty()) entries.removeAt(0)
                is RoomListEntriesUpdate.PopBack -> if (entries.isNotEmpty()) entries.removeAt(entries.lastIndex)
                is RoomListEntriesUpdate.Insert -> entries.add(u.index.toInt().coerceAtMost(entries.size), u.value to summarise(u.value))
                is RoomListEntriesUpdate.Set -> {
                    val i = u.index.toInt()
                    if (i in entries.indices) entries[i] = u.value to summarise(u.value)
                }
                is RoomListEntriesUpdate.Remove -> {
                    val i = u.index.toInt()
                    if (i in entries.indices) entries.removeAt(i)
                }
                is RoomListEntriesUpdate.Truncate -> while (entries.size > u.length.toInt()) entries.removeAt(entries.lastIndex)
                is RoomListEntriesUpdate.Reset -> {
                    entries.clear()
                    u.values.forEach { entries += it to summarise(it) }
                }
            }
        }
        publish()
    }

    private fun publish() {
        _rooms.value = entries.map { it.second }
    }

    private suspend fun summarise(room: Room): RoomSummary {
        val info = room.roomInfo()
        val latest = runCatching { room.latestEvent() }.getOrNull()
        val (preview, ts) = when (latest) {
            is LatestEventValue.Remote -> {
                val sender = if (latest.isOwn) "You" else latest.profile.name() ?: latest.sender
                Formatter.preview(latest.content, sender, includeSender = !info.isDirect || latest.isOwn) to latest.timestamp.toLong()
            }
            is LatestEventValue.Local -> Formatter.preview(latest.content, "You", true) to latest.timestamp.toLong()
            is LatestEventValue.RemoteInvite -> "Invitation" to latest.timestamp.toLong()
            else -> null to 0L
        }
        val isDm = info.isDm || info.isDirect
        val avatar = info.avatarUrl ?: if (isDm) info.heroes.firstOrNull()?.avatarUrl else null
        return RoomSummary(
            id = info.id,
            name = info.displayName ?: info.rawName ?: info.canonicalAlias ?: info.id,
            avatarUrl = avatar,
            isDm = isDm,
            isEncrypted = info.encryptionState == EncryptionState.ENCRYPTED,
            isInvite = info.membership == Membership.INVITED,
            inviter = info.inviter?.let { it.displayName ?: it.userId },
            isFavourite = info.isFavourite,
            isLowPriority = info.isLowPriority,
            isSpace = info.isSpace,
            isMuted = info.cachedUserDefinedNotificationMode == RoomNotificationMode.MUTE,
            isMarkedUnread = info.isMarkedUnread,
            unreadMessages = info.numUnreadMessages.toLong(),
            unreadNotifications = info.numUnreadNotifications.toLong(),
            unreadMentions = info.numUnreadMentions.toLong(),
            hasCall = info.hasRoomCall,
            lastMessage = preview,
            lastMessageTs = ts,
            topic = info.topic,
            alias = info.canonicalAlias,
        )
    }

    fun close() {
        loadingHandle?.cancel()
        result?.entriesStream()?.cancel()
        updates.close()
    }

    private companion object {
        const val TAG = "RoomList"
        const val PAGE_SIZE = 200u
    }
}

fun ProfileDetails.name(): String? = (this as? ProfileDetails.Ready)?.displayName
fun ProfileDetails.avatar(): String? = (this as? ProfileDetails.Ready)?.avatarUrl
