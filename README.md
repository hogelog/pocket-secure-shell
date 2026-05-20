# <img src="docs/assets/app-icon.svg" width="32" align="top"> PocketSecureShell

Android SSH client tuned for terminal-heavy workflows like Claude Code's TUI.

<img src="docs/assets/screenshot-claude-code.png" alt="Running Claude Code over SSH on PocketSecureShell" width="320">

Requires Android 14 (API 34) or later.

## Features

- Biometric-gated SSH public key authentication via [sshlib](https://github.com/connectbot/sshlib); one unlock authorizes signing for 30 minutes (the lock-screen unlock counts)
- Private key stays on the device and cannot be exported
- xterm-256color terminal emulation via [Termux terminal-emulator/terminal-view](https://github.com/termux/termux-app)
- tmux-aware: input surfaces switch with the active pane's foreground command
- Customizable per-context input surfaces: shortcut bar, left/right swipe payloads, and a FAB speed-dial menu
- Learned input suggestions per command, based on your past input
- Image upload: pick an image or paste one from the keyboard clipboard (e.g. a recent screenshot via Gboard) to upload to the remote and insert the path at the cursor — built for handing images to Claude Code
- Japanese IME input
- No telemetry in release builds — the App sends no analytics or crash reports of its own. Debug builds built locally from source include [Sentry](https://sentry.io) for crash reporting; the GitHub Releases / Play Store artifacts users install never do. Android's OS-level crash reporting via Google Play services may still apply on devices that have it enabled (see [Privacy Policy](docs/privacy.md))

## Install

- **Google Play (closed test)**: join the [`pocket-secure-shell-tester` Google Group](https://groups.google.com/g/pocket-secure-shell-tester), then open the [opt-in URL](https://play.google.com/apps/testing/org.hogel.pocketssh) and tap "Become a tester".
- **GitHub Releases**: grab the APK from [Releases](https://github.com/hogelog/pocket-secure-shell/releases). Recommended if you want to verify the binary against this source — see [Verifying release binaries](#verifying-release-binaries).

## Verifying release binaries

Release artifacts are built on GitHub Actions and signed with SLSA build
provenance. To verify that an APK or AAB came from this repository's release
workflow:

```bash
gh attestation verify pocketsecureshell-vX.Y.Z.apk --repo hogelog/pocket-secure-shell
```

## Build

### Requirements

- JDK 21
- Android SDK with `compileSdk` 36 (`minSdk` 34, `targetSdk` 36)
- Android NDK 27.0.12077973

### Steps

```bash
git submodule update --init --recursive
./gradlew assembleDebug
```

The debug APK will be produced at `app/build/outputs/apk/debug/app-debug.apk`.

## License

MIT - See [LICENSE](LICENSE).
