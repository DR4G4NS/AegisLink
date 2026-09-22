# Relay Server

Ktor relay and signaling server.

Current module:

- `relay-main`

Current endpoints:

- `GET /health`
- `GET /live`
- `GET /ready`
- `GET /metrics` (Prometheus text format)
- `POST /v3/devices/challenge`
- `POST /v3/devices/register`
- `POST /v3/devices/rotate-key`
- `POST /v3/devices/revoke`
- `POST /devices/register`
- `POST /sessions`
- `POST /sessions/{sessionId}/approval`
- `GET /turn/credentials`
- `WS /devices/{relayDeviceId}/events` with an `Authorization: Bearer` header
- `WS /signaling/{sessionId}/{relayDeviceId}` with an `Authorization: Bearer` header

The Ktor relay provides rendezvous, authenticated signaling and short-lived TURN
REST credentials. Coturn is the separate media relay; Ktor never transports
video, SSH passwords or private keys. The two services share only the TURN REST
authentication secret.
HTTP session/device/TURN endpoints and WebSocket attempts are protected by a fixed-window rate limiter.
Device registrations and unexpired relay sessions can be persisted in development by setting `AEGIS_RELAY_STORAGE_PATH`; stored relay auth tokens use a server-side HMAC-SHA-256 key and are never stored as plaintext.
Rate-limit buckets can be shared across relay processes by setting `AEGIS_RELAY_RATE_LIMIT_STORAGE_PATH`; each acquire is performed under a `FileChannel.lock()` on that JSON store, expired windows are pruned before snapshots are saved, and malformed snapshots are ignored so a bad local file does not prevent relay startup.
Session-request events include the source device display name and public fingerprint from registration when available, so desktop approval prompts can show more than a relay id.

Production mode requires PostgreSQL and Redis together. PostgreSQL is the
durable authority for identities, key generations, sessions, revocations and
audit events. Flyway migrations run before the listener starts. Redis provides
atomic one-time challenges, shared rate limits, presence, distributed operation
locks, bounded device-event mailboxes and bounded opaque E2EE frame mailboxes.
Opaque frames are never decoded by Redis or the relay. Mailboxes retain early
handshake frames until the second peer joins and expire them after a short TTL.
The process refuses a partial production configuration.

```powershell
$env:AEGIS_DATABASE_URL="jdbc:postgresql://db.example:5432/aegis"
$env:AEGIS_DATABASE_USER="aegis"
$env:AEGIS_DATABASE_PASSWORD="use-a-secret-manager"
$env:AEGIS_REDIS_URL="rediss://redis.example:6379/0"
$env:AEGIS_RELAY_TOKEN_HMAC_SECRET="base64url:<at-least-32-random-bytes>"
$env:AEGIS_RELAY_ORIGIN="https://relay.example.com"
$env:AEGIS_TURN_URLS="turn:turn.example.com:3478?transport=udp,turns:turn.example.com:5349?transport=tcp"
$env:AEGIS_TURN_SHARED_SECRET="read-the-same-value-as-coturn-from-a-secret-manager"
$env:AEGIS_TURN_TTL_SECONDS="600"
```

Coturn must enable `use-auth-secret` and receive the exact same secret through
the deployment secret manager. A hardened baseline is provided at
`relay-server/coturn/turnserver.conf.example`; configure its public/external IP,
TLS certificate and firewall relay range for the deployment. Do not expose
coturn's CLI or substitute anonymous TURN.

Forwarded client headers are ignored by default. Set
`AEGIS_TRUST_FORWARDED_HEADERS=true` only when the relay is reachable solely
through a configured trusted reverse proxy. WebSockets enforce a 128 KiB frame
limit, heartbeat/idle timeout, global connection capacity, two participants per
session, bounded queues/sends, active token/session expiration, distributed
rotation/revocation invalidation, authentication and shared rate limits.

Build and test:

```powershell
.\gradlew.bat :relay-server:relay-main:test :relay-server:relay-main:installDist
```

Run the real PostgreSQL/Redis integration test locally:

```powershell
docker compose -f relay-server/docker-compose.integration.yml up -d --wait
$env:AEGIS_TEST_POSTGRES_URL="jdbc:postgresql://127.0.0.1:55432/aegis_test"
$env:AEGIS_TEST_POSTGRES_USER="aegis"
$env:AEGIS_TEST_POSTGRES_PASSWORD="aegis-integration-only"
$env:AEGIS_TEST_REDIS_URL="redis://127.0.0.1:56379/0"
.\gradlew.bat --dependency-verification strict :relay-server:relay-main:test --tests dev.aegis.remote.relay.RelayPostgresRedisIntegrationTest
```

The integration compose file also starts coturn 4.14 with authenticated TURN
REST credentials. On an environment with Docker and Bash/OpenSSL, verify a real
allocation (not merely credential issuance) with:

```bash
docker compose -f relay-server/docker-compose.integration.yml up -d --wait coturn
bash relay-server/scripts/verify-turn-allocation.sh
docker compose -f relay-server/docker-compose.integration.yml down --remove-orphans
```

Run:

```powershell
$env:AEGIS_RELAY_DEVELOPMENT_MODE="true"
.\relay-server\relay-main\build\install\relay-main\bin\relay-main.bat
```

Run with persisted registry:

```powershell
$env:AEGIS_RELAY_DEVELOPMENT_MODE="true"
$env:AEGIS_RELAY_STORAGE_PATH="$PWD\data\relay-registry.json"
.\relay-server\relay-main\build\install\relay-main\bin\relay-main.bat
```

Run with persisted registry and rate-limit buckets:

```powershell
$env:AEGIS_RELAY_DEVELOPMENT_MODE="true"
$env:AEGIS_RELAY_STORAGE_PATH="$PWD\data\relay-registry.json"
$env:AEGIS_RELAY_RATE_LIMIT_STORAGE_PATH="$PWD\data\relay-rate-limits.json"
.\relay-server\relay-main\build\install\relay-main\bin\relay-main.bat
```

The local JSON stores remain development-only and are not selected when the two
production URLs are present.
