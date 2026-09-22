plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":app-desktop:desktop-capture"))
    implementation(project(":app-desktop:desktop-clipboard"))
    implementation(project(":app-desktop:desktop-input"))
    implementation(project(":app-desktop:desktop-webrtc"))
    implementation(project(":shared:core-model"))
    implementation(project(":shared:core-clipboard"))
    implementation(project(":shared:core-input"))
    implementation(project(":shared:core-monitor"))
    implementation(project(":shared:core-pairing"))
    implementation(project(":shared:core-relay"))
    implementation(project(":shared:core-security"))
    implementation(project(":shared:core-session"))
    implementation(project(":shared:core-storage"))
    implementation(project(":shared:core-webrtc"))
    implementation(project(":shared:core-logging"))
    implementation(project(":shared:relay-client-ktor"))
    implementation(project(":protocol:protocol-models"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.jna.platform)
    // Netty's JDK self-signed generator is unavailable on current JDKs; bcpkix
    // provides the supported certificate generator used by local HTTPS pairing.
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    val isWindows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
    if (isWindows) {
        exclude("**/LinuxAegisOpenSshManagerTest*")
        exclude("**/LinuxPreflightTest*")
        exclude("**/LinuxUfwOnboardingManagerTest*")
    }
}
