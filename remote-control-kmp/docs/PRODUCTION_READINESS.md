# Release-candidate readiness

**Current decision: `RELEASE_GATE_OPEN` / NOT RC.** The integrated tree builds,
tests, packages, and passes limited startup smoke checks. The definitive Windows
goal still requires signed production artifacts, elevated installer lifecycle
evidence, and physical Android-to-Windows LAN/remote matrices. Focused tests,
loopback fixtures, or an unpackaged launch do not satisfy those gates.

## Gate matrix

| Gate | Current evidence | Exit condition |
| --- | --- | --- |
| Grouped repository verification | **Passed 2026-07-15.** `check detekt ktlintCheck lintRelease verifySqlDelightMigration` plus focused security/protocol/relay/Android tests completed in one 357-task Gradle pass | Re-run only if later source/build changes invalidate the recorded graph |
| Windows developer artifact | **Passed as developer evidence.** Inno Setup compiled 0.2.0; metadata and all recorded checksums verify; Setup is `NotSigned` | Release workflow signs/timestamps launcher, embedded uninstaller, and outer Setup and verifies subject, signature, checksum, and packaged inventory |
| Android developer artifact | **Passed as developer evidence.** Package/version/SDK metadata verify; APK has one Android debug signer and v2 signature | Release APK is signed with the intended release key and its signer/package manifest are verified |
| Clean install lifecycle | Not executed; unpackaged isolated startup only | Windows 10/11 clean install, upgrade, repair, and uninstall preserve non-Aegis resources and remove Aegis-owned resources |
| QR/profile bootstrap | QR v3 tests pass and isolated desktop startup produced the pairing UI; no physical scan was performed | Physical one-use/expiry/tamper/wrong-target flow creates the complete Android profile without manual connection secrets |
| LAN visual/control | Native sender/viewer and four channels build/test; Android and desktop startup smokes pass separately | Physical first frame, stats, input, clipboard, reconnect, revoke, and 60-minute session pass as one authorized flow |
| Multi-monitor | Native source IDs, fail-closed mapping, persistence, and Android selection have focused coverage; QA host has one active display | Switching and input geometry are physically verified on multiple real displays |
| SSH/SFTP | SSHJ debug/release loopback integration passes, including cancellation handle closure; installer/provisioner builds | Packaged `AegisOpenSSH` shell/SFTP, pinning, large/hostile files, cancellation, reconnect, and key removal pass on Windows |
| Application E2EE | Crypto, envelope, rekey, timeout, replay/generation, and fresh-reconnect automated coverage passes | Physical remote handshake, opaque relay, both rekey triggers under traffic, timeout fail-closed, generation/replay/downgrade, and fresh reconnect pass |
| Internet connectivity | Durable relay/STUN/TURN plumbing builds/tests | LAN, VPN, mobile network, direct ICE, TURN-only, network changes, suspend/resume, and ICE restart pass |
| Privacy/supply chain | CycloneDX 1.6 JSON/XML runtime SBOM has 300 components/296 dependency entries; 5/5 checksums and developer artifact audit pass; security workflows exist | Release signatures, dependency/license review, secret scan, CodeQL/equivalent results, and final signed packaged-file audit pass |

Wake-on-LAN is conditional on driver, firmware, and hardware support. The QA
host reports no usable capability, so its unexecuted WOL row is
`UNSUPPORTED_ON_DEVICE`, not a global product blocker. Hardware that reports
support must pass a real sleep or shutdown/wake cycle.

## Artifact truth

The recorded `dist/Aegis-Remote-0.2.0` bundle is a developer evidence bundle:

- debug-signed Android APK, version 0.2.0, version code 2000;
- unsigned Windows Setup with version metadata 0.2.0;
- CycloneDX 1.6 JSON and XML runtime-scope SBOM;
- version-pinned Windows update verifier;
- checksum manifest whose five artifact entries recompute successfully.

The signed distributable path remains the release workflow. It requires the
Android keystore and Windows certificate secrets, signs the Windows launcher
before assembly, has Inno sign both its embedded uninstaller and outer Setup
with a timestamp, and then runs the elevated clean-install/verified-repair/
uninstall smoke. Documentation, a filename, or a checksum cannot promote this
unsigned developer bundle.

## Limited physical/startup evidence

- A physical Android 16/API 36 ARM64 device installed and launched the audited
  APK, reached the resumed main activity, rendered, and showed no fatal
  exception or ANR during the smoke window. Existing app data was not cleared.
- The unpackaged Windows app rendered from an isolated QA home and generated
  identity/TLS files only there. The expected Windows firewall prompt appeared
  for the pairing listener; no permission or system change was accepted. This
  was not an install/provisioning test.

Neither row exercised QR pairing, an authorized session, media, input,
clipboard, provisioned OpenSSH, reconnect, or revoke.

## E2EE truth

The P-256 session implementation and acknowledged rekey state machine are real
code with passing automated tests. The claim stops there. RC requires an
integrated physical remote session that demonstrates coverage of sensitive
messages, relay opacity, rekey at time and message thresholds under concurrent
traffic, timeout closure, generation/replay behavior, downgrade rejection, and
fresh reconnect ephemerals.

Until that evidence exists, relay input and clipboard must remain fail-closed
when the E2EE channel is absent.

## Required QA record

Historical local QA logs are excluded from the public source export; the
[release status](../../STATUS.md) distinguishes current checks from older
checkpoints. For every RC run, retain
non-secret evidence containing versions, device/OS class, artifact hashes and
signature results, route/candidate pair, causal error codes, timestamps, test
row, outcome, and sanitized log references. Never retain QR capabilities,
private keys, passwords, tokens, E2EE plaintext, traffic keys, device serials,
MAC addresses, or full authorization values.
