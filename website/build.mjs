// Static build, no dependencies: node build.mjs  ->  dist/
import { mkdirSync, writeFileSync, cpSync, rmSync, readFileSync, existsSync } from 'node:fs';
import { L, APK, GITHUB } from './src/content.mjs';
import { nodePage } from './src/nodes.mjs';
import { legalBody } from './src/legal.mjs';

const V = Date.now().toString(36);
const SITE = process.env.SITE_URL || 'https://umbra-agent.pages.dev';
const sha = APK.sha256;
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const mark = `<svg viewBox="0 0 32 32" aria-hidden="true"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#4a4a4a"/><stop offset="1" stop-color="#050505"/></linearGradient></defs><circle cx="16" cy="16" r="14" fill="url(#g)"/><circle cx="21.5" cy="12" r="10.5" style="fill:var(--base)"/></svg>`;
const dlIcon = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v12m0 0l-5-5m5 5l5-5M4 20h16"/></svg>`;
const tileIcons = {
  levels: '<path d="M4 18h3v-4H4zM10.5 18h3V9h-3zM17 18h3V5h-3z"/>',
  teach: '<path d="M4 4h16v11H4z"/><path d="M8 20h8M12 15v5"/><circle cx="12" cy="9.5" r="2.2"/>',
  tool: '<path d="M14.7 6.3a4 4 0 0 0-5.4 5.2L4 16.8V20h3.2l5.3-5.3a4 4 0 0 0 5.2-5.4l-2.6 2.6-2.4-.6-.6-2.4z"/>',
  plug: '<path d="M9 3v5M15 3v5M6 8h12v3a6 6 0 0 1-12 0zM12 17v4"/>',
  team: '<circle cx="9" cy="8" r="3"/><circle cx="17" cy="9.5" r="2.4"/><path d="M3.5 19a5.5 5.5 0 0 1 11 0M14 19a4 4 0 0 1 7 0"/>',
  send: '<path d="M21 3 3 10.5l7 2.5 2.5 7z"/><path d="m10 13 4.5-4.5"/>',
  lock: '<rect x="5" y="10.5" width="14" height="10" rx="2"/><path d="M8 10.5V7a4 4 0 0 1 8 0v3.5"/>',
  check: '<path d="m4.5 12.5 4.5 4.5L19.5 6.5"/>',
};
const tileIcon = (k) => `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${tileIcons[k] || ''}</svg>`;
const legalLinks = (zh) => `<a href="/privacy/">${zh ? '隱私政策' : 'Privacy'}</a> · <a href="/terms/">${zh ? '服務條款' : 'Terms of service'}</a> · <a href="/policy/">${zh ? '使用政策' : 'Use policy'}</a> · <a href="/licenses/">${zh ? '授權與致謝' : 'Licenses & notices'}</a>`;

function page(t, other) {
  const url = SITE + t.path;
  const alt = `<link rel="alternate" hreflang="en" href="${SITE}/en/"><link rel="alternate" hreflang="zh-Hant-TW" href="${SITE}/zh-tw/"><link rel="alternate" hreflang="x-default" href="${SITE}/">`;
  const ld = JSON.stringify({ '@context': 'https://schema.org', '@type': 'SoftwareApplication', name: 'Umbra', applicationCategory: 'UtilitiesApplication', operatingSystem: 'Android 16', softwareVersion: APK.version, description: t.description, downloadUrl: `${SITE}/download/${APK.file}`, offers: { '@type': 'Offer', price: '0', priceCurrency: 'USD' }, inLanguage: t.htmlLang });
  const chapters = t.chapters.map((c, i) => {
    const part = i === 0 || t.chapters[i - 1].part !== c.part ? `<p class="part">${esc(t.story.parts[c.part])}</p>` : '';
    return `<article class="step" id="${c.id}" data-clip="/clips/${c.clip}.mp4" data-poster="/clips/${c.clip}.jpg" tabindex="-1">${part}<p class="kicker">${esc(c.kicker)}</p><h3>${esc(c.title)}</h3><p>${esc(c.body)}</p><ul>${c.bullets.map((b) => `<li>${esc(b)}</li>`).join('')}</ul></article>`;
  }).join('\n');
  const faq = t.faq.items.map(([q, a]) => `<details><summary>${esc(q)}</summary><p>${esc(a)}</p></details>`).join('');
  const lead = t.download.lead.replace('%v', APK.version).replace('%s', APK.size);
  return `<!doctype html>
<html lang="${t.htmlLang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(t.title)}</title>
<meta name="description" content="${esc(t.description)}">
<link rel="canonical" href="${url}">${alt}
<meta property="og:type" content="website"><meta property="og:title" content="${esc(t.title)}"><meta property="og:description" content="${esc(t.description)}"><meta property="og:url" content="${url}"><meta property="og:image" content="${SITE}/og.png"><meta property="og:locale" content="${t.lang === 'en' ? 'en_US' : 'zh_TW'}">
<meta name="twitter:card" content="summary_large_image">
<meta name="theme-color" content="#f6f6f4" media="(prefers-color-scheme: light)"><meta name="theme-color" content="#000000" media="(prefers-color-scheme: dark)">
<link rel="icon" href="/favicon.svg" type="image/svg+xml">
<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&family=Noto+Sans+TC:wght@400;500;700&family=Noto+Serif+TC:wght@500;600;700&family=Noto+Serif:wght@400;600&display=swap">
<link rel="stylesheet" href="/site.css?v=${V}">
<script type="application/ld+json">${ld}</script>
</head>
<body>
<a class="skip" href="#story">${esc(t.ui.skip)}</a>
<header class="top"><div class="wrap">
  <a class="brand" href="${t.path}" aria-label="Umbra">${mark}<span>Umbra</span></a>
  <nav class="nav" aria-label="Main"><a href="#story">${esc(t.nav.features)}</a><a href="#start">${esc(t.nav.how)}</a><a href="${t.path}nodes/">${t.lang === 'en' ? 'Remote nodes' : '遠端節點'}</a><a href="#trust">${esc(t.nav.trust)}</a><a href="#faq">${esc(t.nav.faq)}</a></nav>
  <a class="langsw" href="${other.path}" hreflang="${other.htmlLang}" lang="${other.htmlLang}" data-lang="${other.lang}">${esc(t.ui.lang)}</a>
  <a class="btn primary sm" href="#download">${dlIcon}${esc(t.nav.download)}</a>
</div></header>
<main>
<section class="hero"><div class="wrap">
  <div>
    <p class="eyebrow">${esc(t.hero.eyebrow)}</p>
    <h1>${t.hero.h1.map((l) => `<span>${esc(l)}</span>`).join('')}</h1>
    <p class="lead">${esc(t.hero.lead)}</p>
    <div class="cta"><a class="btn primary" href="#download">${dlIcon}${esc(t.hero.cta)}</a><a class="btn ghost" href="#story">${esc(t.hero.cta2)}</a></div>
    <p class="meta">${esc(t.hero.meta)}</p>
    <ul class="pills">${t.hero.pills.map((p) => `<li>${esc(p)}</li>`).join('')}</ul>
  </div>
  <div class="phone" role="img" aria-label="Umbra"><div class="screen"><img class="poster on" src="/clips/agents.jpg" alt=""><video class="on" data-autoplay muted loop playsinline preload="metadata" poster="/clips/agents.jpg" src="/clips/agents.mp4" aria-hidden="true"></video></div></div>
</div></section>

<section class="story" id="story"><div class="wrap">
  <div class="sec-head"><p class="eyebrow">${esc(t.story.eyebrow)}</p><h2>${esc(t.story.title)}</h2></div>
  <div class="story-grid">
    <div class="stage"><div class="phone"><div class="screen"></div></div><div class="rail" role="group" aria-label="${esc(t.story.title)}"></div></div>
    <div class="steps">${chapters}</div>
  </div>
  <p class="story-note">${esc(t.story.note)}</p>
</div></section>

<section class="more" id="more"><div class="wrap">
  <p class="eyebrow">${esc(t.more.eyebrow)}</p><h2>${esc(t.more.title)}</h2>
  <div class="bento">${t.more.items.map(([icon, h, p, cls]) => `<div class="tile reveal ${cls}"><div class="ic">${tileIcon(icon)}</div><h3>${esc(h)}</h3><p>${esc(p)}</p>${icon === 'levels' ? `<div class="levels">${['L0', 'L1', 'L2', 'L3', 'L4'].map((l) => `<span class="${l === 'L3' ? 'on' : ''}">${l}</span>`).join('')}</div>` : ''}</div>`).join('')}</div>
</div></section>

<section class="block" id="start"><div class="wrap">
  <p class="eyebrow">${esc(t.how.eyebrow)}</p><h2>${esc(t.how.title)}</h2>
  <div class="cards">${t.how.steps.map(([h, p], i) => `<div class="card"><span class="n">0${i + 1}</span><h3>${esc(h)}</h3><p>${esc(p)}</p></div>`).join('')}</div>
  <div class="two" style="margin-top:56px;align-items:center"><div><h2 style="font-size:clamp(26px,3vw,36px)">${esc(t.req.title)}</h2></div><div><table class="req">${t.req.rows.map(([a, b]) => `<tr><th scope="row">${esc(a)}</th><td>${esc(b)}</td></tr>`).join('')}</table></div></div>
</div></section>

<section class="block trust" id="trust"><div class="wrap">
  <p class="eyebrow">${esc(t.trust.eyebrow)}</p><h2>${esc(t.trust.title)}</h2>
  <p style="margin-top:20px">${esc(t.trust.body)}</p>
  <p><a class="link" href="${GITHUB}/blob/main/docs/traceability.md">${esc(t.trust.link)} →</a></p>
</div></section>

<section class="block dl" id="download"><div class="wrap two">
  <div><p class="eyebrow">${esc(t.download.eyebrow)}</p><h2>${esc(t.download.title)}</h2><p style="margin:18px 0 28px">${esc(lead)}</p>
  <a class="btn primary" href="/download/latest" download>${dlIcon}${esc(t.download.btn)} · ${APK.size}</a>
  <p class="download-stats" data-stats data-downloads="${esc(t.download.statsDownloads)}" data-monthly-active="${esc(t.download.statsActive)}" hidden></p>
  <p class="warn" style="margin-top:20px">${esc(t.download.warn)}</p><p><a href="${t.path}nodes/">${t.lang === 'en' ? 'Set up a Mac or Linux remote node →' : '設定 Mac 或 Linux 遠端節點 →'}</a></p></div>
  <div><p style="margin:0 0 4px;font-weight:600">${esc(t.download.sha)}</p>
  <div class="codeline"><code>${sha}</code><button type="button" data-copy="${sha}" data-done="${esc(t.download.copied)}">${esc(t.download.copy)}</button></div>
  <p style="margin:22px 0 4px;font-weight:600">${esc(t.download.install)}</p>
  <div class="codeline"><code>adb install -r ${APK.file}</code><button type="button" data-copy="adb install -r ${APK.file}" data-done="${esc(t.download.copied)}">${esc(t.download.copy)}</button></div></div>
</div></section>

<section class="block" id="faq"><div class="wrap" style="max-width:860px"><p class="eyebrow">${esc(t.faq.eyebrow)}</p><h2 style="margin-bottom:28px">${esc(t.faq.title)}</h2>${faq}</div></section>
</main>
<footer><div class="wrap"><span>© 2026 Umbra · ${esc(t.foot.tag)}</span><span>${legalLinks(t.lang !== 'en')} · <a href="${GITHUB}">${esc(t.foot.gh)}</a> · <a href="${GITHUB}/tree/main/docs">${esc(t.foot.docs)}</a> · <a href="#top" onclick="scrollTo(0,0);return false">${esc(t.foot.top)}</a></span></div></footer>
<script src="/story.js?v=${V}" defer></script>
<script src="/stats.js?v=${V}" defer></script>
</body></html>`;
}

rmSync('dist', { recursive: true, force: true });
mkdirSync('dist/en', { recursive: true });
mkdirSync('dist/zh-tw', { recursive: true });
if (existsSync('src/node-release.json')) {
  const release = JSON.parse(readFileSync('src/node-release.json', 'utf8'));
  for (const lang of ['en', 'zh-tw']) {
    mkdirSync(`dist/${lang}/nodes`, { recursive: true });
    writeFileSync(`dist/${lang}/nodes/index.html`, nodePage(lang, release, GITHUB));
  }
  writeFileSync('dist/_redirects', '/node /node/install.sh 302\n/nodes /en/nodes/ 302\n');
}
writeFileSync('dist/en/index.html', page(L.en, L['zh-tw']).replaceAll('{size}', APK.size));
writeFileSync('dist/zh-tw/index.html', page(L['zh-tw'], L.en).replaceAll('{size}', APK.size));
// "/" picks a language from the browser (and remembers an explicit choice); it also works without JavaScript.
writeFileSync('dist/index.html', `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Umbra</title>
<link rel="canonical" href="${SITE}/"><link rel="alternate" hreflang="en" href="${SITE}/en/"><link rel="alternate" hreflang="zh-Hant-TW" href="${SITE}/zh-tw/"><link rel="alternate" hreflang="x-default" href="${SITE}/">
<meta property="og:image" content="${SITE}/og.png"><meta name="robots" content="noindex,follow">
<script>(function(){var p;try{p=localStorage.getItem('umbra-lang')}catch(e){}if(!p){var l=(navigator.languages||[navigator.language||'']).join(',').toLowerCase();p=/zh/.test(l)?'zh-tw':'en'}location.replace('/'+p+'/'+location.hash)})()</script>
<style>body{font:18px/1.6 system-ui,sans-serif;background:#f6f6f4;color:#0b0b0b;display:grid;place-items:center;min-height:100vh;margin:0}a{color:#0b0b0b;font-weight:600;margin:0 12px}@media(prefers-color-scheme:dark){body{background:#000;color:#f3f3f1}a{color:#f3f3f1}}</style></head>
<body><p><a href="/zh-tw/">繁體中文</a> · <a href="/en/">English</a></p></body></html>`);
cpSync('src/site.css', 'dist/site.css');
cpSync('src/story.js', 'dist/story.js');
writeFileSync('dist/story.js', readFileSync('src/story.js', 'utf8') + `\ndocument.querySelectorAll('.langsw').forEach((a) => a.addEventListener('click', () => { try { localStorage.setItem('umbra-lang', a.dataset.lang); } catch (e) {} }));\n`);
if (existsSync('public')) cpSync('public', 'dist', { recursive: true });
mkdirSync('dist/legal', { recursive: true });
cpSync('../third_party', 'dist/legal/third_party', { recursive: true });
for (const file of ['LICENSE', 'PRIVACY.md', 'TERMS.md', 'USAGE_POLICY.md', 'THIRD_PARTY_NOTICES.md']) {
  cpSync(`../${file}`, `dist/legal/${file}`);
}
cpSync('../app/src/main/assets/licenses', 'dist/legal/component-licenses', { recursive: true });
for (const [route, title, file] of [
  ['privacy', 'Privacy policy', 'PRIVACY.md'],
  ['terms', 'Terms of service', 'TERMS.md'],
  ['policy', 'Preview use policy', 'USAGE_POLICY.md'],
  ['licenses', 'Licenses and third-party notices', 'THIRD_PARTY_NOTICES.md'],
]) {
  mkdirSync(`dist/${route}`, { recursive: true });
  const text = readFileSync(`../${file}`, 'utf8');
  const formatted = route === 'privacy' || route === 'terms';
  // Escape first, then render only the documents' simple Markdown links.
  const body = esc(text).replace(/\[([^\]\n]+)\]\(([^\s)]+)\)/g, (_, label, target) => {
    const href = /^https:\/\//.test(target) ? target : `${GITHUB}/blob/main/${target}`;
    return `<a href="${href.replace(/"/g, '&quot;')}">${label}</a>`;
  });
  writeFileSync(`dist/${route}/index.html`, `<!doctype html><html lang="en"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${title} · Umbra</title><link rel="canonical" href="${SITE}/${route}/"><link rel="stylesheet" href="/site.css"><link rel="icon" href="/favicon.svg">
</head><body><a class="skip" href="#document">Skip to content</a><main class="block"><article class="wrap legal-page" style="max-width:960px">
<nav aria-label="Website languages"><a href="/en/">Umbra · English</a> · <a href="/zh-tw/" lang="zh-Hant-TW">繁體中文</a></nav>
<h1 style="font-size:36px;margin:24px 0">${title}</h1>
<nav class="legal-nav" aria-label="Legal documents">${legalLinks(false)}</nav>
<div id="document" class="legal-body" tabindex="-1">${formatted ? legalBody(text, GITHUB) : `<pre style="white-space:pre-wrap;overflow-wrap:anywhere;font:inherit">${body}</pre>`}</div>
<p><a href="/legal/${file}">Download document</a> · <a href="/legal/LICENSE">MIT license</a></p>
<p><a href="${GITHUB}/blob/main/docs/RELEASE_LEGAL_REVIEW.md">Public release review and outstanding requirements</a></p>
</article></main></body></html>`);
}
// The app checks this file for updates (app/.../update/AppUpdater.kt); the APK itself comes from R2 through /download.
// Written only when src/apk.json describes the uploaded file of this very version (a stale one would offer the wrong APK).
if (APK.bytes > 0 && /^[0-9a-f]{64}$/.test(APK.sha256) && APK.versionCode > 0 && APK.file === `umbra-${APK.version}.apk`) {
  writeFileSync('dist/update.json', JSON.stringify({ version: APK.version, versionCode: APK.versionCode, file: APK.file, bytes: APK.bytes, sha256: APK.sha256, url: `/download/${APK.file}` }, null, 2) + '\n');
} else console.warn(`src/apk.json does not describe umbra-${APK.version}.apk: dist/update.json not written`);
writeFileSync('dist/robots.txt', `User-agent: *\nAllow: /\nDisallow: /download/\nSitemap: ${SITE}/sitemap.xml\n`);
writeFileSync('dist/sitemap.xml', `<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">${['en', 'zh-tw'].map((k) => `<url><loc>${SITE}${L[k].path}</loc><xhtml:link rel="alternate" hreflang="en" href="${SITE}/en/"/><xhtml:link rel="alternate" hreflang="zh-Hant-TW" href="${SITE}/zh-tw/"/></url>`).join('')}${['privacy', 'terms'].map(route => `<url><loc>${SITE}/${route}/</loc></url>`).join('')}</urlset>`);
writeFileSync('dist/_headers', `/.well-known/assetlinks.json\n  Content-Type: application/json; charset=utf-8\n  Cache-Control: public, max-age=3600\n/credential-handoff*\n  Cache-Control: no-store\n  X-Frame-Options: DENY\n  X-Robots-Tag: noindex, nofollow, noarchive\n  Permissions-Policy: camera=(), microphone=(), geolocation=()\n  Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'\n/node/install.sh\n  Content-Type: text/plain; charset=utf-8\n  Cache-Control: no-cache\n/node/minisign.pub\n  Content-Type: text/plain; charset=utf-8\n/update.json\n  Cache-Control: no-cache\n  Access-Control-Allow-Origin: *\n/models.json\n  Cache-Control: no-cache\n  Access-Control-Allow-Origin: *\n/clips/*\n  Cache-Control: public, max-age=604800\n/site.css\n  Cache-Control: public, max-age=3600\n/story.js\n  Cache-Control: public, max-age=3600\n/*\n  X-Content-Type-Options: nosniff\n  Referrer-Policy: strict-origin-when-cross-origin\n`);
console.log('built dist/');
