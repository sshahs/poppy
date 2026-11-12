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

@Immutable
data class StateEntry(override val key: String, val text: String, val timestamp: Long) : TimelineEntry

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
    ) : MessageBody
    data class Video(override val plain: String, val source: MediaSource, val thumbnail: MediaSource?, val width: Int, val height: Int, val durationMs: Long, val mimeType: String?, val filename: String) : MessageBody
    data class Audio(override val plain: String, val source: MediaSource, val durationMs: Long, val mimeType: String?, val filename: String, val isVoice: Boolean) : MessageBody
    data class File(override val plain: String, val source: MediaSource, val size: Long, val mimeType: String?, val filename: String) : MessageBody
    data class Location(override val plain: String, val geoUri: String) : MessageBody
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
data class PollOption(val id: String, val text: String, val votes: Int, val mine: Boolean)

enum class TextKind { NORMAL, NOTICE, EMOTE }
