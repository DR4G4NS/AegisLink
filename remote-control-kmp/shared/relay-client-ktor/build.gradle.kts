kotlin {
    sourceSets.getByName("jvmMain").dependencies {
        implementation(libs.ktor.client.cio)
    }
    sourceSets.getByName("commonTest").dependencies {
        implementation(libs.ktor.client.mock)
    }
}
