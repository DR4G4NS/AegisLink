# AegisLink application source

Aegis connects an Android client to a visible Windows 10/11 x64 or Linux x86_64
host. The public project overview is in [../README.md](../README.md). Platform
requirements and limitations are documented in [app-desktop/README.md](app-desktop/README.md).

The integrated implementation, grouped build, and developer packaging
checkpoint is complete, but the tree is **not a release candidate**.
[../STATUS.md](../STATUS.md) separates executed evidence from the physical,
elevated, infrastructure, and signing gates that remain open.

## Product flow

1. Install `Aegis-Remote-Desktop-Setup-<version>.exe` on Windows.
2. The installer provisions the visible host and its isolated
   `AegisOpenSSH` service on loopback TCP 48222. The desktop app owns external
   access on that port and closes active terminal/file connections when it exits.
3. Scan the signed, short-lived, one-use QR payload v3 in Android.
4. Verify the payload's P-256 signature and pins, then approve the Android
   identity visibly on Windows.
5. Android creates the PC profile and enrolls only its generated SSH public key.
6. Use the LAN session for video, input, bounded text clipboard, terminal, SFTP,
   and supported Wake-on-LAN.
7. Save the PC address in **Remote access** on Android and verify the paired PC.
   The guided option uses Tailscale on both devices; public IP/port forwarding is
   also supported. See the [remote access guide](docs/REMOTE_ACCESS.md).
   The optional relay connection remains under diagnostics and requires its
   application E2EE channel before remote control becomes available.

No password, private key, permanent token, or recovery secret is placed in the
QR.

## Current implementation

- Algorithm-tagged P-256 device identity, proof of possession, persisted trust,
  permission checks, reconnect checks, and revocation.
- Native WebRTC sender/viewer with Windows Desktop Duplication capture, ICE
  telemetry, quality control, native-source monitor IDs, Android monitor
  selection, and explicit lifecycle cleanup.
- Dedicated DataChannels:
  - `aegis-control`: reliable, ordered.
  - `aegis-pointer`: unordered, `maxRetransmits=0`.
  - `aegis-keyboard`: reliable, ordered.
  - `aegis-clipboard`: reliable, ordered, bounded.
- Windows SendInput mouse/keyboard injection with rate and state guards.
- Opt-in text clipboard synchronization with size limits, timeout,
  deduplication, secret filtering, and loop suppression.
- SSHJ terminal and SFTP clients with route restrictions and host-key pinning.
- Ktor pairing/relay services, temporary TURN credential support, and
  relay-backed signaling.
- P-256 ECDH, HKDF-SHA-256, AES-256-GCM envelopes, replay/order checks, and
  acknowledged automatic rekey in the protocol adapter.
- Inno Setup packaging, a signer/version-pinned update verifier, and a release
  workflow that signs and smoke-tests the launcher, Setup, and uninstaller.

## Repository map

- `app-android`: Compose Android client and platform adapters.
- `app-desktop`: visible Windows agent, capture, input, clipboard, WebRTC, and
  packaging entry point.
- `shared`: platform-neutral models, policies, state machines, and ports.
- `protocol`: wire messages, DataChannel routing, signaling, and E2EE channel.
- `relay-server`: rendezvous, approval, opaque signaling forwarding, TURN
  credential issuance, persistence, health, and rate limits.
- `packaging/windows`: Inno Setup definition and isolated OpenSSH scripts.
- `docs`: architecture, security, installation, testing, and RC gates.

## Build locally

Use JDK 21 and a configured Android SDK.

```powershell
.\gradlew.bat jvmTest
.\gradlew.bat :app-android:assembleDebug
.\gradlew.bat :app-desktop:desktop-main:compileKotlin
.\gradlew.bat :relay-server:relay-main:test
```

Create a developer evidence bundle with Inno Setup 6 installed:

```powershell
.\packaging\build-release.ps1 -Version 0.2.0 -RunTests
```

That local bundle contains a debug-signed Android APK and an unsigned Windows
Setup. It must not be represented as a production release. The release workflow
is the path that requires release signing credentials and verifies signatures.

## Recorded checkpoint

On 2026-07-15 the grouped verification pass completed successfully across 357
actionable Gradle tasks, including repository checks, Detekt, ktlint, Android
release lint, SQLDelight migration verification, and focused security,
protocol, relay, and Android tests. The version 0.2.0 developer bundle was then
created in `dist/Aegis-Remote-0.2.0`; all recorded checksums, APK metadata and
v2 signature, Setup metadata, and the CycloneDX JSON/XML SBOM were audited.

A physical Android startup smoke and an isolated unpackaged Windows startup
smoke also passed. These prove package launch/rendering only; they do not replace
the clean-install or Android-to-Windows end-to-end matrices. Historical local QA
logs are not included in the public source export. See
[docs/PRODUCTION_READINESS.md](docs/PRODUCTION_READINESS.md) for remaining gates.

## Open release gates

- A release-key-signed APK and Authenticode-signed/timestamped Setup have not
  been produced. The current APK is debug-signed and the Setup is unsigned.
- Elevated clean install, upgrade, repair, uninstall, and resource-ownership
  validation remain open.
- Physical Android-to-Windows LAN validation remains open: QR, approval,
  profile, first frame, input, clipboard, real provisioned OpenSSH terminal and
  SFTP, reconnect, and revoke.
- Physical switching between multiple real Windows monitors remains an RC
  evidence gate; native source IDs and Android selection are implemented and
  covered by focused tests. The current host exposes one active display.
- Remote E2EE/rekey, TURN-only, network-transition, suspend/resume, long-session,
  and relay-opacity matrices remain RC gates.
- Wake-on-LAN could not be exercised because the available adapter reports no
  usable capability; this is a device-scoped degradation.

See [docs/README.md](docs/README.md) for the documentation order and
[docs/PRODUCTION_READINESS.md](docs/PRODUCTION_READINESS.md) for the exact exit
gates.
