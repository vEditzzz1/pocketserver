# PocketServer

Run a Linux userland on an Android phone and serve web sites from it — no PC, no
Docker daemon, and no root required to get started.

PocketServer is a Kotlin/Jetpack Compose app (`dev.pocketserver`) that boots a real
Alpine or Ubuntu filesystem on the device, starts web servers inside it, and gives you
two ways to drive everything: the on-device dashboard and a built-in web control panel.

- **Package:** `dev.pocketserver`
- **UI:** Compose dashboard + web panel on port `9090`
- **Android:** 8.0+ (`minSdk 26`), `compileSdk 36`
- **ABI:** arm64-v8a — the bundled userland and `proot` binaries are aarch64

## What it does

A site is a process inside a Linux filesystem, listening on a port. PocketServer
installs the runtime that site needs, starts it, keeps a pid file in the guest, and
stops the leftover processes when you press Stop (PRoot does not forward signals, so
that cleanup has to be explicit).

Site kinds: **static** (`httpd` or `busybox httpd`), **PHP** (`php -S`, plus fpm/nginx),
**Node.js**, **Python**, **container** (a Docker image unpacked by udocker and entered
by a single PRoot) and **custom** (your own command line).

## Runtime modes

How a guest command is executed. The mode decides *how* Linux is entered; the selected
distro decides *which* filesystem it lands in.

| Mode | How | Notes |
| --- | --- | --- |
| **PRoot** | `proot` as the app UID | Works on any phone, no privileges. Sites stop when the app process is killed. |
| **Root** | real `chroot` through `su` | Real UIDs, privileged ports (80/443). Needs root granted to the app. |
| **Shizuku** | the same PRoot runtime, started by the Shizuku service | Sites keep serving after the app is closed. Privileged ports only if the Shizuku server itself runs as root. |
| **Podroid** | a User-Mode Linux kernel booted as a real Alpine VM | A genuine kernel and root inside the guest, no AVF needed. Alpine only, needs Shizuku, and the VM images are a 306 MB one-time download. |

## Distros

- **Alpine 3.24** — bundled in the APK (`assets/rootfs`), package manager `apk`.
- **Ubuntu 24.04** — downloaded on demand (~28 MB tar, ~105 MB on disk), package
  manager `apt`.

Package commands are chosen by the guest, not by a preference: each package set emits
`if command -v apt-get … else apk …`, so switching distro does not leave you with the
wrong package manager. The two filesystems are separate, so packages installed in one
do not exist in the other.

## The web panel

Served by the app itself on `http://127.0.0.1:9090` (or the phone's LAN address) and
protected by a token the app generates. Tabs: **Sites**, **Images**, **Packages**,
**Podroid**, **System**, **Guide**.

The Guide tab is built from `/api/state` rather than from fixed prose, so what it says
about a mode (privileged ports, what can run together, what the app will refuse) follows
the live state of the device.

Panel API mutations are `GET` with query parameters — for example
`/api/site/start?id=<site>`, `/api/stacks`, `/api/distro?set=alpine|ubuntu`,
`/api/catalog?q=nginx&remote=1`.

## Containers

Container sites use [udocker](https://github.com/indigo-dc/udocker): it pulls and
unpacks an image, and PocketServer enters the unpacked rootfs with a single PRoot. This
works in every mode, including PRoot without root, because `udocker run` is never used
(nesting PRoot inside PRoot fails).

Per-container settings — volumes, env, workdir, user, entrypoint — are translated into
the wrapper rather than passed to a container runtime. A container here is a
filesystem, not a sandbox: there are no PID/IPC namespaces, no cgroups, no real port
publishing and no read-only volumes.

## Build

There is no Gradle wrapper in this tree; `build.sh` drives a Gradle installed in a
Termux-style prefix, which is how this app is built on a phone:

```sh
sh build.sh          # :app:assembleDebug -> app/build/outputs/apk/debug/app-debug.apk
```

Requirements: JDK 17+, Android SDK with platform 36, Gradle 9.x. On a normal machine
`gradle :app:assembleDebug` with `ANDROID_HOME` set works the same way.

Note the pinned signing config: `app/build.gradle.kts` uses
`$HOME/.android/debug.keystore` explicitly instead of Gradle's default, because Java's
`user.home` on the build device points elsewhere and a mismatched debug key makes
`pm install -r` fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`.

## Install

```sh
adb install -r app-debug.apk
# or, on the device with a privileged shell:
pm install -r -d app-debug.apk
```

## Project layout

| Path | Role |
| --- | --- |
| `engine/Shell.kt` | Enter the userland via PRoot, root (`su`) or Shizuku |
| `engine/RuntimeInstaller.kt` | `install()` repairs in place, `reset()` rebuilds the guest filesystem |
| `engine/Stacks.kt` | One-tap package sets, probes, `heal()` |
| `engine/Distros.kt` | Alpine/Ubuntu install, switch, rootfs permissions |
| `engine/Containers.kt` | udocker pull/create, container rootfs, capability probes |
| `engine/Servers.kt` | Site profiles, start/stop, guest pid files |
| `engine/Podroid.kt`, `engine/VmService.kt` | The VM backend and its launcher |
| `engine/Panel.kt` + `assets/panel/` | HTTP API and the web UI |
| `ui/Dashboard.kt` | Compose screen |

## Known limits

- No container isolation: no namespaces, cgroups, port publishing or read-only volumes.
- Ports under 1024 need a privileged runtime (Root, or a Shizuku server running as root).
- The Podroid VM is Alpine-only, needs Shizuku, and its kernel cannot run from inside the
  app sandbox on devices whose seccomp filter kills it.
- Two runtimes cannot hold the same port; the app forwards a running site's port when the
  VM starts and takes it back when the VM stops, but never shares it.
- `targetSdk` is 28.

## Credits

PocketServer bundles and builds on other people's work:

- [proot](https://proot-me.github.io/) (GPL-2.0, Termux build) — `assets/runtime`
- [Alpine Linux](https://alpinelinux.org/) minirootfs — `assets/rootfs`; the packages
  inside keep their own upstream licenses
- [Podroid](https://github.com/excp/podroid) (GPL-2.0) — the User-Mode Linux VM images
  are downloaded from its releases at runtime, not redistributed here
- [udocker](https://github.com/indigo-dc/udocker) — container image pull/unpack
- Docker Hub search API for the image catalog

## License

PocketServer's own source is released under the [MIT License](LICENSE).

That does not relicense the third-party pieces it bundles or downloads: the
`proot` binaries in `assets/runtime` are GPL-2.0, and the Alpine minirootfs in
`assets/rootfs` plus everything inside it keeps its upstream licenses. See
[Credits](#credits) for the full list.
