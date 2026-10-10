# Crashy Login (acceptance fixture)

A tiny Android app with a deliberate bug, used to check that the agent can do real engineering work on
the phone (spec §33): find a crash from the log, fix the source, rebuild, reinstall and confirm.

* Sign in as `demo` / `umbra123`.
* On a fresh install, the first correct sign-in crashes the app (`NullPointerException` in `MainActivity.signIn`).
* After the fix, the same sign-in shows "Welcome, demo", and the next one "Welcome back, demo".

Build (inside the Linux environment, after the *Android app builds* pack is installed from the app:
Settings → Linux environment, or `umbra-pack install android` as root):

```bash
./build.sh        # -> build/crashy-login.apk
```

The task given to the agent (see `scripts/ondevice-verify.sh --agent`) is: "This project is in
/workspace/projects/crashy-login. Signing in crashes the app. Build it, install it, reproduce the
crash in a background display, fix the code, rebuild and reinstall it, and show that signing in works."
