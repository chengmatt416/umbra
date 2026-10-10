# 0014. The Capsule and Snapshot selection

Status: accepted. Architecture spec §3.1. Replaces the floating bubble of the functional spec's §26.

## Context

The spec puts a Material Capsule at the camera cutout: always in the same place, the orb and the task when collapsed,
a mini island at rest, Previous / Current / Next for sessions and steps when expanded, approvals answered in place, and
a composer that shares its draft and context with Home. Snapshot selection lassoes part of a screenshot (with the
accessibility tree read at the same moment), returns to the app underneath and attaches the selection to the bottom
Quick Composer. One snapshot by default; Live Context only when the user turns it on.

Building it on the Android 16 emulator showed what Android allows:

* An ordinary overlay (`TYPE_APPLICATION_OVERLAY`) lies below the status bar. Placed at the camera, its touches go to
  the status bar, so a capsule there cannot be tapped.
* Apps that guard against tapjacking (Settings, installers, permission screens) force-hide ordinary overlays. A
  composer drawn that way vanished as soon as Settings came to the front.
* An accessibility overlay (`TYPE_ACCESSIBILITY_OVERLAY`, added through the accessibility service's own window manager)
  lies above the status bar, is not hidden by those apps, can take focus and the keyboard, and moves above the keyboard
  with `SOFT_INPUT_ADJUST_RESIZE`. It is not fitted above the navigation bar, so it keeps clear of it itself.
* The cutout's reserved rectangle (`boundingRectTop`) is wider than the hole; the exact hole is the cutout path.
* A screenshot includes every overlay, Umbra's too.

## Decision

1. **Window type follows the accessibility service.** With Umbra's service on, the capsule, the composer and the
   selection are accessibility overlays: the capsule sits at the camera itself. Without it they are ordinary overlays
   (the draw-over-apps permission), the capsule just under the status bar, and an app that hides overlays hides them.
2. **The capsule wraps the camera.** Collapsed, it is as tall as the hole plus a margin and leaves a gap for it at one
   end; the orb and the task go on the side of the camera with more room, and the task is cut short so a fifth of the
   screen stays clear at that edge for the clock and icons. Expanded, its content starts below the hole.
3. **Typing happens only in the bottom Quick Composer**, and only after the user taps its field: until then the window
   cannot take focus at all (no keyboard is forced, §3.1). The capsule shows the shared draft and opens the composer.
4. **One draft and one set of context chips** (`UiState`) for Home, the capsule and the Quick Composer. A new snapshot
   replaces the previous one. Selections are kept in the app's cache until sent or removed and never outlive the app
   process.
5. **A selection is a picture and text.** `Lasso.select` (pure, unit-tested) takes the elements mostly inside the
   stroke's bounds whose centre lies inside the loop (a diagonal drag selects its rectangle, a tap the smallest element
   under it); `Lasso.describe` names the app, the region and each element's role and words. Password fields say only
   that they are there. The crop is sent as a JPEG of at most 1600 px; a custom provider's models receive it only when
   the user says they accept images (`ProviderConfig.images`), since omp otherwise treats them as text-only.
6. **Umbra's windows are hidden from the picture and from agents.** They are closed before the screenshot is taken; the
   accessibility backend skips windows titled `umbra-overlay` and Umbra's own accessibility overlays. An app that marks
   its screen secure is respected: the selection fails with that reason, no other way around it is tried.
7. **Live Context is opt-in** (`AppPrefs.liveContext`, off by default): only then does a message sent from the capsule
   carry the text of the app in front, read at the moment of sending, never password fields. Home never adds it.
8. **Attention Focus peeks, it does not switch.** A new approval or a session that failed in the last ten minutes
   expands the capsule; nothing navigates. Critical approvals cannot be approved from it.
9. **A window is removed on the next turn of the main loop.** Windows are closed from their own buttons and gestures,
   and Compose still reads from the pressed node right after the handler (a long press asks for haptic feedback);
   removing the window synchronously crashed the app.
10. **The capsule lives in the foreground service** while the user has it on, so it stays when the app is closed.

## Consequences

* Without the accessibility service the capsule is not at the camera and disappears over apps that hide overlays; the
  setting says so.
* The microphone of the overlay composer goes through a see-through activity (`VoiceInputActivity`) that writes the
  recognized words into the shared draft; the composer stays where it was.
* Verified on the emulator (Android 16, punch-hole at x=540 of 720): the capsule anchored on the hole, tap at rest
  opens the composer without the keyboard, typing over Settings, long-press → lasso → Done → chip, the model receiving
  the crop and the four circled Settings entries, Previous / Current / Next from omp's todo plan, an approval denied
  from the capsule reaching the agent as a denial.
