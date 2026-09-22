# Contributing to AegisLink

Use JDK 21 and the Android SDK. Build commands run inside `remote-control-kmp/`.

1. Open an issue describing the behavior, platform and reproducible steps, with private device data removed.
2. Create a focused branch and keep changes scoped to one problem.
3. Add or update tests for behavior changes. Preserve visible approval, key pinning, revocation and fail-closed behavior.
4. Run the relevant module tests and the formatting/static checks:

```powershell
.\gradlew.bat --dependency-verification strict ktlintCheck detekt
.\gradlew.bat --dependency-verification strict :app-android:testDebugUnitTest :app-desktop:desktop-main:test
```

The complete CI matrix also covers Linux, relay dependencies, migrations, Android lint and package creation. Some integration paths need OS services or physical devices; report exactly what you ran and what remains untested.

For UI changes, verify alignment, wrapping, keyboard focus, accessibility and both supported languages. Keep terminal output, editable fields and file listings readable.

Never commit build outputs, local configuration, signing material, device identities, QR payloads, screenshots with personal data or session logs. Security reports belong in the private route described in [SECURITY.md](SECURITY.md).

By contributing, you agree that your contribution is distributed under this repository's MIT license. Preserve third-party notices and document new dependencies.
