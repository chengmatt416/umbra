// Static build, no dependencies: node build.mjs  ->  dist/
import { mkdirSync, writeFileSync, cpSync, rmSync, readFileSync, existsSync } from 'node:fs';
import { L, APK, GITHUB } from './src/content.mjs';

const V = Date.now().toString(36);
const SITE = process.env.SITE_URL || 'https://umbra-agent.pages.dev';
const sha = APK.sha256;
const esc = (s) => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const mark = `<svg viewBox="0 0 32 32" aria-hidden="true"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#8fa88f"/><stop offset="1" stop-color="#476d54"/></linearGradient></defs><circle cx="16" cy="16" r="14" fill="url(#g)"/><circle cx="21.5" cy="12" r="10.5" fill="#e9ede5" opacity=".92" style="mix-blend-mode:normal"/></svg>`;
const dlIcon = `<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v12m0 0l-5-5m5 5l5-5M4 20h16"/></svg>`;

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
<meta name="theme-color" content="#e9ede5" media="(prefers-color-scheme: light)"><meta name="theme-color" content="#1c2823" media="(prefers-color-scheme: dark)">
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
  <nav class="nav" aria-label="Main"><a href="#story">${esc(t.nav.features)}</a><a href="#start">${esc(t.nav.how)}</a><a href="#trust">${esc(t.nav.trust)}</a><a href="#faq">${esc(t.nav.faq)}</a></nav>
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
  <div class="phone" role="img" aria-label="Umbra"><div class="screen"><img class="poster on" src="/clips/say.jpg" alt=""><video class="on" data-autoplay muted loop playsinline preload="metadata" poster="/clips/say.jpg" src="/clips/say.mp4" aria-hidden="true"></video></div></div>
</div></section>

<section class="story" id="story"><div class="wrap">
  <div class="sec-head"><p class="eyebrow">${esc(t.story.eyebrow)}</p><h2>${esc(t.story.title)}</h2></div>
  <div class="story-grid">
    <div class="stage"><div class="phone"><div class="screen"></div></div><div class="rail" role="group" aria-label="${esc(t.story.title)}"></div></div>
    <div class="steps">${chapters}</div>
  </div>
  <p class="story-note">${esc(t.story.note)}</p>
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
  <a class="btn primary" href="/download/${APK.file}" download>${dlIcon}${esc(t.download.btn)} · ${APK.size}</a>
  <p class="warn" style="margin-top:20px">${esc(t.download.warn)}</p></div>
  <div><p style="margin:0 0 4px;font-weight:600">${esc(t.download.sha)}</p>
  <div class="codeline"><code>${sha}</code><button type="button" data-copy="${sha}" data-done="${esc(t.download.copied)}">${esc(t.download.copy)}</button></div>
  <p style="margin:22px 0 4px;font-weight:600">${esc(t.download.install)}</p>
  <div class="codeline"><code>adb install -r ${APK.file}</code><button type="button" data-copy="adb install -r ${APK.file}" data-done="${esc(t.download.copied)}">${esc(t.download.copy)}</button></div></div>
</div></section>

<section class="block" id="faq"><div class="wrap" style="max-width:860px"><p class="eyebrow">${esc(t.faq.eyebrow)}</p><h2 style="margin-bottom:28px">${esc(t.faq.title)}</h2>${faq}</div></section>
</main>
<footer><div class="wrap"><span>© 2026 Umbra · ${esc(t.foot.tag)}</span><span><a href="${GITHUB}">${esc(t.foot.gh)}</a> · <a href="${GITHUB}/tree/main/docs">${esc(t.foot.docs)}</a> · <a href="#top" onclick="scrollTo(0,0);return false">${esc(t.foot.top)}</a></span></div></footer>
<script src="/story.js?v=${V}" defer></script>
</body></html>`;
}

rmSync('dist', { recursive: true, force: true });
mkdirSync('dist/en', { recursive: true });
mkdirSync('dist/zh-tw', { recursive: true });
writeFileSync('dist/en/index.html', page(L.en, L['zh-tw']).replaceAll('{size}', APK.size));
writeFileSync('dist/zh-tw/index.html', page(L['zh-tw'], L.en).replaceAll('{size}', APK.size));
// "/" picks a language from the browser (and remembers an explicit choice); it also works without JavaScript.
writeFileSync('dist/index.html', `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>Umbra</title>
<link rel="canonical" href="${SITE}/"><link rel="alternate" hreflang="en" href="${SITE}/en/"><link rel="alternate" hreflang="zh-Hant-TW" href="${SITE}/zh-tw/"><link rel="alternate" hreflang="x-default" href="${SITE}/">
<meta property="og:image" content="${SITE}/og.png"><meta name="robots" content="noindex,follow">
<script>(function(){var p;try{p=localStorage.getItem('umbra-lang')}catch(e){}if(!p){var l=(navigator.languages||[navigator.language||'']).join(',').toLowerCase();p=/zh/.test(l)?'zh-tw':'en'}location.replace('/'+p+'/'+location.hash)})()</script>
<style>body{font:18px/1.6 system-ui,sans-serif;background:#e9ede5;color:#32443a;display:grid;place-items:center;min-height:100vh;margin:0}a{color:#476d54;font-weight:600;margin:0 12px}</style></head>
<body><p><a href="/zh-tw/">繁體中文</a> · <a href="/en/">English</a></p></body></html>`);
cpSync('src/site.css', 'dist/site.css');
cpSync('src/story.js', 'dist/story.js');
writeFileSync('dist/story.js', readFileSync('src/story.js', 'utf8') + `\ndocument.querySelectorAll('.langsw').forEach((a) => a.addEventListener('click', () => { try { localStorage.setItem('umbra-lang', a.dataset.lang); } catch (e) {} }));\n`);
if (existsSync('public')) cpSync('public', 'dist', { recursive: true });
writeFileSync('dist/robots.txt', `User-agent: *\nAllow: /\nDisallow: /download/\nSitemap: ${SITE}/sitemap.xml\n`);
writeFileSync('dist/sitemap.xml', `<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">${['en', 'zh-tw'].map((k) => `<url><loc>${SITE}${L[k].path}</loc><xhtml:link rel="alternate" hreflang="en" href="${SITE}/en/"/><xhtml:link rel="alternate" hreflang="zh-Hant-TW" href="${SITE}/zh-tw/"/></url>`).join('')}</urlset>`);
writeFileSync('dist/_headers', `/clips/*\n  Cache-Control: public, max-age=604800\n/site.css\n  Cache-Control: public, max-age=3600\n/story.js\n  Cache-Control: public, max-age=3600\n/*\n  X-Content-Type-Options: nosniff\n  Referrer-Policy: strict-origin-when-cross-origin\n`);
console.log('built dist/');
