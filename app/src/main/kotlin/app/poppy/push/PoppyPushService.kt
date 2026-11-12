package app.poppy.push

import android.content.Context
import android.os.PowerManager
import android.util.Log
import app.poppy.graph
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/** Receives pushes from any UnifiedPush distributor (e.g. Sunup, ntfy). */
class PoppyPushService : PushService() {

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val g = applicationContext.graph
        g.scope.launch { g.push.onNewEndpoint(endpoint.url) }
    }

    override fun onMessage(message: PushMessage, instance: String) {
        val payload = runCatching { JSONObject(String(message.content, Charsets.UTF_8)) }.getOrNull() ?: return
        val notification = payload.optJSONObject("notification") ?: return
        val roomId = notification.optString("room_id").takeIf { it.isNotEmpty() }
        val eventId = notification.optString("event_id").takeIf { it.isNotEmpty() }
        val unread = notification.optJSONObject("counts")?.optInt("unread", -1) ?: -1
        val g = applicationContext.graph
        if (roomId == null || eventId == null) {
            // Count-only push (e.g. read on another device): clear when nothing is unread.
            if (unread == 0) g.notifications.clearAll()
            return
        }
        val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "poppy:push").apply { acquire(30_000) }
        g.scope.launch {
            try {
                withTimeoutOrNull(25_000) { g.notifications.handlePush(roomId, eventId) }
            } catch (t: Throwable) {
                Log.e("PoppyPush", "Failed to handle push", t)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
            }
        }
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        applicationContext.graph.push.onRegistrationFailed(
            when (reason) {
                FailedReason.NETWORK -> "The distributor could not reach its server"
                FailedReason.ACTION_REQUIRED -> "Open your UnifiedPush distributor to finish setup"
                FailedReason.VAPID_REQUIRED -> "This distributor requires VAPID"
                else -> "The distributor refused the registration"
            },
        )
    }

    override fun onUnregistered(instance: String) {
        val g = applicationContext.graph
        g.scope.launch { g.push.onUnregistered() }
    }
}
