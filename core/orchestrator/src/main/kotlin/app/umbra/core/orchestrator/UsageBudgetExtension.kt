package app.umbra.core.orchestrator

internal object UsageBudgetExtension {
    const val TITLE = "umbra.internal.usage-check.v1"
    const val BLOCKED = "umbra.internal.usage-blocked.v1"

    val source = """
        export default function(pi) {
            async function check(ctx, phase) {
                const totalCost = ctx.sessionManager.getUsageStatistics().cost;
                const costKnown = typeof totalCost === 'number' && Number.isFinite(totalCost) && totalCost >= 0;
                const costs = ctx.model?.cost;
                const priced = !!costs && Object.values(costs).some(v => typeof v === 'number' && v > 0);
                return await ctx.ui.confirm('$TITLE', JSON.stringify({
                    transcript: ctx.sessionManager.getSessionFile() || ctx.sessionManager.getSessionId(),
                    provider: ctx.model?.provider || '', totalCost, priced, costKnown, phase
                }), { timeout: 25000 });
            }
            async function auxiliary(ctx) {
                try { return await check(ctx, 'auxiliary'); } catch { return false; }
            }
            pi.on('before_agent_start', async (_event, ctx) => {
                let unrestricted = false;
                try { unrestricted = await check(ctx, 'capabilities'); } catch {}
                if (!unrestricted) pi.setThinkingLevel(pi.getThinkingLevel() || 'medium');
            });
            pi.on('tool_call', async (event, ctx) => {
                if (event.toolName === 'read' && typeof event.input?.path === 'string' && /[?&]q=/i.test(event.input.path) && !await auxiliary(ctx))
                    return { block: true, reason: 'Usage-limited tasks cannot run native image-question auxiliary inference. Read the image directly or use managed backup vision.' };
            });
            pi.on('session_before_compact', async (_event, ctx) => {
                if (!await auxiliary(ctx)) return { cancel: true };
            });
            pi.on('session_before_tree', async (event, ctx) => {
                if (event.preparation?.userWantsSummary && !await auxiliary(ctx)) return { cancel: true };
            });
            pi.on('before_provider_request', async (_event, ctx) => {
                let stopped = false;
                function stop() {
                    if (stopped) return;
                    stopped = true;
                    try { ctx.ui.notify('$BLOCKED', 'warning'); } finally { ctx.abort(); }
                }
                const timer = setTimeout(stop, 22000);
                let allowed = false;
                try { allowed = await check(ctx, 'request'); } catch { allowed = false; }
                finally { clearTimeout(timer); }
                if (!allowed) stop();
                return _event.payload;
            });
            pi.on('agent_end', async (_event, ctx) => { await check(ctx, 'settled'); });
        }
    """.trimIndent()
}
