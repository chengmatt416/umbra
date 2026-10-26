import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import test from 'node:test';
import { legalBody } from '../src/legal.mjs';

const built = new URL('../dist/', import.meta.url);
const github = 'https://github.com/chengmatt416/umbra';

test('policy formatting preserves lists, escapes HTML and links to local legal pages', () => {
  const html = legalBody('# Title\n\n## Data & control\n\nKeep <private> data.\n\n- **Providers:** your chosen\n  service.\n- Read [privacy](PRIVACY.md) and [terms](TERMS.md).\n\nUse `umbra-lang`.', github);
  assert.doesNotMatch(html, /<h1>|<private>|# Title/);
  assert.match(html, /<h2>Data &amp; control<\/h2>/);
  assert.match(html, /Keep &lt;private&gt; data\./);
  assert.match(html, /<li><strong>Providers:<\/strong> your chosen service\.<\/li>/);
  assert.match(html, /href="\/privacy\/"/);
  assert.match(html, /href="\/terms\/"/);
  assert.match(html, /<code>umbra-lang<\/code>/);
});

test('built privacy and terms are readable, mutually linked and discoverable', () => {
  const sitemap = readFileSync(new URL('sitemap.xml', built), 'utf8');
  for (const [route, file, title] of [
    ['privacy', 'PRIVACY.md', 'Privacy policy'],
    ['terms', 'TERMS.md', 'Terms of service'],
  ]) {
    const html = readFileSync(new URL(`${route}/index.html`, built), 'utf8');
    assert.match(html, new RegExp(`<h1[^>]*>${title}</h1>`));
    assert.equal((html.match(/<h1\b/g) || []).length, 1);
    assert.match(html, /Effective date: 2026-10-25\./);
    assert.match(html, /<h2>/);
    assert.doesNotMatch(html, /<pre|\*\*|## /);
    assert.match(html, /href="\/privacy\/"/);
    assert.match(html, /href="\/terms\/"/);
    assert.ok(sitemap.includes(`/${route}/</loc>`));
    assert.match(html, new RegExp(`<link rel="canonical" href="https?://[^\"]+/${route}/">`));
    for (const [, href] of html.matchAll(/href="(\/[^\"#]*)"/g)) {
      const path = href.endsWith('/') ? `${href.slice(1)}index.html` : href.slice(1);
      assert.ok(existsSync(new URL(path, built)), `${route}: missing ${href}`);
    }
    assert.equal(readFileSync(new URL(`legal/${file}`, built), 'utf8'), readFileSync(new URL(`../../${file}`, import.meta.url), 'utf8'));
  }
});

test('both landing pages and node pages link to privacy and terms', () => {
  for (const page of ['en/index.html', 'zh-tw/index.html', 'en/nodes/index.html', 'zh-tw/nodes/index.html']) {
    const html = readFileSync(new URL(page, built), 'utf8');
    assert.match(html, /<footer[\s\S]*href="\/privacy\/"/);
    assert.match(html, /<footer[\s\S]*href="\/terms\/"/);
    assert.ok(html.includes(page.startsWith('zh-tw') ? '服務條款' : 'Terms of service'));
  }
});
