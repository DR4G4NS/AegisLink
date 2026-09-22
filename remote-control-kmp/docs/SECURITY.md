# Security design

Aegis combines visible local authorization with separate cryptographic and
transport boundaries. No single successful transport connection grants control.

## Identity and pairing

The product baseline is an algorithm-tagged P-256 identity. Android uses
Android Keystore after a real operation probe. Windows stores a persistent
software P-256 key wrapped by DPAPI and reports that storage level honestly.

The primary enrollment path is signed QR payload v3:

1. Windows creates a short-lived, one-use capability and signs the canonical
   payload.
2. Android verifies version, expiry, target, signature, host identity, TLS pin,
   and SSH host-key pin.
3. Android creates its identity and SSH keypair locally and sends only public
   material.
4. Windows shows the Android identity for explicit approval.
5. After approval, Windows stores the exact trust tuple and enrolls the marked
   SSH public key.
6. Reuse, tampering, expiry, target mismatch, or revocation fails closed.

The QR contains no password, private key, permanent token, or recovery secret.

## Authorization lifecycle

Permissions are per device and per feature. A display name, IP address, or
relay locator is not an authorization identity. Autoapproval is limited to the
same pinned, non-revoked identity and existing permissions.

Revocation closes active protocol authorization, rejects later reconnects, and
removes only that device's marked AegisOpenSSH key. Deleting metadata cannot be
used to bypass the revoke step.

## Local and remote channels

Local pairing/protocol traffic uses the QR-pinned local endpoint and transport
security plus application admission checks. WebRTC uses DTLS/SRTP and four
purpose-specific DataChannels.

Remote rendezvous requires a registered target that advertises remote access
and a visible approval or a still-valid exact-device trust decision. Relay
approval is not equivalent to E2EE. The remote path must complete the P-256
application handshake before relay input or clipboard is enabled. The relay
must see opaque encrypted frames only.

The E2EE implementation provides transcript signatures, ephemeral ECDH,
HKDF-SHA-256, AES-256-GCM, directional sequence/replay checks, generation
binding, and acknowledged rekey. Its integrated physical matrix remains an
open RC gate; see [E2EE_PROTOCOL.md](E2EE_PROTOCOL.md).

## Input and clipboard

Windows input uses SendInput and is restricted to an authorized live session.
It applies frequency bounds, validates coordinates, and releases pressed keys
and buttons when the session ends. Aegis does not attempt to bypass the secure
desktop or UAC.

Clipboard synchronization is visible and opt-in. It is text-only, bounded to
32 KiB with an 8 KiB automatic-send limit, uses two-second operations, filters
common secrets, deduplicates content, and suppresses loops. Dedicated clipboard
routing fails rather than silently using the control channel. Files use SFTP.

## SSH/SFTP

Setup provisions an isolated `AegisOpenSSH` service on TCP 48222 with
public-key authentication, restricted forwarding, restrictive ACLs, and
Private/Domain firewall rules. It does not alter the stock Windows SSH service
or configuration.

Android pins the real server host key and keeps its private authentication key
in secure storage. SSH/SFTP uses LAN, VPN, or another explicitly authorized TCP
route; TURN and the signaling relay are not SSH transports.

## Installer and supply chain

The canonical artifact is the Inno Setup executable
`Aegis-Remote-Desktop-Setup-<version>.exe`. Local developer and ordinary CI
builds are unsigned evidence. The release workflow requires signing secrets,
signs the bundled launcher and outer Setup with Authenticode, and verifies the
signature. Android release signing is also required.

A release additionally needs dependency review, secret scanning, SBOM,
checksums, reproducible artifact inventory, and packaged-file/signature review.

## Open security gates

- Physical QR tamper/reuse/expiry, takeover, reconnect, rotation, and revocation
  tests.
- Integrated remote E2EE relay-opacity and automatic-rekey matrix.
- Clean install/upgrade/uninstall audit of service, ACL, firewall, key, and
  Wake-on-LAN ownership.
- Physical terminal/SFTP pin mismatch, hostile path, large transfer,
  cancellation, and revoke tests.
- Network transitions, TURN-only, suspend/resume, and long-session cleanup.
- Verification that logs and QA artifacts contain no secrets.
