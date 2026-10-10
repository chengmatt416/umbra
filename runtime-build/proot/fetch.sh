#!/usr/bin/env bash
# Lays out proot for Android (arm64) as native libraries the APK can carry, so the Linux environment also runs on
# a phone without root (ADR 0012). The binaries are Termux's builds, which carry Android's fixes (seccomp
# acceleration, link2symlink, /proc quirks); each package is pinned by its SHA-256 from Termux's signed index.
#
#   runtime-build/proot/fetch.sh     ->  build/proot/jniLibs/arm64-v8a/{libproot.so, libproot-loader.so,
#                                                                       libtalloc.so, libandroid-shmem.so}
#
# Android extracts only files named lib*.so from the APK into the app's native library directory (the one place an
# app can always execute from), so the executables are renamed, and proot's reference to libtalloc.so.2 is renamed
# to match. The licences (proot: GPL-2.0, talloc: LGPL-3.0, libandroid-shmem: BSD-3-Clause) are in third_party/proot.
set -euo pipefail
cd "$(dirname "$0")/../.."
OUT=build/proot
WORK=$OUT/work
LIBS=$OUT/jniLibs/arm64-v8a
rm -rf "$OUT" && mkdir -p "$WORK" "$LIBS"
BASE=https://packages.termux.dev/apt/termux-main

fetch() { # path-in-repository sha256
  local file="$WORK/$(basename "$1")"
  curl -fsSL --retry 5 -o "$file" "$BASE/$1"
  echo "$2  $file" | shasum -a 256 -c - >/dev/null
  # A .deb is an ar archive; its data.tar.xz holds the files (under data/data/com.termux/files/usr).
  python3 - "$file" "$WORK/root" <<'PY'
import io, sys, tarfile
deb, dest = sys.argv[1], sys.argv[2]
data = open(deb, "rb").read()
assert data[:8] == b"!<arch>\n", "not an ar archive: " + deb
pos = 8
while pos < len(data):
    name = data[pos:pos + 16].decode().strip().rstrip("/")
    size = int(data[pos + 48:pos + 58].decode().strip())
    body = data[pos + 60:pos + 60 + size]
    if name.startswith("data.tar"):
        tarfile.open(fileobj=io.BytesIO(body)).extractall(dest, filter="tar")
    pos += 60 + size + (size % 2)
PY
}

fetch pool/main/p/proot/proot_5.1.107.96_aarch64.deb 8199dca06dccb693ec09fb1759e3e1ad08b4863f0c11c612f89c20bd9ecdc1a0
fetch pool/main/libt/libtalloc/libtalloc_2.5.0_aarch64.deb 556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7
fetch pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb 0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6

USR=$WORK/root/data/data/com.termux/files/usr
cp "$USR/bin/proot" "$LIBS/libproot.so"
cp "$USR/libexec/proot/loader" "$LIBS/libproot-loader.so"
cp "$(ls "$USR"/lib/libtalloc.so.2* | head -1)" "$LIBS/libtalloc.so"
cp "$USR/lib/libandroid-shmem.so" "$LIBS/libandroid-shmem.so"

# proot asks the dynamic linker for "libtalloc.so.2"; only lib*.so names survive the APK, so the name in its dynamic
# section is shortened in place (the string table keeps its length; the freed bytes become NULs).
python3 - "$LIBS/libproot.so" <<'PY'
import sys
path = sys.argv[1]
data = bytearray(open(path, "rb").read())
old, new = b"libtalloc.so.2\x00", b"libtalloc.so\x00\x00\x00"
assert data.count(old) == 1, "expected exactly one reference to libtalloc.so.2"
data[data.index(old):data.index(old) + len(old)] = new
open(path, "wb").write(bytes(data))
PY
chmod 755 "$LIBS"/*.so
rm -rf "$WORK"
ls -la "$LIBS"
file "$LIBS"/*.so 2>/dev/null || true
