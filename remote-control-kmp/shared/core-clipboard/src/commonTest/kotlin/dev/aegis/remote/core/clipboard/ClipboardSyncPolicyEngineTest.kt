package dev.aegis.remote.core.clipboard

import dev.aegis.remote.core.model.ClipboardPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ClipboardSyncPolicyEngineTest {
    private val engine = ClipboardSyncPolicyEngine(maxAutomaticTextBytes = 12)

    @Test
    fun manualPolicyRequiresExplicitUserActionByDefault() {
        val decision =
            engine.evaluate(
                request(
                    policy = ClipboardSyncPolicy.Manual,
                    text = "copy me",
                    userConfirmed = false,
                ),
            )

        assertIs<ClipboardSyncDecision.RequiresConfirmation>(decision)
    }

    @Test
    fun manualPolicyAllowsSafeTextAfterUserConfirmation() {
        val decision =
            engine.evaluate(
                request(
                    policy = ClipboardSyncPolicy.Manual,
                    text = "copy me",
                    userConfirmed = true,
                ),
            )

        assertEquals(ClipboardSyncDecision.Allowed, decision)
    }

    @Test
    fun disabledPolicyBlocksClipboardSyncEvenWhenConfirmed() {
        val decision =
            engine.evaluate(
                request(
                    policy = ClipboardSyncPolicy.Disabled,
                    text = "copy me",
                    userConfirmed = true,
                ),
            )

        assertIs<ClipboardSyncDecision.Blocked>(decision)
    }

    @Test
    fun askEveryTimeRequiresConfirmationForEachTransfer() {
        val pending =
            engine.evaluate(
                request(
                    policy = ClipboardSyncPolicy.AskEveryTime,
                    text = "copy me",
                    userConfirmed = false,
                ),
            )
        val confirmed =
            engine.evaluate(
                request(
                    policy = ClipboardSyncPolicy.AskEveryTime,
                    text = "copy me",
                    userConfirmed = true,
                ),
            )

        assertIs<ClipboardSyncDecision.RequiresConfirmation>(pending)
        assertEquals(ClipboardSyncDecision.Allowed, confirmed)
    }

    @Test
    fun automaticPolicyAllowsSafeShortText() {
        val decision =
            engine.evaluate(
                request(
                    policy = ClipboardSyncPolicy.Automatic,
                    text = "safe text",
                ),
            )

        assertEquals(ClipboardSyncDecision.Allowed, decision)
    }

    @Test
    fun automaticPolicyBlocksBlankText() {
        val decision =
            engine.evaluate(
                request(
                    policy = ClipboardSyncPolicy.Automatic,
                    text = "   ",
                ),
            )

        assertIs<ClipboardSyncDecision.Blocked>(decision)
    }

    @Test
    fun automaticPolicyBlocksLongText() {
        val decision =
            engine.evaluate(
                request(
                    policy = ClipboardSyncPolicy.Automatic,
                    text = "this text is too long",
                ),
            )

        assertIs<ClipboardSyncDecision.Blocked>(decision)
    }

    @Test
    fun absoluteByteLimitAlsoAppliesToConfirmedManualSync() {
        val boundedEngine = ClipboardSyncPolicyEngine(maxAutomaticTextBytes = 4, maxTextBytes = 6)

        val decision =
            boundedEngine.evaluate(
                request(
                    policy = ClipboardSyncPolicy.Manual,
                    text = "éééé",
                    userConfirmed = true,
                ),
            )

        val blocked = assertIs<ClipboardSyncDecision.Blocked>(decision)
        assertEquals(CLIPBOARD_PAYLOAD_TOO_LARGE_CODE, blocked.code)
    }

    @Test
    fun loopGuardSuppressesRemoteEchoAndShortLivedLocalDuplicates() {
        var now = 1_000L
        val guard = ClipboardLoopGuard(clock = { now }, remoteEchoSuppressionMillis = 100, duplicateSuppressionMillis = 50)
        val remote = ClipboardPayload.Text("remote")
        val local = ClipboardPayload.Text("local")

        guard.markRemoteWrite(remote)
        assertEquals(false, guard.shouldForwardLocalChange(remote))
        assertEquals(true, guard.shouldForwardLocalChange(local))
        assertEquals(false, guard.shouldForwardLocalChange(local))

        now += 101L
        assertEquals(true, guard.shouldForwardLocalChange(remote))
    }

    @Test
    fun automaticPolicyBlocksCommonSecretMarkers() {
        val samples =
            listOf(
                "password=hunter2",
                "Authorization: Bearer abc123",
                "api_key=abc123",
                "refresh_token=abc123",
                "secret=abc123",
            )

        samples.forEach { sample ->
            assertIs<ClipboardSyncDecision.Blocked>(
                engine.evaluate(request(policy = ClipboardSyncPolicy.Automatic, text = sample)),
                "Expected secret marker to be blocked: $sample",
            )
        }
    }

    @Test
    fun automaticPolicyBlocksPrivateKeysAndJwtLikeTokens() {
        val privateKeyDecision =
            engine.evaluate(
                request(
                    policy = ClipboardSyncPolicy.Automatic,
                    text = "-----BEGIN OPENSSH PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----",
                ),
            )
        val jwtDecision =
            engine.evaluate(
                request(
                    policy = ClipboardSyncPolicy.Automatic,
                    text = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.signature",
                ),
            )

        assertIs<ClipboardSyncDecision.Blocked>(privateKeyDecision)
        assertIs<ClipboardSyncDecision.Blocked>(jwtDecision)
    }

    private fun request(
        policy: ClipboardSyncPolicy,
        text: String,
        userConfirmed: Boolean = false,
    ) = ClipboardSyncRequest(
        policy = policy,
        direction = ClipboardSyncDirection.DesktopToAndroid,
        payload = ClipboardPayload.Text(text),
        userConfirmed = userConfirmed,
    )
}
