#!/usr/bin/env bash
# End-to-end verification of Umbra on a real device or the emulator, through the debug build's control
# channel. Nothing is simulated: the real app runs the real Linux environment, the real omp and real tools.
#
#   scripts/ondevice-verify.sh                 build, install, set up, run the self-test (no model needed)
#   scripts/ondevice-verify.sh --agent         also run an agent task end to end (needs a model key, see below)
#   scripts/ondevice-verify.sh --acceptance    also run the §33 acceptance task on the crashy-login fixture
#
# Options: --no-build (reuse the APK), --rootfs PATH (default build/rootfs/umbra-rootfs-dev-arm64.tar.xz)
#
# A model for --agent/--acceptance, from your environment (nothing is stored in the repository):
#   UMBRA_VERIFY_PROVIDER   anthropic | openai | google | openrouter | xai   (a built-in provider)
#   UMBRA_VERIFY_KEY        the API key
#   UMBRA_VERIFY_MODEL      model id, e.g. claude-sonnet-5-5
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=scripts/env.sh
source scripts/env.sh

BUILD=1; AGENT=0; ACCEPT=0; ROOTFS=build/rootfs/umbra-rootfs-dev-arm64.tar.xz
while [ $# -gt 0 ]; do
  case "$1" in
    --no-build) BUILD=0 ;;
    --agent) AGENT=1 ;;
    --acceptance) AGENT=1; ACCEPT=1 ;;
    --rootfs) ROOTFS="$2"; shift ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
  shift
done

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"; ADB="$SDK/platform-tools/adb"
ctl() { scripts/umbractl.py "$@"; }
step() { printf '\n== %s\n' "$*"; }
fail() { printf 'FAILED: %s\n' "$*" >&2; exit 1; }

step "device"
"$ADB" get-state >/dev/null 2>&1 || fail "no device attached (scripts/emulator.sh start)"
# shellcheck source=scripts/lib-root.sh
source scripts/lib-root.sh
[ "$(rootsh 'id -u' | tr -d '\r')" = "0" ] || fail "the device does not give root to adb through su; the Linux environment needs it"

if [ "$BUILD" = 1 ]; then step "build"; ./gradlew --console=plain :app:assembleDebug -q; fi
step "install, grant, helpers, control channel"
scripts/dev-device.sh setup

step "Linux environment"
if ctl call guest.status | grep -q Ready; then echo "already installed"; else scripts/dev-device.sh rootfs "$ROOTFS"; fi
ctl call guest.status

step "self-test (screen, shell, launch, background display, Linux, network)"
ctl call selftest.run | python3 -c '
import json, sys
steps = json.load(sys.stdin)
bad = 0
for s in steps:
    print("%-8s %-40s %s" % (s["status"], s["title"], s["detail"]))
    bad += s["status"] == "FAILED"
sys.exit(1 if bad else 0)'

step "what the agent cannot reach (system part of the environment, the app's private files)"
scripts/ondevice-confinement.py

if [ "$AGENT" = 1 ]; then
  : "${UMBRA_VERIFY_PROVIDER:?set UMBRA_VERIFY_PROVIDER, UMBRA_VERIFY_KEY and UMBRA_VERIFY_MODEL for --agent}"
  : "${UMBRA_VERIFY_KEY:?set UMBRA_VERIFY_KEY}"
  : "${UMBRA_VERIFY_MODEL:?set UMBRA_VERIFY_MODEL}"
  step "model"
  ctl call provider.set "{\"id\":\"$UMBRA_VERIFY_PROVIDER\",\"key\":\"$UMBRA_VERIFY_KEY\",\"model\":\"$UMBRA_VERIFY_MODEL\"}" >/dev/null

  step "agent task: write and run a script in the Linux environment"
  id=$(ctl call session.create '{"request":"In the Linux environment, create a file hello.py that prints the numbers 1 to 5 separated by commas, run it, and tell me its exact output."}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
  ctl wait-session "$id" 900 --approve || fail "the task did not complete"

  step "agent task: operate the phone"
  id=$(ctl call session.create '{"request":"Open the Settings app, find the Battery page, and tell me what the battery percentage shows. Work in a background display if you can."}' | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
  ctl wait-session "$id" 900 --approve || fail "the task did not complete"
fi

if [ "$ACCEPT" = 1 ]; then
  step "acceptance (§33): fix the crash in fixtures/crashy-login"
  scripts/acceptance-crashy-login.sh
fi

step "done"
