package app.poppy.core.timeline

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.AudioInfo
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.EditedContent
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.FileInfo
import org.matrix.rustcomponents.sdk.GalleryItemInfo
import org.matrix.rustcomponents.sdk.GalleryUploadParameters
import org.matrix.rustcomponents.sdk.Mentions
import org.matrix.rustcomponents.sdk.ImageInfo
import org.matrix.rustcomponents.sdk.PollKind
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.SendTarget
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.TypingNotificationsListener
import org.matrix.rustcomponents.sdk.UploadParameters
import org.matrix.rustcomponents.sdk.UploadSource
import org.matrix.rustcomponents.sdk.VideoInfo
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdownAsEmote
import uniffi.matrix_sdk.PaginationStatus
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking
import org.matrix.rustcomponents.sdk.PaginationStatusListener

/** A live, paginating view of one room's timeline. */
class RoomTimeline(
    val room: Room,
    ownUserId: String,
    /** When set, this is a thread timeline rooted at this event; sends go into the thread. */
    val threadRootId: String? = null,
    private val privateReceipts: () -> Boolean,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mapper = TimelineMapper(ownUserId)

    private val _entries = MutableStateFlow<List<TimelineEntry>>(emptyList())
    val entries: StateFlow<List<TimelineEntry>> = _entries

    private val _paginating = MutableStateFlow(false)
    val paginating: StateFlow<Boolean> = _paginating

    private val _atStart = MutableStateFlow(false)
    val atStart: StateFlow<Boolean> = _atStart

    private val _typing = MutableStateFlow<List<String>>(emptyList())
    val typing: StateFlow<List<String>> = _typing

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var timeline: Timeline? = null
    private val handles = mutableListOf<TaskHandle>()
    private val items = ArrayList<TimelineEntry?>()
    private val diffs = Channel<List<TimelineDiff>>(Channel.UNLIMITED)
    private val nameCache = HashMap<String, String>()

    fun open() {
        scope.launch {
            try {
                val tl = room.timelineWithConfiguration(
                    TimelineConfiguration(
                        focus = threadRootId?.let { TimelineFocus.Thread(it) } ?: TimelineFocus.Live(hideThreadedEvents = true),
                        filter = TimelineFilter.All,
                        internalIdPrefix = null,
                        dateDividerMode = DateDividerMode.DAILY,
                        trackReadReceipts = TimelineReadReceiptTracking.MESSAGE_LIKE_EVENTS,
                        reportUtds = false,
                    ),
                )
                timeline = tl
                launch {
                    for (batch in diffs) {
                        try {
                            batch.forEach(::apply)
                            publish()
                        } catch (t: Throwable) {
                            Log.e(TAG, "diff failed", t)
                        }
                    }
                }
                handles += tl.addListener(object : TimelineListener {
                    override fun onUpdate(diff: List<TimelineDiff>) {
                        diffs.trySend(diff)
                    }
                })
                handles += tl.subscribeToBackPaginationStatus(object : PaginationStatusListener {
                    override fun onUpdate(status: PaginationStatus) {
                        when (status) {
                            is PaginationStatus.Idle -> {
                                _paginating.value = false
                                _atStart.value = status.hitTimelineStart
                            }
                            is PaginationStatus.Paginating -> _paginating.value = true
                        }
                    }
                })
                handles += room.subscribeToTypingNotifications(object : TypingNotificationsListener {
                    override fun call(typingUserIds: List<String>) {
                        scope.launch {
                            _typing.value = typingUserIds.filter { it != room.ownUserId() }.map { id ->
                                nameCache[id] ?: (runCatching { room.memberDisplayName(id) }.getOrNull() ?: id)
                                    .also { nameCache[id] = it }
                            }
                        }
                    }
                })
                paginateBack()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to open timeline", t)
                _error.value = t.message
            }
        }
    }

    private fun apply(diff: TimelineDiff) {
        when (diff) {
            is TimelineDiff.Append -> diff.values.forEach { items += map(it) }
            is TimelineDiff.Clear -> items.clear()
            is TimelineDiff.PushFront -> items.add(0, map(diff.value))
            is TimelineDiff.PushBack -> items += map(diff.value)
            is TimelineDiff.PopFront -> if (items.isNotEmpty()) items.removeAt(0)
            is TimelineDiff.PopBack -> if (items.isNotEmpty()) items.removeAt(items.lastIndex)
            is TimelineDiff.Insert -> items.add(diff.index.toInt().coerceAtMost(items.size), map(diff.value))
            is TimelineDiff.Set -> {
                val i = diff.index.toInt()
                if (i in items.indices) items[i] = map(diff.value)
            }
            is TimelineDiff.Remove -> {
                val i = diff.index.toInt()
                if (i in items.indices) items.removeAt(i)
            }
            is TimelineDiff.Truncate -> while (items.size > diff.length.toInt()) items.removeAt(items.lastIndex)
            is TimelineDiff.Reset -> {
                items.clear()
                diff.values.forEach { items += map(it) }
            }
        }
    }

    private fun map(item: TimelineItem): TimelineEntry? = try {
        mapper.map(item)
    } catch (t: Throwable) {
        Log.w(TAG, "map failed", t)
        null
    }

    /**
     * Single pass over the raw items: computes sender grouping for bubbles and collapses runs of
     * 3+ membership/profile changes into one [StateGroup], then publishes an immutable snapshot.
     */
    private fun publish() {
        val out = ArrayList<TimelineEntry>(items.size)
        var prev: MessageEntry? = null
        var prevIdx = -1
        val run = ArrayList<StateEntry>()
        fun flushRun() {
            if (run.size >= MEMBERSHIP_GROUP_MIN) out += StateGroup("group-" + run.first().key, run.toList()) else out.addAll(run)
            run.clear()
        }
        for (raw in items) {
            val e = raw ?: continue
            if (e is StateEntry && e.kind != StateKind.OTHER) {
                run += e
                prev = null
                continue
            }
            flushRun()
            if (e is MessageEntry) {
                val grouped = prev != null && prev.senderId == e.senderId && e.timestamp - prev.timestamp < GROUP_WINDOW_MS
                if (grouped && prevIdx >= 0) {
                    out[prevIdx] = (out[prevIdx] as MessageEntry).copy(groupedWithNext = true)
                }
                prevIdx = out.size
                out += if (grouped) e.copy(groupedWithPrevious = true) else e
                prev = e
            } else {
                if (e !is ReadMarker) {
                    prev = null
                    prevIdx = -1
                }
                out += e
            }
        }
        flushRun()
        _entries.value = out
    }

    fun paginateBack() {
        if (_paginating.value || _atStart.value) return
        scope.launch {
            runCatching { timeline?.paginateBackwards(PAGE) }.onFailure { Log.w(TAG, "paginate", it) }
        }
    }

    fun sendText(markdown: String, replyToEventId: String?, emote: Boolean = false, mentions: Mentions? = null) = launchCatching {
        val tl = timeline ?: return@launchCatching
        var content = if (emote) messageEventContentFromMarkdownAsEmote(markdown) else messageEventContentFromMarkdown(markdown)
        // Always attach intentional mentions (MSC3952); an empty set stops accidental pings.
        content = content.withMentions(mentions ?: Mentions(emptyList(), false))
        if (replyToEventId != null) tl.sendReply(content, replyToEventId) else tl.send(content)
    }

    fun edit(id: EventOrTransactionId, markdown: String, mentions: Mentions? = null) = launchCatching {
        val content = messageEventContentFromMarkdown(markdown).withMentions(mentions ?: Mentions(emptyList(), false))
        timeline?.edit(id, EditedContent.RoomMessage(content))
    }

    fun editCaption(id: EventOrTransactionId, caption: String) = launchCatching {
        timeline?.edit(id, EditedContent.MediaCaption(caption.ifBlank { null }, null, null))
    }

    /** Several photos/videos as one gallery message (MSC4274). */
    fun sendGallery(items: List<app.poppy.util.PickedFile>, caption: String?, replyTo: String?) = launchCatching {
        val infos = items.map { f ->
            val src = UploadSource.File(f.file.absolutePath)
            if (f.mime.startsWith("video/")) {
                GalleryItemInfo.Video(
                    VideoInfo(java.time.Duration.ofMillis(f.durationMs), f.height.toULong(), f.width.toULong(), f.mime, f.size.toULong(), null, null, null),
                    src, null, null, null, null,
                )
            } else {
                GalleryItemInfo.Image(
                    ImageInfo(f.height.toULong(), f.width.toULong(), f.mime, f.size.toULong(), null, null, null, f.mime == "image/gif"),
                    src, null, null, null, null,
                )
            }
        }
        timeline?.sendGallery(GalleryUploadParameters(caption?.takeIf { it.isNotBlank() }, null, null, replyTo), infos)?.join()
    }

    fun sendVoice(path: String, mime: String, durationMs: Long, size: Long, waveform: List<Float>, replyTo: String?) = launchCatching {
        val info = AudioInfo(duration = java.time.Duration.ofMillis(durationMs), size = size.toULong(), mimetype = mime)
        timeline?.sendVoiceMessage(params(path, "Voice message.${path.substringAfterLast('.')}", null, replyTo), info, waveform)?.join()
    }

    fun sendImage(path: String, filename: String, mime: String, width: Int, height: Int, size: Long, caption: String?, replyTo: String?) = launchCatching {
        val info = ImageInfo(
            height = height.toULong(), width = width.toULong(), mimetype = mime, size = size.toULong(),
            thumbnailInfo = null, thumbnailSource = null, blurhash = null, isAnimated = mime == "image/gif",
        )
        timeline?.sendImage(params(path, filename, caption, replyTo), null, info)?.join()
    }

    fun sendVideo(path: String, filename: String, mime: String, width: Int, height: Int, durationMs: Long, size: Long, caption: String?, replyTo: String?) = launchCatching {
        val info = VideoInfo(
            duration = java.time.Duration.ofMillis(durationMs), height = height.toULong(), width = width.toULong(),
            mimetype = mime, size = size.toULong(), thumbnailInfo = null, thumbnailSource = null, blurhash = null,
        )
        timeline?.sendVideo(params(path, filename, caption, replyTo), null, info)?.join()
    }

    fun sendAudio(path: String, filename: String, mime: String, durationMs: Long, size: Long, caption: String?, replyTo: String?) = launchCatching {
        val info = AudioInfo(duration = java.time.Duration.ofMillis(durationMs), size = size.toULong(), mimetype = mime)
        timeline?.sendAudio(params(path, filename, caption, replyTo), info)?.join()
    }

    fun sendFile(path: String, filename: String, mime: String, size: Long, caption: String?, replyTo: String?) = launchCatching {
        val info = FileInfo(mimetype = mime, size = size.toULong(), thumbnailInfo = null, thumbnailSource = null)
        timeline?.sendFile(params(path, filename, caption, replyTo), info)?.join()
    }

    private fun params(path: String, filename: String, caption: String?, replyTo: String?) = UploadParameters(
        source = UploadSource.File(path),
        caption = caption?.takeIf { it.isNotBlank() },
        formattedCaption = null,
        mentions = null,
        inReplyTo = replyTo,
    )

    fun sendLocation(geoUri: String, description: String) = launchCatching {
        timeline?.sendLocation(description, geoUri, description, 15u, null, null)
    }

    fun createPoll(question: String, answers: List<String>, disclosed: Boolean) = launchCatching {
        timeline?.createPoll(question, answers, 1u, if (disclosed) PollKind.DISCLOSED else PollKind.UNDISCLOSED)
    }

    fun vote(pollEventId: String, answerId: String) = launchCatching {
        timeline?.sendPollResponse(pollEventId, listOf(answerId))
    }

    fun endPoll(pollEventId: String) = launchCatching {
        timeline?.endPoll(pollEventId, "The poll has ended")
    }

    fun toggleReaction(id: EventOrTransactionId, key: String) = launchCatching {
        timeline?.toggleReaction(id, key)
    }

    fun redact(id: EventOrTransactionId, reason: String? = null) = launchCatching {
        timeline?.redactEvent(id, reason)
    }

    fun retry(id: EventOrTransactionId) = launchCatching {
        timeline?.retrySend(id, SendTarget.Event)
    }

    fun abort(id: EventOrTransactionId) = launchCatching {
        timeline?.abortSend(id, SendTarget.Event)
    }

    fun pin(eventId: String) = launchCatching { timeline?.pinEvent(eventId) }

    fun unpin(eventId: String) = launchCatching { timeline?.unpinEvent(eventId) }

    /** Loads older history until [eventId] is in [entries] (or the start is reached). */
    suspend fun ensureLoaded(eventId: String, maxPages: Int = 40): Boolean {
        repeat(maxPages) {
            if (_entries.value.any { (it as? MessageEntry)?.eventId == eventId }) return true
            if (_atStart.value) return false
            runCatching { timeline?.paginateBackwards(PAGE) }
            kotlinx.coroutines.delay(150)
        }
        return _entries.value.any { (it as? MessageEntry)?.eventId == eventId }
    }

    fun markAsRead() = launchCatching {
        timeline?.markAsRead(if (privateReceipts()) ReceiptType.READ_PRIVATE else ReceiptType.READ)
    }

    fun typing(isTyping: Boolean) = launchCatching { room.typingNotice(isTyping) }

    private fun launchCatching(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (t: Throwable) {
                Log.w(TAG, "timeline action failed", t)
                _error.value = t.message ?: t.javaClass.simpleName
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun close() {
        handles.forEach { runCatching { it.cancel() } }
        handles.clear()
        diffs.close()
        timeline?.let { runCatching { it.destroy() } }
        scope.cancel()
    }

    private companion object {
        const val TAG = "RoomTimeline"
        const val PAGE: UShort = 50u
        const val GROUP_WINDOW_MS = 5 * 60 * 1000L
        const val MEMBERSHIP_GROUP_MIN = 3
    }
}
