package app.poppy.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.SessionVerificationController
import org.matrix.rustcomponents.sdk.SessionVerificationControllerDelegate
import org.matrix.rustcomponents.sdk.SessionVerificationData
import org.matrix.rustcomponents.sdk.SessionVerificationRequestDetails

data class SasEmoji(val symbol: String, val description: String)

sealed interface VerificationStep {
    data object Idle : VerificationStep
    data object Requesting : VerificationStep
    data class Incoming(val userId: String, val deviceId: String, val deviceName: String?, val flowId: String) : VerificationStep
    data object Accepted : VerificationStep
    data object Starting : VerificationStep
    data class Emojis(val emojis: List<SasEmoji>) : VerificationStep
    data class Decimals(val values: List<Int>) : VerificationStep
    data object Confirming : VerificationStep
    data object Done : VerificationStep
    data object Cancelled : VerificationStep
    data object Failed : VerificationStep
}

/** Interactive (SAS emoji) device verification, both outgoing and incoming. */
class VerificationManager(private val client: Client, private val scope: CoroutineScope) {
    private var controller: SessionVerificationController? = null

    private val _step = MutableStateFlow<VerificationStep>(VerificationStep.Idle)
    val step: StateFlow<VerificationStep> = _step

    private var incomingIsPending = false

    private val delegate = object : SessionVerificationControllerDelegate {
        override fun didReceiveVerificationRequest(details: SessionVerificationRequestDetails) {
            incomingIsPending = true
            _step.value = VerificationStep.Incoming(
                userId = details.senderProfile.userId,
                deviceId = details.deviceId,
                deviceName = details.deviceDisplayName,
                flowId = details.flowId,
            )
            scope.launch {
                runCatching { controller?.acknowledgeVerificationRequest(details.senderProfile.userId, details.flowId) }
            }
        }

        override fun didAcceptVerificationRequest() {
            _step.value = VerificationStep.Accepted
            // Outgoing flow: we drive SAS once the other device accepts.
            if (!incomingIsPending) {
                scope.launch {
                    _step.value = VerificationStep.Starting
                    runCatching { controller?.startSasVerification() }.onFailure { _step.value = VerificationStep.Failed }
                }
            }
        }

        override fun didStartSasVerification() {
            if (_step.value !is VerificationStep.Emojis) _step.value = VerificationStep.Starting
        }

        override fun didReceiveVerificationData(data: SessionVerificationData) {
            _step.value = when (data) {
                is SessionVerificationData.Emojis -> VerificationStep.Emojis(data.emojis.map { SasEmoji(it.symbol(), it.description()) })
                is SessionVerificationData.Decimals -> VerificationStep.Decimals(data.values.map { it.toInt() })
            }
        }

        override fun didFail() {
            incomingIsPending = false
            _step.value = VerificationStep.Failed
        }

        override fun didCancel() {
            incomingIsPending = false
            _step.value = VerificationStep.Cancelled
        }

        override fun didFinish() {
            incomingIsPending = false
            _step.value = VerificationStep.Done
        }
    }

    suspend fun attach() {
        if (controller != null) return
        client.encryption().waitForE2eeInitializationTasks()
        controller = client.getSessionVerificationController().also { it.setDelegate(delegate) }
    }

    fun requestVerification() = act {
        incomingIsPending = false
        _step.value = VerificationStep.Requesting
        it.requestDeviceVerification()
    }

    fun acceptIncoming() = act {
        _step.value = VerificationStep.Accepted
        it.acceptVerificationRequest()
    }

    fun approve() = act {
        _step.value = VerificationStep.Confirming
        it.approveVerification()
    }

    fun decline() = act { it.declineVerification() }

    fun cancel() = act {
        it.cancelVerification()
        _step.value = VerificationStep.Cancelled
    }

    fun reset() {
        incomingIsPending = false
        _step.value = VerificationStep.Idle
    }

    private fun act(block: suspend (SessionVerificationController) -> Unit) {
        scope.launch {
            try {
                if (controller == null) attach()
                block(controller!!)
            } catch (t: Throwable) {
                _step.value = VerificationStep.Failed
            }
        }
    }

    fun close() {
        runCatching { controller?.setDelegate(null) }
    }
}
