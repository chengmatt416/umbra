import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { panelIntent, preparePanel } from '../public/credential-handoff/panel.mjs';

const token = 'A'.repeat(43);
assert.equal(panelIntent('#'+token), `intent://credential-handoff/${token}#Intent;scheme=umbra;package=app.umbra.agent;end`);
for (const value of ['', '#short', '#'+token+';package=evil', '#'+token+'?next=evil', '#%41'+token, '#'+token+'/extra', '#'+token+'#extra'])
  assert.equal(panelIntent(value), null, value);

function page() {
  const nodes = Object.fromEntries(['title', 'message', 'hint', 'open-input'].map(id => [id, { hidden: true, textContent: '', removeAttribute(name) { delete this[name]; } }]));
  return { nodes, document: { documentElement: {}, getElementById(id) { return nodes[id]; } } };
}
const fixture = page();
let replaced = false;
preparePanel(fixture.document, { hash: '#'+token, pathname: '/credential-handoff/' }, { replaceState(a, b, path) {
  assert.equal(path, '/credential-handoff/');
  assert.equal(fixture.nodes['open-input'].hidden, true);
  replaced = true;
} }, 'zh-TW');
assert.equal(replaced, true);
assert.equal(fixture.document.documentElement.lang, 'zh-Hant-TW');
assert.equal(fixture.nodes['open-input'].hidden, false);
assert.equal(fixture.nodes['open-input'].href, panelIntent('#'+token));
assert.equal(Object.values(fixture.nodes).some(node => node.textContent.includes(token)), false);
preparePanel(fixture.document, { hash: '#invalid', pathname: '/credential-handoff/' }, { replaceState() {} }, 'en');
assert.equal(fixture.nodes['open-input'].hidden, true);
assert.equal(fixture.nodes['open-input'].href, undefined);

const built = new URL('../dist/', import.meta.url);
const html = readFileSync(new URL('credential-handoff/index.html', built), 'utf8');
assert.equal(/<(?:input|textarea|form|iframe)\b/i.test(html), false);
assert.ok(html.includes('name="referrer" content="no-referrer"'));
const association = JSON.parse(readFileSync(new URL('.well-known/assetlinks.json', built), 'utf8'));
assert.equal(association.length, 1);
assert.deepEqual(association[0].relation, ['delegate_permission/common.handle_all_urls']);
assert.equal(association[0].target.package_name, 'app.umbra.agent');
assert.equal(association[0].target.namespace, 'android_app');
assert.equal(association[0].target.sha256_cert_fingerprints.length, 1);
assert.match(association[0].target.sha256_cert_fingerprints[0], /^(?:[A-F0-9]{2}:){31}[A-F0-9]{2}$/);
const headers = readFileSync(new URL('_headers', built), 'utf8');
assert.ok(headers.includes('/.well-known/assetlinks.json\n  Content-Type: application/json; charset=utf-8'));
assert.ok(headers.includes("connect-src 'none'"));
assert.ok(headers.includes("form-action 'none'"));
console.log('Credential-link parsing, history clearing, native package binding and built assets passed');
