package dev.aegis.remote.desktop.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class WindowsPreflightTest {
    @Test
    fun nonWindowsHostReportsUnsupportedOperatingSystem() {
        val report =
            WindowsPreflightDetector(
                osName = "Linux",
                architecture = "amd64",
            ).detect()

        assertEquals("Linux", report.operatingSystem)
        assertEquals(WindowsPreflightStatus.Unavailable, report.capabilities.getValue("operating-system").status)
    }

    @Test
    fun windowsHostReportsCoreCapabilitiesWhenDependenciesArePresent() {
        val home = temporaryHome()
        val scriptRoot = createOpenSshScriptTree(home.resolve("repo/packaging/windows/openssh"))
        val available =
            WindowsPreflightCapability(
                WindowsPreflightStatus.Available,
                "available",
                "No action required",
            )
        val report =
            WindowsPreflightDetector(
                osName = "Windows 11",
                architecture = "amd64",
                userHome = home,
                appData = home.resolve("AppData/Roaming").toString(),
                headless = false,
                portProbe = { true },
                openSshScriptDirectory = { scriptRoot },
                webrtcNativeProbe = { available },
                desktopDuplicationProbe = { _, _ -> available },
                sendInputProbe = { available },
                dpapiProbe = { available },
                networkCategories = { listOf("Private") },
            ).detect()

        assertEquals("windows", report.capabilities.getValue("operating-system").backend)
        assertEquals(WindowsPreflightStatus.Available, report.capabilities.getValue("network-profile").status)
        assertEquals(WindowsPreflightStatus.Available, report.capabilities.getValue("architecture").status)
        assertEquals(WindowsPreflightStatus.Available, report.capabilities.getValue("interactive-session").status)
        assertEquals(WindowsPreflightStatus.Available, report.capabilities.getValue("openssh-scripts").status)
        assertEquals(WindowsPreflightStatus.Available, report.capabilities.getValue("openssh-port").status)
        assertEquals(WindowsPreflightStatus.Available, report.capabilities.getValue("data-directory").status)
    }

    @Test
    fun headlessWindowsSessionMarksInteractiveCapabilitiesUnavailable() {
        val unavailable =
            WindowsPreflightCapability(
                WindowsPreflightStatus.Unavailable,
                "unavailable",
                "Launch Aegis from an interactive Windows user session",
            )
        val report =
            WindowsPreflightDetector(
                osName = "Windows 11",
                architecture = "amd64",
                headless = true,
                portProbe = { true },
                openSshScriptDirectory = { null },
                webrtcNativeProbe = { unavailable },
                desktopDuplicationProbe = { _, _ -> unavailable },
                sendInputProbe = { unavailable },
                dpapiProbe = { unavailable },
                networkCategories = { listOf("Private") },
            ).detect()

        assertEquals(WindowsPreflightStatus.Unavailable, report.capabilities.getValue("interactive-session").status)
        assertEquals(WindowsPreflightStatus.Unavailable, report.capabilities.getValue("capture").status)
        assertEquals(WindowsPreflightStatus.Unavailable, report.capabilities.getValue("input").status)
        assertEquals(WindowsPreflightStatus.Unavailable, report.capabilities.getValue("clipboard").status)
    }

    @Test
    fun missingOpenSshScriptsAreReportedAsDegraded() {
        val available =
            WindowsPreflightCapability(
                WindowsPreflightStatus.Available,
                "available",
                "No action required",
            )
        val report =
            WindowsPreflightDetector(
                osName = "Windows 11",
                architecture = "amd64",
                headless = false,
                portProbe = { true },
                openSshScriptDirectory = { null },
                webrtcNativeProbe = { available },
                desktopDuplicationProbe = { _, _ -> available },
                sendInputProbe = { available },
                dpapiProbe = { available },
                networkCategories = { listOf("Private") },
            ).detect()

        assertEquals(WindowsPreflightStatus.Degraded, report.capabilities.getValue("openssh-scripts").status)
    }

    @Test
    fun occupiedOpenSshPortIsAvailableWhenManagedServiceIsRunning() {
        val available =
            WindowsPreflightCapability(
                WindowsPreflightStatus.Available,
                "available",
                "No action required",
            )
        val report =
            WindowsPreflightDetector(
                osName = "Windows 11",
                architecture = "amd64",
                headless = false,
                portProbe = { false },
                openSshScriptDirectory = { Path.of("C:/Program Files/Aegis Remote Desktop/provisioning/openssh") },
                webrtcNativeProbe = { available },
                desktopDuplicationProbe = { _, _ -> available },
                sendInputProbe = { available },
                dpapiProbe = { available },
                openSshServiceRunning = { true },
                networkCategories = { listOf("Private") },
            ).detect()

        assertEquals(WindowsPreflightStatus.Available, report.capabilities.getValue("openssh-port").status)
        assertTrue(
            report.capabilities
                .getValue("openssh-port")
                .cause
                .contains("AegisOpenSSH"),
        )
    }

    @Test
    fun publicOnlyNetworkProfileIsDegraded() {
        val report = detectorWithNetworkCategories(listOf("Public")).detect()
        val capability = report.capabilities.getValue("network-profile")
        assertEquals(WindowsPreflightStatus.Degraded, capability.status)
        assertEquals("NETWORK_PUBLIC", capability.cause)
    }

    @Test
    fun spanishPublicCategoryIsTreatedAsPublic() {
        val report = detectorWithNetworkCategories(listOf("Público")).detect()
        assertEquals(WindowsPreflightStatus.Degraded, report.capabilities.getValue("network-profile").status)
    }

    @Test
    fun mixedPrivateAndPublicNetworkStaysAvailable() {
        val report = detectorWithNetworkCategories(listOf("Public", "Private")).detect()
        assertEquals(WindowsPreflightStatus.Available, report.capabilities.getValue("network-profile").status)
    }

    @Test
    fun locateWindowsOpenSshScriptDirectoryFindsPackagedScripts() {
        val home = temporaryHome()
        val scriptRoot = createOpenSshScriptTree(home.resolve("repo/packaging/windows/openssh"))

        val previousProperty = System.getProperty("aegis.openssh.scripts")
        System.setProperty("aegis.openssh.scripts", scriptRoot.toString())
        try {
            assertEquals(scriptRoot, locateWindowsOpenSshScriptDirectory())
        } finally {
            if (previousProperty == null) {
                System.clearProperty("aegis.openssh.scripts")
            } else {
                System.setProperty("aegis.openssh.scripts", previousProperty)
            }
        }
    }

    private fun detectorWithNetworkCategories(categories: List<String>): WindowsPreflightDetector {
        val available =
            WindowsPreflightCapability(
                WindowsPreflightStatus.Available,
                "available",
                "No action required",
            )
        return WindowsPreflightDetector(
            osName = "Windows 11",
            architecture = "amd64",
            headless = false,
            portProbe = { true },
            openSshScriptDirectory = { null },
            webrtcNativeProbe = { available },
            desktopDuplicationProbe = { _, _ -> available },
            sendInputProbe = { available },
            dpapiProbe = { available },
            networkCategories = { categories },
        )
    }

    private fun temporaryHome(): Path = Files.createTempDirectory("aegis-windows-preflight-home")

    private fun createOpenSshScriptTree(directory: Path): Path {
        Files.createDirectories(directory)
        WINDOWS_OPENSSH_SCRIPT_NAMES.forEach { script ->
            Files.writeString(directory.resolve(script), "# test\n")
        }
        assertTrue(Files.isDirectory(directory))
        return directory
    }
}
