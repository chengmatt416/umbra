package app.umbra.tools

import app.umbra.core.model.UmbraJson
import app.umbra.core.model.action.ActionResult
import app.umbra.core.model.action.ActionStatus
import app.umbra.runtime.omp.ContentBlock
import app.umbra.runtime.omp.HostToolPayload
import java.util.Base64

/** Turns an [ExecutionResult] into what the model reads: short, concrete, honest about what was tried. */
object ResultRenderer {
    fun text(r: ActionResult): String = buildString {
        append(r.summary.ifBlank { r.status.name.lowercase() })

        val tried = r.attempts
        if (tried.size > 1 || tried.any { it.status != ActionStatus.OK }) {
            append("\nTried: ").append(tried.joinToString("; ") { a ->
                "${a.strategy} (${a.backend}) ${if (a.status == ActionStatus.OK) "worked" else "failed"}${a.detail?.takeIf { d -> a.status != ActionStatus.OK && d.isNotBlank() }?.let { ": ${it.take(120)}" } ?: ""}"
            })
        } else if (r.isOk && tried.size == 1) {
            append(" [via ").append(tried.single().strategy).append(" on ").append(tried.single().backend).append(']')
        }

        r.verification?.let { v ->
            if (v.checks.isNotEmpty()) {
                append("\nVerification ").append(if (v.passed) "passed: " else "not satisfied: ").append(v.checks.joinToString("; ") { it.evidence })
            }
        }
        if (!r.isOk) {
            r.error?.let { e -> append("\n(").append(e.code.name.lowercase()).append(if (e.retryable) ", may succeed if retried" else "").append(')') }
            if (r.status == ActionStatus.NEEDS_USER) append("\nThe user must act or approve before this can continue.")
        }
    }

    fun payload(exec: ExecutionResult): HostToolPayload {
        val r = exec.result
        val blocks = buildList<ContentBlock> {
            add(ContentBlock.Text(text(r)))
            exec.images.forEach { add(ContentBlock.Image(Base64.getEncoder().encodeToString(it.bytes), it.mime)) }
        }
        return HostToolPayload(blocks, UmbraJson.encodeToJsonElement(ActionResult.serializer(), r), isError = !r.isOk)
    }
}
