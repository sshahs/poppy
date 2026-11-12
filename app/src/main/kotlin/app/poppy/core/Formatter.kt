package app.poppy.core

import org.matrix.rustcomponents.sdk.MembershipChange
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.OtherState
import org.matrix.rustcomponents.sdk.TimelineItemContent

/** Turns timeline content into short human readable strings (previews, state events). */
object Formatter {

    fun messageBody(type: MessageType): String = when (type) {
        is MessageType.Text -> type.content.body
        is MessageType.Notice -> type.content.body
        is MessageType.Emote -> "* ${type.content.body}"
        is MessageType.Image -> type.content.caption ?: "🖼 Photo"
        is MessageType.Video -> type.content.caption ?: "🎬 Video"
        is MessageType.Audio -> if (type.content.voice != null) "🎤 Voice message" else type.content.caption ?: "🎵 Audio"
        is MessageType.File -> type.content.caption ?: "📎 ${type.content.filename}"
        is MessageType.Gallery -> type.content.body.ifBlank { "🖼 Gallery" }
        is MessageType.Location -> "📍 Location"
        is MessageType.Other -> type.body
    }

    fun preview(content: TimelineItemContent, sender: String, includeSender: Boolean): String {
        val text = when (content) {
            is TimelineItemContent.MsgLike -> when (val k = content.content.kind) {
                is MsgLikeKind.Message -> stripReplyFallback(messageBody(k.content.msgType))
                is MsgLikeKind.Sticker -> "Sticker"
                is MsgLikeKind.Poll -> "📊 ${k.question}"
                is MsgLikeKind.Redacted -> "Message deleted"
                is MsgLikeKind.UnableToDecrypt -> "🔒 Encrypted message"
                is MsgLikeKind.LiveLocation -> "📍 Live location"
                is MsgLikeKind.Other -> "Unsupported event"
            }
            else -> return stateText(content, sender) ?: ""
        }
        return if (includeSender) "$sender: $text" else text
    }

    /** Returns null for state events that should be hidden. */
    fun stateText(content: TimelineItemContent, sender: String): String? = when (content) {
        is TimelineItemContent.RoomMembership -> {
            val who = content.userDisplayName ?: content.userId
            when (content.change) {
                MembershipChange.JOINED -> "$who joined"
                MembershipChange.LEFT -> "$who left"
                MembershipChange.BANNED, MembershipChange.KICKED_AND_BANNED -> "$sender banned $who"
                MembershipChange.UNBANNED -> "$sender unbanned $who"
                MembershipChange.KICKED -> "$sender removed $who"
                MembershipChange.INVITED -> "$sender invited $who"
                MembershipChange.INVITATION_ACCEPTED -> "$who accepted the invite"
                MembershipChange.INVITATION_REJECTED -> "$who declined the invite"
                MembershipChange.INVITATION_REVOKED -> "$sender revoked the invite for $who"
                MembershipChange.KNOCKED -> "$who asked to join"
                MembershipChange.KNOCK_ACCEPTED -> "$sender let $who in"
                MembershipChange.KNOCK_RETRACTED -> "$who cancelled their request to join"
                MembershipChange.KNOCK_DENIED -> "$sender declined $who's request to join"
                else -> null
            } ?: content.reason
        }
        is TimelineItemContent.ProfileChange -> when {
            content.displayName != content.prevDisplayName && content.displayName != null && content.prevDisplayName != null ->
                "${content.prevDisplayName} changed their name to ${content.displayName}"
            content.displayName != null && content.prevDisplayName == null -> "$sender set their name to ${content.displayName}"
            content.avatarUrl != content.prevAvatarUrl -> "$sender changed their avatar"
            else -> null
        }
        is TimelineItemContent.State -> when (val s = content.content) {
            is OtherState.RoomName -> if (s.name.isNullOrBlank()) "$sender removed the room name" else "$sender renamed the room to ${s.name}"
            is OtherState.RoomTopic -> if (s.topic.isNullOrBlank()) "$sender removed the topic" else "$sender changed the topic to \"${s.topic}\""
            is OtherState.RoomAvatar -> "$sender changed the room avatar"
            is OtherState.RoomCreate -> "$sender created the room"
            is OtherState.RoomEncryption -> "Messages are end-to-end encrypted"
            is OtherState.RoomTombstone -> "This room has been replaced"
            is OtherState.RoomJoinRules -> "$sender changed who can join"
            is OtherState.RoomHistoryVisibility -> "$sender changed history visibility"
            is OtherState.RoomPinnedEvents -> "$sender changed the pinned messages"
            is OtherState.RoomCanonicalAlias -> "$sender changed the room address"
            is OtherState.RoomPowerLevels -> null
            is OtherState.RoomThirdPartyInvite -> "$sender invited ${s.displayName ?: "someone"}"
            else -> null
        }
        is TimelineItemContent.CallInvite -> "$sender started a call"
        is TimelineItemContent.RtcNotification -> "$sender started a call"
        else -> null
    }

    private val replyFallback = Regex("^(> .*\\n)+\\n", RegexOption.MULTILINE)

    /** Removes the legacy "> <@user> quoted text" reply fallback from plain bodies. */
    fun stripReplyFallback(body: String): String =
        if (body.startsWith("> ")) body.replaceFirst(replyFallback, "") else body

    private val mxReply = Regex("<mx-reply>.*?</mx-reply>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    fun stripHtmlReplyFallback(html: String): String = html.replace(mxReply, "")
}
