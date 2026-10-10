package app.umbra.tools

import app.umbra.core.model.action.ClipboardAction
import app.umbra.core.model.action.NotificationAction
import app.umbra.core.model.action.Operation

/** Facts about operations that the pipeline's stages need but the operation types do not carry. */
internal object OperationTraits {
    /** Changes what is on screen or what has focus, so it needs the display lease. */
    fun mutatesUi(op: Operation): Boolean = when (op) {
        is Operation.Tap, is Operation.DoubleTap, is Operation.RightClick, is Operation.LongPress,
        is Operation.Swipe, is Operation.Scroll, is Operation.Drag, is Operation.TypeText,
        is Operation.SelectText, is Operation.PressKey, is Operation.Global, is Operation.LaunchApp,
        is Operation.SwitchApp, is Operation.Window, is Operation.LaunchCommand,
        -> true
        is Operation.Clipboard -> op.action != ClipboardAction.GET
        is Operation.Notifications -> op.action != NotificationAction.LIST
        else -> false
    }

    /** Needs a resolved node or point from a UI snapshot before it can run (and be risk-classified). */
    fun targetsUi(op: Operation): Boolean = when (op) {
        is Operation.Tap, is Operation.DoubleTap, is Operation.RightClick, is Operation.LongPress,
        is Operation.Swipe, is Operation.Scroll, is Operation.Drag, is Operation.TypeText,
        is Operation.SelectText, is Operation.Clipboard, is Operation.PressKey,
        -> true
        else -> false
    }

    /**
     * Safe to run again with a different strategy when its verification fails: doing it twice leaves
     * the same state as doing it once.
     */
    fun idempotent(op: Operation): Boolean = when (op) {
        is Operation.Tap, is Operation.LaunchApp, is Operation.SwitchApp, is Operation.StopApp -> true
        is Operation.Clipboard -> op.action == ClipboardAction.SET || op.action == ClipboardAction.GET
        else -> false
    }

    /**
     * Can change something outside the agent (screen, apps, files, processes, settings, displays). These are what an
     * emergency input lockdown fences (§8.2); looking is always allowed.
     */
    fun hasSideEffects(op: Operation): Boolean = when (op) {
        is Operation.Observe, is Operation.Screenshot, is Operation.Find, is Operation.WaitFor, is Operation.Verify,
        is Operation.SysInfo, is Operation.Logs,
        -> false
        is Operation.Clipboard -> op.action != ClipboardAction.GET
        is Operation.Notifications -> op.action != NotificationAction.LIST
        is Operation.Packages -> op.action != app.umbra.core.model.action.PackageAction.LIST && op.action != app.umbra.core.model.action.PackageAction.INFO
        is Operation.File -> op.action != app.umbra.core.model.action.FileAction.READ && op.action != app.umbra.core.model.action.FileAction.LIST && op.action != app.umbra.core.model.action.FileAction.STAT
        is Operation.Process -> op.action == app.umbra.core.model.action.ProcessAction.KILL
        is Operation.Display -> op.action != app.umbra.core.model.action.DisplayAction.LIST
        else -> true
    }

    /** True for operations that never touch a display, so a lease and a UI snapshot are pointless. */
    fun isSystemOnly(op: Operation): Boolean = when (op) {
        is Operation.Shell, is Operation.Packages, is Operation.SysInfo, is Operation.Logs,
        is Operation.File, is Operation.Process, is Operation.StopApp,
        -> true
        else -> false
    }
}
