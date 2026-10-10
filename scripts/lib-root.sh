# Source this: it defines `rootsh '<command>'`, which runs a command as root on the device reached through adb,
# whichever way its `su` wants to be called. Emulator images take `su 0 sh -c '...'`; Magisk and KernelSU take
# `su -c '...'` (or `su root -c '...'`). The first form that answers uid 0 is remembered for the rest of the script.
# The command must not contain single quotes.
: "${ADB:?set ADB before sourcing lib-root.sh}"

_su_run() { # form, command
  case "$1" in
    1) "$ADB" shell "su 0 sh -c '$2'" ;;
    2) "$ADB" shell "su -c '$2'" ;;
    3) "$ADB" shell "su root -c '$2'" ;;
  esac
}

rootsh() {
  if [ -z "${UMBRA_SU_FORM:-}" ]; then
    local f
    for f in 1 2 3; do
      if [ "$(_su_run "$f" 'id -u' 2>/dev/null | tr -d '\r')" = "0" ]; then UMBRA_SU_FORM=$f; break; fi
    done
    [ -n "${UMBRA_SU_FORM:-}" ] || { echo "the device does not give root to adb through su (tried: su 0 sh -c, su -c, su root -c)" >&2; return 1; }
  fi
  _su_run "$UMBRA_SU_FORM" "$1"
}
