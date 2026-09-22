# Dependency and native-runtime inventory

This is the minimum human-readable inventory for security and release review.
`gradle/libs.versions.toml`, module build files, strict Gradle verification
metadata, and the generated CycloneDX SBOM remain the machine-readable sources
of truth. License conclusions come from the dependency-review gate and SBOM;
this page is not a substitute for their release evidence.

## Security-critical direct dependencies

| Component | Pinned version | Product responsibility |
| --- | --- | --- |
| Kotlin / coroutines / serialization | 2.0.21 / 1.9.0 / 1.7.3 | Concurrency, state machines, and canonical protocol serialization |
| Ktor | 3.0.3 | Local HTTPS, relay HTTP/WebSocket client/server, and signaling transport |
| Bouncy Castle provider / PKIX | 1.78.1 | Mature cryptographic/provider and local certificate primitives; protocol policy remains in Aegis adapters |
| Android WebRTC SDK | 144.7559.09 | Android native peer connection, decoder, renderer, and DataChannels |
| webrtc-java | 0.14.0 | Windows native peer connection, Desktop Duplication source, encoder, and DataChannels |
| SSHJ | 0.37.0 | Android SSH terminal and SFTP protocol implementation behind pinning/policy adapters |
| SQLDelight | 2.0.2 | Android profile persistence and verified migrations |
| JNA | 5.16.0 | Windows `SendInput`, DPAPI, and other bounded native API bridges |
| ZXing | 3.5.3 | QR decoding/encoding; Aegis separately verifies the signed v3 payload |
| HikariCP / PostgreSQL JDBC | 7.0.2 / 42.7.12 | Relay database pool and durable registry/audit store |
| Flyway | 12.6.2 | Relay schema migrations before readiness |
| Jedis | 7.5.2 | Redis locks, leases, pub/sub, mailboxes, and distributed rate limits |

Compose/AndroidX and their transitive dependencies supply the visible Windows
and Android UI. They do not replace Aegis authorization, identity, E2EE,
pinning, or bounds checks.

## Native and operating-system runtime surface

| Runtime surface | Origin | Release check |
| --- | --- | --- |
| Android `libwebrtc` ABIs | `io.github.webrtc-sdk:android` AAR | APK signer/manifest verification, SBOM, dependency review, and physical decoder/DataChannel QA |
| Windows `libwebrtc` and Desktop Duplication bridge | `dev.onvoid.webrtc:webrtc-java` resolved distribution | Strict Gradle checksum verification, packaged-file inventory, SBOM, first-frame/multi-monitor QA |
| JNA native dispatch | `net.java.dev.jna` dependency | Strict checksum verification, SBOM, Windows API tests, and packaged-file inventory |
| Skiko/Skia desktop runtime | Compose Desktop resolved distribution | Strict checksum verification, SBOM, signed Setup inventory, and UI smoke |
| Win32-OpenSSH 10.0.0.0p2-Preview | Official PowerShell/Win32-OpenSSH archive, downloaded at build time and SHA-256 pinned by `tools/release/prepare-openssh.ps1` | Archive checksum rechecked for cached builds; bundled binary inventory, upstream license notices, isolated-service lifecycle audit |

No release script downloads an arbitrary executable at application runtime.
Inno Setup is a CI/developer build prerequisite; the installed product uses the
signed package contents, including the pinned OpenSSH distribution. Existing
Windows OpenSSH capabilities and services must be preserved by the installer.

## Enforcement

- Gradle runs with `--dependency-verification strict` and the repository keeps
  `gradle/verification-metadata.xml`.
- Pull requests run GitHub's dependency review with vulnerability and strong
  copyleft-license policy.
- CI/release run Gitleaks, CodeQL, tests, migrations, and CycloneDX SBOM
  generation.
- Signed release jobs verify the Android certificate/package manifest and the
  Windows Authenticode subject, timestamped package lifecycle, hashes, and
  artifact inventory.
