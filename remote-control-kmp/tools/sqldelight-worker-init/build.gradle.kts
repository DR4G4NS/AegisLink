plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    compileOnly("app.cash.sqldelight:gradle-plugin:${libs.versions.sqldelight.get()}")
}

val driverInitializerServiceFile =
    layout.projectDirectory
        .file(
            "src/main/resources/META-INF/services/app.cash.sqldelight.gradle.DriverInitializer",
        ).asFile

val verifyDriverInitializerService =
    tasks.register("verifyDriverInitializerService") {
        dependsOn(tasks.named("classes"))
        doLast {
            check(driverInitializerServiceFile.isFile) {
                "SQLDelight DriverInitializer service descriptor is missing: $driverInitializerServiceFile"
            }
            val providers =
                driverInitializerServiceFile
                    .readLines()
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
            check(providers.isNotEmpty()) {
                "SQLDelight DriverInitializer service descriptor declares no provider"
            }
            val classDirectories =
                sourceSets
                    .getByName("main")
                    .output.classesDirs.files
            providers.forEach { provider ->
                val classPath = provider.replace('.', '/') + ".class"
                check(classDirectories.any { it.resolve(classPath).isFile }) {
                    "SQLDelight DriverInitializer provider $provider is not present in the worker output"
                }
            }
        }
    }

tasks.named("check") {
    dependsOn(verifyDriverInitializerService)
}
