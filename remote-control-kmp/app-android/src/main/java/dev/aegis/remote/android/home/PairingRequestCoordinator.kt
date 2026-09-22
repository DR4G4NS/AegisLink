package dev.aegis.remote.android.home

import dev.aegis.remote.android.pairing.LocalPairingPollingOutcome
import dev.aegis.remote.android.pairing.isTransientLocalPairingFailure
import dev.aegis.remote.android.pairing.resolveVerifiedLocalPairingEndpoint
import dev.aegis.remote.android.security.AndroidSshEnrollmentKey
import dev.aegis.remote.core.pairing.LocalPairingRequestBody
import dev.aegis.remote.core.pairing.LocalPairingRequestStatus
import dev.aegis.remote.core.pairing.localPairingDeviceProofPayload
import dev.aegis.remote.core.pairing.localPairingProofPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.Base64

/** Owns the outbound request part of pairing without owning the UI lifecycle job. */
internal class PairingRequestCoordinator(
    private val dependencies: PairingCoordinatorDependencies,
    private val pairingLog: (String, Throwable?) -> Unit,
    private val pollLocalPairingStatus: suspend (LocalPairingDraft, String, dev.aegis.remote.android.pairing.AndroidPairingIdentity) -> Unit,
    private val finishLocalPairingRequest: (LocalPairingRequestStatus, String, LocalPairingDraft) -> Unit,
    private val failLocalPairing: (Throwable, String, LocalPairingStage, Boolean) -> Unit,
) {
    private val state = dependencies.state
    private val scope = dependencies.scope
    private val updateState = dependencies.updateState
    private val localPairingIdentityStore = dependencies.localPairingIdentityStore
    private val deviceIdentityStore = dependencies.deviceIdentityStore
    private val sshEnrollmentKeyStore = dependencies.sshEnrollmentKeyStore
    private val localPairingClient = dependencies.localPairingClient
    private val localPairingCrypto = dependencies.localPairingCrypto
    private val localPairingQrVerifier = dependencies.localPairingQrVerifier
    private val localPairingQrPayloadParser = dependencies.localPairingQrPayloadParser

    fun start(sourceDraft: LocalPairingDraft): Job {
        val draft = sourceDraft.copy(requestId = null, discoveredHost = null)
        updateState {
            it.copy(
                localPairingBusy = true,
                localPairingDraft = draft,
                localPairingMessage = "Contacting the desktop agent...",
                localPairingStage = LocalPairingStage.VerifyingDesktop,
                errorMessage = null,
            )
        }
        return scope.launch {
            runCatching { submitRequest(draft) }
                .onFailure { error ->
                    if (error is CancellationException) {
                        pairingLog("Pairing attempt cancelled because the flow was closed", error)
                        throw error
                    }
                    val stageAtFailure = state.value.localPairingStage
                    val fallback =
                        if (stageAtFailure == LocalPairingStage.SubmittingRequest) {
                            "The PC did not confirm the request in time. No duplicate request was sent."
                        } else {
                            "Could not establish the secure pairing connection."
                        }
                    failLocalPairing(
                        error,
                        fallback,
                        if (error.isTransientLocalPairingFailure()) LocalPairingStage.NetworkError else LocalPairingStage.Failed,
                        state.value.localPairingDraft.requestId != null,
                    )
                }
        }
    }

    private suspend fun submitRequest(draft: LocalPairingDraft) {
        pairingLog("Starting scanned QR verification against the advertised desktop endpoints", null)
        val identity = localPairingIdentityStore.get()
        val deviceIdentity = deviceIdentityStore.getOrCreate()
        val enrollmentKey =
            if (draft.sshCredentialRef != null && draft.sshPublicKey.isNotBlank()) {
                AndroidSshEnrollmentKey(
                    credentialsRef = draft.sshCredentialRef,
                    authorizedKey = draft.sshPublicKey,
                )
            } else {
                sshEnrollmentKeyStore.create("aegis-${deviceIdentity.publicIdentity.deviceId.value.take(24)}")
            }
        val endpoint =
            resolveVerifiedLocalPairingEndpoint(
                pairingUrls = (listOf(draft.pairingUrl) + draft.pairingUrls).filter(String::isNotBlank),
                expectedAgentFingerprint = checkNotNull(draft.agentFingerprint),
                expectedPairingCode = draft.pairingCode,
                client = localPairingClient,
            )
        val requestDraft =
            draft.copy(
                phoneName = identity.displayName,
                pairingUrl = endpoint.pairingUrl,
                sshCredentialRef = enrollmentKey.credentialsRef,
                sshPublicKey = enrollmentKey.authorizedKey,
            )
        val payload = endpoint.payload
        pairingLog("Verified desktop endpoint; submitting one pairing request", null)
        updateState {
            it.copy(
                localPairingBusy = true,
                localPairingDraft = requestDraft,
                localPairingMessage = "Sending the secure approval request...",
                localPairingStage = LocalPairingStage.SubmittingRequest,
                errorMessage = null,
            )
        }
        val unsignedRequest =
            LocalPairingRequestBody(
                pairingCode = requestDraft.pairingCode,
                challengeNonce = payload.nonce,
                challengeProof = "",
                deviceName = identity.displayName,
                deviceFingerprint = deviceIdentity.publicIdentity.fingerprint,
                qrTokenId = requestDraft.qrTokenId,
                deviceIdentity = deviceIdentity.publicIdentity,
                deviceIdentitySignature = "",
                sshPublicKey = enrollmentKey.authorizedKey,
            )
        val hmacProof =
            localPairingCrypto.derive(
                requestDraft.pairingSecret,
                localPairingProofPayload(
                    nonce = payload.nonce,
                    deviceName = identity.displayName,
                    deviceFingerprint = deviceIdentity.publicIdentity.fingerprint,
                    qrTokenId = requestDraft.qrTokenId,
                    sshPublicKey = enrollmentKey.authorizedKey,
                    deviceIdentity = deviceIdentity.publicIdentity,
                ),
            )
        val requestWithHmac = unsignedRequest.copy(challengeProof = hmacProof)
        val identitySignature =
            Base64.getUrlEncoder().withoutPadding().encodeToString(
                deviceIdentity.sign(localPairingDeviceProofPayload(requestWithHmac)),
            )
        val response =
            localPairingClient.requestPairing(
                pairingUrl = requestDraft.pairingUrl,
                request = requestWithHmac.copy(deviceIdentitySignature = identitySignature),
            )
        if (response.status != LocalPairingRequestStatus.Pending) {
            finishLocalPairingRequest(response.status, response.message, requestDraft)
            return
        }
        val requestId = response.requestId?.takeIf { it.isNotBlank() } ?: error("Desktop accepted pairing without a request id")
        val pendingDraft =
            requestDraft.copy(
                requestId = requestId,
                discoveredHost = payload.host,
                agentFingerprint = payload.agentFingerprint,
            )
        pairingLog("Desktop accepted pairing request ${requestId.logId()}; waiting for the user's decision", null)
        updateState {
            it.copy(
                localPairingBusy = true,
                localPairingDraft = pendingDraft,
                localPairingMessage = response.message,
                localPairingStage = LocalPairingStage.AwaitingApproval,
                errorMessage = null,
            )
        }
        pollLocalPairingStatus(pendingDraft, requestId, identity)
    }
}
