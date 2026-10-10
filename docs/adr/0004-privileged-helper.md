# ADR 0004 — One privileged helper, three launch modes; UiAutomation as an accessibility fallback

Status: accepted · Spec: §6, §8, §9, §10

## Context

* A shell-uid process can create trusted VirtualDisplays and run `am start --display <id>`;
  root-uid creation fails on some ROMs ("packageName must match the calling uid").
* AccessibilityService can be revoked/blocked by Advanced Protection Mode for apps that are not
  accessibility tools, and sideloaded apps need "Allow restricted settings".
* Shizuku is the standard non-root route to a shell-uid Binder process.

## Decision

* A single AIDL interface (`IPrivilegedService`) with one implementation class, started three
  ways: (1) Shizuku `UserService` (shell uid), (2) libsu `RootService` (uid 0), (3) root →
  `app_process` as uid 2000 (shell identity without Shizuku installed).
* Virtual displays and `am start --display` always prefer the shell-uid instance.
* `UiBackend` has three implementations behind one interface — AccessibilityService,
  UiAutomation (hosted in the privileged process), and root-shell `uiautomator`/`input` — chosen by
  capability probe. Accessibility remains the preferred source when it is enabled and not blocked.
* Onboarding auto-grants what root/Shizuku can grant (`pm grant`, `appops
  ACCESS_RESTRICTED_SETTINGS`, `settings put secure enabled_accessibility_services`) and disables
  the phantom-process killer.

## Consequences

* Only one UiAutomation connection may exist at a time; the privileged service owns it and the
  conformance suite reports conflicts with other automation tools.
* Approval policy applies to host tools regardless of which privilege backs them
  (`core/safety`), and "revoke privileged access" tears down all three launch modes.
