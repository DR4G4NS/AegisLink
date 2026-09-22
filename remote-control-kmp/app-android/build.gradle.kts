plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

val releaseVersion = project.version.toString()
val semanticVersion =
    requireNotNull(Regex("^(\\d+)\\.(\\d+)\\.(\\d+)$").matchEntire(releaseVersion)) {
        "aegisVersion must be a numeric major.minor.patch version"
    }
val releaseVersionCode =
    semanticVersion.groupValues
        .drop(1)
        .map(String::toLong)
        .let { (major, minor, patch) -> major * 1_000_000L + minor * 1_000L + patch }
        .also { require(it in 1..2_100_000_000L) { "aegisVersion produces an invalid Android versionCode" } }
        .toInt()

android {
    namespace = "dev.aegis.remote.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.aegis.remote.android"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = releaseVersion
    }

    val releaseKeystore = System.getenv("AEGIS_ANDROID_KEYSTORE")
    val releaseStorePassword = System.getenv("AEGIS_ANDROID_STORE_PASSWORD")
    val releaseKeyAlias = System.getenv("AEGIS_ANDROID_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("AEGIS_ANDROID_KEY_PASSWORD")
    val hasReleaseSigning =
        listOf(
            releaseKeystore,
            releaseStorePassword,
            releaseKeyAlias,
            releaseKeyPassword,
        ).all { !it.isNullOrBlank() }
    if (hasReleaseSigning) {
        signingConfigs {
            create("aegisRelease") {
                storeFile = file(checkNotNull(releaseKeystore))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("aegisRelease")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        // Compose's detector crashes under this AGP/Kotlin UAST combination before reporting a finding.
        disable +=
            setOf(
                "AutoboxingStateCreation",
                "MutableCollectionMutableState",
            )
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

sqldelight {
    databases {
        create("AegisDatabase") {
            packageName.set("dev.aegis.remote.android.db")
            schemaOutputDirectory.set(file("src/main/sqldelight/databases"))
        }
    }
}

dependencies {
    // SQLDelight owns the migration task classpath, so load the worker hook through its MigrationEnv.
    add("AegisDatabaseMigrationEnv", project(":tools:sqldelight-worker-init"))

    implementation(project(":shared:core-model"))
    implementation(project(":shared:core-clipboard"))
    implementation(project(":shared:core-nat"))
    implementation(project(":shared:core-network"))
    implementation(project(":shared:core-pairing"))
    implementation(project(":shared:core-quality"))
    implementation(project(":shared:core-relay"))
    implementation(project(":shared:core-routing"))
    implementation(project(":shared:core-security"))
    implementation(project(":shared:core-session"))
    implementation(project(":shared:core-sftp"))
    implementation(project(":shared:core-storage"))
    implementation(project(":shared:core-terminal"))
    implementation(project(":shared:core-wol"))
    implementation(project(":shared:relay-client-ktor"))
    implementation(project(":protocol:protocol-models"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.websockets)
    implementation(libs.sqldelight.android.driver)
    implementation(libs.sqldelight.coroutines.extensions)
    implementation(libs.sshj)
    implementation(libs.zxing.core)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.bouncycastle.provider)
    implementation(libs.webrtc.android)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    // Loopback-only real SSH/SFTP protocol fixture; never shipped in the APK.
    testImplementation("org.apache.sshd:sshd-core:2.12.1")
    testImplementation("org.apache.sshd:sshd-sftp:2.12.1")
}
