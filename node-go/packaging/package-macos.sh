#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
version="${1:-0.4.4}"
out="$PWD/build/release"
mkdir -p "$out" build/macos
for arch in arm64 amd64; do
  CGO_ENABLED=1 GOOS=darwin GOARCH="$arch" MACOSX_DEPLOYMENT_TARGET=14.0 go build -trimpath -tags desktop,production -ldflags "-w -s -X app.umbra/node/internal/node.Version=$version" -o "build/macos/umbra-node-$arch" ./cmd/umbra-node
done
lipo -create build/macos/umbra-node-arm64 build/macos/umbra-node-amd64 -output "$out/umbra-node-$version-darwin-universal"
codesign --force --sign - --identifier app.umbra.node --options runtime "$out/umbra-node-$version-darwin-universal"
app="build/macos/Umbra Node.app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp "$out/umbra-node-$version-darwin-universal" "$app/Contents/MacOS/umbra-node"
cat > "$app/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>CFBundleName</key><string>Umbra Node</string><key>CFBundleDisplayName</key><string>Umbra Node</string><key>CFBundleIdentifier</key><string>app.umbra.node</string><key>CFBundleExecutable</key><string>umbra-node</string><key>CFBundlePackageType</key><string>APPL</string><key>CFBundleShortVersionString</key><string>$version</string><key>LSMinimumSystemVersion</key><string>14.0</string><key>NSHighResolutionCapable</key><true/><key>NSScreenCaptureUsageDescription</key><string>Share this computer's screen with your paired Umbra phone.</string></dict></plist>
PLIST
cp ../LICENSE "$app/Contents/Resources/LICENSE"
codesign --force --deep --sign - --identifier app.umbra.node "$app"
stage="build/macos/dmg-$version"
mkdir -p "$stage"
ditto "$app" "$stage/Umbra Node.app"
ln -s /Applications "$stage/Applications"
cat > "$stage/OPEN-FIRST.txt" <<'NOTE'
Drag Umbra Node into Applications, then open it to begin setup.
This build is ad-hoc signed and is not notarized. On first launch, right-click → Open, or use System Settings → Privacy & Security → Open Anyway.
將 Umbra Node 拖到 Applications 後打開，即可開始設定。此版本為 ad-hoc 簽署，未經公證；第一次請右鍵 → 打開，或到「系統設定 → 隱私權與安全性 → 仍要打開」。
ScreenCaptureKit requires macOS 14 or later. Screen Recording and Accessibility permissions are optional; without them the node can run jobs only.
NOTE
hdiutil create -quiet -volname "Umbra Node $version" -srcfolder "$stage" -ov -format UDZO "$out/umbra-node-$version-macos-universal.dmg"
codesign --force --sign - "$out/umbra-node-$version-macos-universal.dmg"
codesign --verify --deep --strict "$app"
file "$out/umbra-node-$version-darwin-universal"
