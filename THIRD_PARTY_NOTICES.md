# Third-party notices

The MIT license at the repository root applies to the project's original source and documentation. Third-party software, fonts and redistributed components retain their own licenses.

- JetBrains Mono Nerd Font: [bundled font license](remote-control-kmp/app-android/src/main/assets/licenses/JetBrainsMonoNerdFont-OFL.txt).
- Gradle wrapper: distributed by the Gradle project under its upstream licensing; dependency versions and verification hashes are committed under `remote-control-kmp/gradle/`.
- JVM, Compose, Kotlin, Android, WebRTC, SSH and relay dependencies: declared in Gradle build files and summarized in [DEPENDENCIES.md](remote-control-kmp/docs/DEPENDENCIES.md).
- Packaged Java runtimes and bundled OpenSSH retain their upstream legal notices. These components are acquired during packaging rather than checked into this repository.

Release packaging generates a CycloneDX software bill of materials. Review that inventory and the licenses of the exact resolved dependencies before distributing binaries. This file is an orientation to the included notices, not a completed binary license audit.
