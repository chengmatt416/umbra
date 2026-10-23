# Third-party software and assets

Umbra's original code is Copyright (c) 2026 鄭品彥 and licensed under [MIT](LICENSE).
Third-party code, fonts, programs, libraries and media retain their own copyrights and licenses.
The MIT license does not relicense those components. Names and logos do not imply endorsement.

## Android app and node runner

The exact resolved runtime coordinates, versions, declared licenses and artifact SHA-256 values are in
[third_party/gradle/inventory.json](third_party/gradle/inventory.json). Original license and notice files
found inside those artifacts are preserved in [third_party/gradle/notices](third_party/gradle/notices).
This includes transitive dependencies, rather than only the direct dependencies below.

| Project | License | Upstream |
| --- | --- | --- |
| AndroidX: Activity, Annotation, Collection, Compose UI/Foundation/Runtime/Material/Material3/icons, Core, Lifecycle, Navigation, SavedState and other resolved AndroidX modules | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin standard library and kotlinx.coroutines / kotlinx.serialization | Apache-2.0 | https://github.com/JetBrains/kotlin, https://github.com/Kotlin/kotlinx.coroutines, https://github.com/Kotlin/kotlinx.serialization |
| OkHttp and Okio | Apache-2.0 | https://github.com/square/okhttp, https://github.com/square/okio |
| ZXing | Apache-2.0 | https://github.com/zxing/zxing |
| Backdrop / AndroidLiquidGlass, including modified catalog examples | Apache-2.0; Copyright 2025 Kyant | https://github.com/Kyant0/AndroidLiquidGlass; modifications documented in [third_party/backdrop](third_party/backdrop/README.md) |
| Shizuku API, provider, AIDL and shared library | MIT | https://github.com/RikkaApps/Shizuku-API; [original copyright/license](third_party/shizuku/LICENSE) |
| AndroidHiddenApiBypass | Apache-2.0 | https://github.com/LSPosed/AndroidHiddenApiBypass |
| libsu | Apache-2.0 | https://github.com/topjohnwu/libsu |
| XZ for Java | 0BSD (version 1.10) | https://tukaani.org/xz/java.html; [full license](third_party/xz/COPYING) |
| Guava ListenableFuture | Apache-2.0 | https://github.com/google/guava |
| JSpecify and JetBrains annotations | Apache-2.0 | https://github.com/jspecify/jspecify, https://github.com/JetBrains/java-annotations |
| thinking-orbs, native adaptation at upstream commit de85557 | MIT; Copyright 2026 Jakub Antalik | https://github.com/Jakubantalik/thinking-orbs; original notice in `app/src/main/assets/licenses/thinking-orbs.txt` |
| Geist and Newsreader fonts (unmodified) | SIL Open Font License 1.1 | [font sources, checksums and full licenses](third_party/fonts/README.md) |

Some Maven POMs omit their license or inherit it from a parent. `UNKNOWN` in the inventory means the
collector found no direct declaration, not permission to redistribute. ZXing core 3.5.3 and Guava
listenablefuture 1.0 are Apache-2.0; their upstream projects and embedded notices are the additional evidence.
Keep original notices even when several dependencies use the same license.

## Linux programs distributed inside the APK

| Component | Version / license | Upstream and materials |
| --- | --- | --- |
| Debian trixie and offline add-on packages | Per-package licenses, including GPL, LGPL, BSD, MIT, Apache and other terms | https://www.debian.org/; each package's `/usr/share/doc/<package>/copyright` and `/usr/share/common-licenses/` remain in the image/packages |
| oh-my-pi (`@oh-my-pi/pi-coding-agent`) and its dependencies | omp 18.12.1, MIT; transitive packages have individual licenses | https://github.com/can1357/oh-my-pi; [original omp copyright/license](third_party/omp/LICENSE) |
| Bun and its embedded libraries, including JavaScriptCore/WebKit | Bun 1.3.14, MIT; embedded components include LGPL and other licenses | https://github.com/oven-sh/bun/tree/bun-v1.3.14; https://bun.com/docs/project/license |
| PRoot | Termux 5.1.107.97, GPL-2.0-or-later | https://github.com/termux/proot; [packaging and source requirements](third_party/proot/README.md) |
| talloc | 2.5.0, LGPL-3.0-or-later | https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz |
| libandroid-shmem | 0.7, BSD-3-Clause | https://github.com/termux/libandroid-shmem/tree/v0.7; [full copyright/license](third_party/proot/libandroid-shmem-LICENSE) |
| R8 / D8 | 9.4.28, BSD-3-Clause with bundled third-party notices | https://r8.googlesource.com/r8/; preserve the distributed JAR's licenses and notices |
| Android SDK platform ZIP | `platform-34-ext7_r03.zip`; SDK agreement and component-specific notices | https://developer.android.com/studio/terms; redistribution clearance is outstanding; see [release review](docs/RELEASE_LEGAL_REVIEW.md) |

The build's Debian packages include bash, GNU utilities, git, OpenSSH, Python/pip, curl, wget, ripgrep,
fd, jq, archive tools, tmux and nano, plus the dependency closure. Optional packs carry GCC/Clang and
build tools, OpenJDK, Node/npm, Chromium, X11/Openbox/VNC, accessibility libraries and fonts.
The [runtime inventory snapshot](third_party/runtime-inventory.json) lists the actual Debian and npm
packages in the inspected 0.4.0 Linux archives, including versions, source-package identifiers and archive hashes.
It is a snapshot of those archives, not a claim that every future build contains the same packages.

Run [the collector](scripts/third-party-notices.py) on each release's **actual** archives to preserve all
available original notices, including bundled native-library notices. See the commands in
[the release review](docs/RELEASE_LEGAL_REVIEW.md). Inventories and notices are not corresponding source.
An upstream repository URL or an SPDX identifier alone does not satisfy binary-distribution obligations.

Laya (`laya[serve]`) and its model `laya-typed-decisions` are downloaded when enabled, not bundled by
this repository's rootfs build. Their package dependencies and model weights have their own terms;
review the exact package version and model revision before mirroring or bundling them.

## Website, recordings and development tools

The landing page requests Inter, Noto Sans TC, Noto Serif TC and Noto Serif from Google Fonts
(SIL Open Font License 1.1; https://github.com/google/fonts). They are hosted by Google rather than
copied into the static website. Preserve their font-specific copyright/OFL files if self-hosting.
Cloudflare hosts the site and downloads; its service terms apply separately from Umbra's code license.

Marketing compositions request GSAP 3.14.2 from jsDelivr. GSAP has its own
[Standard License](https://gsap.com/community/standard-license/); do not label it MIT.
Hyperframes 0.8.146 (https://github.com/heygen-com/hyperframes),
[brag](https://github.com/latent-spaces/brag) and
[onetake](https://github.com/feitangyuan/onetake) are production tools with their own licenses.
The committed OGG effects are identified in the marketing sources as Kenney assets (CC0):
https://kenney.nl/assets/interface-sounds and https://kenney.nl/assets/impact-sounds.
Keep provenance evidence for the exact audio files and check copied tool/helper code before publishing
standalone compositions. A rendered video does not relicense its scripts, fonts, samples or depicted brands.

Build/test tools include Gradle (Apache-2.0), Android Gradle Plugin (Apache-2.0), Kotlin compiler
(Apache-2.0), JUnit (EPL-2.0), MockK (Apache-2.0), SnakeYAML (Apache-2.0), Docker, Android SDK,
Python/Pillow/NumPy/SciPy/FFmpeg, and GitHub Actions. These are not all shipped in Umbra's APK;
use their own distribution licenses if redistributing the tools themselves. FFmpeg configuration and
codecs affect its licensing. The Android SDK and proprietary services have additional terms.

## License texts and scope

Full common license texts and component notices live under `third_party/`, and thinking-orbs' notice
lives under `app/src/main/assets/licenses/`. The app bundles the repository's legal documents and
third-party notices under `assets/legal/`; Settings → About lets users read them offline.
This document records attribution and review scope; it does not certify that all legal obligations
have been discharged. Outstanding binary-release requirements are tracked in the release review.
