import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":app-desktop:desktop-capture"))
    implementation(project(":protocol:protocol-models"))
    implementation(project(":shared:core-clipboard"))
    implementation(project(":shared:core-input"))
    implementation(project(":shared:core-model"))
    implementation(project(":shared:core-monitor"))
    implementation(project(":shared:core-nat"))
    implementation(project(":shared:core-network"))
    implementation(project(":shared:core-pairing"))
    implementation(project(":shared:core-session"))
    implementation(project(":shared:core-webrtc"))
    implementation(libs.jna.platform)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.webrtc.java)
    // The upstream POM references a Maven property for its native classifier,
    // which Gradle cannot resolve automatically. Native distributions are
    // produced on their target OS, so resolve that OS's JNI artifact explicitly.
    val nativeClassifier =
        when {
            System.getProperty("os.name").contains("Windows", ignoreCase = true) -> "windows-x86_64"
            System.getProperty("os.name").contains("Linux", ignoreCase = true) -> "linux-x86_64"
            else -> null
        }
    nativeClassifier?.let { classifier ->
        runtimeOnly(variantOf(libs.webrtc.java) { classifier(classifier) })
    }

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}

tasks.withType<Test>().configureEach {
    providers.systemProperty("aegis.nativeWebRtcIntegration").orNull?.let { enabled ->
        systemProperty("aegis.nativeWebRtcIntegration", enabled)
    }
}

val isLinuxHost = System.getProperty("os.name").contains("Linux", ignoreCase = true)
val portalPipeWireSource = layout.projectDirectory.file("src/main/native/aegis_pipewire_portal_capture.c")
val portalPipeWireBinary = layout.buildDirectory.file("native/linux-x86_64/aegis-pipewire-portal-capture")

val compilePortalPipeWireBridge =
    tasks.register<Exec>("compilePortalPipeWireBridge") {
        onlyIf { isLinuxHost }
        inputs.file(portalPipeWireSource)
        outputs.file(portalPipeWireBinary)
        doFirst {
            val pkgConfig =
                ProcessBuilder("pkg-config", "--cflags", "--libs", "libpipewire-0.3", "dbus-1")
                    .redirectErrorStream(true)
                    .start()
            val flags = pkgConfig.inputStream.bufferedReader().use { it.readText().trim() }
            check(pkgConfig.waitFor() == 0) {
                "PipeWire portal bridge requires libpipewire-0.3 and dbus-1 development flags: $flags"
            }
            val output = portalPipeWireBinary.get().asFile
            output.parentFile.mkdirs()
            commandLine(
                listOf("cc", "-std=c11", "-Wall", "-Wextra", "-Werror", portalPipeWireSource.asFile.absolutePath) +
                    flags.split(Regex("\\s+")).filter(String::isNotBlank) +
                    listOf("-o", output.absolutePath),
            )
        }
    }

if (isLinuxHost) {
    tasks.named<ProcessResources>("processResources") {
        dependsOn(compilePortalPipeWireBridge)
        from(portalPipeWireBinary) {
            into("native/linux-x86_64")
        }
    }
}
