#!/usr/bin/env bash
# The §33 acceptance task on the real app: the agent finds and fixes the login crash in
# fixtures/crashy-login, builds and reinstalls it, and shows the fix works. The result is then checked
# independently of what the agent says: the source is rebuilt here, installed, signed in to through the
# real UI, and the log is searched for crashes. Needs a model (see scripts/ondevice-verify.sh).
set -euo pipefail
cd "$(dirname "$0")/.."
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"; ADB="$SDK/platform-tools/adb"
# shellcheck source=scripts/lib-root.sh
source scripts/lib-root.sh
PKG=app.umbra.agent; FIXTURE=app.umbra.fixture.crashylogin
ctl() { scripts/umbractl.py "$@"; }
fail() { printf 'FAILED: %s\n' "$*" >&2; exit 1; }

echo "-- put the project in a new workspace"
ws=$(ctl call workspace.create '{"name":"crashy-login"}')
id=$(echo "$ws" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
dest=$(echo "$ws" | python3 -c 'import json,sys; print(json.load(sys.stdin)["host_path"])')
uid=$("$ADB" shell pm list packages -U $PKG | tr -d '\r' | sed -n 's/.*uid:\([0-9]*\).*/\1/p')
COPYFILE_DISABLE=1 tar -C fixtures/crashy-login -cf /tmp/crashy-login.tar AndroidManifest.xml build.sh README.md src   # no macOS ._* files
"$ADB" push /tmp/crashy-login.tar /data/local/tmp/crashy-login.tar >/dev/null
rootsh "mkdir -p $dest && tar -xf /data/local/tmp/crashy-login.tar -C $dest && chown -R $uid:$uid $dest"
"$ADB" shell "rm -f /data/local/tmp/crashy-login.tar"
"$ADB" shell "pm uninstall $FIXTURE" >/dev/null 2>&1 || true

echo "-- toolchain for building Android apps in the Linux environment (a few minutes, once)"
ctl call guest.exec_root '{"argv":["bash","-c","umbra-pack install android"],"timeout_ms":2400000}' >/dev/null

echo "-- run the task"
task="This project is in /workspace/projects/$id (an Android app, package $FIXTURE). Signing in as demo / umbra123 crashes the app. Build it with ./build.sh, install the apk on the phone, reproduce the crash by operating the app in a background display, read the log to find the cause, fix the source, rebuild and reinstall, and show that signing in now works. Reply with what was wrong, the code change, and the evidence it works."
sid=$(ctl call session.create "{\"request\":$(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$task"),\"workspace\":\"$id\"}" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')
ctl wait-session "$sid" 2700 --approve || fail "the agent did not finish the task"

echo "-- verify the outcome independently"
grep -q 'lastUser.equals' "$dest/src/app/umbra/fixture/crashylogin/MainActivity.java" 2>/dev/null && fail "the null dereference is still in the source" || true
ctl call guest.exec "{\"argv\":[\"bash\",\"-c\",\"cd /workspace/projects/$id && ./build.sh\"],\"timeout_ms\":600000}" >/dev/null
rootsh "cp $dest/build/crashy-login.apk /data/local/tmp/verify.apk && chmod 644 /data/local/tmp/verify.apk"
"$ADB" shell pm install -r /data/local/tmp/verify.apk >/dev/null
"$ADB" logcat -c
"$ADB" shell am force-stop $FIXTURE
"$ADB" shell am start -n $FIXTURE/.MainActivity >/dev/null
sleep 3
python3 - <<'PY'
import json, subprocess, sys, os
sys.path.insert(0, "scripts")
import umbractl
b = umbractl.Bridge()
def tool(name, **args):
    r = b.tool(name, args, session="acceptance")
    return "\n".join(c.get("text", "") for c in r.get("content", []))
tool("android_launch", package="app.umbra.fixture.crashylogin")
tool("android_tap", text="Username")
tool("android_type", text="demo")
tool("android_tap", text="Password")
tool("android_type", text="umbra123", secret=True)
tool("android_tap", text="Log in")
screen = tool("android_observe", level="brief") + tool("android_observe")
if "Welcome" not in screen:
    print(screen); sys.exit("signing in did not show the welcome message")
print("signing in works: the welcome message is on screen")
PY
if "$ADB" logcat -d -b crash,main | grep -q "FATAL EXCEPTION.*\|AndroidRuntime.*$FIXTURE"; then fail "the app crashed again"; fi
echo "ACCEPTANCE OK: the agent fixed the crash, and the rebuilt app signs in without crashing"
