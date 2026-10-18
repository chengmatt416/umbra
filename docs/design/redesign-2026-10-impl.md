# Redesign v1 — implementation plan

Design: `docs/design/redesign-2026-10.md` and the Figma file it links. Branch `redesign-v1`.

| Phase | What | Main files |
|---|---|---|
| A | Prefs: `uiMode` (SIMPLE/POWER/DEV), `onboarded`, `leftHanded`, `autoModel`, `skippedUpdate`; `ProviderConfig.oauth/account`. New strings in `strings_redesign.xml` (en + zh-TW). | `data/AppSettings.kt`, `res/values*/strings_redesign.xml` |
| B | Shell without bottom bar or drawer: FAB menu per mode, routes for hubs. Home = tasks (needs you → running → recent, inline approvals) + composer row. Safety hub (Power), Developer hub (Dev, every original page). | `ui/shell/PorcelainApp.kt`, `ui/shell/FabMenu.kt`, `ui/home/HomeScreen.kt`, `ui/hubs/*` |
| C | Task page by mode: Simple = chat only, with a live display card while the agent drives a screen; Power/Dev = chat · steps · terminal · changes. | `ui/current/CurrentScreen.kt`, `ui/current/LiveDisplay.kt` |
| D | Models: catalog from `umbra-agent.pages.dev/models.json` (cached) merged with omp's; picker sheet from the composer chip, "Auto", "Other model". Accounts: OpenAI / Anthropic / Antigravity by `omp login <provider>` (loopback callback, browser opened by the app), API keys as before. | `data/ModelDirectory.kt`, `data/OmpLogin.kt`, `ui/models/*`, `ui/accounts/AccountsScreen.kt` |
| E | Capsule: centred on the cutout (left zone, camera slot = cutout + 16dp, right zone), card expands in place, swipe toward the centre tucks it, long press selects; selection controls live in a card at the top. | `capsule/Capsule.kt`, `capsule/CapsuleUi.kt` |
| F | Onboarding (mode → connect → Laya → permissions), Laya kept in the agent's home and started with sessions, OTA asks once per version at launch then installs by itself, Settings rebuilt, Displays page (background + Linux desktop), resources automatic outside Dev. | `ui/onboarding/*`, `data/LayaSidecar.kt`, `update/AppUpdater.kt`, `ui/SettingsScreen.kt`, `ui/displays/*` |
| G | `website/public/models.json`, build, verify on the emulator. | `website/` |
