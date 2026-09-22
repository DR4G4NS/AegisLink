package dev.aegis.remote.desktop.agent

object OpenSshDetector {
    fun isAvailable(): Boolean =
        runCatching {
            val command =
                if (System.getProperty("os.name").contains("Windows", ignoreCase = true)) {
                    listOf("where", "ssh")
                } else {
                    listOf("sh", "-c", "command -v ssh")
                }
            ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
                .waitFor() == 0
        }.getOrDefault(false)
}
