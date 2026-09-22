# Connectivity

## Route priority

WebRTC evaluates:

1. direct LAN;
2. explicit VPN/manual endpoint;
3. direct Internet ICE/STUN;
4. TURN when direct ICE fails.

The relay provides registration, rendezvous, approval, events, and signaling.
It is not the media server: WebRTC media uses a direct candidate pair or TURN.

SSH terminal and SFTP use only LAN, VPN, or another explicitly authorized TCP
route to the pinned AegisOpenSSH endpoint on port 48222. They never use STUN,
TURN, or the signaling relay.

## Feature matrix

| Feature | LAN/VPN | Direct Internet | TURN | Relay |
| --- | --- | --- | --- | --- |
| QR pairing | HTTPS :48291 | No | No | Separate remote approval path |
| WebRTC video | Direct candidate | Direct candidate | Relayed candidate | Rendezvous/signaling only |
| Input | Dedicated DataChannels | Dedicated DataChannels after E2EE gate | Dedicated DataChannels after E2EE gate | No plaintext fallback |
| Clipboard | Bounded dedicated DataChannel | Dedicated channel after E2EE gate | Dedicated channel after E2EE gate | No plaintext fallback |
| Terminal/SFTP | Pinned SSH :48222 | Only if an explicit safe TCP route exists | No | No |
| Wake-on-LAN | UDP local broadcast | No | No | No |

## Local visual flow

1. QR v3 establishes the pinned PC profile and permissions.
2. Android opens the authenticated local protocol endpoint on TCP 48291.
3. Peers exchange WebRTC offer/answer/ICE.
4. Windows attaches its native Desktop Duplication source.
5. Android renders the video track.
6. Peers open control, pointer, keyboard, and clipboard DataChannels.
7. Real candidate-pair stats feed route diagnostics and adaptive quality.
8. Reconnect policy uses bounded retry/ICE restart/route re-evaluation.

Multi-monitor uses native WebRTC source IDs end-to-end. Enumeration mismatches,
duplicates, and unknown selections fail closed; Android persists and sends the
selected monitor through the live session. Real display switching remains a
physical RC evidence row.

## Remote flow

1. Both devices register by challenge/proof; the target advertises whether
   remote access is enabled.
2. Android creates a session for the exact Windows relay locator/identity.
3. Windows shows and accepts/rejects the request unless exact trust permits the
   existing autoapproval policy.
4. Approval opens rendezvous only.
5. The peers complete application E2EE before remote-sensitive protocol
   traffic is allowed.
6. Encrypted signaling drives WebRTC; ICE chooses direct or TURN.
7. Relay-visible frames must remain opaque.

Remote input/clipboard has no plaintext relay fallback. If E2EE is absent or
fails, those features fail closed. The crypto/adapter exists, but this
integrated physical path and automatic-rekey matrix remain an RC gate.

## Relay deployment modes

Durable deployment requires PostgreSQL and Redis together, a token-HMAC secret,
a canonical relay origin, HTTPS/WSS termination, and correctly scoped forwarded
headers. Development in-memory/JSON mode must be explicitly enabled.

Temporary TURN REST credentials are issued only when TURN URLs and shared
secret are configured together. TURN increases latency/cost and can observe
traffic metadata, not application plaintext.

## Open network evidence

- Physical LAN/VPN/direct/TURN-only candidate selection.
- Mobile-network and captive/public-network behavior.
- ICE restart, network change, suspend/resume, app lifecycle, and cleanup.
- Real stats accuracy; unknown values must remain unknown.
- 60-minute session and relay/coturn resource limits.
