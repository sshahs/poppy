package app.poppy.core.timeline

import app.poppy.core.Formatter
import app.poppy.core.name
import app.poppy.core.avatar
import org.matrix.rustcomponents.sdk.EmbeddedEventDetails
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.EventSendState
import org.matrix.rustcomponents.sdk.EventTimelineItem
import org.matrix.rustcomponents.sdk.MessageFormat
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.PollKind
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.VirtualTimelineItem

/** Converts Rust timeline items into immutable UI models. Pure and allocation-light. */
class TimelineMapper(private val ownUserId: String) {

    fun map(item: TimelineItem): TimelineEntry? {
        val key = item.uniqueId().id
        item.asVirtual()?.let { v ->
            return when (v) {
                is VirtualTimelineItem.DateDivider -> DayDivider(key, v.ts.toLong())
                is VirtualTimelineItem.ReadMarker -> ReadMarker(key)
                is VirtualTimelineItem.TimelineStart -> TimelineStart(key)
            }
        }
        // Note: the event is not destroyed here; its MediaSource handles live on in the UI model
        // and are released by UniFFI's cleaner once unreachable.
        val event = item.asEvent() ?: return null
        return mapEvent(key, event)
    }

    private fun mapEvent(key: String, e: EventTimelineItem): TimelineEntry? {
        val senderName = e.senderProfile.name() ?: e.sender
        val content = e.content
        if (content !is TimelineItemContent.MsgLike) {
            val text = Formatter.stateText(content, senderName) ?: return null
            return StateEntry(key, text, e.timestamp.toLong())
        }
        val msgLike = content.content
        val (body, edited) = when (val kind = msgLike.kind) {
            is MsgLikeKind.Message -> mapMessage(kind.content.msgType) to kind.content.isEdited
            is MsgLikeKind.Sticker -> MessageBody.Image(
                plain = kind.body,
                source = kind.source,
                thumbnail = kind.info.thumbnailSource,
                width = kind.info.width?.toInt() ?: 0,
                height = kind.info.height?.toInt() ?: 0,
                mimeType = kind.info.mimetype,
                filename = kind.body,
                isSticker = true,
            ) to false
            is MsgLikeKind.Poll -> {
                val counts = HashMap<String, Int>()
                val mine = HashSet<String>()
                kind.votes.forEach { (answerId, voters) ->
                    counts[answerId] = voters.size
                    if (ownUserId in voters) mine += answerId
                }
                MessageBody.Poll(
                    plain = kind.question,
                    question = kind.question,
                    answers = kind.answers.map { PollOption(it.id, it.text, counts[it.id] ?: 0, it.id in mine) },
                    totalVotes = kind.votes.values.sumOf { it.size },
                    ended = kind.endTime != null,
                    disclosed = kind.kind == PollKind.DISCLOSED,
                    maxSelections = kind.maxSelections.toInt(),
                ) to kind.hasBeenEdited
            }
            is MsgLikeKind.Redacted -> MessageBody.Redacted() to false
            is MsgLikeKind.UnableToDecrypt -> MessageBody.Encrypted() to false
            is MsgLikeKind.LiveLocation -> MessageBody.Unsupported("📍 Live location") to false
            is MsgLikeKind.Other -> return null
        }
        val reply = msgLike.inReplyTo?.let { details ->
            val (name, text) = when (val ev = details.event()) {
                is EmbeddedEventDetails.Ready -> {
                    val n = ev.senderProfile.name() ?: ev.sender
                    n to Formatter.preview(ev.content, n, includeSender = false)
                }
                is EmbeddedEventDetails.Error -> null to "Unable to load message"
                else -> null to null
            }
            ReplyPreview(details.eventId(), name, text)
        }
        val status = when (val s = e.localSendState) {
            null -> SendStatus.REMOTE
            is EventSendState.NotSentYet -> SendStatus.SENDING
            is EventSendState.SendingFailed -> SendStatus.FAILED
            is EventSendState.Sent -> SendStatus.SENT
        }
        val eventId = (e.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId
        return MessageEntry(
            key = key,
            id = e.eventOrTransactionId,
            eventId = eventId,
            senderId = e.sender,
            senderName = senderName,
            senderAvatar = e.senderProfile.avatar(),
            isOwn = e.isOwn,
            timestamp = e.timestamp.toLong(),
            body = body.let { if (it is MessageBody.Text && it.kind == TextKind.NORMAL) it.copy(emojiOnly = e.lazyProvider.containsOnlyEmojis()) else it },
            reactions = e.reactions.map { r ->
                ReactionUi(
                    key = r.key,
                    count = r.senders.size,
                    mine = r.senders.any { it.senderId == ownUserId },
                    senders = r.senders.map { it.senderId },
                )
            },
            replyTo = reply,
            isEdited = edited,
            isEditable = e.isEditable,
            canReply = e.canBeRepliedTo,
            status = status,
            threadReplies = msgLike.threadSummary?.numReplies()?.toInt() ?: 0,
            readBy = e.readReceipts.keys.count { it != e.sender },
        )
    }

    private fun mapMessage(type: MessageType): MessageBody = when (type) {
        is MessageType.Text -> MessageBody.Text(
            plain = Formatter.stripReplyFallback(type.content.body),
            html = type.content.formatted?.takeIf { it.format is MessageFormat.Html }?.body?.let(Formatter::stripHtmlReplyFallback),
            kind = TextKind.NORMAL,
            emojiOnly = false,
        )
        is MessageType.Notice -> MessageBody.Text(
            type.content.body,
            type.content.formatted?.takeIf { it.format is MessageFormat.Html }?.body?.let(Formatter::stripHtmlReplyFallback),
            TextKind.NOTICE,
            false,
        )
        is MessageType.Emote -> MessageBody.Text(
            type.content.body,
            type.content.formatted?.takeIf { it.format is MessageFormat.Html }?.body,
            TextKind.EMOTE,
            false,
        )
        is MessageType.Image -> MessageBody.Image(
            plain = type.content.caption ?: type.content.filename,
            source = type.content.source,
            thumbnail = type.content.info?.thumbnailSource,
            width = type.content.info?.width?.toInt() ?: 0,
            height = type.content.info?.height?.toInt() ?: 0,
            mimeType = type.content.info?.mimetype,
            filename = type.content.filename,
        )
        is MessageType.Video -> MessageBody.Video(
            plain = type.content.caption ?: type.content.filename,
            source = type.content.source,
            thumbnail = type.content.info?.thumbnailSource,
            width = type.content.info?.width?.toInt() ?: 0,
            height = type.content.info?.height?.toInt() ?: 0,
            durationMs = type.content.info?.duration?.toMillis() ?: 0,
            mimeType = type.content.info?.mimetype,
            filename = type.content.filename,
        )
        is MessageType.Audio -> MessageBody.Audio(
            plain = type.content.caption ?: type.content.filename,
            source = type.content.source,
            durationMs = type.content.info?.duration?.toMillis() ?: 0,
            mimeType = type.content.info?.mimetype,
            filename = type.content.filename,
            isVoice = type.content.voice != null,
        )
        is MessageType.File -> MessageBody.File(
            plain = type.content.caption ?: type.content.filename,
            source = type.content.source,
            size = type.content.info?.size?.toLong() ?: 0,
            mimeType = type.content.info?.mimetype,
            filename = type.content.filename,
        )
        is MessageType.Location -> MessageBody.Location(type.content.body, type.content.geoUri)
        else -> MessageBody.Text(Formatter.messageBody(type), null, TextKind.NOTICE, false)
    }
}
