# Third-party materials

Start with [the project notices](../THIRD_PARTY_NOTICES.md) and
[the public-release review](../docs/RELEASE_LEGAL_REVIEW.md).

- `gradle/inventory.json`: resolved APK and node-runner runtime dependencies, artifact hashes and
  direct POM license declarations. `gradle/notices/` preserves the binary artifacts' available notices.
- `runtime-inventory.json`: local 0.4.0 rootfs/add-on archive hashes, Debian binary/source package
  identifiers, and npm package manifests. Nested manifests include bundled examples as well as
  runtime modules; this is not a computed npm dependency graph. The unlicensed
  `pi-extension-with-deps` manifest is an omp example, not a separately cleared library.
- `fonts/`: unchanged Geist/Newsreader copyrights and OFL texts.
- `backdrop/`: original upstream license and description of modified catalog examples.
- `omp/LICENSE`: original notice from installed `@oh-my-pi/pi-coding-agent` 18.12.1.
- `shizuku/LICENSE`: original MIT notice from https://github.com/RikkaApps/Shizuku-API/blob/master/LICENSE;
  the installed 13.1.5 POMs also declare MIT.
- `xz/COPYING`: https://github.com/tukaani-project/xz-java/blob/v1.10/COPYING.
- `proot/libandroid-shmem-LICENSE`: https://github.com/termux/libandroid-shmem/blob/v0.7/LICENSE.
- `licenses/Bun-LICENSE.md`: https://github.com/oven-sh/bun/blob/bun-v1.3.14/LICENSE.md,
  a version-specific licensing overview, not the full notice set of all embedded libraries.
- `licenses/Apache-2.0.txt`: Apache text retained from the vendored Backdrop license.
- `licenses/GPL-2.0.txt`, `GPL-3.0.txt`, `LGPL-3.0.txt`: full texts from https://www.gnu.org/licenses/.
- `licenses/LGPL-2.1.txt`: full text from the inspected Debian rootfs's `/usr/share/common-licenses/LGPL-2.1`.

The license for thinking-orbs is retained separately at `app/src/main/assets/licenses/thinking-orbs.txt`.
The collector's complete runtime output is in ignored `build/legal/runtime/`; it contains original
notices from the inspected rootfs and `.deb` packages. Regenerate it from each release and distribute
that notice bundle with the binaries. The committed JSON index does not replace those original texts
or the corresponding source/build materials required for copyleft binaries.
