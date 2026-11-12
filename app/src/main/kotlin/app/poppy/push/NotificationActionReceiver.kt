package app.poppy.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import app.poppy.graph
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.messageEventContentFromMarkdown

/** Direct reply and "mark as read" straight from the notification shade. */
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val roomId = intent.getStringExtra(EXTRA_ROOM) ?: return
        val g = context.graph
        val pending = goAsync()
        g.scope.launch {
            try {
                withTimeoutOrNull(9_000) {
                    when (intent.action) {
                        ACTION_DISMISS -> g.notifications.forget(roomId)
                        ACTION_MARK_READ -> {
                            g.notifications.clearRoom(roomId)
                            g.matrix.ensureSession()?.client?.getRoom(roomId)?.markAsRead(ReceiptType.READ)
                        }
                        ACTION_REPLY -> {
                            val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
                            if (!text.isNullOrBlank()) {
                                val room = g.matrix.ensureSession()?.client?.getRoom(roomId)
                                if (room != null) {
                                    val timeline = room.timeline()
                                    timeline.send(messageEventContentFromMarkdown(text))
                                    g.notifications.appendOwnReply(roomId, text)
                                    room.markAsRead(ReceiptType.READ)
                                }
                            }
                        }
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_REPLY = "app.poppy.action.REPLY"
        const val ACTION_MARK_READ = "app.poppy.action.MARK_READ"
        const val ACTION_DISMISS = "app.poppy.action.DISMISS"
        const val EXTRA_ROOM = "room_id"
        const val KEY_REPLY = "reply_text"
    }
}
