# The Linux guest the agent works in: Debian (glibc) for arm64, with Bun and the pinned omp. It runs in a
# chroot on a rooted phone (ADR 0003). Built with `runtime-build/rootfs/build.sh`.
# The base is Debian's official docker rootfs, imported by build.sh as umbra-debian-base (no registry pull).
FROM umbra-debian-base:trixie

ARG BUN_VERSION=1.3.14
ARG OMP_VERSION=18.12.1

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

# The base is what an agent needs for everyday coding work, plus tmux: every terminal is a tmux pane (spec §6.1). Bigger toolchains (compilers, JDK, Android
# build tools, a desktop) are packs installed on demand: `umbra-pack install <name>`.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      bash coreutils findutils grep sed gawk diffutils patch less file procps psmisc util-linux \
      ca-certificates curl wget git openssh-client \
      python3 python3-pip \
      ripgrep fd-find jq unzip zip xz-utils bzip2 tar zstd \
      iproute2 locales tzdata nano tmux \
 && ln -s /usr/bin/fdfind /usr/local/bin/fd \
 && rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*

# Bun: omp is a Bun program (needs >= 1.3.14), and the official glibc build is the one that runs here.
RUN curl -fsSL --retry 5 --retry-all-errors --retry-delay 3 "https://github.com/oven-sh/bun/releases/download/bun-v${BUN_VERSION}/bun-linux-aarch64.zip" -o /tmp/bun.zip \
 && unzip -q /tmp/bun.zip -d /tmp \
 && install -m 0755 /tmp/bun-linux-aarch64/bun /usr/local/bin/bun \
 && ln -s bun /usr/local/bin/bunx \
 && rm -rf /tmp/bun*

# omp, pinned (spec: locked production version). Installed under /opt so the agent's own HOME stays clean.
ENV BUN_INSTALL=/opt/bun
RUN bun install -g "@oh-my-pi/pi-coding-agent@${OMP_VERSION}" \
 && ln -s /opt/bun/bin/omp /usr/local/bin/omp \
 # Bun hard-links packages out of its cache; the cache is not needed once installed.
 && rm -rf "$BUN_INSTALL/install/cache" \
 # Native binaries for other operating systems and CPUs are dead weight on a phone.
 && cd "$BUN_INSTALL/install/global/node_modules" \
 && rm -rf onnxruntime-node/bin/napi-v6/darwin onnxruntime-node/bin/napi-v6/win32 onnxruntime-node/bin/napi-v6/linux/x64 \
           @img/sharp-linuxmusl-arm64 @img/sharp-libvips-linuxmusl-arm64 @img/sharp-libvips-linuxmusl-x64 \
 # Bun makes the scripts it links into bin/ writable by everyone. The agent runs omp from here; it must not be able to change it.
 && chmod -R go-w "$BUN_INSTALL"

# Umbra's guest helpers and the pack definitions.
# The build context is guest/ itself, so only the helpers are sent to the daemon.
COPY . /opt/umbra/
RUN chmod -R a+rX /opt/umbra && chmod a+x /opt/umbra/bin/* \
 && for f in /opt/umbra/bin/*; do ln -sf "$f" "/usr/local/bin/$(basename "$f")"; done \
 && mkdir -p /workspace /home/agent /tmp \
 && chmod 1777 /tmp && chmod 0777 /home/agent /workspace \
 && echo "umbra-rootfs bun=${BUN_VERSION} omp=${OMP_VERSION}" > /opt/umbra/VERSION

# The archive is unpacked on Android by toybox tar, which cannot restore a hard link whose target path is
# longer than 100 characters. Turn every remaining hard link into an ordinary file; the copies compress away.
# RUN uses dash, which has no `read -d`, so xargs does the looping. The copy must not preserve timestamps:
# with identical size, mode and mtime the layer diff treats the copy as unchanged and drops it.
RUN find / -xdev -type f -links +1 -print0 \
 | xargs -0 -r -n1 sh -c 'cp --preserve=mode,ownership --remove-destination "$0" "$0.dehard" && mv -f "$0.dehard" "$0"'

WORKDIR /workspace
ENV HOME=/home/agent \
    PATH=/opt/umbra/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin
CMD ["/bin/bash"]
