#!/usr/bin/env bash
# Installs the omp version the product pins (the one in the Linux image) into build/omp-pinned, without touching a
# global omp, and prints the path of its binary. The host-side protocol and orchestrator tests run against it
# (scripts/verify.sh exports it as OMP_BIN), so a newer omp on this machine cannot change what they check.
set -euo pipefail
cd "$(dirname "$0")/.."
VERSION=$(sed -n 's/^ARG OMP_VERSION=//p' runtime-build/rootfs/Dockerfile)
DIR="$PWD/build/omp-pinned"
BIN="$DIR/bin/omp"
if [ ! -x "$BIN" ] || ! "$BIN" --version 2>/dev/null | grep -q "omp/$VERSION"; then
  rm -rf "$DIR"
  BUN_INSTALL="$DIR" bun install -g "@oh-my-pi/pi-coding-agent@$VERSION" >&2
fi
echo "$BIN"
