# OpenMinis — Android

[![License: GPL v3](https://img.shields.io/badge/License-GPLv3-blue.svg)](LICENSE)
[![Platform](https://img.shields.io/badge/Platform-Android-lightgrey.svg)](#building-from-source)

**Your private, on-device AI agent.**

OpenMinis brings leading models — Claude, GPT, Gemini and more — into a native
mobile experience, and gives them a real computer to work with: a full Linux
shell running on your device, browser automation, extensible skills, persistent
memory, and deep system integration.

It is free, and fully open source.

> This repository is an **Android-only trim** of the upstream
> [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis) monorepo — the
> iOS app and its platform-specific dependencies (iSH, FFmpeg, LAME) have been
> removed to keep the tree focused on Android development.

<a href="https://github.com/OpenMinis/OpenMinis/releases">
  <img alt="Get the APK on GitHub" height="48" src="assets/badge-android.svg" />
</a>

---

## What it does

| | |
|---|---|
| **Bring your own model** | Claude, GPT, Gemini and other providers, via your own API keys or account sign-in. |
| **A real Linux shell** | A sandboxed Alpine Linux environment runs on-device — the agent can install packages, run scripts, and work with real files. |
| **Device integration** | Calendar, Reminders, Contacts, Bluetooth, Clipboard, Media, Alarms and more, exposed to the agent as tools. |
| **Browser automation** | The agent can browse and interact with the web on your behalf. |
| **Skills & memory** | Extensible skills plus persistent memory across sessions. |
| **Workspaces** | Organise work into separate contexts, addressable via `minis://workspace/`. |
| **Native offloads** | Heavy or platform-specific work is handed to native code instead of the sandbox. |

**→ [OpenMinis/MinisSkills](https://github.com/OpenMinis/MinisSkills)** — ready-made
skills. Skills built for Claude, Codex, OpenClaw or Hermes Agent generally run in
Minis as-is.

**→ [OpenMinis/AwesomeMinis](https://github.com/OpenMinis/AwesomeMinis)** — a
curated collection of use cases and workflows.

---

## Building from source

Minis ships a Linux sandbox inside the app, so the native PRoot dependency and
the Alpine rootfs are **built from source** rather than committed as binaries.

**→ See [BUILDING.md](BUILDING.md) for the full first-build guide.**

The short version:

```sh
git clone --recurse-submodules https://github.com/nnn669/OpenMinis.git
cd OpenMinis

./deps/build_proot.sh && ./scripts/prepare_android_sandbox.sh
cd src/android && ./gradlew :app:assembleDebug
```

`BUILDING.md` covers the toolchain requirements, the build-time customization
templates, and a troubleshooting section for the failure modes you are most
likely to hit.

---

## Repository layout

```
src/android/      Android app (Kotlin / Compose) + JNI native code
src/shared/       Assets shared with the upstream iOS tree (e.g. bashism rules)
deps/             PRoot submodule + talloc + build_proot.sh
docs/specs/       Architecture and interface specifications
scripts/          Rootfs preparation and developer tooling
```

---

## Acknowledgements

OpenMinis stands on a great deal of open-source work. Our thanks to the
maintainers of these projects — the full inventory, with versions and license
terms, is in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

**The sandbox** — the heart of the product:

- **[PRoot](https://github.com/termux/proot)** (GPLv2) — user-space chroot for the
  Android sandbox, via [OpenMinis's fork](https://github.com/OpenMinis/proot);
  **[talloc](https://talloc.samba.org)** (LGPLv3+) underpins it.
- **[Alpine Linux](https://alpinelinux.org)** — the minirootfs the sandbox boots.

**Text & rendering** — [cppjieba](https://github.com/yanyiwu/cppjieba) (MIT),
[KaTeX](https://katex.org) (MIT).

**Android** — [AndroidX & Jetpack Compose](https://developer.android.com/jetpack),
[OkHttp](https://square.github.io/okhttp/), [Coil](https://coil-kt.github.io/coil/),
[kotlinx](https://github.com/Kotlin) serialization & coroutines,
[multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer),
[Reorderable](https://github.com/Calvin-LL/Reorderable), [ACRA](https://github.com/ACRA/acra)
(all Apache-2.0), and [Shizuku](https://github.com/RikkaApps/Shizuku-API) (MIT).

---

## License

OpenMinis is licensed under the **[GNU General Public License v3.0](LICENSE)**.

The app links a GPL-licensed component — [PRoot](https://github.com/OpenMinis/proot)
(GPLv2) — so the combined work is distributed under GPLv3. Bundled third-party
licenses are listed in [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

---

## Upstream

Original project, iOS app, issues and community:

**→ [OpenMinis/OpenMinis](https://github.com/OpenMinis/OpenMinis)** ·
[openminis.app](https://openminis.app) ·
[Telegram](https://t.me/+2NzhOJuzRyI1YmM1)

For general app bugs, check whether they also reproduce on the official
upstream build. Upstream issues belong on `OpenMinis/OpenMinis`; issues with
this Android-only trim (missing files, build breakage from the removal) can go
on this repository instead.
