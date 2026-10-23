# Umbra 0.4.4 release verification

Released 2026-10-21. Version code **11**. The shared workspace retains its existing HEAD and index;
implementation changes were merged without discarding the concurrent legal/notice work.

- Production: https://umbra-agent.pages.dev/en/
- Remote nodes: https://umbra-agent.pages.dev/en/nodes/ and https://umbra-agent.pages.dev/zh-tw/nodes/
- Immutable Pages deployment: https://57838e8f.umbra-agent.pages.dev
- APK: https://umbra-agent.pages.dev/download/umbra-0.4.4.apk
- APK bytes: `852849082`
- APK SHA-256: `afb116c8df52415d36b059b98e0deecb867416af148dcaedf7fbb00b80e90800`
- Android signer SHA-256: `b38060dee29910fb73d15cb75fe4b557708d85d37cb2c3bdbe6a5ecc3a2231c3`
- Bundled Linux image and offline package archive: `0.4.0`.

## Implementation

Independent Laya risk review feeds the existing Settings approval policy for phone actions, coding tools,
and remote-node actions. It can add risk, but cannot remove static safeguards. Unavailable, timed-out,
malformed or uncertain answers retain static policy enforcement. Secret typing and environment values are
withheld. Shell stdin/working-directory context is classified, and lockdown is rechecked before dispatch.
Android permits local Laya HTTP on loopback while retaining the external HTTP prohibition.

Remote nodes are exposed in Simple, Power and Dev modes. The macOS/Linux companion provides desktop and
terminal setup, host-confirmed QR/code pairing over pinned mutual TLS, jobs and screen access. Linux VNC
backends use a private Unix socket. Downloads include signed macOS DMG, Linux AppImages and headless
binaries/tarballs for both Linux architectures, plus the verified installer.

## Verification performed

- Android debug and production release builds, app unit tests, and debug/release lint passed.
- Laya policy, static-floor, unavailable/uncertain response, cancellation, redaction and lockdown
  regression tests passed. The real pinned omp test verifies that a denied independent review prevents a
  coding file write even under Autonomous policy.
- All ten `OnDeviceLiveTest` scenarios passed in the disposable emulator.
- A real Android HTTP client reached a local Laya protocol fixture: all nine risk/Settings combinations
  passed, loopback HTTP was permitted and external HTTP remained blocked. This tests integration, not
  live model quality.
- Real Android/Go pairing and synthetic RFB viewing/takeover passed in all three UI modes; viewing sent
  no input, while takeover delivered pointer and keyboard events to the fixture.
- Production-signed full APK installed over the existing app and exposed Nodes. The first smoke check
  was interrupted by emulator System UI ANR; the recovered emulator passed. No app crash was observed.
- Go race tests and vet passed. Linux amd64/arm64 CI run `39505208694` passed, including real Xvfb/x11vnc
  Unix-socket RFB smoke coverage. Kotlin/Go protocol parity passed without skipping.
- macOS universal package signing was verified. Installer tests accepted valid releases with either
  minisign or the built-in verifier, and rejected binary/signature tampering.
- `website/tools/test-node-downloads.mjs` passed. Public `verify-release.mjs` and
  `verify-node-release.mjs` passed, including full download hashes, companion signatures, HEAD/Range,
  latest redirect, feed, installer and both languages. The temporary APK uploader was deleted.

## Limits

macOS packages are ad-hoc signed and not notarized. No host Screen Recording or Accessibility permission
was granted during verification; screen/input transport used synthetic fixtures. Actual desktop capture,
provider-account behavior and live Laya inference were not claimed as tested. The separate Cloudflare
skill audit uses an immutable source snapshot and its own evidence rules; these development checks are
not audit reproduction evidence. Existing `docs/RELEASE_LEGAL_REVIEW.md` requirements remain applicable;
this record does not declare binary-distribution legal clearance.
