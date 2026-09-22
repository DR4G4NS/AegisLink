package dev.aegis.remote.desktop.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LinuxUfwOnboardingManagerTest {
    @Test
    fun `requires explicit consent and invokes fixed pkexec helper with exact scope`() {
        val runner = RecordingRunner()
        val manager = LinuxUfwOnboardingManager(runner, "Linux", ENABLED)
        val scope = LinuxLanScope("wlan0", "192.168.7.0/24")

        assertEquals(LinuxUfwApplyResult.ConsentRequired, manager.apply(scope, explicitConsent = false))
        assertEquals(emptyList(), runner.calls)
        runner.enqueueInspection()
        runner.results += LinuxUfwCommandResult(0, "ok")
        assertEquals(LinuxUfwApplyResult.Applied, manager.apply(scope, explicitConsent = true))
        assertEquals(listOf("pkexec", "/usr/lib/aegis-remote-desktop/aegis-ufw-onboard", "wlan0", "192.168.7.0/24"), runner.calls.last())
    }

    @Test
    fun `rejects ambiguous default interfaces`() {
        val runner = RecordingRunner()
        runner.results += LinuxUfwCommandResult(0, "default via 10.0.0.1 dev eth0\ndefault via 10.0.1.1 dev wlan0\n")

        assertIs<LinuxUfwInspection.Rejected>(LinuxUfwOnboardingManager(runner, "Linux", configurationReader = ENABLED).inspect())
        assertEquals(1, runner.calls.size)
    }

    @Test
    fun `rejects injection and noncanonical or ambiguous CIDR`() {
        val invalid = LinuxLanScope("eth0;id", "192.168.1.0/24")
        val manager = LinuxUfwOnboardingManager(RecordingRunner(), "Linux", configurationReader = ENABLED)
        assertIs<LinuxUfwApplyResult.Failed>(manager.apply(invalid, explicitConsent = true))

        val runner = RecordingRunner()
        runner.results += LinuxUfwCommandResult(0, "default via 192.168.1.1 dev eth0\n")
        runner.results += LinuxUfwCommandResult(0, "192.168.1.7/24 proto kernel scope link src 192.168.1.7\n")
        assertIs<LinuxUfwInspection.Rejected>(LinuxUfwOnboardingManager(runner, "Linux", configurationReader = ENABLED).inspect())
    }

    @Test
    fun `rejects loopback container and VPN default interfaces`() {
        listOf("lo", "docker0", "veth123", "tun0", "wg0", "tailscale0").forEach { interfaceName ->
            val runner = RecordingRunner()
            runner.results += LinuxUfwCommandResult(0, "default dev $interfaceName\n")
            assertIs<LinuxUfwInspection.Rejected>(
                LinuxUfwOnboardingManager(runner, "Linux", configurationReader = ENABLED).inspect(),
            )
        }
    }

    @Test
    fun `does not onboard or authorize when ufw is inactive`() {
        val runner = RecordingRunner()
        val disabled = LinuxUfwConfigurationReader { false }
        assertEquals(
            LinuxUfwInspection.UfwInactive,
            LinuxUfwOnboardingManager(runner, "Linux", configurationReader = disabled).inspect(),
        )
        assertEquals(0, runner.calls.size)
    }

    @Test
    fun `rejects changed LAN scope before authorization`() {
        val runner = RecordingRunner()
        runner.enqueueInspection(cidr = "10.4.0.0/16")
        val result = LinuxUfwOnboardingManager(runner, "Linux", configurationReader = ENABLED).apply(LinuxLanScope("eth0", "192.168.7.0/24"), true)
        assertIs<LinuxUfwApplyResult.Failed>(result)
        assertEquals(2, runner.calls.size)
    }

    private companion object {
        val ENABLED = LinuxUfwConfigurationReader { true }
    }

    private class RecordingRunner : LinuxUfwCommandRunner {
        val calls = mutableListOf<List<String>>()
        val results = ArrayDeque<LinuxUfwCommandResult>()

        override fun run(arguments: List<String>): LinuxUfwCommandResult {
            calls += arguments
            return results.removeFirst()
        }

        fun enqueueInspection(cidr: String = "192.168.7.0/24") {
            results += LinuxUfwCommandResult(0, "default via 192.168.7.1 dev wlan0 proto dhcp\n")
            results += LinuxUfwCommandResult(0, "$cidr proto kernel scope link src 192.168.7.5\n")
        }
    }
}
