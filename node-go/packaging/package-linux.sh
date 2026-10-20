#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
version="${1:-0.4.4}"
arch="${2:-amd64}"
out="$PWD/build/release"
mkdir -p "$out" "build/linux-$arch"
CGO_ENABLED=0 GOOS=linux GOARCH="$arch" go build -trimpath -ldflags "-w -s -X app.umbra/node/internal/node.Version=$version" -o "$out/umbra-node-$version-linux-$arch" ./cmd/umbra-node
cp "$out/umbra-node-$version-linux-$arch" "build/linux-$arch/umbra-node"
cp ../LICENSE README.md "build/linux-$arch/"
tar -czf "$out/umbra-node-$version-linux-$arch.tar.gz" -C "build/linux-$arch" umbra-node LICENSE README.md
if [ "${UMBRA_BUILD_APPIMAGE:-0}" != 1 ]; then exit 0; fi
triplet="$(gcc -dumpmachine)"
image_arch=x86_64
if [ "$arch" = arm64 ]; then image_arch=aarch64; fi
appdir="$PWD/build/linux-$arch/Umbra-Node.AppDir"
mkdir -p "$appdir/usr/bin" "$appdir/usr/lib"
CGO_ENABLED=1 GOOS=linux GOARCH="$arch" go build -trimpath -tags desktop,production,webkit2_41 -ldflags "-w -s -X app.umbra/node/internal/node.Version=$version" -o "$appdir/usr/bin/umbra-node" ./cmd/umbra-node
# Include WebKit helpers and the GTK/WebKit dependency closure. Driver and glibc libraries come from the host.
cp -a "/usr/lib/$triplet/webkit2gtk-4.1" "$appdir/usr/lib/webkit2gtk-4.1"
python3 - "$appdir" <<'PY'
import pathlib,subprocess,shutil,sys,re
root=pathlib.Path(sys.argv[1]); queue=list((root/'usr').rglob('*')); seen=set()
skip=re.compile(r'^(ld-linux|lib(c|m|pthread|dl|rt|resolv|nss_[^.]+|GL[^.]*|EGL|drm[^.]*|gbm)\.so)')
while queue:
 p=queue.pop()
 if not p.is_file():continue
 out=subprocess.run(['ldd',str(p)],text=True,capture_output=True).stdout
 for line in out.splitlines():
  match=re.search(r'=> (/\S+)',line)
  if not match:continue
  source=pathlib.Path(match[1])
  if source.name in seen or skip.match(source.name):continue
  seen.add(source.name);dest=root/'usr/lib'/source.name;shutil.copy2(source.resolve(),dest);queue.append(dest)
PY
cat > "$appdir/AppRun" <<'APP'
#!/bin/sh
set -eu
root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
export LD_LIBRARY_PATH="$root/usr/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
export WEBKIT_EXEC_PATH="$root/usr/lib/webkit2gtk-4.1"
export WEBKIT_INJECTED_BUNDLE_PATH="$root/usr/lib/webkit2gtk-4.1/injected-bundle"
if [ "$#" -eq 0 ]; then set -- setup --gui; fi
exec "$root/usr/bin/umbra-node" "$@"
APP
chmod +x "$appdir/AppRun"
cp packaging/umbra-node.svg "$appdir/umbra-node.svg"
cat > "$appdir/umbra-node.desktop" <<'DESKTOP'
[Desktop Entry]
Type=Application
Name=Umbra Node
Exec=umbra-node
Icon=umbra-node
Categories=Utility;Network;
Terminal=false
DESKTOP
: "${APPIMAGETOOL:?Set APPIMAGETOOL to the verified appimagetool binary}"
: "${APPIMAGE_RUNTIME:?Set APPIMAGE_RUNTIME to the verified type-2 runtime}"
ARCH="$image_arch" "$APPIMAGETOOL" --runtime-file "$APPIMAGE_RUNTIME" "$appdir" "$out/umbra-node-$version-linux-$arch.AppImage"
"$out/umbra-node-$version-linux-$arch.AppImage" --appimage-extract-and-run version
