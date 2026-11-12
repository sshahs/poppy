package app.poppy.push

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import app.poppy.graph
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.NotificationEvent
import org.matrix.rustcomponents.sdk.NotificationItem
import org.matrix.rustcomponents.sdk.SyncNotificationListener

/**
 * Fallback for devices without a UnifiedPush distributor: every ~15 minutes (as scheduled by
 * the OS, respecting Doze) run sync for a short window and surface anything new.
 */
class PollingJobService : JobService() {
    private var job: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val g = applicationContext.graph
        if (g.isForeground.value) return false
        job = g.scope.launch {
            try {
                val session = g.matrix.ensureSession() ?: return@launch
                registerHandler(session.client)
                session.startSync()
                delay(SYNC_WINDOW_MS)
                if (!g.isForeground.value) session.stopSync()
            } catch (t: Throwable) {
                Log.w("PollingJob", "poll failed", t)
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        job?.cancel()
        return true
    }

    private suspend fun registerHandler(client: org.matrix.rustcomponents.sdk.Client) {
        if (registeredFor == client.userId()) return
        val g = applicationContext.graph
        client.registerNotificationHandler(object : SyncNotificationListener {
            override fun onNotification(notification: NotificationItem, roomId: String) {
                val eventId = when (val e = notification.event) {
                    is NotificationEvent.Timeline -> e.event.eventId()
                    is NotificationEvent.Invite -> "invite-$roomId"
                }
                // Only surface while backgrounded; the open app shows messages itself.
                if (!g.isForeground.value) g.scope.launch { g.notifications.show(notification, roomId, eventId) }
            }
        })
        registeredFor = client.userId()
    }

    companion object {
        const val JOB_ID = 4242
        private const val SYNC_WINDOW_MS = 30_000L
        @Volatile private var registeredFor: String? = null
    }
}
