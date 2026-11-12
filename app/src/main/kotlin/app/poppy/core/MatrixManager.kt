package app.poppy.core

import android.content.Context
import android.os.Build
import android.util.Log
import app.poppy.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uniffi.matrix_sdk_common.BackgroundTaskFailureReason
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.ClientDelegate
import org.matrix.rustcomponents.sdk.ClientSessionDelegate
import org.matrix.rustcomponents.sdk.HomeserverLoginDetails
import org.matrix.rustcomponents.sdk.OAuthConfiguration
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SsoHandler
import org.matrix.rustcomponents.sdk.TaskHandle
import uniffi.matrix_sdk.BackupDownloadStrategy
import uniffi.matrix_sdk.OAuthAuthorizationData
import java.io.File

sealed interface SessionState {
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val session: MatrixSession) : SessionState
}

/** A login attempt against one homeserver, before we have a session. */
class LoginFlow internal constructor(
    val client: Client,
    val details: HomeserverLoginDetails,
    internal val dataPath: String,
    internal val cachePath: String,
    internal val passphrase: String,
) {
    val homeserver: String get() = details.url()
    val supportsOAuth: Boolean get() = details.supportsOauthLogin()
    val supportsPassword: Boolean get() = details.supportsPasswordLogin()
    val supportsSso: Boolean get() = details.supportsSsoLogin()
    internal var oauth: OAuthAuthorizationData? = null
    internal var sso: SsoHandler? = null
}

class MatrixManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val store: SessionStore,
) {
    private val _state = MutableStateFlow<SessionState>(SessionState.Loading)
    val state: StateFlow<SessionState> = _state

    private val mutex = Mutex()
    private var pendingLogin: LoginFlow? = null
    private var delegateHandle: TaskHandle? = null
    private var stopJob: Job? = null
    @Volatile private var foreground = false

    val current: MatrixSession? get() = (state.value as? SessionState.LoggedIn)?.session
    fun currentClient(): Client? = current?.client

    private val sessionDelegate = object : ClientSessionDelegate {
        override fun retrieveSessionFromKeychain(userId: String): Session =
            store.find(userId)?.session ?: error("No session for $userId")

        override fun saveSessionInKeychain(session: Session) = store.updateSession(session)
    }

    private val clientDelegate = object : ClientDelegate {
        override fun didReceiveAuthError(isSoftLogout: Boolean) {
            Log.w(TAG, "Auth error (soft=$isSoftLogout)")
            if (!isSoftLogout) scope.launch { clearLocal() }
        }

        override fun onBackgroundTaskErrorReport(taskName: String, error: BackgroundTaskFailureReason) {
            Log.w(TAG, "Background task $taskName failed: $error")
        }
    }

    private fun builder(dataPath: String, cachePath: String, passphrase: String): ClientBuilder =
        ClientBuilder()
            .sqliteStore(SqliteStoreBuilder(dataPath, cachePath).passphrase(passphrase))
            .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.DISCOVER_NATIVE)
            .autoEnableCrossSigning(true)
            .autoEnableBackups(true)
            .backupDownloadStrategy(BackupDownloadStrategy.AFTER_DECRYPTION_FAILURE)
            .enableShareHistoryOnInvite(true)
            .threadsEnabled(true, false)
            .userAgent("Poppy/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE}; ${Build.MODEL})")
            .setSessionDelegate(sessionDelegate)

    /** Restore the last account, if any. Called once at start-up. */
    suspend fun restore() = mutex.withLock {
        if (_state.value is SessionState.LoggedIn) return@withLock
        val account = store.active()
        if (account == null) {
            _state.value = SessionState.LoggedOut
            return@withLock
        }
        try {
            val client = builder(account.dataPath, account.cachePath, account.storePassphrase)
                .homeserverUrl(account.session.homeserverUrl)
                .build()
            client.restoreSession(account.session)
            activate(client)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to restore session", t)
            // Keep the account; a transient failure (e.g. no network) must not log the user out.
            _state.value = SessionState.LoggedOut
        }
    }

    /** Make sure a client exists (used by push / background work while the UI is closed). */
    suspend fun ensureSession(): MatrixSession? {
        current?.let { return it }
        restore()
        return current
    }

    suspend fun startLogin(serverInput: String): LoginFlow {
        pendingLogin?.let { runCatching { it.client.destroy() } }
        val (data, cache) = store.newPaths()
        val pass = store.newPassphrase()
        val server = serverInput.trim().removeSuffix("/")
        val client = builder(data, cache, pass).serverNameOrHomeserverUrl(server).build()
        val details = client.homeserverLoginDetails()
        return LoginFlow(client, details, data, cache, pass).also { pendingLogin = it }
    }

    suspend fun loginWithPassword(flow: LoginFlow, user: String, password: String) {
        flow.client.login(user.trim(), password, deviceName(), null)
        completeLogin(flow)
    }

    /** Returns the URL to open in a Custom Tab for OAuth 2.0 (MAS / next-gen auth). */
    suspend fun oauthUrl(flow: LoginFlow): String {
        val data = flow.client.urlForOauth(oauthConfig(), null, null, null, null)
        flow.oauth = data
        return data.loginUrl()
    }

    suspend fun ssoUrl(flow: LoginFlow): String {
        val handler = flow.client.startSsoLogin(REDIRECT_URI, null)
        flow.sso = handler
        return handler.url()
    }

    /** Called with the redirect URI the browser sends back to us. */
    suspend fun completeBrowserLogin(callbackUrl: String): Boolean {
        val flow = pendingLogin ?: return false
        when {
            flow.oauth != null -> flow.client.loginWithOauthCallback(callbackUrl)
            flow.sso != null -> flow.sso!!.finish(callbackUrl)
            else -> return false
        }
        completeLogin(flow)
        return true
    }

    suspend fun cancelBrowserLogin() {
        val flow = pendingLogin ?: return
        flow.oauth?.let { runCatching { flow.client.abortOauthAuth(it) } }
        flow.oauth = null
        flow.sso = null
    }

    private suspend fun completeLogin(flow: LoginFlow) = mutex.withLock {
        val session = flow.client.session()
        store.save(StoredAccount(session, flow.dataPath, flow.cachePath, flow.passphrase))
        pendingLogin = null
        activate(flow.client)
    }

    private fun activate(client: Client) {
        delegateHandle?.cancel()
        delegateHandle = client.setDelegate(clientDelegate)
        val session = MatrixSession(context, client, scope)
        _state.value = SessionState.LoggedIn(session)
        scope.launch {
            session.init()
            if (foreground) session.startSync()
        }
    }

    suspend fun logout() {
        val session = current ?: return
        runCatching { session.syncService?.stop() }
        runCatching { session.client.logout() }.onFailure { Log.w(TAG, "Server logout failed", it) }
        clearLocal()
    }

    private suspend fun clearLocal() = mutex.withLock {
        val session = current ?: return@withLock
        val account = store.find(session.userId)
        session.close()
        store.remove(session.userId)
        account?.let {
            File(it.dataPath).deleteRecursively()
            File(it.cachePath).deleteRecursively()
        }
        _state.value = SessionState.LoggedOut
    }

    fun onForeground() {
        foreground = true
        stopJob?.cancel()
        scope.launch { current?.startSync() }
    }

    fun onBackground() {
        foreground = false
        stopJob?.cancel()
        // Linger briefly so quick app switches don't tear down the sliding sync connection.
        stopJob = scope.launch {
            delay(STOP_DELAY_MS)
            current?.stopSync()
        }
    }

    val isForeground: Boolean get() = foreground

    private fun deviceName() = "Poppy on ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    private fun oauthConfig() = OAuthConfiguration(
        clientName = "Poppy",
        redirectUri = REDIRECT_URI,
        clientUri = CLIENT_URI,
        logoUri = null,
        tosUri = null,
        policyUri = null,
        staticRegistrations = emptyMap(),
    )

    companion object {
        private const val TAG = "MatrixManager"
        private const val STOP_DELAY_MS = 8_000L
        const val OAUTH_SCHEME = "io.github.sshahs.poppy"
        const val REDIRECT_URI = "$OAUTH_SCHEME:/oauth"
        const val CLIENT_URI = "https://sshahs.github.io/poppy/"
    }
}
