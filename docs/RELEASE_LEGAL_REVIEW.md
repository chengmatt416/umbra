# Public release review

Reviewed 2026-10-20 against the repository and the local 0.4.0 Linux archives.
**Status: attribution and policy improvements are implemented; binary distribution is not yet cleared.**
An HN post linking source is different from inviting people to download an APK containing third-party
binaries. MIT is suitable for Umbra's own original code; it does not discharge the obligations below.
This is an engineering review, not a jurisdiction-specific legal opinion.

## Requirements before announcing binary downloads

- [ ] **Publish matching corresponding source and build materials.** The APK includes PRoot (GPL),
  talloc (LGPL), and many Debian GPL/LGPL packages. Retain the exact source versions, Debian source
  packages (`.dsc`, original tarballs, Debian patches), Termux packaging recipes/patches/toolchain
  information, and Umbra's binary modifications. Publish them with equivalent access alongside each
  release, including add-on packages that are distributed even if users never install them. A link
  to a moving upstream branch and a license table are not sufficient. Keep a release-specific
  mapping from binary/package version to archived source. Do not claim an unimplemented written
  source offer. Source-code hosting of Umbra alone does not include the guest's corresponding source.
- [ ] **Complete Bun/native-library review.** Bun 1.3.14 embeds JavaScriptCore/WebKit and other libraries.
  Retain version-specific notices and matching source/build or relinking materials required by LGPL;
  verify the exact Bun release's compliance materials. Also review omp's native dependencies, including
  Sharp/libvips and ONNX Runtime, using their bundled notices and exact sources. Host-side macOS npm
  dependencies are not evidence of the shipped arm64 Linux dependency set.
  The inspected guest also carries `axe-core` 4.13.0 under MPL-2.0; preserve its license and provide
  the required source-availability information for covered files.
- [ ] **Resolve Android SDK platform redistribution.** `runtime-build/rootfs/build.sh` downloads and
  redistributes Google's `platform-34-ext7_r03.zip`. The Android SDK agreement restricts redistribution
  except where applicable component licenses permit it. The ZIP contains component notices, but their
  presence does not establish a license for the entire archive. Confirm rights for every redistributed
  file, replace it with a properly licensed source-built alternative, or change the pack to a
  user-initiated download under applicable terms. Merely adding Google attribution does not clear it.
- [ ] **Review exact inventory and preserve notices.** Generate reports from the actual release, review
  unknown declarations and embedded multi-license components, and bundle/publish notices. The snapshot
  covers 0.4.0 guest archives and the currently resolved JVM dependencies, not all future versions.
- [ ] **Verify marketing provenance.** Retain the exact Kenney pack licenses/download provenance and
  licenses for any copied Hyperframes/brag/onetake helpers. GSAP uses its own Standard License. Avoid
  presenting scripted-model emulator demos as proof of live provider or message-delivery behavior.
- [ ] **Verify launch claims and policy deployment.** Deploy the linked privacy/use/notices pages, check
  their descriptions against the release configuration, and review account-provider terms for sign-in
  and automated use. Confirm a usable private privacy/security contact on the maintainer profile.
  Obtain legal review for intended distribution jurisdictions and personal-data handling where needed.

Existing APKs and downloaded archives are not changed by this review. Rebuild and republish corrected
artifacts after these requirements are met; publishing these documents alone does not cure old binaries.

## Show HN suitability

[Show HN's guidelines](https://news.ycombinator.com/showhn.html) call for something users can actually
try, with minimal barriers. Link working source/build instructions and a cleared, usable download;
state the Android 16/arm64, storage and model-account requirements. Keep the scripted-model demo
disclosure visible. A landing page or recording alone is not enough for a software Show HN.
The [HN posting guidelines](https://news.ycombinator.com/newsguidelines.html) also ask people to write
their own posts/comments and avoid automated posting or soliciting votes. No submission was made.

## Reproduce inventories and notice bundles

```bash
source scripts/env.sh
./gradlew --no-configuration-cache -I scripts/third-party.init.gradle :app:thirdPartyArtifacts :node-runner:thirdPartyArtifacts
python3 scripts/third-party-notices.py --gradle app/build/legal/artifacts.json --gradle node-runner/build/legal/artifacts.json --output build/legal/jvm
python3 scripts/third-party-notices.py --rootfs build/rootfs/umbra-rootfs-0.4.0-arm64.tar.xz --packs build/rootfs/umbra-packs-0.4.0-arm64.tar --output build/legal/runtime
```

Use the intended release version instead of 0.4.0. The Python tool uses standard-library archive readers,
records archive hashes and copies original license/notice files without running the binaries. Its
inventory is evidence, not a compatibility decision. Missing license metadata remains `UNKNOWN`.
It does not download source, infer all statically linked components, or reconstruct missing notices.
Debian copyright symlinks refer to other documents in the original image; preserve the whole image's
copyright tree. If a new package uses an unsupported archive compression format, collection fails;
resolve that failure rather than silently excluding it.

Refresh `third_party/gradle/` from the JVM report when dependencies change. The Gradle artifact path
report is build-local and contains machine paths; only the sanitized inventory/notices belong in git.
Retain the complete runtime notice bundle with the release; `third_party/runtime-inventory.json` is
an index, not a substitute for those texts or source archives. Preserve `/usr/share/doc` and
`/usr/share/common-licenses` in guest images and all licenses/notices in npm and offline Debian packages.
The CI workflow collects runtime notices as release artifacts; source publication remains outstanding.

## Reference requirements

- [Apache-2.0 section 4](https://www.apache.org/licenses/LICENSE-2.0): include the license, applicable
  notices and attribution; identify modifications to adapted code.
- [GNU GPL binary/source FAQ](https://www.gnu.org/licenses/gpl-faq.html#SourceAndBinaryOnDifferentSites):
  provide the required corresponding source for distributed covered binaries.
- [GNU LGPL v3](https://www.gnu.org/licenses/lgpl-3.0.html): library notices and source/recombination
  requirements depend on how the library is conveyed and linked.
- [Bun licensing documentation](https://bun.com/docs/project/license): Bun's own MIT license does not
  replace JavaScriptCore/WebKit's LGPL or other embedded components' licenses.
- [Android SDK agreement](https://developer.android.com/studio/terms), sections 3.4–3.5:
  redistribution restrictions and the exception for applicable open-source component licenses.
