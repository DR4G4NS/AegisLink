# Threat model

| Threat | Current control | Residual risk / required gate |
| --- | --- | --- |
| QR capture, tamper, replay, or wrong target | Signed canonical QR v3, short expiry, one-use capability, identity/target binding | Physical shared-LAN tamper/reuse/expiry matrix |
| Identity substitution or provider downgrade | Algorithm-tagged P-256 baseline, exact secp256r1 parameter validation, real cached capability probes, canonical device-ID derivation | Physical reboot persistence, takeover, rotation, and provider-variance matrix |
| Stolen Windows identity file | DPAPI wrapping and current-user file restrictions | Software-backed key remains extractable by a sufficiently compromised user session; report storage level honestly |
| Unauthorized relay session | Target must advertise remote access; visible approval/exact trust; per-device permissions | Relay/service compromise can deny service and observe metadata |
| Relay reads or rewrites sensitive signaling/control | Signed transcript, AES-GCM envelopes, sequence/generation checks | Integrated relay-opacity, downgrade, replay, and malformed-frame evidence is open |
| Rekey desynchronization | Acknowledged state machine, traffic pause, generation checks, timeout closure | Both triggers, acknowledgement loss, concurrent load, and reconnect require physical validation |
| Input abuse | Live-session permission checks, SendInput bounds/rate limits, pressed-state cleanup | Secure desktop remains intentionally inaccessible; physical layout/UAC/session-edge tests |
| Clipboard exfiltration or loops | Visible opt-in, text-only bounds, timeout, secret filter, deduplication, dedicated channel | Heuristics cannot recognize every secret; physical two-way loop tests |
| SSH credential or host impersonation | Android-generated key, public-key-only service, host-key pinning, marked key enrollment | Packaged server, pin mismatch, revoke, and private-key-storage tests |
| Existing SSH configuration damaged | Isolated service/root/port and ownership-scoped uninstall | Clean install/upgrade/repair/uninstall audit |
| Public network exposure | Pairing/SSH firewall rules limited to Private/Domain; no router forwarding | User or administrator can still broaden exposure outside Aegis |
| Malicious filename/path | SFTP normalization/validation and SSH server permissions | Hostile traversal, links, Unicode, large files, and cancellation tests |
| Installer replacement | Release workflow signs launcher and outer Setup and verifies Authenticode | Local/CI packages are unsigned; only signed workflow output may ship |
| Wrong monitor captured | Exact native capture IDs, fail-closed topology/duplicate checks, persisted Android selection | Physical switching and pointer geometry across real mixed layouts remain required |
| Sensitive diagnostics | Structured causal codes and redaction policy | Secret/log scan and QA artifact review required |
| Wake-on-LAN overclaim | Capability reporting and reversible adapter changes | Firmware/driver behavior varies; actual wake test required when support is reported |

## Trust boundaries

- The Android Keystore and Windows DPAPI protect different storage levels; the
  UI and logs must not present them as equivalent.
- Pairing approval authorizes a device but does not prove that a later remote
  E2EE session succeeded.
- Relay, STUN, and TURN are untrusted for application plaintext and private
  credentials.
- WebRTC transport encryption does not replace the application E2EE requirement
  for remote-sensitive protocol messages.
- SSH is a separate pinned transport. The relay does not terminate or proxy it.
- The Windows installer may mutate only explicitly Aegis-owned resources.
