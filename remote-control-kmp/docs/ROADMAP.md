# Windows delivery roadmap

This roadmap tracks the public [AegisLink project](../../README.md).
It lists remaining evidence and defects; it does not treat code presence as
release completion.

## Current checkpoint

| Vertical | Code state | Remaining exit gate |
| --- | --- | --- |
| Identity and QR v3 | Implemented; automated security tests and isolated desktop generation pass | Physical clean-device scan, persistence, tamper/reuse/takeover/rotation/revoke matrix |
| Windows Setup and AegisOpenSSH | Inno/provisioner build and unsigned Setup audit pass | Signed artifact plus elevated clean install/upgrade/repair/uninstall audit |
| WebRTC capture/viewer | Native implementation and Android/desktop startup smokes pass independently | Authorized physical first frame, stats, cleanup, and 60-minute run |
| Multi-monitor | Native ID mapping and Android selection implemented | Physically switch among real displays and verify geometry/input |
| Input and clipboard | Windows adapters and four channels implemented | Physical layout/rate/bounds/loop/revoke checks |
| Terminal and SFTP | SSHJ debug/release loopback integration passes, including cancellation handle closure | Packaged `AegisOpenSSH` matrix, large/hostile file, cancel, pin, revoke, reconnect |
| Relay/STUN/TURN | Server/client plumbing implemented | Durable deployment integration and real network/TURN-only matrix |
| Application E2EE | Crypto, adapter, acknowledged rekey, timeout, and fresh-reconnect automated coverage pass | Integrated coverage, relay opacity, both rekey triggers, timeout/reconnect |
| Release supply chain | Developer SBOM/checksums/artifact audit pass; signing/security workflows implemented | Produce and verify signed APK/Setup; run dependency/license, secret, and CodeQL gates |

## Phase 1 — stabilize the integrated tree

**Status: completed for the 2026-07-15 checkpoint.**

1. Resolve compile/test conflicts from the current cross-module work.
2. Run one grouped Windows build, affected unit/integration tests,
   lint/static-analysis, migrations, and packaging pass.
3. Fix only causal failures; keep evidence and commands in the QA record.
4. Re-run only impacted rows after fixes, then one final grouped pass.

Exit: the tree is reproducibly buildable and the developer Setup bundle can be
created without representing it as signed.

Recorded result: the grouped pass completed with 357 actionable Gradle tasks,
the Inno Setup developer package compiled, 5/5 checksum entries verified, and
the CycloneDX runtime SBOM parsed as JSON/XML. Historical local QA logs are
excluded from the public export; current exit conditions are documented in
[PRODUCTION_READINESS.md](PRODUCTION_READINESS.md).

## Phase 2 — close the LAN milestone

**Status: next physical/elevated phase.** The independent Android and isolated
desktop startup smokes are complete; no end-to-end LAN row is claimed.

1. Prove native capture source ↔ Android monitor selection on physical displays.
2. Verify pointer geometry on negative-origin and mixed-resolution layouts.
3. Produce a signed candidate Setup/APK through the release workflow.
4. On clean Windows, install and provision AegisOpenSSH without altering stock
   SSH resources.
5. Execute QR v3 → approval → complete profile → first frame.
6. Validate mouse, keyboard, clipboard, terminal, SFTP, reconnect, and revoke.
7. Test supported Wake-on-LAN hardware; record unsupported hardware as a
   feature degradation.
8. Run repair/upgrade/uninstall ownership audit.

Exit: one physical Android/Windows pair completes the definitive LAN vertical
with non-secret evidence.

## Phase 3 — close remote identity, E2EE, and connectivity

1. Trace every remote-sensitive protocol message through the encrypted adapter.
2. Capture relay-side evidence that SDP, ICE credentials, input, clipboard,
   commands, and sensitive telemetry remain opaque.
3. Trigger automatic rekey by elapsed time and message volume under concurrent
   traffic.
4. Prove timeout closure, ack/request replay rejection, generation mismatch,
   downgrade rejection, and fresh reconnect ephemerals.
5. Validate LAN, VPN, mobile network, direct ICE, TURN-only, network changes,
   ICE restart, suspend/resume, and route fallback.
6. Run a 60-minute session and review memory, handles, logs, dropped frames,
   stats accuracy, and deterministic cleanup.

Exit: the remote P0 and real-network rows pass together.

## Phase 4 — release candidate

1. Run complete unit/integration, crypto known-answer, MITM/replay/downgrade,
   OpenSSH/SFTP, coturn, UI smoke, and package lifecycle suites.
2. Run dependency review, secret scan, CodeQL/equivalent, SBOM, license and
   packaged-file audit.
3. Verify Android signer, Windows Authenticode subject/timestamp, hashes, and
   artifact inventory.
4. Review privacy/redaction evidence and causal error coverage.
5. Publish only after every mandatory gate is green or explicitly classified as
   a documented non-blocking hardware limitation.

No local debug APK or unsigned Setup can be promoted by renaming it.

The developer SBOM, checksum, APK/Setup metadata, and packaged-artifact audit
are already green. They must be regenerated and re-verified for the signed
release artifacts; release-only scans and signatures remain open.
