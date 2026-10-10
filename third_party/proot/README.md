# proot, talloc and libandroid-shmem (shipped in the APK)

The APK carries Termux's arm64 builds of these libraries so the Linux environment runs on phones without root
(ADR 0012). `runtime-build/proot/fetch.sh` downloads the exact packages below (SHA-256 pinned), renames them to
`lib*.so` and patches one `DT_NEEDED` name (`libtalloc.so.2` → `libtalloc.so`); nothing else is changed.

| Library | Version (Termux package) | Licence | Source |
|---|---|---|---|
| proot | 5.1.107.96 | GPL-2.0-or-later | https://github.com/termux/proot (and the Termux package recipe: https://github.com/termux/termux-packages/tree/master/packages/proot) |
| talloc | 2.5.0 | LGPL-3.0-or-later | https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz |
| libandroid-shmem | 0.7 | BSD-3-Clause | https://github.com/termux/libandroid-shmem |

Umbra itself is GPL-3.0; GPL-2.0-or-later and LGPL-3.0 code may be distributed with it. Anyone who receives the
APK may obtain the corresponding source of these binaries from the links above, or by asking the Umbra maintainers,
who will provide it for at least three years from each release.

Package files (Termux apt repository, `https://packages.termux.dev/apt/termux-main`):

- `proot_5.1.107.96_aarch64.deb`
- `libtalloc_2.5.0_aarch64.deb`
- `libandroid-shmem_0.7_aarch64.deb`
