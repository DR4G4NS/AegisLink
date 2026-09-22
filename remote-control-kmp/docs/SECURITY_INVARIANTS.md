# Security invariants

These rules apply to every Android ↔ Windows path.

1. The Windows host is visible and every first trust decision requires visible
   local approval.
2. The mandatory identity baseline is algorithm-tagged P-256/SHA-256. Device
   IDs derive from the canonical versioned identity, never from a display name,
   IP address, relay locator, or untagged bytes.
3. Storage claims match reality. Android reports the actual Keystore security
   level; Windows reports its DPAPI-wrapped software identity as such.
4. QR pairing uses signed payload v3, a short-lived one-use capability, target
   binding, expiry checks, and replay rejection. No permanent secret, password,
   or private key appears in the QR.
5. Trust pins the complete peer identity, generation, permissions, TLS pin, and
   SSH host-key pin required by the feature.
6. Relay approval is authorization, not proof that E2EE succeeded. Remote input
   and clipboard remain disabled until authenticated application E2EE is live.
7. TLS/WSS and WebRTC DTLS never substitute for the application E2EE boundary.
8. Failed authentication, downgrade, replay, invalid generation, or rekey
   timeout closes the affected session.
9. Revocation takes effect immediately for active control and removes only the
   matching marked SSH public key.
10. SSH/SFTP accepts only an explicitly reachable TCP route and the pinned host
    key. It is never carried through TURN or the signaling relay.
11. Clipboard is text-only in V1, visibly opt-in, size/time bounded, filtered,
    deduplicated, and loop-suppressed. Files use SFTP.
12. Input is permission-checked, rate-limited, bound to a live session, and
    releases pressed state on disconnect/revoke. Secure desktops are not
    bypassed.
13. The installer owns only the isolated AegisOpenSSH service, Aegis
    configuration/keys, Aegis firewall rules, and reversible Aegis Wake-on-LAN
    changes. Existing Windows SSH resources are not overwritten.
14. Local/CI unsigned artifacts are never represented as signed releases.
15. Logs never contain private keys, passwords, permanent tokens, QR
    capabilities, plaintext E2EE data, traffic keys, nonces, or full
    authorization headers.
