# Connection model

| Feature/path | Transport | Security boundary | Current status |
| --- | --- | --- | --- |
| QR bootstrap | Local HTTPS on TCP 48291 | Signed one-use QR v3, P-256 host identity, TLS pin, visible approval | Implemented; physical clean-install gate open |
| LAN visual | WebRTC direct | Pinned paired peer, DTLS/SRTP, session permissions | Implemented; first-frame/long-session physical gate open |
| LAN control | Four WebRTC DataChannels | Per-device permissions, strict channel routing, rate/bounds | Implemented; physical semantics/revoke gate open |
| Remote rendezvous | Relay WebSocket | Relay registration plus visible approval/exact trust | Implemented; approval alone grants no control |
| Remote signaling/control | E2EE protocol adapter over relay/WebRTC path | P-256 authenticated application E2EE | Crypto/adapter implemented; integrated coverage and rekey gate open |
| Internet media | WebRTC direct ICE or TURN | DTLS/SRTP, selected candidate-pair validation | Implemented; direct/TURN/network-transition gate open |
| Terminal/SFTP | TCP 48222 over LAN/VPN/authorized route | SSH host-key pinning and Android public-key auth | Implemented; packaged physical server gate open |
| Wake-on-LAN | UDP magic packet on LAN | Explicit profile/adapter capability | Implemented; hardware-dependent physical gate open |

## Route order

For WebRTC, prefer LAN, then an explicit VPN/manual route, then direct Internet
ICE, then TURN. The signaling relay is rendezvous/control transport, not a video
server. SSH/SFTP uses only an explicitly reachable TCP route and never TURN.

## DataChannels

- `aegis-control`: reliable and ordered.
- `aegis-pointer`: unordered with no retransmission.
- `aegis-keyboard`: reliable and ordered.
- `aegis-clipboard`: reliable, ordered, and bounded.

A missing dedicated channel is an error; messages do not silently fall back to
`aegis-control`.

## Fail-closed rules

- A relay-approved session without successful application E2EE cannot carry
  input or clipboard.
- Transport TLS/WSS/DTLS does not replace E2EE.
- A revoked device cannot reconnect or retain its managed SSH key.
- An SSH host-key mismatch blocks terminal and SFTP.
- Unknown ICE/stat values stay unknown; they are not reported as zero/success.
- A route probe does not prove the full feature works.

## Multi-monitor boundary

Windows exposes the native WebRTC `DesktopSource.id` as the protocol monitor
ID and combines it with AWT geometry by deterministic index only when the
enumerations agree. Duplicate, missing, or unknown IDs fail closed instead of
capturing the first display. Android renders the monitor list, persists the
selection, and sends the switch through the live authorized session. Switching
between physical displays remains an RC evidence gate.
