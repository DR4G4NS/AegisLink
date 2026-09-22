# Releasing AegisLink

The source is public under the [MIT license](../../LICENSE). A public source
launch and a signed production binary release have different prerequisites.
The current binary-release decision remains **NOT RC** until the gates in
[PRODUCTION_READINESS.md](PRODUCTION_READINESS.md) are satisfied.

## Build and CI layout

Keep the `remote-control-kmp/` directory inside the repository. GitHub workflows,
packaging scripts, dependency verification metadata, and documentation use this
layout. Run Gradle commands inside that directory with JDK 21 and Android SDK
platform 35 installed. The wrapper pins Gradle 8.13 and verifies its SHA-256.

```powershell
cd remote-control-kmp
.\gradlew.bat --dependency-verification strict check detekt ktlintCheck lintRelease verifySqlDelightMigration
```

CI validates Windows and Linux, runs PostgreSQL/Redis relay integration and a
real TURN credential allocation fixture, and produces developer artifacts.
Linux packaging depends on successful tests and static checks. CodeQL and
dependency-review failures are reported as failures in the public repository.
CI artifacts are not signed production downloads.

## Production signing configuration

Configure these values in the GitHub `production` environment. Use established
release keys and publisher identity; replacing a key can break update trust.

| Type | Name | Purpose |
| --- | --- | --- |
| Secret | `ANDROID_KEYSTORE_B64` | Base64-encoded release keystore |
| Secret | `ANDROID_STORE_PASSWORD` | Keystore password |
| Secret | `ANDROID_KEY_ALIAS` | Release signing key alias |
| Secret | `ANDROID_KEY_PASSWORD` | Signing key password |
| Secret | `WINDOWS_PFX_B64` | Base64-encoded Authenticode certificate and private key |
| Secret | `WINDOWS_PFX_PASSWORD` | PFX password |
| Variable | `ANDROID_CERT_SHA256` | Expected release certificate SHA-256 fingerprint |
| Variable | `WINDOWS_CERT_SUBJECT` | Exact expected Authenticode publisher subject |

The signed workflow fails if any value is absent. Temporary keystore/PFX files
are removed in an `always()` cleanup step. Neither credentials nor generated
private keys belong in the source repository or in workflow artifacts.

The launch preparation check on 2026-09-21 found no repository Actions secrets
or `production` environment in the existing project, and the new public
repositories have no signing secrets. No signed binary release was produced
by that check.

## Candidate workflow

1. Run and review CI for the exact candidate commit.
2. Configure production signing and certificate pins above.
3. Push a semantic-version tag such as `v0.2.0` to run **Signed release**. A
   manual dispatch uses the current default version, `0.2.0`.
4. The workflow runs static, migration, unit, cryptographic, durable relay,
   and TURN allocation gates before the signing job.
5. The job signs the Android APK and verifies its package, version, and
   certificate. It signs the Windows launcher and timestamped Setup/uninstaller,
   then executes the elevated installer lifecycle smoke on the runner.
6. Review the signed artifact bundle and complete the physical Windows/Android
   and remote-session gates. A green workflow does not substitute for those
   physical checks.

The workflow uploads an Actions artifact. It does not automatically create a
public GitHub Release or claim that the physical gates passed.

## Bundled OpenSSH and artifact verification

`tools/release/prepare-openssh.ps1` fetches the official pinned Win32-OpenSSH
archive during the build, checks its SHA-256 even when cached, re-extracts it,
and verifies the required server tools exist. Developer packaging, Windows CI,
and signed releases all call this same script. The installer receives the
verified directory explicitly and never needs an ignored pre-existing binary
directory in the source checkout.

The final signed bundle contains six files:

- The versioned Android APK.
- The versioned Windows Setup executable.
- `verify-windows-update.ps1`.
- Versioned CycloneDX JSON and XML runtime SBOMs.
- `SHA256SUMS.txt`, covering all five files above.

Checksums verify integrity. Android and Authenticode certificate verification
establish release identity. The OpenSSH upstream license notices and third-party
runtime licenses remain applicable alongside the AegisLink MIT license.

## Public source hygiene

Export source, tests, build definitions, Gradle wrapper/checksum metadata,
packaging scripts, application assets, third-party license notices, and current
operational documentation. Exclude local configuration, `.qa/`, historic raw QA
logs, local prompts, editor caches, build trees, installers, `.env` files,
keystores, certificates, and credentials. Keep test fixtures with deliberately
non-production values; they are required to reproduce the test suite.

Relay production infrastructure is separate from GitHub Pages. Its database,
Redis, origin, token-HMAC secret, and TURN settings are described in
[relay-server/README.md](../relay-server/README.md). A static website deployment
does not provision those services.
