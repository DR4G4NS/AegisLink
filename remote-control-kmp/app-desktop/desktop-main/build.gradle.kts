import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":app-desktop:desktop-agent"))
    implementation(project(":app-desktop:desktop-capture"))
    implementation(project(":app-desktop:desktop-clipboard"))
    implementation(project(":app-desktop:desktop-input"))
    implementation(project(":shared:core-model"))
    implementation(project(":shared:core-monitor"))
    implementation(project(":shared:core-pairing"))
    implementation(project(":shared:core-security"))
    implementation(project(":shared:core-storage"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.zxing.core)
    implementation(libs.jna.platform)

    testImplementation(kotlin("test"))
}

compose.desktop {
    application {
        mainClass = "dev.aegis.remote.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Deb, TargetFormat.Rpm)
            packageName = "Aegis Remote Desktop"
            packageVersion = project.version.toString()
            description = "Visible desktop agent for pairing and controlling an approved PC from Aegis Remote Control."
            copyright = "Copyright 2026 Aegis Remote Control contributors"
            vendor = "Aegis Remote Control"
            licenseFile.set(project.file("src/main/package/LICENSE.txt"))
            modules(
                "java.desktop",
                "java.logging",
                "java.management",
                "java.naming",
                "java.net.http",
                "java.prefs",
                "java.sql",
                "jdk.crypto.ec",
                "jdk.unsupported",
            )

            windows {
                iconFile.set(project.file("src/main/package/windows/aegis.ico"))
                menuGroup = "Aegis Remote Control"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "8d87fa7d-7c2d-4c23-9f29-4f3cb67fd6d1"
            }

            linux {
                iconFile.set(project.file("src/main/package/linux/aegis.png"))
                shortcut = true
                menuGroup = "Network"
                appCategory = "Network"
                debMaintainer = "Aegis Remote Control contributors"
                rpmLicenseType = "MIT"
            }
        }
    }
}
