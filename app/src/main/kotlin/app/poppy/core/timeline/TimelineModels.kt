package app.poppy.core.timeline

import androidx.compose.runtime.Immutable
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.MediaSource

@Immutable
sealed interface TimelineEntry {
    val key: String
}

@Immutable
data class DayDivider(override val key: String, val timestamp: Long) : TimelineEntry

@Immutable
data class ReadMarker(override val key: String) : TimelineEntry

@Immutable
data class TimelineStart(override val key: String) : TimelineEntry

enum class StateKind { JOIN, LEAVE, PROFILE, OTHER }

@Immutable
data class StateEntry(override val key: String, val text: String, val timestamp: Long, val kind: StateKind = StateKind.OTHER) : TimelineEntry

/** A run of membership/profile changes collapsed into one expandable line. */
@Immutable
data class StateGroup(override val key: String, val items: List<StateEntry>) : TimelineEntry {
    val summary: String
        get() {
            val joins = items.count { it.kind == StateKind.JOIN }
            val leaves = items.count { it.kind == StateKind.LEAVE }
            val other = items.size - joins - leaves
            return listOfNotNull(
                joins.takeIf { it > 0 }?.let { "$it joined" },
                leaves.takeIf { it > 0 }?.let { "$it left" },
                other.takeIf { it > 0 }?.let { "$it other change" + if (it > 1) "s" else "" },
            ).joinToString(", ")
        }
}

@Immutable
data class ReactionUi(val key: String, val count: Int, val mine: Boolean, val senders: List<String>)

@Immutable
data class ReplyPreview(val eventId: String, val senderName: String?, val text: String?)

enum class SendStatus { SENDING, SENT, FAILED, REMOTE }

@Immutable
data class MessageEntry(
    override val key: String,
    val id: EventOrTransactionId,
    val eventId: String?,
    val senderId: String,
    val senderName: String,
    val senderAvatar: String?,
    val isOwn: Boolean,
    val timestamp: Long,
    val body: MessageBody,
    val reactions: List<ReactionUi>,
    val replyTo: ReplyPreview?,
    val isEdited: Boolean,
    val isEditable: Boolean,
    val canReply: Boolean,
    val status: SendStatus,
    val threadReplies: Int,
    val readBy: Int,
    /** The message mentions us (intentional mentions or @room); shown highlighted. */
    val mentionsMe: Boolean = false,
    /** True when this message continues a run from the same sender (no header/avatar). */
    val groupedWithPrevious: Boolean = false,
    /** True when the next message is from the same sender (tighter bottom corner). */
    val groupedWithNext: Boolean = false,
) : TimelineEntry

@Immutable
sealed interface MessageBody {
    /** Plain text used for copy, quotes and previews. */
    val plain: String

    data class Text(override val plain: String, val html: String?, val kind: TextKind, val emojiOnly: Boolean) : MessageBody
    data class Image(
        override val plain: String,
        val source: MediaSource,
        val thumbnail: MediaSource?,
        val width: Int,
        val height: Int,
        val mimeType: String?,
        val filename: String,
        val isSticker: Boolean = false,
        val caption: String? = null,
    ) : MessageBody
    data class Video(override val plain: String, val source: MediaSource, val thumbnail: MediaSource?, val width: Int, val height: Int, val durationMs: Long, val mimeType: String?, val filename: String, val caption: String? = null) : MessageBody
    data class Audio(
        override val plain: String,
        val source: MediaSource,
        val durationMs: Long,
        val mimeType: String?,
        val filename: String,
        val isVoice: Boolean,
        val waveform: List<Float> = emptyList(),
        val caption: String? = null,
    ) : MessageBody
    data class File(override val plain: String, val source: MediaSource, val size: Long, val mimeType: String?, val filename: String, val caption: String? = null) : MessageBody
    data class Gallery(override val plain: String, val caption: String?, val items: List<GalleryItem>) : MessageBody
    data class Location(
        override val plain: String,
        val geoUri: String,
        val isLive: Boolean = false,
        /** Epoch ms when a live share ends (0 for static locations). */
        val liveUntil: Long = 0,
        val updatedAt: Long = 0,
    ) : MessageBody
    data class Poll(
        override val plain: String,
        val question: String,
        val answers: List<PollOption>,
        val totalVotes: Int,
        val ended: Boolean,
        val disclosed: Boolean,
        val maxSelections: Int,
    ) : MessageBody
    data class Redacted(override val plain: String = "Message deleted") : MessageBody
    data class Encrypted(override val plain: String = "Waiting for this message") : MessageBody
    data class Unsupported(override val plain: String) : MessageBody
}

@Immutable
data class GalleryItem(
    val source: MediaSource,
    val thumbnail: MediaSource?,
    val width: Int,
    val height: Int,
    val isVideo: Boolean,
    val filename: String,
    val mimeType: String?,
)

/** True for media bodies that carry a user-written caption. */
val MessageBody.mediaCaption: String?
    get() = when (this) {
        is MessageBody.Image -> caption
        is MessageBody.Video -> caption
        is MessageBody.Audio -> caption
        is MessageBody.File -> caption
        is MessageBody.Gallery -> caption
        else -> null
    }

@Immutable
data class PollOption(val id: String, val text: String, val votes: Int, val mine: Boolean)

enum class TextKind { NORMAL, NOTICE, EMOTE }
