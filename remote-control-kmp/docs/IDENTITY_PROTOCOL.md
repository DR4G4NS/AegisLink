# Device identity and QR v3 bootstrap

## Baseline identity

The mandatory product baseline is ECDSA P-256 with SHA-256. Public identities
are algorithm-tagged canonical SPKI values; a device identifier is derived from
the protocol identity version, algorithm identifier, and canonical public key.
Code must not infer an algorithm from key length or silently accept a provider
substitution.

The identity record binds at least:

- device ID and display name;
- identity algorithm and canonical public key;
- public-key fingerprint;
- generation/rotation state;
- storage security level and capability report.

## Storage truth

Android creates the identity in Android Keystore only after generating, signing,
and verifying with the exact requested algorithm. Hardware-backed or StrongBox
status is reported only when the device actually provides it.

Both platforms derive support from real cryptographic operations, not provider
declarations. The probes exercise identity sign/verify, key agreement, and AEAD
round trips; P-256 public keys must match every secp256r1 domain parameter. A
non-secret capability report is cached only for a compatibility key that binds
the platform/runtime and provider set. A cache mismatch, corruption, forced
refresh, OS/runtime/provider change, or incoherent suite selection causes a new
probe (`IDN-1008`/`IDN-1009` cover cache diagnostics).

The Windows host currently creates a persistent software P-256 key and protects
its encoded private material with DPAPI. This is reported as wrapped software,
not as a non-exportable platform key. Trust metadata is also DPAPI-protected and
restricted to the current user where supported.

Private identity keys are never serialized into a QR, profile, relay request,
log, or diagnostic bundle.

## QR payload v3

The Windows host signs the canonical `pairing-v3` payload with its P-256
identity. The payload includes the host identity and fingerprint, LAN pairing
endpoints, pairing and SSH ports, TLS and SSH pins, a high-entropy one-use
pairing capability, timestamps, capabilities, and supported Wake-on-LAN adapter
metadata.

Rules:

1. Android verifies version, structure, expiry, target binding, canonical
   signature, and pins before making a pairing request.
2. The capability is short lived, can be consumed once, and is never rendered
   as a manual backup secret.
3. The QR contains no password, private key, permanent access token, or recovery
   secret.
4. Windows displays the Android identity and fingerprint for explicit approval.
5. Android generates its SSH keypair locally; Windows receives only the public
   key and enrolls it only after approval.
6. Successful pairing persists the exact host identity, pins, permissions,
   endpoint, SSH credential reference, and capability data needed by the
   profile.
7. Reuse, expiry, signature failure, wrong target, or identity mismatch fails
   closed with a causal pairing error.

## Relay proof of possession

Relay registration uses a challenge/proof flow. The client signs a canonical,
short-lived challenge that binds the relay origin, requested identity,
generation, capabilities, nonce, and expiry. The relay verifies the signature
and device-ID derivation before issuing an expiring token.

If an expiring bearer token is unavailable while the persisted relay locator
and identity still exist, registration recovery obtains a fresh challenge and
proves the exact current identity (or the recorded retired source identity for
an idempotent rotation retry). It never reconstructs ownership from a locator,
display name, or bearer-token sentinel alone.

A user-chosen relay locator is an address, not an identity. It cannot replace
the pinned public identity. A conflicting identity or generation must be
rejected rather than overwriting an existing device.

## Trust, reconnect, rotation, and revocation

Trusted peers are compared using the complete algorithm-tagged identity and
generation, not a display name or relay locator. Autoapproval is allowed only
for the same non-revoked identity and the permissions already granted.

Rotation is a durable two-phase transaction on Android and Windows. The store
first persists a replacement beside the active identity in `Prepared`, with a
canonical operation UUID and strictly increasing generation. The relay accepts
only a transcript signed by the current key, records the operation
idempotently, and returns the replacement registration. The client then
persists `RemoteConfirmed` before activating the replacement locally. A restart
resumes either phase; an ambiguous response remains safely prepared as
`IDN-1011`, and only an explicitly requested `Prepared` operation can roll
back. A relay-confirmed operation can never roll back to the retired key.

Revocation closes active authorization, prevents new sessions, invalidates
relay/event access, and removes only the matching Aegis-managed SSH public key.

## Unclosed gates

The implementation and focused tests do not replace the RC matrix. Record
physical evidence for QR expiry/reuse/tamper, provider persistence after reboot,
takeover rejection, rotation, exact-identity reconnect, revocation during an
active session, and re-pairing from a clean Android/Windows install.
