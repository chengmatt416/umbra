# 0008. Root through the helper

Status: accepted. Extends [0004](0004-privileged-helper.md) and [0003](0003-root-first-linux-runtime.md).

## Context

The Linux environment needs root (mounts, `chroot`, switching to the app's uid), and so does anything that
touches another app's files. On a phone with Magisk or KernelSU the app calls `su` itself. That is not
true everywhere:

* an emulator image answers `su` only to the adb shell, not to apps;
* a root manager can prompt on every call, or deny background calls;
* Shizuku can run as root, and then root is reachable only through its binder.

The first design had one question, "can I run `su`?", and no answer when it was no.

## Decision

A second copy of the privileged helper can run as uid 0. The app treats it as one more way to be root:

* `FirstAvailableShell(ROOT, [su, RootViaHelperShell])` is the app's root. Callers do not know which
  answered, and a helper that dies is replaced by `su` and the other way round.
* `RootLauncher` is the other half: the agent's own process in the Linux environment is started with
  `su -c`, or, where there is no `su`, as a process of the root helper (`proc.start`, `proc.write`,
  `proc.close_input`, `proc.kill`). The helper owns what it starts: a process dies with the connection
  that started it, and its whole process tree is killed with it.
* Large inputs (the root file system archive going into `tar`, an APK going into `pm install -S`) are fed
  to a helper process in 128 KB pieces, each acknowledged before the next, so a slow consumer slows the
  reader down instead of filling memory. `ServerShell.exec` does this whenever a request carries
  `stdinSource`, so the same code path works over `su`, `adb` and the helper.
* A helper started by root can drop to the shell user (`--uid 2000`) because virtual displays are created
  in the name of a package, and only uid 2000 has one that is not an app. Android's libcore cannot set
  supplementary groups, so the dropped helper keeps the groups it was started with. The hello reply
  reports them, so a missing `log` or `readproc` group shows up as a fact in diagnostics, not as empty
  logcat output. (The adb shell's groups are inherited on a development image; a root manager decides on
  a phone.)

## Consequences

* The same app works on an emulator, on a rooted phone and behind Shizuku-as-root, and the tests run the
  same code on all of them.
* Two helpers are two long-lived processes. Both are small, both listen on loopback only, and both
  require a random token that lives in the app's private preferences.
* The helper is root. A bug in it is a root bug, so it takes no commands from anything that does not hold
  the token, and it runs only what the app sends it.
