# Backdrop (Liquid Glass)

The Liquid and Apple themes draw their glass with Kyant's Backdrop library and follow its example components.

| What | Where in the app | Source | License |
|---|---|---|---|
| `io.github.kyant0:backdrop:2.0.1` (refraction, blur, vibrancy, rim highlight, shadows), a Maven dependency | `ui/porcelain/Liquid.kt`, `Glass.kt`, `Overlays.kt`, `shell/PorcelainApp.kt` | https://github.com/Kyant0/AndroidLiquidGlass | Apache-2.0 (`LICENSE` here) |
| Adapted from the library's catalog app (`DampedDragAnimation`, `inspectDragGestures`, `InteractiveHighlight`, `LiquidToggle`, `LiquidBottomTabs`, `LiquidSlider`) | `ui/porcelain/Glass.kt` (`DampedDrag`, `inspectDragGestures`, `InteractiveHighlight`, `AppleSwitch`, `AppleSegmented`, `AppleTabBar`) | same repository, `app/src/commonMain/kotlin/com/kyant/backdrop/catalog/` | Apache-2.0; modified: Umbra's spring vocabulary, momentum projection, consumed drags, adaptive tint, Apple colours |

Copyright 2025 Kyant. Licensed under the Apache License, Version 2.0.
