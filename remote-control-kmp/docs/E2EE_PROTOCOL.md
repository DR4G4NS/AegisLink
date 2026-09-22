# Application E2EE protocol

**Implementation status:** the P-256 crypto/session layer and encrypted protocol
adapter exist with focused tests. The integrated remote-session and automatic
rekey release gates remain open.

## Baseline suite

`AEGIS_P256_AESGCM_V1` uses:

- ECDSA P-256/SHA-256 device identities;
- ephemeral ECDH P-256 for each session;
- HKDF-SHA-256 for directional key derivation;
- AES-256-GCM for authenticated encryption;
- SHA-256 for transcript/key hashes.

The negotiated suite, both exact identities, both ephemeral public keys,
session ID, source/target roles, relay origin, capabilities hash, timestamps,
and generation are included in a canonical signed transcript. A relay cannot
select or rewrite the suite.

Derived material is separated by direction and purpose: two traffic keys, two
nonce bases, an exporter secret, and a rekey secret.

## Envelope checks

An encrypted envelope authenticates the protocol and suite versions, session
ID, sender device ID, generation, monotonically increasing sequence number, and
message type as additional data. The receiver rejects authentication failure,
wrong peer/session/generation, replay, excessive gaps, reordering outside the
accepted policy, downgrade, expiry, and sequence exhaustion.

Reconnect creates fresh ephemeral keys. A stored trust decision never turns an
old traffic key into a reconnect credential.

## Acknowledged rekey

`E2eeProtocolMessageChannel` contains an automatic, acknowledged rekey state
machine triggered by time or per-direction message volume. The source sends an
encrypted request, the target validates the next generation, returns an
encrypted acknowledgement, and both sides replace directional material and
reset sequence state. Traffic waits during the transition, and timeout or
generation mismatch closes the channel.

That is an implementation statement, not a release claim. The RC gate is still
open until a physical remote session proves both thresholds, concurrent
traffic pause/resume, acknowledgement loss, timeout fail-closed behavior,
replayed request/ack rejection, generation changes, and reconnect with fresh
ephemerals.

## Coverage boundary

The encrypted adapter is intended to cover remote SDP, ICE candidates, input,
clipboard, commands, monitor/quality settings, sensitive telemetry, and
session-revealing errors. Remote relay input and clipboard must stay disabled
until the authenticated E2EE channel is established.

Do not infer complete application-envelope coverage merely because a live path
uses HTTPS, WSS, WebRTC DTLS, or a native DataChannel. The RC trace must show
that each remote-sensitive message takes the encrypted adapter and that the
relay observes only opaque frames. LAN pairing and WebRTC transport encryption
remain valuable, but they do not replace this application boundary.

SSH terminal and SFTP are outside this envelope format. Their confidentiality,
integrity, and authentication come from the separately pinned SSH transport;
file contents are not forwarded as relay protocol envelopes.

## Required evidence

- Android ↔ Windows authenticated handshake and exact peer binding.
- Bit flip, replay, wrong target/session/generation, downgrade, gap, reorder,
  expiry, and malformed-frame rejection.
- Relay capture showing no plaintext SDP, ICE credentials, input, clipboard, or
  internal session data.
- Automatic rekey at time and volume thresholds, including timeout failure.
- Network reconnect with fresh ephemerals and no old-generation acceptance.
- Secret-scan/log review confirming keys, plaintext, nonces, and tokens are not
  emitted.
