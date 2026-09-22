# SSH terminal and SFTP flow

Aegis uses a mature SSH library on Android and an isolated Windows OpenSSH
instance installed and managed by Aegis. It does not implement the SSH or SFTP
protocols itself.

## Installed Windows service

The Inno Setup installer provisions:

- service `AegisOpenSSH`;
- TCP port `48222`;
- configuration and host material under
  `%ProgramData%\Aegis\OpenSSH`;
- public-key-only authentication;
- restrictive ACLs and Private/Domain firewall rules;
- forwarding and tunnelling disabled;
- idempotent inspect/install/repair/uninstall scripts.

The provisioner checks service/port ownership and fails on collision. It does
not modify the stock Windows SSH service or its configuration. Uninstall removes
only the Aegis-owned instance and marked keys.

## Pairing and key enrollment

1. Windows includes the SSH port and real host-key fingerprint in signed QR
   payload v3.
2. Android verifies the QR and stores the pin in the PC profile.
3. Android generates the SSH keypair locally and stores a secure private-key
   reference.
4. Only after visible Windows approval does Android send the public key for
   enrollment.
5. Windows writes a uniquely marked authorized-key entry for that device.
6. Revocation removes the exact marked entry and closes active Aegis
   authorization.

The QR and relay never receive the private SSH key or a Windows password.

## Terminal

Android resolves an explicitly reachable LAN, VPN, or manual TCP route, pins the
server host key before authentication, opens a PTY/shell through SSHJ, streams
bounded output, sends input, resizes the PTY, and applies bounded reconnect
policy. Host-key change, unavailable route, timeout, authentication error, or
revocation is surfaced as a causal SSH error.

## SFTP

The Android file flow uses SSHJ SFTP for list, navigation, upload, download,
rename, create, delete, progress, and cancellation. Paths are normalized and
validated before operations; transfer cancellation closes the active operation
rather than leaving an unbounded background task.

Files are not encoded as clipboard messages or forwarded through the signaling
relay. SSH/SFTP does not use STUN or TURN.

## Open physical gate

Run against the packaged `AegisOpenSSH` instance and record:

- first shell without password entry;
- exact host-key pin success and mismatch rejection;
- list/upload/download/rename/create/delete;
- zero-byte and large files, Unicode names, denied permissions, hostile
  traversal attempts, interruption, cancellation, and reconnect;
- concurrent terminal and SFTP use;
- device revoke during a live session;
- install/repair/uninstall ownership audit.

The existence of SSHJ tests or a local SSH client does not prove this gate.
