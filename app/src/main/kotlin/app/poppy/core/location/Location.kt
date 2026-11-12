package app.poppy.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.poppy.R
import app.poppy.graph
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/** Location without Google Play Services: plain android.location.LocationManager. */
object Locations {
    fun hasPermission(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun geoUri(l: Location): String =
        String.format(Locale.US, "geo:%.6f,%.6f;u=%.0f", l.latitude, l.longitude, l.accuracy.toDouble())

    fun describe(l: Location): String = String.format(Locale.US, "Location: %.5f, %.5f", l.latitude, l.longitude)

    /** A fresh fix if possible (≤ 20 s), else the best last-known location. */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context): Location? {
        if (!hasPermission(context)) return null
        val lm = context.getSystemService(LocationManager::class.java)
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        val fresh = withTimeoutOrNull(20_000) {
            val provider = if (Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
                LocationManager.FUSED_PROVIDER
            } else {
                providers.firstOrNull { it != LocationManager.PASSIVE_PROVIDER } ?: return@withTimeoutOrNull null
            }
            suspendCancellableCoroutine<Location?> { cont ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val signal = CancellationSignal()
                    cont.invokeOnCancellation { signal.cancel() }
                    lm.getCurrentLocation(provider, signal, context.mainExecutor) { cont.resume(it) }
                } else {
                    val listener = object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            lm.removeUpdates(this)
                            if (cont.isActive) cont.resume(location)
                        }
                    }
                    cont.invokeOnCancellation { lm.removeUpdates(listener) }
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                }
            }
        }
        return fresh ?: providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }
}

/**
 * Foreground service that keeps sending beacons for a live location share (MSC3489) until the
 * chosen duration ends or the user stops it.
 */
class LiveLocationService : Service() {
    private var job: Job? = null
    private var listener: LocationListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSharing()
            return START_NOT_STICKY
        }
        val roomId = intent?.getStringExtra(EXTRA_ROOM) ?: return START_NOT_STICKY
        val duration = intent.getLongExtra(EXTRA_DURATION, 15 * 60_000L)
        startForegroundCompat(roomId)
        start(roomId, duration)
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun start(roomId: String, durationMs: Long) {
        job?.cancel()
        val g = applicationContext.graph
        job = g.scope.launch {
            val room = g.matrix.ensureSession()?.client?.getRoom(roomId) ?: return@launch stopSelf()
            try {
                room.startLiveLocationShare(durationMs.toULong())
                _sharing.value = _sharing.value + roomId
                Locations.current(this@LiveLocationService)?.let { room.sendLiveLocation(Locations.geoUri(it)) }
                val lm = getSystemService(LocationManager::class.java)
                val provider = if (Build.VERSION.SDK_INT >= 31 && lm.isProviderEnabled(LocationManager.FUSED_PROVIDER)) LocationManager.FUSED_PROVIDER else LocationManager.GPS_PROVIDER
                val l = LocationListener { loc -> g.scope.launch { runCatching { room.sendLiveLocation(Locations.geoUri(loc)) } } }
                listener = l
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    lm.requestLocationUpdates(provider, 30_000L, 15f, l, Looper.getMainLooper())
                }
                delay(durationMs)
            } catch (t: Throwable) {
                Log.w("LiveLocation", "share failed", t)
            } finally {
                // Runs on cancellation too (user tapped Stop), so it must not be cancellable itself.
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    runCatching { room.stopLiveLocationShare() }
                }
                _sharing.value = _sharing.value - roomId
                stopSharing()
            }
        }
    }

    private fun stopSharing() {
        listener?.let { getSystemService(LocationManager::class.java).removeUpdates(it) }
        listener = null
        val j = job
        job = null
        j?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundCompat(roomId: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live location", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, LiveLocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = applicationContext.graph.matrix.currentClient()?.getRoom(roomId)?.displayName() ?: "a chat"
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Sharing live location")
            .setContentText("With $name")
            .setOngoing(true)
            .addAction(0, "Stop", stop)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) else startForeground(NOTIF_ID, n)
    }

    companion object {
        private const val CHANNEL = "live_location"
        private const val NOTIF_ID = 77
        private const val ACTION_STOP = "app.poppy.location.STOP"
        private const val EXTRA_ROOM = "room"
        private const val EXTRA_DURATION = "duration"

        private val _sharing = MutableStateFlow<Set<String>>(emptySet())
        /** Rooms we're currently sharing live location with. */
        val sharing: StateFlow<Set<String>> = _sharing

        fun start(context: Context, roomId: String, durationMs: Long) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, LiveLocationService::class.java).putExtra(EXTRA_ROOM, roomId).putExtra(EXTRA_DURATION, durationMs),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, LiveLocationService::class.java).setAction(ACTION_STOP))
        }
    }
}
