package dev.aegis.remote.build;

import app.cash.sqldelight.core.SqlDelightDatabaseProperties;
import app.cash.sqldelight.gradle.DriverInitializer;

import java.util.Properties;

/**
 * SQLDelight 2.x keeps this extension point for custom migration drivers.
 * Aegis uses the plugin's embedded SQLite catalog, so no driver properties
 * need to be mutated. Keeping the declared provider concrete makes the
 * ServiceLoader contract explicit and fail-closed if the plugin changes it.
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
    }
}
