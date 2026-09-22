import org.cyclonedx.gradle.CyclonedxDirectTask
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.sqldelight) apply false
    id("org.cyclonedx.bom") version "3.2.4"
    id("io.gitlab.arturbosch.detekt") version "1.23.8" apply false
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0" apply false
}

val aegisVersion = providers.gradleProperty("aegisVersion").orElse("0.2.0")

group = "dev.aegis.remote"
version = aegisVersion.get()

allprojects {
    tasks.withType<CyclonedxDirectTask>().configureEach {
        // A release SBOM describes code shipped at runtime. Excluding build,
        // lint, and test configurations also keeps dependency verification
        // fail-closed instead of authorizing tool-only POMs for packaging.
        includeConfigs.set(
            listOf("^(releaseRuntimeClasspath|runtimeClasspath|jvmRuntimeClasspath)$"),
        )
        // The Gradle graph already supplies exact coordinates and files. POM
        // enrichment makes the plugin re-resolve its own parser dependencies,
        // which are not product artifacts and are intentionally untrusted by
        // verification-metadata.xml.
        includeMetadataResolution.set(false)
    }
}

subprojects {
    group = "dev.aegis.remote"
    version = aegisVersion.get()
    apply(plugin = "io.gitlab.arturbosch.detekt")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    extensions.configure<DetektExtension>("detekt") {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        baseline = rootProject.file(
            "config/detekt/baselines/${path.removePrefix(":").replace(':', '-')}.xml",
        )
        parallel = true
    }
    extensions.configure<KtlintExtension>("ktlint") {
        version.set("1.8.0")
        android.set(path == ":app-android")
        outputToConsole.set(true)
        filter {
            exclude("**/build/**")
            exclude("**/generated/**")
        }
    }

    val isSharedKmpProject = path == ":shared" ||
        path == ":protocol" ||
        path.startsWith(":shared:") ||
        path.startsWith(":protocol:")

    if (isSharedKmpProject) {
        apply(plugin = "org.jetbrains.kotlin.multiplatform")
        apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

        extensions.configure<KotlinMultiplatformExtension>("kotlin") {
            jvmToolchain(21)
            jvm()

            sourceSets.getByName("commonMain").dependencies {
                implementation(rootProject.libs.kotlinx.coroutines.core)
                implementation(rootProject.libs.kotlinx.serialization.json)
            }
            sourceSets.getByName("commonTest").dependencies {
                implementation(kotlin("test"))
                implementation(rootProject.libs.kotlinx.coroutines.test)
            }
        }
    }
}

fun Project.commonMainDependencies(block: org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler.() -> Unit) {
    extensions.configure<KotlinMultiplatformExtension>("kotlin") {
        sourceSets.getByName("commonMain").dependencies(block)
    }
}

project(":shared:core-security").commonMainDependencies {
    implementation(project(":shared:core-model"))
}

project(":shared:core-security").extensions.configure<KotlinMultiplatformExtension>("kotlin") {
    sourceSets.getByName("jvmMain").dependencies {
        implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    }
}

project(":shared:core-network").commonMainDependencies {
    implementation(project(":shared:core-model"))
}

project(":shared:core-terminal").commonMainDependencies {
    implementation(project(":shared:core-model"))
    implementation(project(":shared:core-security"))
}

project(":shared:core-sftp").commonMainDependencies {
    implementation(project(":shared:core-model"))
}

project(":shared:core-input").commonMainDependencies {
    implementation(project(":shared:core-model"))
}

project(":shared:core-webrtc").commonMainDependencies {
    implementation(project(":shared:core-model"))
    api(project(":shared:core-input"))
}

project(":shared:core-clipboard").commonMainDependencies {
    implementation(project(":shared:core-model"))
}

project(":shared:core-wol").commonMainDependencies {
    implementation(project(":shared:core-model"))
}

project(":shared:core-storage").commonMainDependencies {
    implementation(project(":shared:core-model"))
    implementation(project(":shared:core-security"))
}

project(":shared:core-monitor").commonMainDependencies {
    implementation(project(":shared:core-model"))
}

project(":shared:core-quality").commonMainDependencies {
    implementation(project(":shared:core-model"))
}

project(":shared:core-relay").commonMainDependencies {
    implementation(project(":shared:core-model"))
    implementation(project(":shared:core-security"))
    implementation(project(":shared:core-webrtc"))
}

project(":shared:relay-client-ktor").commonMainDependencies {
    implementation(project(":shared:core-model"))
    implementation(project(":shared:core-relay"))
    implementation(project(":shared:core-security"))
    implementation(project(":shared:core-webrtc"))
    implementation(project(":protocol:protocol-models"))
    api(rootProject.libs.ktor.client.core)
    implementation(rootProject.libs.ktor.client.content.negotiation)
    implementation(rootProject.libs.ktor.client.websockets)
    implementation(rootProject.libs.ktor.serialization.kotlinx.json)
}

project(":shared:core-nat").commonMainDependencies {
    implementation(project(":shared:core-model"))
}

project(":shared:core-routing").commonMainDependencies {
    implementation(project(":shared:core-model"))
    implementation(project(":shared:core-nat"))
    implementation(project(":shared:core-relay"))
}

project(":shared:core-pairing").commonMainDependencies {
    implementation(project(":shared:core-model"))
    implementation(project(":shared:core-security"))
    implementation(project(":shared:core-relay"))
}

project(":shared:core-session").commonMainDependencies {
    implementation(project(":shared:core-model"))
    implementation(project(":shared:core-nat"))
    implementation(project(":shared:core-quality"))
    implementation(project(":shared:core-routing"))
    implementation(project(":shared:core-terminal"))
    implementation(project(":shared:core-webrtc"))
}

project(":protocol:protocol-models").commonMainDependencies {
    api(project(":shared:core-model"))
    api(project(":shared:core-input"))
    api(project(":shared:core-webrtc"))
    api(project(":shared:core-relay"))
    implementation(project(":shared:core-security"))
}

project(":protocol:protocol-tests").commonMainDependencies {
    implementation(project(":protocol:protocol-models"))
}
