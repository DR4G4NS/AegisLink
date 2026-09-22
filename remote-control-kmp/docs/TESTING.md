# Verification strategy

Aegis follows the definitive goal's grouped-testing policy: use focused tests
while changing a security-sensitive contract, then run one phase-level pass and
one RC-level pass. Repeated broad test loops are not evidence of a working
Android ↔ Windows product.

## Focused automated checks

Run only the affected module graph during implementation. Examples:

```powershell
.\gradlew.bat :shared:core-pairing:jvmTest
.\gradlew.bat :shared:core-security:jvmTest
.\gradlew.bat :protocol:protocol-models:jvmTest
.\gradlew.bat :app-desktop:desktop-input:test
.\gradlew.bat :app-desktop:desktop-clipboard:test
.\gradlew.bat :app-desktop:desktop-webrtc:test
.\gradlew.bat :app-desktop:desktop-agent:test
.\gradlew.bat :app-android:testDebugUnitTest
.\gradlew.bat :relay-server:relay-main:test
```

Focus security tests on canonicalization, provider substitution, QR
expiry/reuse/tamper, identity binding, pinning, permissions, replay/order/gaps,
downgrade, rekey, timeout, revocation, path validation, bounds, and log
redaction.

## Recorded phase-level grouped pass

The 2026-07-15 checkpoint ran this grouped command:

```powershell
.\gradlew.bat check detekt ktlintCheck lintRelease verifySqlDelightMigration `
  :shared:core-security:jvmTest `
  :protocol:protocol-models:jvmTest `
  :protocol:protocol-tests:jvmTest `
  :relay-server:relay-main:test `
  :app-android:testDebugUnitTest `
  --max-workers=1 --console=plain
```

Result: `BUILD SUCCESSFUL in 18m 22s`; 357 actionable tasks, 78
executed, 279 up-to-date. The log is
`build/evidence/2026-07-15/rc-verification-pass7.stdout.log`.

The version 0.2.0 developer packaging pass then built the APK and Windows app,
generated the runtime CycloneDX SBOM, and compiled the Inno Setup package. The
artifact audit recomputed 5/5 checksum entries, verified APK v2/package/version/
SDK metadata, confirmed the Setup 0.2.0 metadata and unsigned status, and parsed
both SBOM formats. This remains unsigned developer evidence, not a release
artifact.

The focused SSHJ SFTP loopback row ran both debug and release unit-test
variants. All six integration scenarios in each variant passed, including
cancellation that closes the active local stream/destination handle. Evidence:
`build/evidence/2026-07-15/sftp-cancellation-stream-ownership.stdout.log`.

## Limited startup smoke recorded

- Physical Android 16/API 36 ARM64: audited APK install, launch, resumed main
  activity, visible render, and no fatal exception/ANR during the smoke window.
- Windows 11 x64: unpackaged app launched from an isolated QA home and rendered
  the pairing screen. The firewall permission prompt was not accepted; no
  installer, service, ACL, or firewall change was made.

These startup rows did not connect the peers and therefore do not close any
physical LAN matrix row below.

## Physical LAN matrix

Use a clean Windows 10/11 x64 machine and a real Android device:

1. Verify signed Setup/APK, install, and confirm only expected elevation.
2. Confirm isolated `AegisOpenSSH` on 48222, firewall scope, ACLs, and real
   host-key pin.
3. Scan QR v3; test happy path, expiry, reuse, tamper, and wrong target.
4. Approve Android and verify the complete profile without manual connection
   secrets.
5. Verify first frame and real stats; switch native monitor IDs and verify
   pointer geometry on mixed/negative-origin layouts.
6. Test absolute/relative pointer, buttons, wheel, key press/release, modifiers,
   layout/scancodes, Unicode text, rate limits, and pressed-state cleanup.
7. Test manual/automatic clipboard, 32 KiB rejection boundary, 8 KiB automatic
   boundary, timeout, secret filtering, deduplication, and two-way loop
   suppression.
8. Test terminal and SFTP list/upload/download/rename/create/delete, zero/large
   files, Unicode/hostile paths, permissions, cancel, reconnect, and concurrent
   use.
9. Revoke during activity and verify control closes and the marked SSH key is
   removed.
10. Test supported Wake-on-LAN hardware through a real power-state transition.
11. Run repair/upgrade/uninstall and audit all Aegis-owned resources.

## Remote/E2EE matrix

- Authenticated Android ↔ Windows P-256 handshake with exact identity/role.
- Relay capture containing opaque frames and no sensitive plaintext.
- Bit flip, replay, reorder, excessive gap, wrong target/session/generation,
  expiry, downgrade, and malformed envelope.
- Automatic rekey by time and message volume under concurrent traffic.
- Rekey request/ack replay, acknowledgement loss, timeout fail-closed, and
  generation reset.
- Reconnect with fresh ephemeral keys.
- LAN, VPN, mobile network, direct ICE, TURN-only, ICE restart, network change,
  app background/foreground, suspend/resume, and route fallback.
- 60-minute session with memory/handle, frame, latency, and cleanup evidence.

## Release-candidate pass

- Full unit/integration and crypto known-answer suites.
- Real AegisOpenSSH and coturn integration.
- UI smoke and install/upgrade/repair/uninstall.
- Secret scan, dependency review, SBOM, static security analysis, and license
  inventory.
- Android signer and manifest verification.
- Windows Authenticode subject/timestamp verification for launcher, embedded
  uninstaller, and outer Setup; reject downgrade/untrusted update packages.
- Checksums and packaged-file audit.
- Sanitized QA evidence with versions, routes/candidate pair, causal error
  codes, timestamps, and results.

The grouped pass, developer artifact audit, SFTP loopback row, and limited
startup smokes above are the only rows claimed by the 2026-07-15 record. Every
unexecuted physical, elevated, remote, and release-candidate row remains open.
