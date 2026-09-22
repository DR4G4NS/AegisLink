plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":shared:core-input"))
    implementation(project(":shared:core-model"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.jna.platform)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
