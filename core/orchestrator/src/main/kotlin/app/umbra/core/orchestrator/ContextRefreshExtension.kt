package app.umbra.core.orchestrator

import kotlinx.serialization.json.JsonPrimitive

/** The per-turn override is never persisted into the base prompt, so revoked memories do not survive a follow-up. */
internal object ContextRefreshExtension {
    fun source(path: String): String = """
        import { readFileSync } from 'node:fs';
        export default function(pi) {
            pi.on('before_agent_start', async (event, ctx) => {
                try {
                    const context = readFileSync(${JsonPrimitive(path)}, 'utf8');
                    if (context.length > 2000000) throw new Error('Context is too large');
                    return { systemPrompt: event.systemPrompt + (context.trim() ? '\n\n' + context : '') };
                } catch (_) {
                    try { ctx.ui.notify('Current private context could not be read. Restart this task after fixing its saved context.', 'warning'); }
                    finally { ctx.abort(); }
                }
            });
        }
    """.trimIndent()
}
