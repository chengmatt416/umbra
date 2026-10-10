#!/usr/bin/env bash
# Builds the Debian arm64 guest and packs it as build/rootfs/umbra-rootfs-<version>-arm64.tar.xz, and the add-ons
# (guest/packs) as an offline apt repository, build/rootfs/umbra-packs-<version>-arm64.tar (ADR 0011). Both get a
# .sha256; manifest.json describes them. The APK carries both, so a phone needs no download for any of it.
# Needs Docker. Usage: runtime-build/rootfs/build.sh [version]
set -euo pipefail
cd "$(dirname "$0")/../.."
VERSION="${1:-dev}"
OUT=build/rootfs
IMAGE="umbra-rootfs:$VERSION"
mkdir -p "$OUT"

# Nothing here needs registry credentials (the base image is imported, everything else is downloaded inside
# the build), and Docker Desktop's credential helper can hang the CLI indefinitely when its keychain is
# locked. So use a private, credential-free CLI config and talk to the same daemon.
DOCKER_HOST="${DOCKER_HOST:-$(docker context inspect --format '{{.Endpoints.docker.Host}}' 2>/dev/null || echo unix:///var/run/docker.sock)}"
DOCKER_CONFIG="$(mktemp -d)"
export DOCKER_HOST DOCKER_CONFIG
echo '{"auths":{}}' > "$DOCKER_CONFIG/config.json"
trap 'rm -rf "$DOCKER_CONFIG"' EXIT

# The base image: Debian's official docker rootfs for arm64. Importing it avoids depending on a registry
# pull, which some networks and proxies stall on; it is the same tarball `docker pull debian` unpacks.
BASE=umbra-debian-base:trixie
if ! docker image inspect "$BASE" >/dev/null 2>&1; then
  BASE_TAR="$OUT/debian-trixie-slim-arm64.tar.gz"
  [ -s "$BASE_TAR" ] || curl -fL --retry 5 --retry-all-errors --retry-delay 3 -o "$BASE_TAR" \
    "https://raw.githubusercontent.com/debuerreotype/docker-debian-artifacts/dist-arm64v8/trixie/slim/oci/blobs/rootfs.tar.gz"
  docker import --platform linux/arm64 --change 'CMD ["bash"]' "$BASE_TAR" "$BASE"
fi

# The classic builder uses the local base image as is; BuildKit would first try to resolve it on Docker Hub.
DOCKER_BUILDKIT=0 docker build --platform linux/arm64 -f runtime-build/rootfs/Dockerfile -t "$IMAGE" guest/

# Flatten the image into a rootfs tarball: export a stopped container, which is the filesystem without layers.
cid=$(docker create --platform linux/arm64 "$IMAGE" /bin/true)
trap 'docker rm -f "$cid" >/dev/null 2>&1 || true; rm -rf "$DOCKER_CONFIG"' EXIT
docker export "$cid" | xz -T0 -6 > "$OUT/umbra-rootfs-$VERSION-arm64.tar.xz"
# The archive is unpacked by Android's toybox tar; refuse to publish one it cannot restore faithfully.
python3 runtime-build/rootfs/check-tar.py "$OUT/umbra-rootfs-$VERSION-arm64.tar.xz"
( cd "$OUT" && shasum -a 256 "umbra-rootfs-$VERSION-arm64.tar.xz" > "umbra-rootfs-$VERSION-arm64.tar.xz.sha256" )

# ---- the add-ons as an offline repository ----
# Every package the packs need on top of this image (their union, so any combination installs), resolved against the
# image itself, plus the Android files Debian does not package. Installed on the phone from the APK, never downloaded.
REPO_DIR="$OUT/umbra-packs-$VERSION"
REPO_TAR="$OUT/umbra-packs-$VERSION-arm64.tar"
rm -rf "$REPO_DIR" && mkdir -p "$REPO_DIR/extras"
PACKAGES=$(cat guest/packs/*.apt | grep -v '^#' | sort -u | tr '\n' ' ')
docker run --rm --platform linux/arm64 -v "$PWD/$REPO_DIR:/repo" "$IMAGE" bash -c "
  set -e
  apt-get update -qq
  mkdir -p /repo/partial
  apt-get install -y -qq --download-only --no-install-recommends -o Dir::Cache::archives=/repo $PACKAGES
  rm -rf /repo/partial /repo/lock
  # Only now (the download set must not count it as installed): the index generator.
  apt-get install -y -qq --no-install-recommends apt-utils >/dev/null
  cd /repo && apt-ftparchive packages . > Packages
"
eval "$(grep -E '^(R8_VERSION|R8_SHA256|PLATFORM_ZIP|PLATFORM_SHA1)=' guest/packs/android.post)"
curl -fsSL --retry 5 --retry-all-errors --retry-delay 3 -o "$REPO_DIR/extras/r8-$R8_VERSION.jar" "https://dl.google.com/dl/android/maven2/com/android/tools/r8/$R8_VERSION/r8-$R8_VERSION.jar"
echo "$R8_SHA256  $REPO_DIR/extras/r8-$R8_VERSION.jar" | shasum -a 256 -c -
curl -fsSL --retry 5 --retry-all-errors --retry-delay 3 -o "$REPO_DIR/extras/$PLATFORM_ZIP" "https://dl.google.com/android/repository/$PLATFORM_ZIP"
echo "$PLATFORM_SHA1  $REPO_DIR/extras/$PLATFORM_ZIP" | shasum -a 1 -c -

# Proof that it is complete: a container without any network installs every pack from it alone.
docker run --rm --network none --platform linux/arm64 -v "$PWD/$REPO_DIR:/var/cache/umbra-repo:ro" "$IMAGE" bash -c '
  set -e
  for f in /opt/umbra/packs/*.apt; do
    p=$(basename "$f" .apt)
    umbra-pack install "$p" > "/tmp/$p.log" 2>&1 || { tail -30 "/tmp/$p.log"; echo "OFFLINE INSTALL FAILED: $p" >&2; exit 1; }
    echo "offline install ok: $p"
  done
  gcc --version | head -1; java -version 2>&1 | head -1; node --version; command -v Xvfb; d8 --version | head -1; aapt2 version
'
# macOS tar would add ._* resource-fork files.
( cd "$REPO_DIR" && COPYFILE_DISABLE=1 tar -cf "../$(basename "$REPO_TAR")" . )
( cd "$OUT" && shasum -a 256 "$(basename "$REPO_TAR")" > "$(basename "$REPO_TAR").sha256" )
rm -rf "$REPO_DIR"

versions=$(docker run --rm --platform linux/arm64 "$IMAGE" bash -c 'echo "{\"debian\":\"$(cat /etc/debian_version)\",\"bun\":\"$(bun --version)\",\"omp\":\"$(omp --version | cut -d/ -f2)\",\"python\":\"$(python3 --version | cut -d" " -f2)\",\"git\":\"$(git --version | cut -d" " -f3)\"}"')
size=$(stat -f%z "$OUT/umbra-rootfs-$VERSION-arm64.tar.xz" 2>/dev/null || stat -c%s "$OUT/umbra-rootfs-$VERSION-arm64.tar.xz")
packs_size=$(stat -f%z "$REPO_TAR" 2>/dev/null || stat -c%s "$REPO_TAR")
echo "{\"version\":\"$VERSION\",\"arch\":\"arm64\",\"bytes\":$size,\"packsBytes\":$packs_size,\"components\":$versions}" > "$OUT/manifest.json"
cat "$OUT/manifest.json"
