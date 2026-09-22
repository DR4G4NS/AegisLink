# Windows Setup and post-install gate

The supported host is Windows 10/11 x64. The canonical product artifact is
`Aegis-Remote-Desktop-Setup-<version>.exe`, built with Inno Setup. An unpacked
Compose application image does not provision the complete Aegis host and must
not be distributed as the installer.

## Developer packaging

From `remote-control-kmp`, with JDK 21, Android SDK, and Inno Setup 6:

```powershell
# Package current outputs
.\packaging\build-release.ps1 -Version 0.2.0

# Run the script's grouped checks before packaging
.\packaging\build-release.ps1 -Version 0.2.0 -RunTests
```

The script publishes atomically under `dist/Aegis-Remote-<version>` and writes
checksums. This is a developer evidence bundle: its Android APK is debug-signed
and its Windows Setup is unsigned. Ordinary CI also builds an unsigned Setup.
Neither is a release artifact.

The release workflow is the signed path. It requires the Android release
keystore and Windows PFX secrets, signs the bundled Windows launcher, then has
Inno sign both the embedded uninstaller and outer Setup with SHA-256 and an RFC
3161 timestamp. It verifies the pinned subject and executes clean install,
verified repair/update, identity retention, and uninstall smoke. A missing or
invalid signature fails the workflow.

The protected `production` environment must define these GitHub secrets:
`ANDROID_KEYSTORE_B64`, `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
`ANDROID_KEY_PASSWORD`, `WINDOWS_PFX_B64`, and `WINDOWS_PFX_PASSWORD`. It must
also define `ANDROID_CERT_SHA256` and `WINDOWS_CERT_SUBJECT` as environment
variables. `ANDROID_CERT_SHA256` is the 64-hex SHA-256 digest reported by
`apksigner --print-certs` (plain or colon-separated hex, without a `sha256:`
prefix). The workflow fails with `PKG-9021` on a signer mismatch and
`PKG-9022` when package ID/version manifest verification fails.

For an installed signed build, verify an update before elevation:

```powershell
& "$env:ProgramFiles\Aegis Remote Desktop\tools\verify-windows-update.ps1" `
  -PackagePath .\Aegis-Remote-Desktop-Setup-0.3.0.exe `
  -Install
```

The verifier requires a valid Authenticode chain, the same pinned publisher,
an optional SHA-256 pin, and a strictly newer numeric version. Setup separately
blocks downgrades even if invoked without the helper.

For an unpackaged development image only:

```powershell
.\gradlew.bat :app-desktop:desktop-main:createDistributable
```

## Aegis-owned changes

One visible administrator approval installs the application and provisions:

- isolated service `AegisOpenSSH` on TCP 48222;
- `%ProgramData%\Aegis\OpenSSH`, host key, marked
  `authorized_keys`, state, logs, and restrictive ACLs;
- public-key-only authentication with password, interactive authentication,
  forwarding, tunnelling, and graphical-session forwarding disabled;
- removable inbound firewall rules limited to Private/Domain profiles;
- local pairing endpoint on TCP 48291;
- reversible Wake-on-LAN changes only when the adapter exposes a supported
  setting.

Provisioning fails closed on a service-name or port collision. Setup does not
edit the stock `sshd` service or `%ProgramData%\ssh\sshd_config`.
Uninstall removes only Aegis-owned service/rules/configuration/keys, reverses
Aegis-recorded Wake-on-LAN changes, and preserves pre-existing SSH resources.

## Clean-install LAN gate

This checklist is required evidence; it has not been recorded for the current
tree.

1. Verify Authenticode and checksums on the release Setup.
2. Install on clean Windows 10/11 x64 and confirm a single expected UAC flow.
3. Confirm `AegisOpenSSH` is running on 48222 and the UI exposes the real SSH
   host-key fingerprint.
4. Scan QR payload v3, verify expiry/one-use behavior, and approve the Android
   P-256 identity visibly on Windows.
5. Confirm Android created a complete pinned profile without manual IP, port,
   user, password, or fingerprint entry.
6. Verify first frame, real stats, input, bounded clipboard, terminal shell,
   SFTP list/upload/download/cancel, reconnect, and revoke.
7. Verify the revoked device loses active control and its marked SSH key.
8. If Wake-on-LAN reports supported, perform a real supported-state wake cycle.
   “Packet sent” alone is not proof.
9. Test repair/upgrade, then uninstall and audit services, ports, ACLs, firewall
   rules, files, keys, startup entry, and Wake-on-LAN restoration.
10. Confirm pre-existing Windows SSH configuration is byte-for-byte unaffected.

After LAN passes, follow [RELAY_GUIDE.md](RELAY_GUIDE.md) for remote tests.
TURN applies to WebRTC only; it is not an SSH/SFTP transport.
