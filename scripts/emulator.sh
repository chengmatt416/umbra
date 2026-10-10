#!/usr/bin/env bash
# Starts, stops and checks the Android 16 emulator used for development and for the live-device tests
# (AVD "umbra36": google_apis arm64, API 36.1, rootable via `adb shell su 0`).
#
#   scripts/emulator.sh start     boot headless and wait until it is usable (UMBRA_GPU=swiftshader_indirect for software
#                                 rendering if the host GPU path misbehaves; it is far slower, and Android 16's Settings
#                                 is a Compose UI that is hard work for it)
#   scripts/emulator.sh stop      shut it down (frees ~3 GB; stop it while building the app on a small Mac)
#   scripts/emulator.sh status    print the boot state
#   scripts/emulator.sh trim      switch off the preinstalled Google apps (Play Store, Photos, Maps, ...; once per AVD, it
#                                 survives restarts). The 2 GB guest has no memory to spare for them. Play services stay:
#                                 Settings' own pages expect it.
set -euo pipefail

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$SDK/platform-tools/adb"
AVD="${UMBRA_AVD:-umbra36}"
LOG="${TMPDIR:-/tmp}/umbra-emulator.log"

booted() { [ "$("$ADB" -s emulator-5554 shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; }

case "${1:-status}" in
  start)
    if booted; then echo "already running"; exit 0; fi
    nohup "$SDK/emulator/emulator" -avd "$AVD" -no-window -no-audio -no-snapshot -no-boot-anim \
      -gpu "${UMBRA_GPU:-host}" -netdelay none -netspeed full >"$LOG" 2>&1 &
    for _ in $(seq 1 120); do
      if booted; then
        "$ADB" shell 'input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard' >/dev/null 2>&1 || true
        echo "booted (log: $LOG)"; exit 0
      fi
      sleep 5
    done
    echo "emulator did not boot within 10 minutes; see $LOG" >&2; exit 1 ;;
  stop)
    "$ADB" emu kill >/dev/null 2>&1 || true
    sleep 3
    pkill -f "qemu-system-aarch64.*$AVD" >/dev/null 2>&1 || true
    echo "stopped" ;;
  status)
    if booted; then echo "booted"; else echo "not running"; fi ;;
  trim)
    for p in com.android.vending com.google.android.as com.google.android.as.oss \
             com.google.android.partnersetup com.google.android.setupwizard com.google.android.configupdater com.google.android.feedback \
             com.google.android.ondevicepersonalization.services com.google.android.healthconnect.controller com.google.android.apps.wallpaper \
             com.google.android.adservices.api com.google.android.dialer com.google.android.apps.messaging com.google.android.apps.photos \
             com.google.android.youtube com.google.android.apps.youtube.music com.google.android.gm com.google.android.apps.maps \
             com.google.android.calendar com.google.android.contacts com.google.android.apps.docs com.android.chrome \
             com.google.android.apps.safetyhub com.google.android.googlequicksearchbox com.google.android.apps.wellbeing com.android.stk \
             com.google.android.apps.restore com.google.android.projection.gearhead; do
      "$ADB" shell "pm disable-user --user 0 $p" >/dev/null 2>&1 || true
    done
    echo "preinstalled Google apps disabled" ;;
  *) echo "usage: $0 start|stop|status|trim" >&2; exit 2 ;;
esac
