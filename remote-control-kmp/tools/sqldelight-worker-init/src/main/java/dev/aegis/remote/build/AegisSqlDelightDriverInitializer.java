package dev.aegis.remote.build;

import app.cash.sqldelight.core.SqlDelightDatabaseProperties;
import app.cash.sqldelight.gradle.DriverInitializer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * SQLDelight 2.x keeps this extension point for custom migration drivers.
 * Aegis uses the plugin's embedded SQLite catalog. Isolated workers can lack
 * TEMP/TMP and resolve their temporary directory to a protected Windows folder,
 * so native SQLite extraction uses an ignored directory in the build tree.
 * An explicit SQLite temporary-directory override is always preserved.
 */
public final class AegisSqlDelightDriverInitializer implements DriverInitializer {
    @Override
    public void execute(
            SqlDelightDatabaseProperties databaseProperties,
            Properties driverProperties
    ) {
        if (databaseProperties == null || driverProperties == null) {
            throw new IllegalArgumentException("SQLDelight migration properties must not be null");
        }
        if (System.getProperty("org.sqlite.tmpdir") != null) {
            return;
        }
        Path nativeDirectory = databaseProperties.getRootDirectory().toPath()
                .resolve("build/tmp/sqldelight-native").toAbsolutePath().normalize();
        try {
            Files.createDirectories(nativeDirectory);
        } catch (IOException error) {
            throw new IllegalStateException("Could not prepare SQLite migration native directory", error);
        }
        System.setProperty("org.sqlite.tmpdir", nativeDirectory.toString());
    }
}
