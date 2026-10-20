# Umbra Node

Go companion for Umbra on macOS and Linux. The phone's **Nodes** menu is available in Simple, Power and Dev modes.

Download the desktop edition and open it, or install with `curl -fsSL https://umbra-agent.pages.dev/node | sh`.
The installer checks a pinned SHA-256 and the release's minisign signature before installing to `~/.local/bin`.

```
umbra-node setup --gui       # desktop edition
umbra-node setup --tui       # keyboard-only, including headless Linux
umbra-node status
umbra-node serve             # foreground runner, no pairing window by default
umbra-node uninstall --confirm
```

Setup checks the computer, opens a ten-minute pairing window, optionally enables screen access, shows detected
LAN/VPN addresses and phone contact, configures login startup, and reports the resulting state. Pairing requires
the 12-character code/QR and approval of the phone's name on the computer. New phone-side nodes have limited trust.
The GUI and terminal interface use the same private Unix-socket service and persisted configuration.

The protocol remains ADR 0018's newline JSON over pinned mutual TLS 1.3. Jobs receive manifest-listed files only,
verify SHA-256 before execution, use a minimal environment and a renewable offline lease, and are reconciled rather
than assumed finished after disconnection. Pairing is identity, not attestation or an OS sandbox: commands execute
as the computer's user. Each phone can inspect only the jobs it submitted through the protocol. Revoking a phone
blocks subsequent requests and screen streams; existing jobs remain bounded by their leases. Uninstall removes
login startup and pairings, preserving job files.

macOS 14+ uses ScreenCaptureKit only while a screen is connected, and requires Screen Recording permission;
pointer/keyboard events also require Accessibility. Linux supports installed `x11vnc` on X11 and `wayvnc` on
wlroots Wayland, launched on loopback only. GNOME installations that offer only RDP can run jobs without VNC.
VNC is carried inside a separate authenticated mTLS connection, with no public VNC listener. Phone agents also
require Owned trust and screen permission on both devices; user takeover closes agent screen connections and
input operations use the existing phone approval broker.

Desktop daemons are copied to a durable private path before launch so ejecting the DMG or closing an AppImage
does not leave login startup pointing at a temporary mount. Existing JVM node PKCS12 identities and pairing pins
are preserved using `openssl`; stop the old JVM server before starting the replacement. Job files are preserved.

Build: Go 1.26.3; `go test -race ./...`; `go vet ./...`. Desktop builds use `-tags desktop,production`, plus
`webkit2_41` and GTK3/WebKitGTK 4.1 development packages on Linux. `packaging/package-macos.sh` creates an ad-hoc
signed universal DMG (not notarized). `packaging/package-linux.sh` produces static CLI binaries/tarballs;
`UMBRA_BUILD_APPIMAGE=1` also creates GTK/WebKit desktop AppImages on a native Linux builder. Desktop AppImages
target glibc 2.39+ (Ubuntu 24.04+); static CLI builds work without a desktop or JVM. Installer/public download
metadata includes separate checksums and minisign signatures for each artifact.

Kotlin parity tests: build `build/umbra-node`, then run `UMBRA_GO_NODE="$PWD/node-go/build/umbra-node" ./gradlew
:node-runner:test`. These exercise the production Kotlin phone client against the real Go TLS server, including
JVM identity migration, host-confirmed pairing, manifest upload, job output, lease expiry and revocation.
