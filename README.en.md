# lightmux

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](./LICENSE)
[![CI](https://github.com/ninvfeng/lightmux/actions/workflows/ci.yml/badge.svg)](https://github.com/ninvfeng/lightmux/actions/workflows/ci.yml)
[![CNB](https://img.shields.io/badge/CNB-lightmux-0052D9.svg)](https://cnb.cool/ninvfeng/lighttools/lightmux)

**A lightweight Android SSH client built for tmux.**

[简体中文](./README.md)

---

- **3.6 MB.** That is the entire APK — no bundled shell, no analytics SDK, no dead weight.
- **Small, not simple.** Beyond the SSH core: SFTP file management, a host monitor, port forwarding
  with a built-in preview, and a quick bar you can rebuild key by key.

Everyone who actually uses SSH from a phone ends up in tmux: connect → `tmux ls` → `tmux a -t x` → hunt
for the right window. Other Android SSH clients treat tmux as a feature *inside* the terminal — open a
terminal, open a panel, pick a session. On a phone that is three navigations per context switch.

lightmux inverts the hierarchy. The home screen lists **host → tmux session → window** directly.
Expand, tap, you are there. The terminal is the destination, not the entrance.

From the home screen to a specific tmux window is **at most 3 taps**, and 1 tap when the host is
already expanded. Inside a terminal a side swipe pulls out that same tree, so another session is
one more tap away.

## Download

Signed APKs are published on [CNB Releases](https://cnb.cool/ninvfeng/lighttools/lightmux/-/releases).
Requires Android 7.0 (minSdk 24). Not on any app store for now — installing means allowing
installation from an unknown source once.

The code lives on GitHub and CNB alike; the release pipeline runs only on CNB, so that is where the
packages are. See [Repositories](#repositories) below.

## Features

Everything listed here is implemented and shipping.

**tmux session switcher (the home screen)**
- Three-level tree: host → session → window, expanded inline
- Cache-first rendering: cold start paints the last known tree with a "3 min ago" timestamp and makes
  **zero** network calls; a host is probed only when you expand it
- Sessions: attach / create / rename / detach other clients / kill
- Windows: list and switch
- Non-tmux ("bare") sessions are listed under their host alongside tmux sessions
- Every tmux action goes through an out-of-band `exec` channel. lightmux **never injects keystrokes**
  into your foreground terminal — it may be running a full-screen TUI, or not be attached at all

**Terminal**
- Multiple concurrent sessions, owned by an Application-scoped `SessionManager` so they survive
  navigation; scrollback is preserved when you leave and come back
- Foreground service keeps sessions and transfers alive in the background
- Automatic reconnect with exponential backoff, immediate retry on network recovery, and
  **scrollback survives the reconnect** (the emulator is kept, only the transport is swapped)
- After reconnecting it re-attaches to the same tmux session instead of opening a new one
- Copy / paste, pinch to zoom, scrollback history
- Quick bar: `⏎` `Esc` `Tab` `Ctrl` `Alt` `↑↓←→` `-` `|` `~` `/` — without `Ctrl` the tmux prefix key
  is unreachable from an Android soft keyboard, so this is core, not a nicety.
  `Ctrl` and `Alt` are sticky (tap, then tap the next key) and highlight while armed
- The bar is yours to arrange: add or drop keys, drag to reorder, resize the caps. One slot holds
  your own quick commands — tap to send a whole command into the terminal, optionally with Enter
- Custom keys: a cap that sends a string of text, or a key sequence in tmux notation
  (`C-b d`, `M-.`, `F5`, `Up Up Enter`) — a prefixed two-stroke tmux binding becomes one tap

**Host management**
- Name, host, port, user, group; password or private key (PEM) authentication
- Keys are managed in one place: import once, reuse across hosts, edit it and every host follows
- Credentials encrypted with the Android Keystore (AES-GCM), never stored in plaintext
- TOFU host key verification — the key is pinned on first use and a change is a blocking warning
- ProxyJump (jump host)
- Command to run after login

**Host monitor**
- Distribution / kernel / hostname / uptime / load / CPU (total and per core) / memory / swap /
  disks / network interfaces / containers / top processes; local and public IP, tap to copy
- One `exec` per sample, parsed from `/proc` — no dependency on `top` or `free`, whose output varies
  by distro. Anything that cannot be read is shown as **unavailable** rather than padded with zeros
- Containers and processes sort by CPU or memory, so whatever is eating the box shows up first
- Refreshes every 5s while the page is open and stops the instant you leave
- Linux only; hosts without `/proc/stat` say so instead of showing wrong numbers

**Files (SFTP)**
- Browse / upload / download / mkdir / rename / delete, over the same SSH connection
- Small text files can be edited and saved in-app (binary files and oversized files are refused)
- Transfers run on an Application-level background queue with visible, cancellable progress

**Port forwarding**
- The equivalent of `ssh -L`: something serving on the host's port 3000 opens as
  `http://127.0.0.1:3000` in the phone's browser
- Opening the forwarding screen lists what the host is listening on (`ss -tlnp`); loopback-only
  ports sort first, since those are the ones that genuinely need a tunnel. The search box filters
  the list you already have, by port number, process name or listen address
- **Bound to loopback only** — a forwarded port is reachable from this phone and nothing else,
  so joining a café Wi-Fi never quietly exposes the host's internal services
- When an address like `http://127.0.0.1:3000` scrolls past in the terminal, a bar offers to forward
  and open it right there — the moment a service comes up is exactly when you want to look at it
- Reconnects with backoff on drops; live forwards keep the foreground service alive
- Forwarded pages open **inside the app**, in a minimal built-in browser: leaving for an external
  browser is exactly when the phone's battery policy cuts this app off the network, so the tunnel
  drops mid-page. Editable address bar (type just `3000` for `127.0.0.1:3000`), back key goes back,
  and a 44dp bottom bar instead of a title bar, so the page gets the screen; "Open in browser" is
  still one tap away in that bar's overflow menu

**In-app updates**
- In-app update check; releases live in the CNB repository — there is **no background polling**
- The downloaded APK is verified (package name, version is actually newer, signature matches the
  installed app) before the system installer is invoked; anything that fails verification is deleted

**Basics**
- English and 简体中文, following the system locale, switchable in Settings
- Light / dark / follow-system theme
- 3 terminal palettes: Default (xterm), Solarized Dark, Gruvbox Dark; adjustable terminal font size
- JetBrains Mono Regular (OFL 1.1) is bundled as the terminal typeface — the system monospace font
  has no box-drawing glyphs, so TUI borders fall back to another font and come out stretched.
  CJK still falls back to the system font
- If the system restricts background networking, Settings says so and takes you straight to the toggle

## Not doing

- Local terminal / bundled shell (that is termux's job), a font download center, cloud sync, desktop.

## Screenshots

Not published yet — they will be added here once the app has been shot on a real device.

## Build

Requires **JDK 17** and an Android SDK with platform 35.

```bash
./gradlew assembleDebug                    # app/build/outputs/apk/debug/app-debug.apk
./gradlew test                             # all unit tests
./gradlew :app:testDebugUnitTest           # app logic tests (393)
./gradlew :terminal-emulator:testDebugUnitTest   # upstream emulator tests (138)
python3 scripts/check_strings.py           # bilingual string resource check
```

minSdk 24 (Android 7.0), targetSdk 35, Kotlin 2.1 + Jetpack Compose + Material 3, AGP 8.7.

## License

**MIT** — see [LICENSE](./LICENSE).

[`terminal-emulator/`](./terminal-emulator/LICENSE) and [`terminal-view/`](./terminal-view/LICENSE) are
vendored from [termux-app](https://github.com/termux/termux-app) v0.118.0 and stay **Apache-2.0**
(termux's `LICENSE.md` grants an explicit exception for exactly these two modules). Changes to them are
marked `[lightmux]` and listed in [NOTICE](./NOTICE). Dependencies are inventoried in
[THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md) — nothing GPL/LGPL/AGPL in the tree.

## Repositories

The same code lives in two places, and they are kept in sync:

| | |
|---|---|
| [github.com/ninvfeng/lightmux](https://github.com/ninvfeng/lightmux) | Issues and pull requests go here |
| [cnb.cool/ninvfeng/lighttools/lightmux](https://cnb.cool/ninvfeng/lighttools/lightmux) | Builds, signs and publishes the release APKs |

Version tags are pushed to CNB only — a tag there is what triggers a release build.

## Contributing

PRs are welcome — read [CONTRIBUTING.md](./CONTRIBUTING.md) first.
Security issues: see [SECURITY.md](./SECURITY.md), please do not open a public issue for those.

Product design and the reasoning behind it live in [PRD.md](./PRD.md) (Chinese).
Release history is in [CHANGELOG.md](./CHANGELOG.md).
