#!/usr/bin/env bash
# Installs the omp version the product pins (the one in the Linux image) into build/omp-pinned, a folder with its own
# package.json, and prints the path of its binary. Nothing outside build/omp-pinned is written: no global install,
# and no package.json higher up (bun would otherwise pick the nearest one). The host-side protocol and orchestrator
# tests run against it (scripts/verify.sh exports it as OMP_BIN), so a newer omp on this machine cannot change them.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION=$(sed -n 's/^ARG OMP_VERSION=//p' runtime-build/rootfs/Dockerfile)
DIR="$PWD/build/omp-pinned"
BIN="$DIR/node_modules/.bin/omp"
if [ ! -x "$BIN" ] || ! "$BIN" --version 2>/dev/null | grep -q "omp/$VERSION"; then
  rm -rf "$DIR"
  mkdir -p "$DIR"
  printf '{ "name": "umbra-omp-pinned", "private": true }\n' > "$DIR/package.json"
  (cd "$DIR" && bun add --exact "@oh-my-pi/pi-coding-agent@$VERSION" >&2)
fi
echo "$BIN"
