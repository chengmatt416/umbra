package app.umbra.agent.credentials

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import app.umbra.agent.AppContainer
import app.umbra.agent.R
import app.umbra.agent.container
import app.umbra.platform.android.UmbraAccessibilityService
import app.umbra.platform.android.UmbraOverlay
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** A link opens a panel only. Values are accepted solely through its local native input widget. */
class CredentialHandoffActivity : Activity() {
    companion object {
        private var panel = java.lang.ref.WeakReference<AlertDialog>(null)
        private var field = java.lang.ref.WeakReference<EditText>(null)
        internal fun panelBounds(): Map<String, List<Int>> {
            val dialog = panel.get()?.takeIf { it.isShowing } ?: return emptyMap()
            check(dialog.context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0)
            fun bounds(view: View?): List<Int>? = view?.takeIf { it.isShown }?.let {
                val position = IntArray(2); it.getLocationOnScreen(position)
                listOf(position[0], position[1], it.width, it.height)
            }
            return buildMap {
                bounds(field.get())?.let { put("input", it) }
                bounds(dialog.getButton(AlertDialog.BUTTON_POSITIVE))?.let { put("positive", it) }
                bounds(dialog.getButton(AlertDialog.BUTTON_NEGATIVE))?.let { put("negative", it) }
                bounds(dialog.getButton(AlertDialog.BUTTON_NEUTRAL))?.let { put("neutral", it) }
                put("secure", listOf(if ((dialog.window?.attributes?.flags ?: 0) and WindowManager.LayoutParams.FLAG_SECURE != 0) 1 else 0))
            }
        }
        internal fun trackPanel(dialog: AlertDialog, input: EditText) {
            if (dialog.context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                panel = java.lang.ref.WeakReference(dialog); field = java.lang.ref.WeakReference(input)
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = container
        val link = intent.data
        val requestId = intent.getStringExtra("request")
        finish()
        app.scope.launch(Dispatchers.Main.immediate) {
            try {
                val id = if (link != null) {
                    val token = requireNotNull(CredentialLinks.token(link.toString())) { app.context.getString(R.string.credential_link_invalid) }
                    withContext(Dispatchers.IO) { app.credentialHandoff.consumeLink(token) }
                } else requireNotNull(requestId)
                val request = requireNotNull(app.credentialHandoff.pending.value?.takeIf { it.id == id })
                check(withTimeoutOrNull(8_000) { UmbraAccessibilityService.connected.first { it } } == true) {
                    app.context.getString(R.string.credential_accessibility)
                }
                requireNotNull(app.context.packageManager.getLaunchIntentForPackage(request.target.packageName)).let {
                    app.context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
                delay(400)
                showPanel(app, id)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(app.context, e.message ?: app.context.getString(R.string.credential_unavailable), Toast.LENGTH_LONG).show() }
        }
    }
}

private fun showPanel(container: AppContainer, id: String, error: String? = null) {
    val service = requireNotNull(UmbraAccessibilityService.instance) { container.context.getString(R.string.credential_accessibility) }
    val request = requireNotNull(container.credentialHandoff.pending.value?.takeIf { it.id == id })
    val context = ContextThemeWrapper(service, android.R.style.Theme_DeviceDefault_Dialog_Alert)
    val injected = request.state == "injected"
    val content = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (20 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
    }
    val description = TextView(context).apply {
        text = "${request.service}\n${request.target.packageName}\n${request.target.resourceId ?: request.target.hint}\n\n${request.reason}\n\n" +
            context.getString(if (injected) R.string.credential_finish_hint else R.string.credential_input_hint) +
            error?.let { "\n\n$it" }.orEmpty()
    }
    content.addView(description)
    val input = EditText(context).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        isSaveEnabled = false
        setSingleLine(true)
        hint = context.getString(R.string.credential_value)
        filters = arrayOf(android.text.InputFilter.LengthFilter(4096))
    }
    if (!injected) content.addView(input)
    val dialog = AlertDialog.Builder(context).setTitle(R.string.credential_title).setView(content)
        .setPositiveButton(if (injected) R.string.credential_return else R.string.credential_fill, null)
        .setNegativeButton(if (injected) R.string.credential_clear else R.string.credential_cancel, null)
        .setNeutralButton(R.string.credential_close, null).create()
    dialog.window?.apply {
        setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setTitle(UmbraOverlay.WINDOW_TITLE)
        setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    }
    dialog.setCanceledOnTouchOutside(false)
    var observer: Job? = null
    dialog.setOnDismissListener { input.text?.clear(); observer?.cancel() }
    dialog.show()
    CredentialHandoffActivity.trackPanel(dialog, input)
    observer = container.scope.launch(Dispatchers.Main.immediate) {
        container.credentialHandoff.pending.first { it?.id != id }
        dialog.dismiss()
    }
    fun act(block: suspend () -> Unit) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = false
        container.scope.launch(Dispatchers.Main.immediate) {
            try { withContext(Dispatchers.IO) { block() }; dialog.dismiss() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!injected && container.credentialHandoff.pending.value?.let { it.id == id && it.state == "injected" } == true) {
                    dialog.dismiss()
                    showPanel(container, id, e.message)
                    return@launch
                }
                description.text = container.context.getString(R.string.credential_unavailable) + "\n" + (e.message ?: "")
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = true
            }
        }
    }
    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
        if (injected) act { container.credentialHandoff.finish(id, clearField = false) }
        else {
            val value = input.text?.toString().orEmpty()
            input.text?.clear()
            act { container.credentialHandoff.inject(id, value) }
        }
    }
    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { act { container.credentialHandoff.finish(id, clearField = injected) } }
}
