plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("dev.aegis.remote.relay.MainKt")
}

dependencies {
    implementation(project(":shared:core-model"))
    implementation(project(":shared:core-security"))
    implementation(project(":shared:core-relay"))
    implementation(project(":shared:core-webrtc"))
    implementation(project(":protocol:protocol-models"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("org.postgresql:postgresql:42.7.12")
    implementation("org.flywaydb:flyway-core:12.6.2")
    implementation("org.flywaydb:flyway-database-postgresql:12.6.2")
    implementation("redis.clients:jedis:7.5.2")

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.server.test.host)
}
