package app.poppy.push

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import app.poppy.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.HttpPusherData
import org.matrix.rustcomponents.sdk.PushFormat
import org.matrix.rustcomponents.sdk.PusherIdentifiers
import org.matrix.rustcomponents.sdk.PusherKind
import org.unifiedpush.android.connector.UnifiedPush
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Distributor(val packageName: String, val label: String)

sealed interface PushStatus {
    data object NotConfigured : PushStatus
    data object Registering : PushStatus
    data class Registered(val distributor: String, val gateway: String, val warning: String? = null) : PushStatus
    data class Failed(val reason: String) : PushStatus
    data object Polling : PushStatus
}

/**
 * Push without Google: Poppy registers with a UnifiedPush distributor (Sunup, ntfy, NextPush,
 * Conversations, ...) and hands the endpoint to the homeserver through a Matrix push gateway.
 * If no distributor is installed, a battery-friendly periodic sync job is used instead.
 */
class PushManager(private val context: Context, private val graph: AppGraph) {
    private val settings get() = graph.settings

    private val _status = MutableStateFlow<PushStatus>(PushStatus.NotConfigured)
    val status: StateFlow<PushStatus> = _status

    fun distributors(): List<Distributor> {
        val pm = context.packageManager
        return UnifiedPush.getDistributors(context).map { pkg ->
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)).toString()
            }.getOrDefault(pkg)
            Distributor(pkg, if (pkg == context.packageName) "Poppy (embedded)" else label)
        }
    }

    fun currentDistributor(): String? = UnifiedPush.getAckDistributor(context) ?: UnifiedPush.getSavedDistributor(context)

    fun select(packageName: String) {
        _status.value = PushStatus.Registering
        UnifiedPush.saveDistributor(context, packageName)
        UnifiedPush.register(context, INSTANCE, "Poppy", null)
    }

    suspend fun disable() {
        val endpoint = settings.pushEndpoint
        runCatching { UnifiedPush.unregister(context, INSTANCE) }
        if (endpoint != null) removePusher(endpoint)
        settings.pushEndpoint = null
        settings.pushGateway = null
        _status.value = PushStatus.NotConfigured
    }

    /** Called on start-up once a session is available. */
    suspend fun onSessionReady() {
        if (graph.matrix.current == null) return
        val distributor = currentDistributor()
        when {
            distributor != null -> {
                val gw = settings.pushGateway
                val ep = settings.pushEndpoint
                _status.value = if (ep != null && gw != null) PushStatus.Registered(label(distributor), gw, webPushWarning(ep)) else PushStatus.Registering
                if (settings.pollingEnabled) schedulePolling()
                // Re-register every launch: distributors may rotate endpoints.
                UnifiedPush.register(context, INSTANCE, "Poppy", null)
            }
            settings.pollingEnabled -> {
                schedulePolling()
                _status.value = PushStatus.Polling
            }
            else -> _status.value = PushStatus.NotConfigured
        }
    }

    suspend fun onNewEndpoint(endpoint: String) {
        val session = graph.matrix.ensureSession() ?: return
        try {
            val gateway = withContext(Dispatchers.IO) { discoverGateway(endpoint) }
            val previous = settings.pushEndpoint
            if (previous != null && previous != endpoint) removePusher(previous)
            session.client.setPusher(
                identifiers = PusherIdentifiers(pushkey = endpoint, appId = context.packageName),
                kind = PusherKind.Http(HttpPusherData(url = gateway, format = PushFormat.EVENT_ID_ONLY, defaultPayload = null)),
                appDisplayName = "Poppy",
                deviceDisplayName = "${Build.MANUFACTURER} ${Build.MODEL}",
                profileTag = null,
                lang = Locale.getDefault().toLanguageTag(),
                append = true,
            )
            settings.pushEndpoint = endpoint
            settings.pushGateway = gateway
            _status.value = PushStatus.Registered(label(currentDistributor()), gateway, webPushWarning(endpoint))
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register pusher", t)
            _status.value = PushStatus.Failed(t.message ?: "Could not register with the homeserver")
        }
    }

    /**
     * Matrix homeservers send plain JSON through the gateway. Push services built on Mozilla's
     * autopush (e.g. Sunup) only accept RFC 8291-encrypted, VAPID-signed Web Push, so they reject
     * those requests until homeservers implement MSC4174 (Web Push pushers).
     */
    private fun webPushWarning(endpoint: String): String? {
        val host = runCatching { URL(endpoint).host }.getOrNull() ?: return null
        return if (WEB_PUSH_ONLY_HOSTS.any { host == it || host.endsWith(".$it") }) {
            "This distributor uses encrypted Web Push (Mozilla autopush). Matrix homeservers can't send that yet, " +
                "so notifications won't arrive. Use ntfy or NextPush instead, or turn on background polling."
        } else {
            null
        }
    }

    private var testWaiter: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    /**
     * End-to-end check of distributor → push server → gateway, without the homeserver: posts a
     * fake Matrix notification to the gateway and waits for it to come back through the distributor.
     * Returns a human-readable result.
     */
    suspend fun sendTestPush(): String {
        val endpoint = settings.pushEndpoint ?: return "Push isn't registered yet. Pick a distributor first."
        val gateway = settings.pushGateway ?: DEFAULT_GATEWAY
        val waiter = kotlinx.coroutines.CompletableDeferred<Unit>().also { testWaiter = it }
        val body = JSONObject().put(
            "notification",
            JSONObject()
                .put("event_id", TEST_EVENT_ID)
                .put("room_id", TEST_ROOM_ID)
                .put("counts", JSONObject().put("unread", 1))
                .put("prio", "high")
                .put(
                    "devices",
                    org.json.JSONArray().put(
                        JSONObject().put("app_id", context.packageName).put("pushkey", endpoint).put("pushkey_ts", 0).put("data", JSONObject()),
                    ),
                ),
        ).toString()
        val gatewayResult = withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL(gateway).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val text = (if (code < 400) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                code to text
            }
        }
        val (code, text) = gatewayResult.getOrElse { return "Couldn't reach the push gateway ($gateway): ${it.message}" }
        if (code >= 400) return "The push gateway refused the test (HTTP $code): $text"
        if (text.contains(endpoint)) {
            return "The push server rejected the message for your endpoint." + (webPushWarning(endpoint)?.let { "\n\n$it" } ?: "")
        }
        val arrived = kotlinx.coroutines.withTimeoutOrNull(20_000) { waiter.await() } != null
        testWaiter = null
        return if (arrived) {
            "Push works: the test notification came back through your distributor."
        } else {
            "The gateway accepted the test but it never reached this phone. Check that your distributor app is " +
                "connected and allowed to run in the background." + (webPushWarning(endpoint)?.let { "\n\n$it" } ?: "")
        }
    }

    /** Returns true if this push was our self-test (and consumes it). */
    fun onPushReceived(roomId: String?): Boolean {
        if (roomId != TEST_ROOM_ID) return false
        testWaiter?.complete(Unit)
        return true
    }

    fun onRegistrationFailed(reason: String) {
        _status.value = PushStatus.Failed(reason)
    }

    suspend fun onUnregistered() {
        settings.pushEndpoint?.let { removePusher(it) }
        settings.pushEndpoint = null
        _status.value = PushStatus.NotConfigured
    }

    private suspend fun removePusher(endpoint: String) {
        val client = graph.matrix.currentClient() ?: return
        runCatching { client.deletePusher(PusherIdentifiers(pushkey = endpoint, appId = context.packageName)) }
    }

    /**
     * Discover the Matrix gateway for an endpoint as described in the UnifiedPush spec: the push
     * server may embed one at /_matrix/push/v1/notify; otherwise use the public one.
     */
    private fun discoverGateway(endpoint: String): String {
        val url = URL(endpoint)
        val candidate = "${url.protocol}://${url.authority}/_matrix/push/v1/notify"
        return try {
            val conn = (URL(candidate).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5000
                readTimeout = 5000
            }
            conn.inputStream.bufferedReader().use { reader ->
                val json = JSONObject(reader.readText())
                if (json.optJSONObject("unifiedpush")?.optString("gateway") == "matrix") candidate else DEFAULT_GATEWAY
            }
        } catch (t: Throwable) {
            DEFAULT_GATEWAY
        }
    }

    fun setPolling(enabled: Boolean) {
        settings.pollingEnabled = enabled
        if (enabled) {
            schedulePolling()
            _status.value = PushStatus.Polling
        } else {
            context.getSystemService(JobScheduler::class.java).cancel(PollingJobService.JOB_ID)
            if (_status.value == PushStatus.Polling) _status.value = PushStatus.NotConfigured
        }
        if (enabled && currentDistributor() != null) {
            // Polling runs alongside push as a safety net; keep showing the push status.
            val ep = settings.pushEndpoint
            val gw = settings.pushGateway
            if (ep != null && gw != null) _status.value = PushStatus.Registered(label(currentDistributor()), gw, webPushWarning(ep))
        }
    }

    private fun schedulePolling() {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(PollingJobService.JOB_ID) != null) return
        val job = JobInfo.Builder(PollingJobService.JOB_ID, ComponentName(context, PollingJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPeriodic(TimeUnit.MINUTES.toMillis(15), TimeUnit.MINUTES.toMillis(5))
            .setPersisted(true)
            .build()
        scheduler.schedule(job)
    }

    private fun label(pkg: String?): String =
        pkg?.let { p -> distributors().firstOrNull { it.packageName == p }?.label ?: p } ?: "Unknown"

    companion object {
        private const val TAG = "PushManager"
        const val INSTANCE = "default"
        const val DEFAULT_GATEWAY = "https://matrix.gateway.unifiedpush.org/_matrix/push/v1/notify"
        private const val TEST_ROOM_ID = "!poppy-push-test:poppy"
        private const val TEST_EVENT_ID = "\$poppy-push-test"
        private val WEB_PUSH_ONLY_HOSTS = listOf("push.services.mozilla.com")
    }
}
