package app.poppy.ui.call

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.util.Rational
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.poppy.R
import app.poppy.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.ClientProperties
import org.matrix.rustcomponents.sdk.WidgetCapabilities
import org.matrix.rustcomponents.sdk.WidgetCapabilitiesProvider
import org.matrix.rustcomponents.sdk.WidgetDriverHandle
import org.matrix.rustcomponents.sdk.WidgetSettings
import org.matrix.rustcomponents.sdk.generateWebviewUrl
import org.matrix.rustcomponents.sdk.getElementCallRequiredPermissions
import org.matrix.rustcomponents.sdk.makeWidgetDriver
import org.matrix.rustcomponents.sdk.newVirtualElementCallWidget
import uniffi.matrix_sdk.EncryptionSystem
import uniffi.matrix_sdk.HeaderStyle
import uniffi.matrix_sdk.Intent as CallIntent
import uniffi.matrix_sdk.NotificationType
import uniffi.matrix_sdk.VirtualElementCallWidgetConfig
import uniffi.matrix_sdk.VirtualElementCallWidgetProperties
import java.util.Locale
import java.util.UUID

/**
 * MatrixRTC calls through Element Call, embedded as a widget. The Rust SDK's widget driver does
 * all the Matrix work (state events, encryption keys, LiveKit tokens); this activity just hosts the
 * Element Call web app and relays widget-API messages between it and the driver.
 */
class CallActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var handle: WidgetDriverHandle? = null
    private var driverJob: Job? = null
    private var pumpJob: Job? = null
    private var pendingPermission: PermissionRequest? = null

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        pendingPermission?.let { grant(it) }
        pendingPermission = null
        if (result[Manifest.permission.RECORD_AUDIO] == true) CallService.start(this, "Call")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val roomId = intent.getStringExtra(EXTRA_ROOM) ?: return finish()
        val web = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(0xFF101010.toInt())
        }
        webView = web
        setContentView(web)
        val needed = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) permissions.launch(needed.toTypedArray())
        lifecycleScope.launch { start(roomId, web) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun start(roomId: String, web: WebView) {
        val g = graph
        val session = g.matrix.ensureSession() ?: return finish()
        val room = session.client.getRoom(roomId) ?: return finish()
        try {
            val baseUrl = g.settings.elementCallUrl.trimEnd('/')
            val isDm = runCatching { room.isDirect() }.getOrDefault(false)
            val ongoing = room.hasActiveRoomCall()
            val encrypted = runCatching { room.isEncrypted() }.getOrDefault(true)
            val settings: WidgetSettings = newVirtualElementCallWidget(
                VirtualElementCallWidgetProperties(
                    elementCallUrl = baseUrl,
                    widgetId = UUID.randomUUID().toString(),
                    // We host the page top-level, so the "parent" posting messages is the page itself.
                    parentUrl = baseUrl,
                    encryption = if (encrypted) EncryptionSystem.PerParticipantKeys else EncryptionSystem.Unencrypted,
                ),
                VirtualElementCallWidgetConfig(
                    intent = when {
                        isDm && ongoing -> CallIntent.JOIN_EXISTING_DM
                        isDm -> CallIntent.START_CALL_DM
                        ongoing -> CallIntent.JOIN_EXISTING
                        else -> CallIntent.START_CALL
                    },
                    header = HeaderStyle.APP_BAR,
                    preload = false,
                    appPrompt = false,
                    confineToRoom = true,
                    hideScreensharing = true,
                    sendNotificationType = if (isDm) NotificationType.RING else NotificationType.NOTIFICATION,
                ),
            )
            val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val url = generateWebviewUrl(settings, room, ClientProperties(packageName, Locale.getDefault().toLanguageTag(), if (dark) "dark" else "light"))
            val driverAndHandle = makeWidgetDriver(settings)
            val h = driverAndHandle.handle
            handle = h

            val ownUser = session.userId
            val ownDevice = session.deviceId
            driverJob = g.scope.launch {
                try {
                    driverAndHandle.driver.run(room, object : WidgetCapabilitiesProvider {
                        override suspend fun acquireCapabilities(capabilities: WidgetCapabilities): WidgetCapabilities =
                            getElementCallRequiredPermissions(ownUser, ownDevice)
                    })
                } catch (t: Throwable) {
                    Log.w(TAG, "widget driver stopped", t)
                }
            }
            // Driver → widget
            pumpJob = lifecycleScope.launch {
                while (true) {
                    val msg = withContext(Dispatchers.IO) { h.recv() } ?: break
                    web.evaluateJavascript("window.postMessage($msg, '*');", null)
                }
            }

            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                @Suppress("DEPRECATION")
                databaseEnabled = true
            }
            web.addJavascriptInterface(Bridge(), "poppyWidget")
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript(BRIDGE_JS, null)
                }
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: PermissionRequest) {
                    runOnUiThread {
                        val needed = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                            .filter { ContextCompat.checkSelfPermission(this@CallActivity, it) != PackageManager.PERMISSION_GRANTED }
                        if (needed.isEmpty()) grant(request) else {
                            pendingPermission = request
                            permissions.launch(needed.toTypedArray())
                        }
                    }
                }
            }
            CallService.start(this, room.displayName() ?: "Call")
            web.loadUrl(url)
        } catch (t: Throwable) {
            Log.e(TAG, "Couldn't start call", t)
            android.widget.Toast.makeText(this, "Couldn't start the call: ${t.message}", android.widget.Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun grant(request: PermissionRequest) {
        val allowed = request.resources.filter { r ->
            when (r) {
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                else -> false
            }
        }
        if (allowed.isEmpty()) request.deny() else request.grant(allowed.toTypedArray())
    }

    /** Widget → driver. Called on a WebView thread. */
    private inner class Bridge {
        @JavascriptInterface
        fun postMessage(json: String) {
            handle?.send(json)
            val action = runCatching { JSONObject(json).optString("action") }.getOrNull()
            if (action == "im.vector.hangup" || action == "io.element.close") runOnUiThread { finish() }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= 26 && !isFinishing) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(9, 16)).build()) }
        }
    }

    override fun onDestroy() {
        pumpJob?.cancel()
        driverJob?.cancel()
        webView?.destroy()
        CallService.stop(this)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Call"
        private const val EXTRA_ROOM = "room"

        /** Forwards widget→client messages (and widget responses) to the native bridge. */
        private val BRIDGE_JS = """
            (function() {
              if (window.__poppyBridge) return; window.__poppyBridge = true;
              window.addEventListener('message', function(event) {
                var d = event.data;
                if (!d || typeof d !== 'object') return;
                if ((d.api === 'fromWidget' && !d.response) || (d.api === 'toWidget' && d.response)) {
                  poppyWidget.postMessage(JSON.stringify(d));
                }
              });
            })();
        """.trimIndent()

        fun start(context: Context, roomId: String) {
            context.startActivity(Intent(context, CallActivity::class.java).putExtra(EXTRA_ROOM, roomId))
        }
    }
}

/** Keeps the microphone available while a call runs in the background / picture-in-picture. */
class CallService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ongoing call", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Ongoing call")
            .setContentText(intent?.getStringExtra(EXTRA_NAME) ?: "")
            .setOngoing(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 30) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(ID, n)
        } catch (t: Throwable) {
            Log.w("CallService", "foreground start failed", t)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "call"
        private const val ID = 78
        private const val EXTRA_NAME = "name"

        fun start(context: Context, name: String) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, CallService::class.java).putExtra(EXTRA_NAME, name)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CallService::class.java))
        }
    }
}
