# Android client

The Compose Android application discovers and controls an approved Aegis
Windows host.

## Implemented

- Camera QR scan and payload import for signed, expiring, one-use pairing
  payload v3.
- P-256 Android identity in Android Keystore after a real provider capability
  probe; the reported security level reflects the actual device.
- SQLDelight PC profiles containing pinned Windows identity, local endpoints,
  SSH host-key fingerprint, credential references, permissions, and route data.
- Native WebRTC viewer, ICE/candidate-pair telemetry, adaptive quality, and
  reconnection policy.
- Dedicated control, pointer, keyboard, and bounded clipboard DataChannels.
- Touch/pointer, wheel, keyboard, text, and shortcut protocol bridges.
- Manual and opt-in automatic text clipboard synchronization with policy,
  timeout, deduplication, and loop suppression.
- SSHJ terminal with pinned host key, public-key authentication, streaming,
  PTY sizing, and bounded reconnect.
- SSHJ SFTP browser and transfers with path validation, progress, cancellation,
  upload/download, rename, create, and delete operations.
- LAN/VPN route selection, relay approval, STUN probing, temporary TURN
  credentials, and Wake-on-LAN packet sending.

## Security boundaries

The QR never contains passwords, private keys, permanent tokens, or recovery
secrets. Android generates the SSH keypair locally and sends only the public
key after the Windows user approves the device.

SSH/SFTP uses an explicitly reachable pinned SSH route. It is not transported
through TURN or the signaling relay. Remote input and clipboard must remain
disabled until the application E2EE session has completed; TLS/DTLS is not a
substitute.

## Open validation

- Physical install-to-pair LAN flow against the packaged Windows host.
- First frame, monitor selection, input semantics, clipboard bounds, terminal,
  SFTP large-file/cancel/path cases, reconnect, and revoke.
- Remote E2EE automatic rekey, relay opacity, direct/TURN candidate changes,
  app background/foreground, suspend/resume, and 60-minute sessions.
- Signed release APK generation and signature verification.
