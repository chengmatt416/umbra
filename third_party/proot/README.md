# proot, talloc and libandroid-shmem (shipped in the APK)

The APK carries Termux's arm64 builds of these libraries so the Linux environment runs on phones without root
(ADR 0012). `runtime-build/proot/fetch.sh` downloads the exact packages below (SHA-256 pinned), renames them to
`lib*.so` and patches one `DT_NEEDED` name (`libtalloc.so.2` → `libtalloc.so`); nothing else is changed.

| Library | Version (Termux package) | Licence | Source |
|---|---|---|---|
| proot | 5.1.107.97 | GPL-2.0-or-later | https://github.com/termux/proot (and the Termux package recipe: https://github.com/termux/termux-packages/tree/master/packages/proot) |
| talloc | 2.5.0 | LGPL-3.0-or-later | https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz |
| libandroid-shmem | 0.7 | BSD-3-Clause | https://github.com/termux/libandroid-shmem |

Umbra itself is under the MIT License; proot is a separate program the app runs, shipped alongside it with its
libraries, each under its own licence above. The links above identify upstream projects; they are not a complete corresponding-source distribution.
Before publishing an APK, archive and publish the exact source, Termux recipes/patches and build materials
for these binaries, including Umbra's rename and `DT_NEEDED` change. See
[the release review](../../docs/RELEASE_LEGAL_REVIEW.md). No written source offer is made by this README.
Full GPL/LGPL texts are in `third_party/licenses/`; libandroid-shmem's original copyright and BSD
license are in `libandroid-shmem-LICENSE` here.

Package files (Termux apt repository, `https://packages.termux.dev/apt/termux-main`):

- `proot_5.1.107.97_aarch64.deb`
- `libtalloc_2.5.0_aarch64.deb`
- `libandroid-shmem_0.7_aarch64.deb`
