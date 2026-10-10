#!/usr/bin/env python3
"""What the agent in the Linux environment can and cannot reach, tried for real on the device (spec §28, §31).

The agent runs as the app's own uid. This tries, from inside the environment and as that user, the things a
careless or manipulated agent could try, and fails if any of them works:

  * changing the system part of the environment (root enters it by running programs from it);
  * reaching the app's private folder (helper tokens, model keys) through /proc/<pid>/root of the app's process;
  * reading the app's environment, or attaching to it;
  * seeing other users' processes, and so the helpers' command lines.

Needs the debug build's control channel (scripts/dev-device.sh setup) and an installed environment.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(__file__))
import umbractl  # noqa: E402

SCRIPT = r"""
fail=0
check() { # description, command that must FAIL for the agent
  if bash -c "$2" >/dev/null 2>&1; then echo "OPEN   $1"; fail=1; else echo "closed $1"; fi
}
echo "user: $(id -un) ($(id -u)), umask $(umask)"
[ "$(umask)" = "0022" ] || { echo "OPEN   umask is not 022"; fail=1; }

check "create a file in /"                 'touch /agent-was-here'
check "create a folder in /"               'mkdir /agent-dir'
check "add a program to /usr/bin"          'touch /usr/bin/agent-was-here'
check "change /etc/passwd"                 'echo x >> /etc/passwd'
check "change setpriv (run as root to enter the guest)" 'echo x >> /usr/bin/setpriv'
check "change the pack installer (run as root)"         'echo x >> /opt/umbra/bin/umbra-pack'
check "change omp itself"                  'echo x >> /opt/bun/install/global/node_modules/@oh-my-pi/pi-coding-agent/dist/cli.js'

check "see the helpers (their command lines carry tokens)" 'ps -A -o args | grep -q "[S]erverMain"'
check "see processes of other users"      '[ "$(ps -A -o user= | sort -u | wc -l)" -gt 1 ]'
check "read init's command line"          'cat /proc/1/cmdline | grep -q .'

app=APP_PID
if ps -p "$app" >/dev/null 2>&1; then echo "OPEN   the app's process is visible"; fail=1; else echo "closed the app's process is not even listed"; fi
if [ -n "$app" ]; then
  echo "app process: $app"
  check "follow /proc/$app/root"                         "ls /proc/$app/root/"
  check "list the app's private folder"                  "ls /proc/$app/root/data/data/app.umbra.agent"
  check "read the app's environment"                     "cat /proc/$app/environ"
  check "read the app's memory map"                      "cat /proc/$app/maps"
  check "read the app's memory"                          "head -c 1 /proc/$app/mem"
  check "list the app's open files"                      "ls /proc/$app/fd"
fi
# And through any process of the same uid at all.
leaks=0
for p in $(ps -A -o pid,uid | awk -v u="$(id -u)" '$2==u{print $1}'); do
  [ "$p" = "$$" ] && continue
  if ls "/proc/$p/root/data/data/app.umbra.agent" >/dev/null 2>&1; then
    case "$(readlink /proc/$p/root 2>/dev/null)" in /) echo "OPEN   /proc/$p/root shows the phone's files"; leaks=1;; esac
  fi
done
[ $leaks = 0 ] && echo "closed no process of this user leads to the phone's files"
[ $leaks = 0 ] || fail=1
exit $fail
"""


def main():
    bridge = umbractl.Bridge()
    bridge.control("guest.status")  # prepares the environment the way the start of a task does
    pid = bridge.control("status")["pid"]
    out = bridge.control("guest.exec", {"argv": ["bash", "-c", SCRIPT.replace("APP_PID", str(pid))], "timeout_ms": 120000})
    print(out["stdout"])
    if out["stderr"].strip():
        print(out["stderr"])
    root_cwd = bridge.control("guest.exec_root", {"argv": ["pwd"], "timeout_ms": 30000})["stdout"].strip()
    print("root starts in:", root_cwd)
    if root_cwd != "/":
        print("OPEN   root starts in a folder the agent can write to")
        return 1
    if out["exit"] != 0:
        print("\nFAILED: something the agent should not reach is open")
        return 1
    print("\nOK: the system part of the environment and the app's private files are out of the agent's reach")
    return 0


if __name__ == "__main__":
    sys.exit(main())
