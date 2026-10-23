#!/bin/sh
# Umbra Node 0.4.4. Inspect before running: https://umbra-agent.pages.dev/node/install.sh
set -eu
umask 077
case "$(uname -s)" in Darwin) platform=darwin;; Linux) platform=linux;; *) echo 'Umbra Node supports macOS and Linux.' >&2; exit 1;; esac
case "$(uname -m)" in arm64|aarch64) arch=arm64;; x86_64|amd64) arch=amd64;; *) echo 'Unsupported CPU architecture.' >&2; exit 1;; esac
if [ "$platform" = darwin ]; then arch=universal; fi
name="umbra-node-0.4.4-$platform-$arch"
case "$platform-$arch" in
  darwin-universal) expected='84a94cf01ac8acddf9b3b5b3e32b6f46ee4a520ac6994a992645e1e6bb74d6da';;
  linux-amd64) expected='7cd9988f984da423d81e478d1a8e3a9913119f7678301b5bce1447008981d4fe';;
  linux-arm64) expected='15ef94d9e0d8892fb567eded87a8739c1bb1d6b9c69464c384f6fde560eab1cb';;
  *) echo 'No release for this platform.' >&2; exit 1;;
esac
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT HUP INT TERM
base='https://umbra-agent.pages.dev/download'
echo "Umbra Node 0.4.4 · $platform · $arch"
curl --proto '=https' --proto-redir '=https' --tlsv1.2 -fsSL --retry 3 "$base/$name" -o "$work/umbra-node"
if command -v sha256sum >/dev/null 2>&1; then actual=$(sha256sum "$work/umbra-node" | cut -d ' ' -f 1); else actual=$(shasum -a 256 "$work/umbra-node" | cut -d ' ' -f 1); fi
[ "$actual" = "$expected" ] || { echo 'SHA-256 mismatch; nothing installed.' >&2; exit 1; }
curl --proto '=https' --proto-redir '=https' --tlsv1.2 -fsSL --retry 3 "$base/$name.minisig" -o "$work/umbra-node.minisig"
chmod 700 "$work/umbra-node"
if command -v minisign >/dev/null 2>&1; then
  minisign -Vm "$work/umbra-node" -x "$work/umbra-node.minisig" -P 'RWQM+bFfgzUZ9lFb10IqXsWt49UynUH9wtOKZGeXXJON4M4OjRTKJ60D' -q
else
  # The verifier executable has already passed the checksum pinned in this installer.
  "$work/umbra-node" verify-release "$work/umbra-node" "$work/umbra-node.minisig"
fi
echo 'SHA-256 and minisign signature verified.'
destination=${UMBRA_NODE_BIN_DIR:-"$HOME/.local/bin"}
mkdir -p "$destination"
stage="$destination/.umbra-node-$$.new"
cp "$work/umbra-node" "$stage"
chmod 700 "$stage"
mv -f "$stage" "$destination/umbra-node"
echo "Installed to $destination/umbra-node"
if [ "${UMBRA_NODE_SKIP_SETUP:-0}" = 1 ]; then exit 0; fi
rm -rf "$work"
trap - EXIT HUP INT TERM
if [ -t 0 ]; then exec "$destination/umbra-node" setup --tui; fi
if [ -r /dev/tty ] && (exec </dev/tty) 2>/dev/null; then exec "$destination/umbra-node" setup --tui </dev/tty; fi
exec "$destination/umbra-node" setup
