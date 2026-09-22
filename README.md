# AegisLink

Control your Windows PC from Android, with visible device approval and an open-source codebase.

[Website](https://dr4g4ns.github.io/AegisLink-web/) · [Documentation](remote-control-kmp/docs/README.md) · [Build instructions](remote-control-kmp/README.md) · [Release status](STATUS.md)

AegisLink is the public source repository for **Aegis Remote Control**. The application name and package IDs remain Aegis so existing installations and device identities keep working.

## What you can do

- Pair Android with a visible Windows host using a signed, short-lived QR code and approval on the PC.
- View the desktop and control the mouse and keyboard through WebRTC.
- Use an SSH terminal and SFTP with pinned host keys.
- Enable bounded text clipboard sharing and manage approved devices.
- Connect over a local network or an explicitly configured reachable remote route. See [remote access](remote-control-kmp/docs/REMOTE_ACCESS.md).

Linux host code is also included. Platform support and tested paths are recorded separately in the documentation.

## Availability

The source is available under the [MIT license](LICENSE). **A production binary release is not yet certified.** Release signing, installer lifecycle tests and complete physical Android-to-PC sessions remain required before publishing stable installers. The [production readiness document](remote-control-kmp/docs/PRODUCTION_READINESS.md) defines those gates.

Successful CI runs on `main` automatically publish [development pre-releases](https://github.com/DR4G4NS/AegisLink/releases) with an installable debug Android APK, unsigned Windows Setup, Linux DEB/RPM packages, dependency inventories and checksums. These are evaluation builds. The Android development package uses the `.dev` suffix to coexist with production. See [automatic releases and signing](remote-control-kmp/docs/RELEASE.md) for update limitations and stable releases from `vX.Y.Z` tags.

## Build

Install JDK 21 and the Android SDK, then run:

```powershell
git clone https://github.com/DR4G4NS/AegisLink.git
cd AegisLink/remote-control-kmp
.\gradlew.bat :app-android:assembleDebug :app-desktop:desktop-main:compileKotlin
```

On Linux, use `./gradlew`. Full prerequisites, tests, packaging and remote setup are in the [project guide](remote-control-kmp/README.md).

## Repository layout

| Path | Purpose |
| --- | --- |
| `remote-control-kmp/app-android` | Android application |
| `remote-control-kmp/app-desktop` | Desktop host and platform adapters |
| `remote-control-kmp/shared` | Shared policies, identity and state |
| `remote-control-kmp/protocol` | Signaling, wire messages and session protection |
| `remote-control-kmp/relay-server` | Optional relay infrastructure |
| `remote-control-kmp/packaging` | Desktop packaging and installation |
| `.github/workflows` | CI, analysis and signed release gates |

The website has its own repository: [AegisLink-web](https://github.com/DR4G4NS/AegisLink-web).

## Contribute

Read [CONTRIBUTING.md](CONTRIBUTING.md), open an issue for a reproducible defect, or submit a focused pull request. For vulnerabilities, use the private reporting route in [SECURITY.md](SECURITY.md). Never post keys, pairing QR payloads, tokens or personal device information.

Third-party dependencies and fonts retain their own licenses. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
