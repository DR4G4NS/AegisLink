pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "remote-control-kmp"

include(":shared:core-model")
include(":shared:core-session")
include(":shared:core-security")
include(":shared:core-network")
include(":shared:core-terminal")
include(":shared:core-sftp")
include(":shared:core-webrtc")
include(":shared:core-input")
include(":shared:core-clipboard")
include(":shared:core-wol")
include(":shared:core-storage")
include(":shared:core-monitor")
include(":shared:core-logging")
include(":shared:core-quality")
include(":shared:core-pairing")
include(":shared:core-routing")
include(":shared:core-relay")
include(":shared:relay-client-ktor")
include(":shared:core-nat")
include(":app-android")
include(":app-desktop:desktop-agent")
include(":app-desktop:desktop-capture")
include(":app-desktop:desktop-clipboard")
include(":app-desktop:desktop-input")
include(":app-desktop:desktop-main")
include(":app-desktop:desktop-webrtc")
include(":relay-server:relay-main")
include(":protocol:protocol-models")
include(":protocol:protocol-tests")
include(":tools:sqldelight-worker-init")
