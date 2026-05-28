# PocketSecureShell

Android SSH client designed for Claude Code TUI interaction.

## Requirements

- Android SDK with compileSdk 36
- NDK (for terminal-emulator native code)
- JDK 21+

## Project Structure

- `app/` - Main application module (Kotlin)
- `vendor/termux-app/` - Git submodule (Termux terminal-emulator and terminal-view)

### Key Packages

- `org.hogel.pocketssh` - Main activity (connection UI)
- `org.hogel.pocketssh.ui` - Terminal UI (`TerminalActivity`, `ShortcutsSettingsActivity`, `EditContextGroupActivity`)
- `org.hogel.pocketssh.ssh` - SSH session, foreground connection service (`SshConnectionService`), keystore-bound key (`SshKeyManager`), and TOFU host key store (`HostKeyStore` / `TofuHostKeyVerifier`)
- `org.hogel.pocketssh.shortcuts` - Context-aware shortcut groups (`ShortcutStore`, `ShortcutPayload` parser)
- `org.hogel.pocketssh.learning` - Bigram learning that drives the dynamic suggestion row (`BigramTracker` observes ssh stdin, `BigramStore` is a small SQLite table)
- `org.hogel.pocketssh.settings` - JSON export/import (`SettingsBackup`)

## Architecture

Uses Termux's `TerminalView` + `TerminalEmulator` for terminal rendering, with a bridge
pattern to connect SSH I/O (via sshlib) to the terminal emulator. A dummy local process
is created for `TerminalSession`, while actual I/O flows through the SSH channel.

### Background session and replay

`SshConnectionService` is a foreground service that owns the SSH session and a rolling
output buffer. Re-attaching `TerminalActivity` (after rotation, app restart, etc.) replays
the buffer into the emulator to reconstruct the screen, so UI recreation does not drop the
session. Anything touching the Activity ↔ Service boundary needs to preserve this.

### tmux context tracking

The active tmux pane's OSC window title is parsed for the foreground command name and
pushed to `BigramTracker.setContext` and the shortcut resolver as the per-context key.
With `useTmux=false` (or before tmux emits a title) the context falls back to
`BigramStore.UNKNOWN_CONTEXT` (`(unknown)`).

### Shortcut payload DSL

`ShortcutPayload.parseShortcutActions` accepts byte escapes (`\e`, `\xNN`, `\t`/`\r`/`\n`),
caret-Ctrl (`^A`–`^Z`, `^[` etc.), key tokens routed through `KeyHandler`
(`{UP}` `{DOWN}` `{LEFT}` `{RIGHT}` `{TAB}` `{S-TAB}`), and dynamic actions
(`{TMUX-PREFIX}` `{COPY}` `{PASTE}` `{IMAGE-PASTE}` `{SECURE-INPUT}` `{CTRL-INPUT}`). Keep the parser and the
`shortcuts_payload_help` string in `res/values/strings.xml` in sync — that string is the
user-facing reference.

### SettingsBackup versioning

`SettingsBackup.VERSION` gates JSON shape compatibility. Bump it on any non-additive
change and add a migration branch in `import`. The keystore-bound private key and TOFU
host-key records are intentionally excluded from the bundle.

## Submodule

After cloning, initialize the submodule:

```bash
git submodule update --init --recursive
```

The submodule is pinned to termux-app v0.118.3.

## Telemetry

Sentry is **debug-only**. The SDK ships via `debugImplementation` and lives behind a
`CrashReporting` indirection: `app/src/debug/java/.../CrashReporting.kt` does the manual
`SentryAndroid.init`; `app/src/release/java/.../CrashReporting.kt` is a no-op. Release APKs
contain no Sentry classes and make no telemetry phone-home. CI passes `SENTRY_DSN` only to
debug-producing build steps (auto-init is disabled in `app/src/debug/AndroidManifest.xml`).

Even in debug, user-controlled values must never reach Sentry events or breadcrumbs:

- Connection target (host, port, username) and SSH key material (public key, fingerprints, host
  key bytes, signatures) are off-limits — do not pass them to `Sentry.captureException` /
  `captureMessage` / `addBreadcrumb` / `setUser` / `setContext` / `setTag` / `setExtra`, and do
  not interpolate them into exception messages or `Log.*` calls that may propagate uncaught.
- Catch and log SSH-layer exceptions where they originate so they never reach the global
  `UncaughtExceptionHandler` (where their messages would be captured verbatim — e.g.
  `UnknownHostException` carries the hostname).
- Do not enable `attachViewHierarchy`, `attachScreenshot`, or session-replay; the connection
  screen's `EditText`s and the terminal output would otherwise leak.

## License

MIT.

When you add or remove a dependency in `app/build.gradle.kts`, also update
the `sections` list in `LicensesActivity` (and, if a new license type appears,
drop its full text under `app/src/main/res/raw/`). The in-app "Open-source
licenses" screen is the binary-side attribution channel, so a missing entry
means missing attribution.
