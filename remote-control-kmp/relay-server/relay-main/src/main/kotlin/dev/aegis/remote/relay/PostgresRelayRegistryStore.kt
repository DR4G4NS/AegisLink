package dev.aegis.remote.relay

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

class PostgresRelayRegistryStore(
    private val dataSource: DataSource,
    private val json: Json = relayJson,
) : RelayRegistryStore {
    override fun load(nowEpochMillis: Long): RelayRegistrySnapshot =
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.pruneExpired(nowEpochMillis)
                val devices = connection.loadJsonRecords("relay_identities", RegisteredRelayDevice.serializer())
                val sessions = connection.loadJsonRecords("relay_sessions", RelayRendezvousSession.serializer())
                connection.commit()
                RelayRegistrySnapshot(devices = devices, sessions = sessions)
            } catch (failure: SQLException) {
                connection.rollback()
                throw failure
            }
        }

    override fun save(snapshot: RelayRegistrySnapshot) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { statement ->
                    statement.executeUpdate("DELETE FROM relay_sessions")
                    statement.executeUpdate("DELETE FROM relay_identities")
                }
                snapshot.devices.forEach { device -> connection.insertDevice(device) }
                snapshot.sessions.forEach { session -> connection.insertSession(session) }
                connection
                    .prepareStatement(
                        "INSERT INTO relay_audit_events(event_type, device_count, session_count) VALUES ('SNAPSHOT_COMMITTED', ?, ?)",
                    ).use { statement ->
                        statement.setInt(1, snapshot.devices.size)
                        statement.setInt(2, snapshot.sessions.size)
                        statement.executeUpdate()
                    }
                connection.commit()
            } catch (failure: SQLException) {
                connection.rollback()
                throw failure
            }
        }
    }

    fun ready(): Boolean =
        runCatching {
            dataSource.connection.use { connection -> connection.prepareStatement("SELECT 1").use { it.executeQuery().next() } }
        }.getOrDefault(false)

    private fun Connection.insertDevice(device: RegisteredRelayDevice) {
        prepareStatement(
            """
            INSERT INTO relay_identities(
                relay_device_id, device_id, algorithm, public_key, key_generation, security_level,
                status, token_hash, token_expires_at, last_seen_at, record_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, device.relayDeviceId.value)
            statement.setString(2, device.deviceId?.value)
            statement.setString(3, device.keyAlgorithm)
            statement.setBytes(4, device.signingPublicKey)
            device.keyGeneration?.let { statement.setLong(5, it) } ?: statement.setNull(5, java.sql.Types.BIGINT)
            statement.setString(6, device.securityLevel)
            statement.setString(7, if (device.revokedAtEpochMillis == null) "ACTIVE" else "REVOKED")
            statement.setString(8, device.authTokenHash)
            statement.setLong(9, device.expiresAtEpochMillis)
            statement.setLong(10, device.lastSeenAtEpochMillis)
            statement.setString(11, json.encodeToString(device))
            statement.executeUpdate()
        }
        insertKeyGeneration(device)
        device.revokedAtEpochMillis?.let { revokedAt ->
            val ids = listOfNotNull(device.deviceId) + device.retiredDeviceIds
            ids.forEach { revokedId ->
                prepareStatement(
                    """
                    INSERT INTO relay_revocations(device_id, revoked_at, reason) VALUES (?, ?, 'IDENTITY_REVOKED')
                    ON CONFLICT (device_id) DO UPDATE SET revoked_at = EXCLUDED.revoked_at
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, revokedId.value)
                    statement.setLong(2, revokedAt)
                    statement.executeUpdate()
                }
            }
        }
    }

    private fun Connection.pruneExpired(nowEpochMillis: Long) {
        prepareStatement("DELETE FROM relay_identities WHERE token_expires_at <= ? AND status <> 'REVOKED'").use {
            it.setLong(1, nowEpochMillis)
            it.executeUpdate()
        }
        prepareStatement("DELETE FROM relay_sessions WHERE expires_at <= ?").use {
            it.setLong(1, nowEpochMillis)
            it.executeUpdate()
        }
    }

    private fun <T> Connection.loadJsonRecords(
        table: String,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): List<T> =
        prepareStatement("SELECT record_json::text FROM $table").use { statement ->
            statement.executeQuery().use { result ->
                buildList {
                    while (result.next()) add(json.decodeFromString(serializer, result.getString(1)))
                }
            }
        }

    private fun Connection.insertKeyGeneration(device: RegisteredRelayDevice) {
        val deviceId = device.deviceId ?: return
        val generation = device.keyGeneration ?: return
        val algorithm = device.keyAlgorithm ?: return
        val publicKey = device.signingPublicKey ?: return
        prepareStatement(
            """
            INSERT INTO relay_key_generations(device_id, generation, algorithm, public_key)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (device_id, generation) DO NOTHING
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, deviceId.value)
            statement.setLong(2, generation)
            statement.setString(3, algorithm)
            statement.setBytes(4, publicKey)
            statement.executeUpdate()
        }
    }

    private fun Connection.insertSession(session: RelayRendezvousSession) {
        val status =
            when {
                session.approvedAtEpochMillis != null -> "APPROVED"
                session.rejectedAtEpochMillis != null -> "REJECTED"
                else -> "PENDING"
            }
        prepareStatement(
            """
            INSERT INTO relay_sessions(
                session_id, source_relay_device_id, target_relay_device_id, expires_at, status, record_json
            ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, session.session.sessionId.value)
            statement.setString(2, session.sourceRelayDeviceId.value)
            statement.setString(3, session.targetRelayDeviceId.value)
            statement.setLong(4, session.session.expiresAtEpochMillis)
            statement.setString(5, status)
            statement.setString(6, json.encodeToString(session))
            statement.executeUpdate()
        }
    }
}

fun createPostgresRelayStore(
    jdbcUrl: String,
    username: String?,
    password: String?,
    maximumPoolSize: Int = 10,
): Pair<PostgresRelayRegistryStore, AutoCloseable> {
    val config =
        HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            username?.let { this.username = it }
            password?.let { this.password = it }
            this.maximumPoolSize = maximumPoolSize
            minimumIdle = 1
            connectionTimeout = 5_000
            validationTimeout = 2_000
            initializationFailTimeout = 10_000
            addDataSourceProperty("tcpKeepAlive", "true")
            poolName = "aegis-relay-postgres"
        }
    val dataSource = HikariDataSource(config)
    Flyway
        .configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate()
    return PostgresRelayRegistryStore(dataSource) to dataSource
}
