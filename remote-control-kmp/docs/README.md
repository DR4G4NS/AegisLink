# Aegis documentation

These documents describe the AegisLink Android client and visible desktop host.
Start with the [project overview](../../README.md) and
[current release status](../../STATUS.md). Windows and Linux capabilities are
listed in the [desktop guide](../app-desktop/README.md).

Recommended reading order:

1. [ARCHITECTURE.md](ARCHITECTURE.md) — components and trust boundaries.
2. [CONNECTION_MODEL.md](CONNECTION_MODEL.md) — feature/transport matrix.
3. [DESKTOP_INSTALL.md](DESKTOP_INSTALL.md) — canonical Windows Setup and
   clean-install gate.
4. [IDENTITY_PROTOCOL.md](IDENTITY_PROTOCOL.md) — P-256 identity and QR v3
   bootstrap.
5. [E2EE_PROTOCOL.md](E2EE_PROTOCOL.md) — implemented cryptographic channel and
   unclosed validation gates.
6. [SECURITY_INVARIANTS.md](SECURITY_INVARIANTS.md) and
   [THREAT_MODEL.md](THREAT_MODEL.md) — non-negotiable boundaries and risks.
7. [SSH_SFTP_FLOW.md](SSH_SFTP_FLOW.md) — isolated AegisOpenSSH flow.
8. [CONNECTIVITY.md](CONNECTIVITY.md) and [RELAY_GUIDE.md](RELAY_GUIDE.md) —
   LAN, relay, STUN, and TURN operation.
9. [RELEASE.md](RELEASE.md) — release configuration, signing, and artifact verification.
10. [TESTING.md](TESTING.md), [ROADMAP.md](ROADMAP.md), and
    [PRODUCTION_READINESS.md](PRODUCTION_READINESS.md) — grouped verification
    and release-candidate gates.

Security and release reviewers should also use
[DEPENDENCIES.md](DEPENDENCIES.md) for the direct/native runtime inventory and
the enforcement sources that produce the authoritative SBOM/license evidence.

## Documentation truth rules

- “Implemented” means code and focused automated coverage exist.
- “Physically validated” requires recorded evidence from an Android device and
  the packaged Windows host.
- “Release artifact” means the Android APK and Windows Setup were created by
  the signing-enforcing release workflow and their signatures were verified.
- Focused crypto/rekey tests do not close the integrated remote-session gate.
- A local or ordinary CI Setup is unsigned evidence, not a distributable
  release.
- Unsupported Wake-on-LAN hardware is a feature degradation, not a global
  blocker.
