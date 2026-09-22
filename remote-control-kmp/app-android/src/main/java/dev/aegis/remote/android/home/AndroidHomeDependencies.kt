package dev.aegis.remote.android.home

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.aegis.remote.android.clipboard.AndroidClipboardBridge
import dev.aegis.remote.android.clipboard.AndroidClipboardProtocolBridge
import dev.aegis.remote.android.clipboard.AndroidClipboardSyncGate
import dev.aegis.remote.android.data.AndroidDeviceProfileRepository
import dev.aegis.remote.android.data.AndroidRelayConfigRepository
import dev.aegis.remote.android.input.AndroidInputProtocolBridge
import dev.aegis.remote.android.input.AndroidTrackpadGestureMapper
import dev.aegis.remote.android.input.AndroidVisualPointerMapper
import dev.aegis.remote.android.pairing.AndroidLocalPairingClient
import dev.aegis.remote.android.pairing.AndroidLocalPairingCrypto
import dev.aegis.remote.android.pairing.AndroidLocalPairingQrVerifier
import dev.aegis.remote.android.pairing.AndroidPairingIdentityStore
import dev.aegis.remote.android.routing.AndroidRouteHealthChecker
import dev.aegis.remote.android.routing.AndroidTurnConfigProvider
import dev.aegis.remote.android.security.AndroidDeviceIdentityStore
import dev.aegis.remote.android.security.AndroidSecureCredentialStore
import dev.aegis.remote.android.security.AndroidSshEnrollmentKeyStore
import dev.aegis.remote.android.security.AndroidSshHealthChecker
import dev.aegis.remote.android.sftp.AndroidSftpClient
import dev.aegis.remote.android.terminal.AndroidSshTerminalClient
import dev.aegis.remote.android.webrtc.AndroidVisualProtocolBridge
import dev.aegis.remote.android.wol.AndroidWakeOnLanSender
import dev.aegis.remote.core.model.RelayConfig
import dev.aegis.remote.core.model.RelayDeviceId
import dev.aegis.remote.core.pairing.LocalPairingQrPayloadParser
import dev.aegis.remote.core.routing.BasicConnectionRouteManager
import dev.aegis.remote.core.routing.ConnectionRouteManager
import dev.aegis.remote.core.security.SecureCredentialStore
import dev.aegis.remote.core.session.ExponentialBackoffReconnectionManager
import dev.aegis.remote.core.sftp.SftpClient
import dev.aegis.remote.core.storage.DeviceProfileRepository
import dev.aegis.remote.core.storage.RelayConfigRepository
import dev.aegis.remote.core.terminal.SshTerminalClient
import dev.aegis.remote.relayclient.KtorRelayClient
import dev.aegis.remote.relayclient.defaultKtorRelayHttpClient

/** Mutable slot owned by the Android home session and shared with TURN lookup. */
internal class AndroidRelayClientSlot {
    var client: KtorRelayClient? = null
}

internal data class AndroidRouteManagerContext(
    val relayClientProvider: () -> KtorRelayClient?,
    val isRelayRegistered: () -> Boolean,
    val hasApprovedRelaySession: (RelayDeviceId?) -> Boolean,
    val selectedIceRoute: () -> dev.aegis.remote.core.nat.ValidatedIceRoute? = { null },
)

/** Dependencies for the home facade; concrete platform wiring lives in the graph. */
internal data class AndroidHomeDependencies(
    val profileRepository: DeviceProfileRepository,
    val relayConfigRepository: RelayConfigRepository,
    val credentialStore: SecureCredentialStore,
    val deviceIdentityStore: AndroidDeviceIdentityStore,
    val localPairingClient: AndroidLocalPairingClient,
    val localPairingQrPayloadParser: LocalPairingQrPayloadParser,
    val localPairingQrVerifier: AndroidLocalPairingQrVerifier,
    val localPairingCrypto: AndroidLocalPairingCrypto,
    val localPairingIdentityStore: AndroidPairingIdentityStore,
    val sshEnrollmentKeyStore: AndroidSshEnrollmentKeyStore,
    val sshHealthChecker: AndroidSshHealthChecker,
    val terminalClient: SshTerminalClient,
    val sftpClient: SftpClient,
    val wakeOnLanSender: AndroidWakeOnLanSender,
    val clipboardBridge: AndroidClipboardBridge,
    val clipboardProtocolBridge: AndroidClipboardProtocolBridge,
    val clipboardSyncGate: AndroidClipboardSyncGate,
    val inputProtocolBridge: AndroidInputProtocolBridge,
    val trackpadGestureMapper: AndroidTrackpadGestureMapper,
    val visualPointerMapper: AndroidVisualPointerMapper,
    val visualProtocolBridge: AndroidVisualProtocolBridge,
    val terminalReconnectionManager: ExponentialBackoffReconnectionManager,
    val relayReconnectionManager: ExponentialBackoffReconnectionManager,
    val turnConfigProvider: AndroidTurnConfigProvider,
    val relayClientSlot: AndroidRelayClientSlot,
    val relayClientFactory: (RelayConfig) -> KtorRelayClient,
    val routeManagerFactory: (AndroidRouteManagerContext) -> ConnectionRouteManager,
)

/** Manual Android composition root. It has no lifecycle state beyond its dependencies. */
internal class AegisAndroidAppGraph(
    val application: Application,
) {
    private val credentialStore = AndroidSecureCredentialStore(application)
    private val relayClientSlot = AndroidRelayClientSlot()

    val homeDependencies: AndroidHomeDependencies by lazy {
        AndroidHomeDependencies(
            profileRepository = AndroidDeviceProfileRepository.create(application),
            relayConfigRepository = AndroidRelayConfigRepository(application),
            credentialStore = credentialStore,
            deviceIdentityStore = AndroidDeviceIdentityStore(application),
            localPairingClient = AndroidLocalPairingClient(),
            localPairingQrPayloadParser = LocalPairingQrPayloadParser(),
            localPairingQrVerifier = AndroidLocalPairingQrVerifier(),
            localPairingCrypto = AndroidLocalPairingCrypto(),
            localPairingIdentityStore = AndroidPairingIdentityStore(application),
            sshEnrollmentKeyStore = AndroidSshEnrollmentKeyStore(credentialStore),
            sshHealthChecker = AndroidSshHealthChecker(credentialStore),
            terminalClient = AndroidSshTerminalClient(credentialStore),
            sftpClient = AndroidSftpClient(credentialStore, application.contentResolver),
            wakeOnLanSender = AndroidWakeOnLanSender(),
            clipboardBridge = AndroidClipboardBridge(application),
            clipboardProtocolBridge = AndroidClipboardProtocolBridge(),
            clipboardSyncGate = AndroidClipboardSyncGate(),
            inputProtocolBridge = AndroidInputProtocolBridge(),
            trackpadGestureMapper = AndroidTrackpadGestureMapper(),
            visualPointerMapper = AndroidVisualPointerMapper(),
            visualProtocolBridge = AndroidVisualProtocolBridge(),
            terminalReconnectionManager =
                ExponentialBackoffReconnectionManager(
                    baseDelayMillis = 500,
                    maxDelayMillis = 2_000,
                    maxAttempts = 3,
                ),
            relayReconnectionManager =
                ExponentialBackoffReconnectionManager(
                    baseDelayMillis = 1_000,
                    maxDelayMillis = 15_000,
                    maxAttempts = 8,
                ),
            turnConfigProvider =
                AndroidTurnConfigProvider(
                    credentialStore = credentialStore,
                    relayClientProvider = { relayClientSlot.client },
                ),
            relayClientSlot = relayClientSlot,
            relayClientFactory = { config ->
                KtorRelayClient(
                    baseUrl = config.relayUrl.trim(),
                    httpClient = defaultKtorRelayHttpClient(),
                )
            },
            routeManagerFactory = { context ->
                BasicConnectionRouteManager(
                    healthChecker =
                        AndroidRouteHealthChecker(
                            relayClientProvider = context.relayClientProvider,
                            isRelayRegistered = context.isRelayRegistered,
                            hasApprovedRelaySession = { id -> context.hasApprovedRelaySession(id) },
                            selectedIceRoute = context.selectedIceRoute,
                            clock = { System.currentTimeMillis() },
                        ),
                    clock = { System.currentTimeMillis() },
                )
            },
        )
    }
}

/** Factory used by Compose and by tests to construct the facade with supplied fakes. */
internal class AndroidHomeViewModelFactory(
    private val graph: AegisAndroidAppGraph,
    private val dependencies: AndroidHomeDependencies = graph.homeDependencies,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(AndroidHomeViewModel::class.java)) {
            "Unsupported ViewModel: ${modelClass.name}"
        }
        return modelClass.cast(AndroidHomeViewModel(graph.application, dependencies))
    }
}
