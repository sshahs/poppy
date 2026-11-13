package app.poppy.core

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.NotificationClient
import org.matrix.rustcomponents.sdk.NotificationProcessSetup
import org.matrix.rustcomponents.sdk.ProfileListener
import org.matrix.rustcomponents.sdk.RecoveryState
import org.matrix.rustcomponents.sdk.RecoveryStateListener
import org.matrix.rustcomponents.sdk.RoomListService
import org.matrix.rustcomponents.sdk.RoomListServiceSyncIndicator
import org.matrix.rustcomponents.sdk.RoomListServiceSyncIndicatorListener
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.SyncServiceState
import org.matrix.rustcomponents.sdk.SyncServiceStateObserver
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.UserProfile
import org.matrix.rustcomponents.sdk.VerificationState
import org.matrix.rustcomponents.sdk.VerificationStateListener

data class OwnProfile(val userId: String, val displayName: String?, val avatarUrl: String?)

/** Everything that belongs to one logged-in account. */
class MatrixSession(
    val context: Context,
    val client: Client,
    private val scope: CoroutineScope,
) {
    val userId: String = client.userId()
    val deviceId: String = client.deviceId()

    var syncService: SyncService? = null
        private set
    var roomListService: RoomListService? = null
        private set

    val rooms = RoomListRepository(scope)
    val verification = VerificationManager(client, scope)
    val spaces = SpacesRepository()

    private val _syncState = MutableStateFlow(SyncServiceState.IDLE)
    val syncState: StateFlow<SyncServiceState> = _syncState

    private val _syncing = MutableStateFlow(false)
    val syncIndicator: StateFlow<Boolean> = _syncing

    private val _verificationState = MutableStateFlow(VerificationState.UNKNOWN)
    val verificationState: StateFlow<VerificationState> = _verificationState

    private val _recoveryState = MutableStateFlow(RecoveryState.UNKNOWN)
    val recoveryState: StateFlow<RecoveryState> = _recoveryState

    private val _profile = MutableStateFlow(OwnProfile(userId, null, null))
    val profile: StateFlow<OwnProfile> = _profile

    private val handles = mutableListOf<TaskHandle>()
    private val initMutex = Mutex()
    private var initialised = false
    private var notificationClient: NotificationClient? = null

    suspend fun init() = initMutex.withLock {
        if (initialised) return@withLock
        val sync = client.syncService().withSharePos(true).finish()
        syncService = sync
        val rls = sync.roomListService()
        roomListService = rls

        handles += sync.state(object : SyncServiceStateObserver {
            override fun onUpdate(state: SyncServiceState) {
                _syncState.value = state
            }
        })
        handles += rls.syncIndicator(1000u, 500u, object : RoomListServiceSyncIndicatorListener {
            override fun onUpdate(syncIndicator: RoomListServiceSyncIndicator) {
                _syncing.value = syncIndicator == RoomListServiceSyncIndicator.SHOW
            }
        })
        val encryption = client.encryption()
        _verificationState.value = encryption.verificationState()
        _recoveryState.value = encryption.recoveryState()
        handles += encryption.verificationStateListener(object : VerificationStateListener {
            override fun onUpdate(status: VerificationState) {
                _verificationState.value = status
            }
        })
        handles += encryption.recoveryStateListener(object : RecoveryStateListener {
            override fun onUpdate(status: RecoveryState) {
                _recoveryState.value = status
            }
        })
        handles += client.subscribeToOwnProfile(object : ProfileListener {
            override fun onUpdate(profile: UserProfile) {
                _profile.value = OwnProfile(profile.userId, profile.displayName, profile.avatarUrl)
            }
        })
        scope.launch {
            runCatching {
                _profile.value = OwnProfile(userId, client.displayName(), client.cachedAvatarUrl() ?: client.avatarUrl())
            }
        }
        rooms.attach(rls)
        initialised = true
        scope.launch { spaces.attach(client) }
        scope.launch { runCatching { verification.attach() }.onFailure { Log.w(TAG, "verification", it) } }
    }

    private var searchBackfill: TaskHandle? = null

    suspend fun startSync() {
        init()
        runCatching { syncService?.start() }.onFailure { Log.w(TAG, "sync start", it) }
        // Index older encrypted history in the background so search covers it.
        if (searchBackfill == null) {
            searchBackfill = runCatching { client.runSearchBackfill(uniffi.matrix_sdk.SearchBackfillStrategy.BACKGROUND) }
                .onFailure { Log.w(TAG, "search backfill", it) }.getOrNull()
        }
    }

    suspend fun stopSync() {
        runCatching { syncService?.stop() }
    }

    suspend fun notificationClient(): NotificationClient {
        notificationClient?.let { return it }
        init()
        val setup = syncService?.let { NotificationProcessSetup.SingleProcess(it) }
            ?: NotificationProcessSetup.MultipleProcesses
        return client.notificationClient(setup).also { notificationClient = it }
    }

    fun refreshProfile() {
        scope.launch {
            runCatching { _profile.value = OwnProfile(userId, client.displayName(), client.avatarUrl()) }
        }
    }

    fun close() {
        handles.forEach { runCatching { it.cancel() } }
        handles.clear()
        searchBackfill?.cancel()
        rooms.close()
        spaces.close()
        verification.close()
    }

    private companion object {
        const val TAG = "MatrixSession"
    }
}
