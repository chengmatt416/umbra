#!/usr/bin/env bash
# Sets up a debug build of the app on a device or emulator reached through adb, the way the app's own
# onboarding does it with root, so tools can be exercised by hand.
#
#   scripts/dev-device.sh install     install the debug APK (build it first: ./gradlew :app:assembleDebug) and pre-verify its code,
#                                     which an emulator does not do at install time (a first launch would take minutes and ANR)
#   scripts/dev-device.sh grant       apply the system grants (accessibility, notification access, battery, ...)
#   scripts/dev-device.sh helper      start the privileged helper as the shell user and tell the app about it
#   scripts/dev-device.sh roothelper  also start a helper that runs as root, for an emulator image whose `su` the app
#                                     cannot call (the Linux environment then starts its agent process through it)
#   scripts/dev-device.sh bridge      switch the loopback tool bridge on and forward it to this machine
#                                     (prints "PORT TOKEN"; speak JSON lines to 127.0.0.1:PORT, see docs/DEVELOPMENT.md)
#   scripts/dev-device.sh setup       install + grant + helper + roothelper, then switch the control channel on: the whole dev setup
#   scripts/dev-device.sh rootfs [archive]
#                                     put the Linux environment on the device (default build/rootfs/umbra-rootfs-dev-arm64.tar.xz):
#                                     the app decompresses it and unpacks it as root, exactly as the Set up screen does
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}}"
ADB="$SDK/platform-tools/adb"
PKG=app.umbra.agent
ACC="$PKG/app.umbra.platform.android.UmbraAccessibilityService"
NOTIF="$PKG/app.umbra.platform.android.UmbraNotificationListener"
# shellcheck source=scripts/lib-root.sh
source scripts/lib-root.sh
token() { head -c 16 /dev/urandom | xxd -p; }

case "${1:-}" in
  install)
    "$ADB" install -r -t app/build/outputs/apk/debug/app-debug.apk
    "$ADB" shell "cmd package compile -m verify -f $PKG" >/dev/null ;;
  grant)
    "$ADB" shell "am force-stop $PKG; appops set $PKG ACCESS_RESTRICTED_SETTINGS allow; pm grant $PKG android.permission.WRITE_SECURE_SETTINGS; pm grant $PKG android.permission.READ_LOGS; pm grant $PKG android.permission.POST_NOTIFICATIONS; appops set $PKG SYSTEM_ALERT_WINDOW allow; settings put secure enabled_accessibility_services $ACC; settings put secure accessibility_enabled 1; cmd notification allow_listener $NOTIF; cmd deviceidle whitelist +$PKG; settings put global settings_enable_monitor_phantom_procs false; am start -n $PKG/.MainActivity" ;;
  helper)
    port=$((41000 + RANDOM % 2000)); tok=$(token)
    apk=$("$ADB" shell pm path $PKG | tr -d '\r' | sed 's/^package://')
    # Separate invocations: a pkill -f pattern also matches the shell that carries it in its own command line.
    "$ADB" shell "pkill -f app.umbra.platform.server.ServerMain" || true
    "$ADB" shell "UMBRA_HELPER_TOKEN=$tok CLASSPATH=$apk setsid nohup app_process /system/bin app.umbra.platform.server.ServerMain --port $port >/data/local/tmp/umbra-helper.log 2>&1 &"
    sleep 3
    "$ADB" shell "cat /data/local/tmp/umbra-helper.log"
    "$ADB" shell "am broadcast -a app.umbra.agent.dev.HELPER_CONFIGURE --es token $tok --ei port $port -p $PKG" | grep -E 'data=' ;;
  roothelper)
    port=$((43000 + RANDOM % 2000)); tok=$(token)
    apk=$("$ADB" shell pm path $PKG | tr -d '\r' | sed 's/^package://')
    # Only root's own helper: the shell user's helper (started by `helper`) must survive.
    rootsh 'pkill -u 0 -f "ServerMain --port"; true' || true
    rootsh "UMBRA_HELPER_TOKEN=$tok CLASSPATH=$apk setsid nohup app_process /system/bin app.umbra.platform.server.ServerMain --port $port >/data/local/tmp/umbra-root-helper.log 2>&1 &"
    sleep 3
    "$ADB" shell "cat /data/local/tmp/umbra-root-helper.log"
    "$ADB" shell "am broadcast -a app.umbra.agent.dev.ROOT_HELPER_CONFIGURE --es token $tok --ei port $port -p $PKG" | grep -E 'data=' ;;
  bridge)
    port=$((47000 + RANDOM % 900)); tok=$(token)
    "$ADB" shell "am broadcast -a app.umbra.agent.dev.BRIDGE_START --es token $tok --ei port $port -p $PKG" | grep -E 'data=' >&2
    "$ADB" forward tcp:$port tcp:$port >/dev/null
    echo "$port $tok" ;;
  rootfs)
    archive="${2:-build/rootfs/umbra-rootfs-dev-arm64.tar.xz}"
    [ -f "$archive" ] || { echo "no archive at $archive (build it: runtime-build/rootfs/build.sh dev)" >&2; exit 1; }
    uid=$("$ADB" shell pm list packages -U $PKG | tr -d '\r' | sed -n 's/.*uid:\([0-9]*\).*/\1/p')
    dest=/data/user/0/$PKG/files/rootfs-upload.tar.xz
    "$ADB" push "$archive" /data/local/tmp/umbra-rootfs-upload.tar.xz
    rootsh "mkdir -p /data/user/0/$PKG/files && cp /data/local/tmp/umbra-rootfs-upload.tar.xz $dest && chown $uid:$uid $dest && chmod 600 $dest"
    "$ADB" shell "rm -f /data/local/tmp/umbra-rootfs-upload.tar.xz"
    scripts/umbractl.py setup >/dev/null
    scripts/umbractl.py call guest.install_file "{\"path\":\"$dest\"}"
    rootsh "rm -f $dest" ;;
  setup)
    "$0" install
    "$0" grant >/dev/null
    sleep 12
    "$0" helper >/dev/null
    "$0" roothelper >/dev/null
    # A freshly installed app on a busy emulator sometimes loses the accessibility setting again while the system
    # settles; the grant is idempotent, so apply it once more when it is gone.
    if ! "$ADB" shell "settings get secure enabled_accessibility_services" | grep -q "$ACC"; then
      "$0" grant >/dev/null
      sleep 10
    fi
    scripts/umbractl.py setup
    scripts/umbractl.py call status ;;
  *) echo "usage: $0 install|grant|helper|roothelper|bridge|setup|rootfs" >&2; exit 2 ;;
esac
