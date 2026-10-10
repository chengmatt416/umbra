#!/usr/bin/env bash
# Installs the Android app builds pack into the app's Linux environment on the device (what the app's own "Install"
# button does): a JDK, aapt2, zipalign and apksigner from Debian, and d8 and the SDK platform from Google. Downloads
# a few hundred MB, once.
set -euo pipefail
cd "$(dirname "$0")/.."
export UMBRA_CTL_TIMEOUT=3600
scripts/umbractl.py call guest.status >/dev/null
scripts/umbractl.py call guest.exec_root '{"argv":["bash","-c","umbra-pack install android"],"timeout_ms":3000000}' \
  | python3 -c 'import json,sys; r=json.load(sys.stdin); print(r["stdout"][-800:]); print(r["stderr"][-400:]); sys.exit(r["exit"])'
