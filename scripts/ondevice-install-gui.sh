#!/usr/bin/env bash
# Puts the desktop add-on (virtual screen, window manager, accessibility bridge, and a small GTK editor to
# try it on) into the Linux environment of the app on the device. Downloads a few hundred MB with apt.
set -euo pipefail
cd "$(dirname "$0")/.."
export UMBRA_CTL_TIMEOUT=3600
scripts/umbractl.py call guest.status >/dev/null
scripts/umbractl.py call guest.exec_root '{"argv":["bash","-c","umbra-pack install gui && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends mousepad"],"timeout_ms":3000000}' \
  | python3 -c 'import json,sys; r=json.load(sys.stdin); print(r["stdout"][-600:]); sys.exit(r["exit"])'
