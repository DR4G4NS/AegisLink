# Release status

Updated: 2026-09-22.

**Public source launch; production binary gates remain open.** AegisLink contains the Aegis application and its automated build, test and packaging workflows. A successful source publication is not a certification of the installed product.

## Included in this launch

- Source-only repository with a fresh public history, MIT license, contribution guidance and private vulnerability reporting.
- Android and Windows text alignment fixes, including button labels, introductory text, empty states and pairing headings.
- Separate static website at [dr4g4ns.github.io/AegisLink-web](https://dr4g4ns.github.io/AegisLink-web/), with its own source repository and deployment workflow.
- Repaired OpenSSH acquisition for packaging, complete release checksum coverage and temporary signing-key cleanup.
- Automatic development pre-releases after successful `main` CI, and stable release publication after semantic-version tag builds pass signing and release gates. Development downloads do not close the production blockers below.

## Release blockers

1. Configure production Android signing and Windows Authenticode credentials, then build and verify signed packages. The previous private repository had no Actions signing secrets or production environment available during this preparation.
2. Run the elevated Windows clean-install, upgrade, repair and uninstall matrix with a signed installer.
3. Validate the complete physical Android-to-Windows pairing and control flow, including video, input, clipboard, provisioned SSH/SFTP, reconnect and revocation.
4. Complete the remote E2EE, TURN-only, network-transition, multi-monitor and long-session release matrices.
5. Review the exact packaged dependency inventory, signatures, SBOM and release CI evidence.

See [production readiness](remote-control-kmp/docs/PRODUCTION_READINESS.md) for detailed acceptance criteria. No missing physical or signing check is recorded as passed.

## Evidence

Local launch preparation passed Android and desktop compilation, full-repository Detekt and ktlint, Android release lint, both SQLDelight migration checks, and 298 unit tests (114 Android, 19 desktop UI, 135 desktop agent, 30 desktop input; no failures or skips). The final combined run completed 290 Gradle tasks in 1m10s, including cached tasks. The SQLite migration driver now extracts its native library inside the ignored build directory when the worker lacks a writable system temp path.

Desktop screens were rendered with isolated test state to inspect alignment; no physical Android device was connected for this pass.

The clean source export passed Gitleaks 8.30.1 with no findings. Release workflows passed actionlint 1.7.12 and the changed PowerShell scripts passed parser validation. The pinned OpenSSH archive was verified and extracted successfully.

CI publishes test reports for each tested commit. Historical development records are not substituted for tests of the public launch snapshot.

Current execution evidence is available in [GitHub Actions](https://github.com/DR4G4NS/AegisLink/actions). A failed or pending run leaves the corresponding automated gate open.
