package app.poppy

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.poppy.core.AppSettings
import app.poppy.core.MatrixManager
import app.poppy.core.SessionStore
import app.poppy.core.media.MediaFetcher
import app.poppy.core.media.MediaKeyer
import app.poppy.push.NotificationRenderer
import app.poppy.push.PushManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder
import coil3.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.LogLevel
import org.matrix.rustcomponents.sdk.TracingConfiguration
import org.matrix.rustcomponents.sdk.initPlatform

class PoppyApp : Application(), SingletonImageLoader.Factory {

    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        initRust()
        graph = AppGraph(this)
        graph.start()
    }

    private fun initRust() {
        try {
            // JNA opens the library with dlopen, which the JVM can't see; load it through
            // System.loadLibrary too so the JNI init symbol below can be resolved.
            System.loadLibrary("matrix_sdk_ffi")
            org.matrix.rustsdk.Android.init(this)
        } catch (t: Throwable) {
            Log.e("Poppy", "Failed to init TLS platform verifier", t)
        }
        try {
            initPlatform(
                TracingConfiguration(
                    logLevel = if (BuildConfig.DEBUG) LogLevel.INFO else LogLevel.WARN,
                    traceLogPacks = emptyList(),
                    extraTargets = emptyList(),
                    writeToStdoutOrSystem = true,
                    writeToFiles = null,
                    sentryConfig = null,
                ),
                useLightweightTokioRuntime = false,
            )
        } catch (t: Throwable) {
            Log.e("Poppy", "Failed to init Rust platform", t)
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.20).build() }
            .components {
                add(MediaKeyer())
                add(MediaFetcher.Factory { graph.matrix.currentClient() })
                if (Build.VERSION.SDK_INT >= 28) add(AnimatedImageDecoder.Factory()) else add(GifDecoder.Factory())
            }
            .build()
}

/** Hand-rolled dependency graph: no reflection, no codegen, instant start-up. */
class AppGraph(val app: PoppyApp) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settings = AppSettings(app)
    val sessionStore = SessionStore(app)
    val matrix = MatrixManager(app, scope, sessionStore)
    val notifications = NotificationRenderer(app, this)
    val push = PushManager(app, this)

    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground

    /** Room currently on screen; notifications for it are suppressed. */
    @Volatile var visibleRoomId: String? = null

    fun start() {
        notifications.createChannels()
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                _isForeground.value = true
                matrix.onForeground()
            }

            override fun onStop(owner: LifecycleOwner) {
                _isForeground.value = false
                matrix.onBackground()
            }
        })
        scope.launch {
            matrix.restore()
            push.onSessionReady()
        }
    }
}

val Context.graph: AppGraph get() = (applicationContext as PoppyApp).graph
