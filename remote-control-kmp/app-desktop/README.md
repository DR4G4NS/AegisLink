# Desktop host

The desktop application is a visible Windows 10/11 x64 or Linux x86_64 host.
It shows pairing, approvals, authorization state, capabilities, relay state,
and diagnostics in its window/tray UI; it is not a stealth-control service.

Native packages are configured as MSI on Windows and Deb/Rpm on Linux. Each
package includes the application JVM. A package must be built on its target
operating system.

## Platform support

| Capability | Windows 10/11 x64 | Linux x86_64, X11 | Linux x86_64, Wayland |
| --- | --- | --- | --- |
| WebRTC desktop video | Native Desktop Duplication | Native webrtc-java 0.14 X11 capture | Native Portal/PipeWire bridge with visible consent |
| Monitor enumeration/selection | Native source IDs matched fail-closed to AWT geometry | Native X11 source IDs matched fail-closed to AWT geometry | Full-screen portal stream; monitor changes require a new consent session |
| Pointer/keyboard input | SendInput | `xdotool` through XTEST | `ydotool` only when its client and an existing accessible `ydotoold` socket pass the runtime probe |
| Text clipboard | Native AWT bridge | `xclip` | `wl-copy` and `wl-paste` from `wl-clipboard` |
| SSH terminal and SFTP | Isolated installed service | Isolated child owned by the Aegis process | Same user-scoped child |

The Linux WebRTC runtime resolves the `linux-x86_64` native classifier. Native
backend selection is deterministic: an explicit `XDG_SESSION_TYPE` wins, then
`WAYLAND_DISPLAY`, then `DISPLAY`; contradictory markers are never mixed. On
Wayland, the bundled short-lived bridge calls XDG Desktop Portal, waits for
visible consent, opens the PipeWire remote, and feeds bounded raw frames into
the existing WebRTC track. Aegis never treats an XWayland-only view as native
Wayland capture and never falls back silently to X11.

On Wayland, Aegis never starts `ydotoold`. Modern ydotool requires that daemon
and access to `/dev/uinput`; installation, socket permissions, and daemon
lifecycle remain an explicit local-administrator decision. Aegis runs the
`ydotool` client with bounded direct argv calls, probes the existing socket with
the non-input `ydotool debug` command, tracks held keys/buttons, and releases
them when a session closes. Current ydotool text injection is ASCII/layout
dependent; non-ASCII text fails explicitly.

## Windows runtime requirements

The packaged JVM, Skiko runtime, and webrtc-java `windows-x86_64` native bridge
are bundled in the Windows distributable. Host integrations require these
capabilities:

- Windows 10/11 x64 with an interactive user session; headless CI/service
  sessions cannot capture or inject input.
- webrtc-java Desktop Duplication for native WebRTC video.
- JNA `User32` SendInput for pointer/keyboard injection.
- DPAPI for user-scoped identity and trust-store protection.
- Managed OpenSSH provisioning scripts from the signed Setup, or
  `AEGIS_OPENSSH_SCRIPTS` / `-Daegis.openssh.scripts` during development.
- TCP 48222 available for the isolated `AegisOpenSSH` service after install.
- Inno Setup 6 to build the provisioned Windows Setup locally or in CI.
- Win32-OpenSSH 10.0.0.0p2-Preview (checksum-pinned) embedded by the Setup
  build scripts; Aegis does not download it at runtime.

Build the Windows distributable and Setup on a Windows x64 host. Gradle resolves
the `windows-x86_64` webrtc-java classifier automatically on Windows runners.

## Linux runtime requirements

The packaged JVM and application are self-contained. Host integrations require
these executable/runtime capabilities:

- OpenSSH server and client tools: executable `sshd` at `/usr/bin/sshd` (the
  standard `/usr/sbin/sshd` and `/sbin/sshd` locations are also discovered) and
  `/usr/bin/ssh-keygen`.
- X11 video: an interactive X11 session with `DISPLAY`; headless sessions are
  rejected.
- X11 input: `xdotool` on `PATH` and an X server with XTEST.
- Wayland input: `ydotool` on `PATH` plus a pre-existing `ydotoold` socket
  accessible to the Aegis user (`YDOTOOL_SOCKET` or
  `$XDG_RUNTIME_DIR/.ydotool_socket`).
- Optional clipboard: `xclip` on X11 or both `wl-copy` and `wl-paste` on
  Wayland.
- Wayland capture: a session D-Bus, `xdg-desktop-portal` with the compositor
  backend, PipeWire, and the packaged `aegis-pipewire-portal-capture` bridge.
- Linux identity/trust: a user Secret Service. The preflight reports this
  independently; a missing capability does not disable unrelated backends.

Distribution package names vary, but on Debian-family systems the corresponding
packages are normally `openssh-server`, `openssh-client`, `xdotool`, `xclip`,
`ydotool`, and `wl-clipboard`. On Rpm-family systems install packages providing
the exact executables above. The local firewall must permit TCP 48222 when LAN
SSH/SFTP access is desired; Aegis deliberately does not change firewall policy.

## Modules

- `desktop-agent`: identity, trust, QR v3 pairing, local HTTPS/protocol
  endpoints, relay registration, approvals, revocation, user-scoped OpenSSH
  lifecycle, and structured logs.
- `desktop-webrtc`: native Windows/X11 sender, signaling, candidate-pair stats,
  four DataChannels, monitor/quality control, and cleanup.
- `desktop-input`: Windows SendInput, Linux X11 xdotool/XTEST, and Linux
  Wayland ydotool implementations for absolute/relative pointer, buttons,
  wheel, keys, text, shortcuts, and held-input release.
- `desktop-clipboard`: bounded opt-in text clipboard bridges with timeout,
  deduplication, secret filtering, and loop suppression.
- `desktop-main`: Compose window/tray, settings, QR display, approvals, device
  management, capability status, and installer application entry point.

## Security and storage

The host baseline identity is P-256/SHA-256. On Windows its private key is
software-backed and wrapped with DPAPI; on Linux it requires the Secret Service.
The capability report must not label either software key as a non-exportable
platform key. Trust records are protected by the corresponding user-scoped
platform facility.

Pairing requires a valid signed QR v3 capability, a fresh one-use token, and
visible approval. Revocation closes active authorization and removes only the
revoked device's exactly tagged SSH public key.

Remote signaling/control is allowed only through an approved session.
Application E2EE is an additional mandatory boundary for relay input and
clipboard; transport TLS/DTLS alone does not satisfy it.

## WebRTC channels

| Label | Delivery | Content |
| --- | --- | --- |
| `aegis-control` | Reliable, ordered | Session control, signaling-adjacent commands, monitors, quality, stats |
| `aegis-pointer` | Unordered, no retransmission | Pointer movement |
| `aegis-keyboard` | Reliable, ordered | Key/button transitions and text input |
| `aegis-clipboard` | Reliable, ordered, bounded | Authorized clipboard text |

A message routed to a dedicated channel is rejected if that channel is absent;
it does not silently fall back to control.

## Isolated SSH/SFTP hosts

On Windows, the canonical installer provisions an isolated `AegisOpenSSH`
service on TCP 48222 under `%ProgramData%\Aegis\OpenSSH`. It uses public-key
authentication, restrictive ACLs, and removable Private/Domain firewall rules.
The stock Windows SSH service and configuration are not modified.

On Linux, Aegis starts `sshd -D` as a bounded child of the visible desktop
process on TCP 48222. Its Ed25519 host key, generated `sshd_config`,
`authorized_keys`, and pid file live under `~/.aegis/openssh` with owner-only
POSIX permissions. The configuration permits only the current local account
and public-key authentication, provides `internal-sftp`, disables forwarding,
and leaves the system service, `/etc/ssh`, root-owned files, and firewall
untouched. The child is terminated when `DesktopAgent.stop()` runs.

## Open validation

- Clean Deb/Rpm install, upgrade, and removal on supported Debian/Fedora-family
  distributions.
- Physical X11 first frame, input, clipboard, terminal, SFTP, reconnect, and
  revoke; Wayland Portal/PipeWire consent, first frame, and ydotool behavior
  across supported compositors.
- Correct mapping between enumerated monitor IDs and native capture source IDs,
  plus the complete Android monitor-selection path.
- Remote E2EE/rekey, TURN-only, network change, suspend/resume, and long-session
  matrices.
