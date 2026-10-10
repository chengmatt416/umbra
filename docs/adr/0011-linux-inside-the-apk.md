# 0011. The Linux environment and its add-ons inside the APK

Status: accepted. Extends [0003](0003-root-first-linux-runtime.md) and [0009](0009-the-app-carries-the-guest-scripts.md).

## Context

The product must work with no external dependency: no download server, no package mirror reachable from the phone,
no second app. The Debian guest (omp, tmux, Bun, the agent's tools) was downloaded on first run, and the add-ons
(the desktop pack and others) were installed with `apt` from the network.

## Decision

1. `runtime-build/rootfs/build.sh <version>` builds the Debian arm64 guest in Docker and packs it as
   `umbra-rootfs-<version>-arm64.tar.xz`, and packs the add-ons of `guest/packs` as an **offline apt repository**
   (`umbra-packs-<version>-arm64.tar`). Each gets a `.sha256`; `manifest.json` describes both.
2. The release APK carries both as assets with `linux/bundle.json` (sizes and SHA-256). `GuestManager` installs from
   the bundle when there is one; a file or a URL still work.
3. Add-ons install from the carried repository: it is unpacked into the guest and `apt` reads it from there.
4. `-Pumbra.bundleLinux=false` builds without the bundle (a quick development build); the release workflow builds
   the rootfs first, then the APK with it.
5. The build uses a private, credential-free Docker CLI configuration and imports the Debian base image from its
   published tarball, so no registry login or pull is involved.

## Consequences

* The release APK is large (the environment and its add-ons), and a phone needs no network to set Linux up.
* A downloaded archive is checked against its published SHA-256 before it is unpacked. The carried one is covered by
  the APK's signature; its SHA-256 identifies the version installed, so a newer APK's environment replaces it.
* The in-app tar reader refuses entries that would reach outside the target or change modes through links
  (`TarReader`).
