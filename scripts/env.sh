# shellcheck shell=bash
# Source this file. It selects a JDK >= 17 (preferring 21) and the Android SDK location.
#
# `/usr/libexec/java_home -v N` is not trusted on its own: on macOS it prints the default JDK and
# exits 0 when no JDK N is registered, so every candidate is verified by running `java -version`.

umbra_jdk_major() {
  "$1/bin/java" -version 2>&1 | awk -F'"' 'NR==1 { split($2, v, "."); print (v[1] == "1") ? v[2] : v[1]; exit }'
}

umbra_select_jdk() {
  local candidates=() h m
  [[ -n "${JAVA_HOME:-}" ]] && candidates+=("$JAVA_HOME")
  for v in 21 17; do
    h="$(/usr/libexec/java_home -v "$v" 2>/dev/null || true)"
    [[ -n "$h" ]] && candidates+=("$h")
  done
  candidates+=(
    /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
    /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
    /opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home
    /usr/local/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
    /usr/lib/jvm/java-21-openjdk-arm64
    /usr/lib/jvm/java-21-openjdk-amd64
    /usr/lib/jvm/java-17-openjdk-arm64
    /usr/lib/jvm/java-17-openjdk-amd64
  )
  for h in "${candidates[@]}"; do
    [[ -x "$h/bin/java" ]] || continue
    m="$(umbra_jdk_major "$h")"
    if [[ "$m" =~ ^[0-9]+$ ]] && (( m >= 17 )); then
      export JAVA_HOME="$h"
      export PATH="$JAVA_HOME/bin:$PATH"
      return 0
    fi
  done
  echo "No JDK >= 17 found. Install one (brew install openjdk@21) or set JAVA_HOME." >&2
  return 1
}

umbra_select_jdk || return 1 2>/dev/null || exit 1

if [[ -z "${ANDROID_HOME:-}" ]]; then
  for d in "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [[ -d "$d" ]] && export ANDROID_HOME="$d" && break
  done
fi
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
