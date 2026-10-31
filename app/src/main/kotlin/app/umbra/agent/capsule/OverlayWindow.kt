package app.umbra.agent.capsule

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.umbra.agent.ui.porcelain.PorcelainTheme

/**
 * One window drawn over other apps with Compose content: it carries its own lifecycle (there is no activity),
 * [onOutside] hears a touch anywhere else on the screen when the window asks for it (FLAG_WATCH_OUTSIDE_TOUCH), and
 * [onBack] the back key while the window has focus.
 */
internal class OverlayWindow(
    private val windowManager: WindowManager,
    val params: WindowManager.LayoutParams,
    context: Context,
    onOutside: (() -> Unit)? = null,
    /** The back key, while the window has focus: true when handled. */
    onBack: (() -> Boolean)? = null,
    content: @Composable () -> Unit,
) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private val frame = object : FrameLayout(context) {
        @SuppressLint("ClickableViewAccessibility")
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { onOutside?.invoke(); return true }
            return super.dispatchTouchEvent(ev)
        }

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK && onBack != null) {
                if (event.action == KeyEvent.ACTION_UP) onBack()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
    }

    var closed = false
        private set

    init {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.RESUMED
        frame.setViewTreeLifecycleOwner(this)
        frame.setViewTreeSavedStateRegistryOwner(this)
        frame.setViewTreeViewModelStoreOwner(this)
        frame.addView(ComposeView(context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { PorcelainTheme { content() } }
        })
        windowManager.addView(frame, params)
    }

    /**
     * Changes the window's layout (size, position, focusability) in place. A change that changes nothing is not sent:
     * every update is a relayout in the window manager (and a "windows changed" event for accessibility services).
     */
    fun update(change: (WindowManager.LayoutParams) -> Unit) {
        if (closed) return
        val before = WindowManager.LayoutParams().apply { copyFrom(params) }
        change(params)
        if (before.copyFrom(params) == 0) return
        runCatching { windowManager.updateViewLayout(frame, params) }
    }

    val windowToken get() = frame.windowToken

    /**
     * Plays a haptic through this window ([android.view.HapticFeedbackConstants]). It goes through the system's own
     * haptic feedback, so the user's touch-feedback setting decides whether anything is felt.
     */
    fun haptic(feedback: Int) {
        if (!closed && frame.isAttachedToWindow) frame.performHapticFeedback(feedback)
    }

    /**
     * Closes the window on the next turn of the main loop, not now: it is often closed from one of its own buttons or
     * gestures, and Compose still reads from that node right after the handler returns (a long press asks for haptic
     * feedback); a window removed under it would take the app down.
     */
    fun close() {
        if (closed) return
        closed = true
        main.post {
            runCatching { windowManager.removeViewImmediate(frame) }
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }

    private companion object {
        val main = Handler(Looper.getMainLooper())
    }
}
