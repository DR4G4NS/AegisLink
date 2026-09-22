# Security reporting

AegisLink controls real computers. Visible consent, device trust, revocation and pinned transports are part of the product contract.

## Report privately

Use [GitHub private vulnerability reporting](https://github.com/DR4G4NS/AegisLink/security/advisories/new). Include the affected commit, platform, prerequisites, reproduction steps and impact. Remove private keys, tokens, QR payloads and personal device data.

Do not publish an exploit or sensitive details in a public issue. No response deadline or security support SLA is currently promised.

## Supported state

The public source snapshot is under active development. There is no certified stable binary release yet; see [STATUS.md](STATUS.md). Reports against current `main` are the primary focus.

The protocol and trust boundaries are documented in [security invariants](remote-control-kmp/docs/SECURITY_INVARIANTS.md), [architecture](remote-control-kmp/docs/ARCHITECTURE.md) and [production readiness](remote-control-kmp/docs/PRODUCTION_READINESS.md).

Release signing keys and production relay credentials must be configured outside the source tree. Passing CI alone does not replace the physical installation, consent and end-to-end release gates.
