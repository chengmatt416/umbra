package app.umbra.core.orchestrator

import app.umbra.core.model.UmbraJson
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Local final-boundary check, including native omp tool results that bypass host-tool rendering. */
internal object ModelDlpExtension {
    const val TITLE = "umbra.internal.content-check.v1"
    const val BLOCKED = "umbra.internal.protected-content.v1"
    fun source(secretNames: Set<String>): String {
        val names = UmbraJson.encodeToString(ListSerializer(String.serializer()), secretNames.sorted())
        return """
            export default function(pi) {
                const names = $names;
                const secrets = names.map(n => process.env[n]).filter(v => typeof v === 'string' && v.length >= 8);
                async function currentVaultAllows(value, ctx, phase) {
                    try {
                        let characters = 0;
                        const review = JSON.stringify(value, function(key, item) {
                            if (typeof item !== 'string') return item;
                            if (/^data:image\//i.test(item) || key === 'data' && (
                                this?.type === 'base64' && this?.media_type?.startsWith('image/') ||
                                this?.type === 'image' && this?.mimeType?.startsWith('image/'))) return '[Image: separate visual review required]';
                            characters += item.length;
                            if (characters > 2000000) throw new Error('Local content review limit');
                            return item;
                        });
                        return await ctx.ui.confirm('$TITLE', JSON.stringify({ phase, payload: JSON.parse(review) }), { timeout: 15000 });
                    } catch { return false; }
                }
                function protectedText(text) {
                    if (/^data:image\/[a-z0-9.+-]+;base64,/i.test(text)) return false;
                    if (/\b(?:sk-(?:proj-|ant-)?[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|AKIA[A-Z0-9]{16})\b/.test(text)) return true;
                    if (/-----BEGIN (?:[A-Z ]+ )?PRIVATE KEY-----/.test(text)) return true;
                    if (/(?:verification code|one[- ]time (?:code|password)|2fa code|驗證碼|验证码|動態密碼|动态密码)\s*[:：-]?\s*\d{4,8}\b/i.test(text)) return true;
                    if (/\b(?:password|passwd|client_secret|api_key|access_token)["']?\s*[:=]\s*["']?[^\s"']{8,}/i.test(text)) return true;
                    if (secrets.some(s => text.includes(s))) return true;
                    for (const m of text.matchAll(/(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)/g)) {
                        const d = m[0].replace(/\D/g, '');
                        if (new Set(d).size <= 1 || d[0] < '2' || d[0] > '6') continue;
                        const total = [...d].reverse().reduce((sum, c, i) => { let n = Number(c) * (i % 2 ? 2 : 1); return sum + (n > 9 ? n - 9 : n); }, 0);
                        if (total % 10 === 0) return true;
                    }
                    return false;
                }
                pi.on('tool_result', async (event, ctx) => {
                    if ((event.content || []).some(block => block.type === 'text' && protectedText(block.text || '')) || !await currentVaultAllows({ content: event.content || [], details: event.details }, ctx, 'tool_result')) {
                        return { content: [{ type: 'text', text: '[Protected credential or payment/identity content omitted. Use secure native handoff; do not recover or repeat the hidden value.]' }], details: {}, isError: true };
                    }
                });
                pi.on('before_provider_request', async (event, ctx) => {
                    let bytes = 0;
                    function check(value, depth = 0) {
                        if (depth > 40) return true;
                        if (typeof value === 'string') { bytes += value.length; return bytes > 16000000 || protectedText(value); }
                        if (Array.isArray(value)) return value.some(v => check(v, depth + 1));
                        if (value && typeof value === 'object') return Object.values(value).some(v => check(v, depth + 1));
                        return false;
                    }
                    if (check(event.payload) || !await currentVaultAllows(event.payload, ctx, 'provider')) {
                        try { ctx.ui.notify('$BLOCKED', 'warning'); } finally { ctx.abort(); }
                    }
                    return event.payload;
                });
            }
        """.trimIndent()
    }
}
