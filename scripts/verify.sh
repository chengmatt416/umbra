#!/usr/bin/env bash
# Host-side verification: Gradle build + unit tests + lint, then a check that every test actually ran.
# No device or emulator required. Extra arguments are passed to Gradle
# (e.g. scripts/verify.sh --tests '*Rpc*').
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=scripts/env.sh
source scripts/env.sh

echo "JAVA_HOME=$JAVA_HOME ANDROID_HOME=${ANDROID_HOME:-<unset>}"
# The real-omp tests run against the omp version the product pins, whatever omp this machine has globally.
if [ -z "${OMP_BIN:-}" ] && command -v bun >/dev/null 2>&1; then
  OMP_BIN=$(scripts/omp-pinned.sh) && export OMP_BIN && echo "OMP_BIN=$OMP_BIN ($("$OMP_BIN" --version))"
fi
./gradlew --console=plain check assembleDebug "$@"
# Fails on failing tests, modules whose tests did not run, and non-void @Test methods JUnit skips.
python3 scripts/test-summary.py
