// Writes the model directory the app reads (docs/design/redesign-2026-10.md §8) from the catalog of the omp the app
// carries, so every id is one omp knows:  node website/tools/models-json.mjs
// Output: website/public/models.json (served at /models.json) and app/src/main/assets/models.json (the offline copy).
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const catalogPath = join(root, 'build/omp-pinned/node_modules/@oh-my-pi/pi-catalog/src/models.json');
if (!existsSync(catalogPath)) execFileSync(join(root, 'scripts/omp-pinned.sh'), { stdio: 'inherit' });
const catalog = JSON.parse(readFileSync(catalogPath, 'utf8'));

// What the app offers, in order: the provider ids are omp's, the models a short list per provider, the first marked
// recommended is what "Auto" picks once that provider is connected.
const providers = [
  { id: 'openai-codex', label: 'OpenAI', auth: 'oauth', models: ['gpt-6.1-sol', 'gpt-6-sol', 'gpt-6-luna', 'gpt-5.6-terra'], recommended: 'gpt-6.1-sol' },
  { id: 'anthropic', label: 'Anthropic', auth: 'oauth', models: ['claude-sonnet-5-5', 'claude-opus-5-5', 'claude-fable-5-1', 'claude-haiku-4-5'], recommended: 'claude-sonnet-5-5' },
  { id: 'google-antigravity', label: 'Google Antigravity', auth: 'oauth', models: ['gemini-3.1-pro', 'gemini-3.8-flash', 'claude-sonnet-5-5', 'claude-opus-5-5', 'gpt-oss-120b'], recommended: 'gemini-3.1-pro' },
  { id: 'openai', label: 'OpenAI', auth: 'key', models: ['gpt-5.5', 'gpt-5.5-pro', 'gpt-5.4-mini', 'gpt-5.3-codex'], recommended: 'gpt-5.5' },
];

const out = {
  version: 1,
  updated: new Date().toISOString().slice(0, 10),
  providers: providers.map((p) => {
    const known = catalog[p.id] ?? {};
    const models = p.models.filter((id) => known[id]).map((id) => {
      const m = known[id];
      return {
        id,
        name: m.name ?? id,
        images: (m.input ?? []).includes('image'),
        reasoning: !!m.reasoning,
        // The thinking efforts omp accepts for this model (low … max), so the effort picker greys out the rest.
        efforts: m.reasoning && m.thinking?.efforts?.length ? m.thinking.efforts : undefined,
        contextWindow: m.contextWindow ?? undefined,
        recommended: id === p.recommended || undefined,
      };
    });
    const missing = p.models.filter((id) => !known[id]);
    if (missing.length) console.warn(`${p.id}: not in omp's catalog, left out: ${missing.join(', ')}`);
    return { id: p.id, label: p.label, auth: p.auth, models };
  }),
};

const text = JSON.stringify(out, null, 2) + '\n';
for (const target of ['website/public/models.json', 'app/src/main/assets/models.json']) {
  writeFileSync(join(root, target), text);
  console.log(`wrote ${target}`);
}
