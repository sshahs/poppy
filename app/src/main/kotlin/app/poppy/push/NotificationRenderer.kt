package app.poppy.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import app.poppy.AppGraph
import app.poppy.R
import app.poppy.core.Formatter
import app.poppy.ui.MainActivity
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.MessageLikeEventContent
import org.matrix.rustcomponents.sdk.NotificationEvent
import org.matrix.rustcomponents.sdk.NotificationItem
import org.matrix.rustcomponents.sdk.NotificationStatus
import org.matrix.rustcomponents.sdk.TimelineEventContent
import java.util.concurrent.ConcurrentHashMap

/** Builds conversation-style notifications (MessagingStyle, direct reply, mark as read). */
class NotificationRenderer(private val context: Context, private val graph: AppGraph) {
    private val manager = NotificationManagerCompat.from(context)

    private data class Line(val eventId: String, val sender: String, val senderId: String, val text: String, val ts: Long, val avatar: Bitmap?)

    private val history = ConcurrentHashMap<String, MutableList<Line>>()
    private val avatarCache = ConcurrentHashMap<String, Bitmap>()

    fun createChannels() {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(
            listOf(
                NotificationChannel(CH_MESSAGES, context.getString(R.string.notification_channel_messages), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_NOISY, context.getString(R.string.notification_channel_noisy), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CH_INVITES, context.getString(R.string.notification_channel_invites), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CH_BACKGROUND, context.getString(R.string.notification_channel_background), NotificationManager.IMPORTANCE_MIN),
            ),
        )
    }

    suspend fun handlePush(roomId: String, eventId: String) {
        if (history[roomId]?.any { it.eventId == eventId } == true) return
        val session = graph.matrix.ensureSession() ?: return
        val status = session.notificationClient().getNotification(roomId, eventId)
        if (status is NotificationStatus.Event) show(status.item, roomId, eventId)
    }

    /** Also used by the polling fallback (sync notification handler). */
    suspend fun show(item: NotificationItem, roomId: String, eventId: String) {
        if (graph.isForeground.value && graph.visibleRoomId == roomId) return
        if (!canPost()) return
        val room = item.roomInfo
        val senderName = item.senderInfo.displayName ?: "Someone"
        when (val ev = item.event) {
            is NotificationEvent.Invite -> {
                postInvite(roomId, room.displayName, senderName)
                return
            }
            is NotificationEvent.Timeline -> {
                val senderId = ev.event.senderId()
                if (senderId == graph.matrix.current?.userId) return
                val text = describe(ev.event.content()) ?: return
                val avatar = item.senderInfo.avatarUrl?.let { loadAvatar(it) }
                val lines = history.getOrPut(roomId) { mutableListOf() }
                synchronized(lines) {
                    if (lines.none { it.eventId == eventId }) {
                        lines += Line(eventId, senderName, senderId, text, System.currentTimeMillis(), avatar)
                        while (lines.size > MAX_LINES) lines.removeAt(0)
                    }
                }
                val roomAvatar = room.avatarUrl?.let { loadAvatar(it) }
                post(roomId, room.displayName, isGroup = !room.isDirect, noisy = item.isNoisy == true || item.hasMention == true, roomAvatar = roomAvatar)
            }
        }
    }

    private fun describe(content: TimelineEventContent): String? = when (content) {
        is TimelineEventContent.MessageLike -> when (val c = content.content) {
            is MessageLikeEventContent.RoomMessage -> Formatter.stripReplyFallback(Formatter.messageBody(c.messageType))
            is MessageLikeEventContent.Poll -> "📊 ${c.question}"
            is MessageLikeEventContent.Sticker -> "Sent a sticker"
            is MessageLikeEventContent.RoomEncrypted -> "🔒 New encrypted message"
            is MessageLikeEventContent.CallInvite, is MessageLikeEventContent.RtcNotification -> "📞 Incoming call"
            is MessageLikeEventContent.ReactionContent -> null
            else -> null
        }
        is TimelineEventContent.State -> null
    }

    private fun post(roomId: String, roomName: String, isGroup: Boolean, noisy: Boolean, roomAvatar: Bitmap?) {
        val lines = history[roomId]?.toList() ?: return
        val me = Person.Builder().setName("You").setKey(graph.matrix.current?.userId ?: "me").build()
        val style = NotificationCompat.MessagingStyle(me)
            .setConversationTitle(if (isGroup) roomName else null)
            .setGroupConversation(isGroup)
        lines.forEach { l ->
            val person = Person.Builder().setName(l.sender).setKey(l.senderId)
                .apply { l.avatar?.let { setIcon(IconCompat.createWithBitmap(it)) } }
                .build()
            style.addMessage(l.text, l.ts, if (l.senderId == "me") null else person)
        }
        val id = roomId.hashCode()
        val builder = NotificationCompat.Builder(context, if (noisy) CH_NOISY else CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.poppy_red))
            .setStyle(style)
            .setLargeIcon(roomAvatar ?: lines.last().avatar)
            .setContentIntent(openRoomIntent(roomId))
            .setDeleteIntent(actionIntent(NotificationActionReceiver.ACTION_DISMISS, roomId))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setGroup(GROUP)
            .setShortcutId(roomId)
            .setNumber(lines.size)
            .addAction(replyAction(roomId))
            .addAction(
                NotificationCompat.Action.Builder(0, "Mark as read", actionIntent(NotificationActionReceiver.ACTION_MARK_READ, roomId))
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                    .setShowsUserInterface(false)
                    .build(),
            )
        if (canPost()) {
            @Suppress("MissingPermission")
            manager.notify(TAG_ROOM, id, builder.build())
            postSummary()
        }
    }

    /** Appends our own direct-reply to the conversation notification. */
    fun appendOwnReply(roomId: String, text: String) {
        val lines = history[roomId] ?: return
        synchronized(lines) { lines += Line("local-${System.nanoTime()}", "You", "me", text, System.currentTimeMillis(), null) }
        val name = graph.matrix.current?.client?.getRoom(roomId)?.displayName() ?: ""
        post(roomId, name, isGroup = true, noisy = false, roomAvatar = null)
    }

    private fun postInvite(roomId: String, roomName: String, inviter: String) {
        val n = NotificationCompat.Builder(context, CH_INVITES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.poppy_red))
            .setContentTitle(roomName)
            .setContentText("$inviter invited you")
            .setContentIntent(openRoomIntent(roomId))
            .setAutoCancel(true)
            .setGroup(GROUP)
            .build()
        if (canPost()) {
            @Suppress("MissingPermission")
            manager.notify(TAG_INVITE, roomId.hashCode(), n)
        }
    }

    private fun postSummary() {
        val n = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.poppy_red))
            .setGroup(GROUP)
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        manager.notify(SUMMARY_ID, n)
    }

    fun showTest() {
        if (!canPost()) return
        val n = NotificationCompat.Builder(context, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(ContextCompat.getColor(context, R.color.poppy_red))
            .setContentTitle("Poppy")
            .setContentText("Push notifications are working \uD83D\uDC3E")
            .setAutoCancel(true)
            .build()
        @Suppress("MissingPermission")
        manager.notify(TEST_ID, n)
    }

    fun clearRoom(roomId: String) {
        history.remove(roomId)
        manager.cancel(TAG_ROOM, roomId.hashCode())
        manager.cancel(TAG_INVITE, roomId.hashCode())
        if (history.isEmpty()) manager.cancel(SUMMARY_ID)
    }

    fun forget(roomId: String) {
        history.remove(roomId)
    }

    fun clearAll() {
        history.clear()
        manager.cancelAll()
    }

    private fun replyAction(roomId: String): NotificationCompat.Action {
        val input = RemoteInput.Builder(NotificationActionReceiver.KEY_REPLY).setLabel("Reply").build()
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(NotificationActionReceiver.ACTION_REPLY)
            .putExtra(NotificationActionReceiver.EXTRA_ROOM, roomId)
        val pi = PendingIntent.getBroadcast(
            context, roomId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
        return NotificationCompat.Action.Builder(0, "Reply", pi)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
    }

    private fun actionIntent(action: String, roomId: String): PendingIntent {
        val intent = Intent(context, NotificationActionReceiver::class.java)
            .setAction(action)
            .putExtra(NotificationActionReceiver.EXTRA_ROOM, roomId)
        return PendingIntent.getBroadcast(context, (action + roomId).hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun openRoomIntent(roomId: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_ROOM)
            .putExtra(MainActivity.EXTRA_ROOM_ID, roomId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, roomId.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private suspend fun loadAvatar(mxc: String): Bitmap? {
        avatarCache[mxc]?.let { return it }
        val client = graph.matrix.currentClient() ?: return null
        return runCatching {
            val source = MediaSource.fromUrl(mxc)
            val bytes = client.getMediaThumbnail(source, 128u, 128u)
            source.destroy()
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let(::circle)
        }.getOrNull()?.also { avatarCache[mxc] = it }
    }

    private fun circle(src: Bitmap): Bitmap {
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return out
    }

    private fun canPost(): Boolean =
        android.os.Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val CH_MESSAGES = "messages"
        const val CH_NOISY = "noisy"
        const val CH_INVITES = "invites"
        const val CH_BACKGROUND = "background"
        private const val GROUP = "poppy.messages"
        private const val TAG_ROOM = "room"
        private const val TAG_INVITE = "invite"
        private const val SUMMARY_ID = 1
        private const val TEST_ID = 2
        private const val MAX_LINES = 8
    }
}
