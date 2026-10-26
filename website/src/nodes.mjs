const text = {
  en: {
    title: 'Let your phone’s agent use your computer', label: 'Remote nodes', features: 'Features', app: 'Download app', docs: 'Docs',
    intro: 'Install umbra-node on Mac or Linux. Run long jobs on your computer, or view and control its screen through an encrypted connection.',
    terminal: 'Terminal', copy: 'Copy', copied: 'Copied', install: 'Detects macOS / Linux and arm64 / x86_64, verifies SHA-256 and minisign, then opens setup.',
    download: 'Download', headless: 'Linux · terminal only', mac: 'Universal · macOS 14 or later', linux: 'Desktop · Ubuntu 24.04 or compatible', server: 'Terminal only · servers and NAS',
    signature: 'Signature', source: 'Source (MIT)', key: 'minisign public key', windows: 'Windows later',
    macNote: 'This macOS build is ad-hoc signed, not notarized. On first launch, right-click → Open, or use Privacy & Security → Open Anyway.',
    steps: [['Install', 'Use the command, or download and open the desktop app.'], ['Pair', 'Scan the wizard’s QR in Umbra → Nodes. Confirm the names on both devices.'], ['Use it', 'Run jobs and share the screen. You can revoke access at any time.']],
    privacy: 'Jobs run as your computer’s user. Screen sharing and agent input are optional and require permission on both devices. VNC stays inside the paired TLS connection. Pair on a trusted local network or your own VPN; no public port forwarding is needed.',
    requirements: 'For Linux screen sharing, install x11vnc (X11) or wayvnc (compatible Wayland compositors). Desktops without a supported VNC backend remain available for jobs.',
  },
  'zh-tw': {
    title: '讓手機上的代理，用你的電腦', label: '遠端節點', features: '功能', app: '下載 App', docs: '文件',
    intro: '在 Mac 或 Linux 裝 umbra-node：手機可以把長時間的工作放上去跑，也能經過加密連線看到並操作這台的畫面。',
    terminal: '終端機', copy: '複製', copied: '已複製', install: '自動判斷 macOS／Linux 與 arm64／x86_64，檢查 SHA-256 與 minisign 簽章，再開啟設定精靈。',
    download: '下載', headless: 'Linux（無桌面）', mac: 'Universal・macOS 14 以上', linux: '桌面版・Ubuntu 24.04 或相容系統', server: '只要終端機・伺服器、NAS',
    signature: '簽章', source: '原始碼（MIT）', key: 'minisign 公鑰', windows: 'Windows 之後推出',
    macNote: '此 macOS 版本為 ad-hoc 簽署，未經公證。第一次請右鍵 → 打開，或到「隱私權與安全性 → 仍要打開」。',
    steps: [['裝好', 'curl 一行，或下載後雙擊。'], ['配對', '手機在 Umbra → 節點掃精靈上的 QR，兩邊確認名字。'], ['用它', '代理放工作上去、看畫面；你隨時能撤銷。']],
    privacy: '工作以電腦使用者的身分執行。螢幕分享與代理輸入可自由選擇，並需兩端允許；VNC 僅在配對後的 TLS 連線內傳送。請使用可信任的區域網路或自己的 VPN，不必開放公開連接埠。',
    requirements: 'Linux 畫面分享需安裝 x11vnc（X11）或 wayvnc（相容的 Wayland compositor）。沒有支援的 VNC 後端時，仍可執行工作。',
  },
};
const esc = s => String(s).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
const icon = name => `<img src="/nodes/${name}.svg" alt="" aria-hidden="true">`;
const size = file => `${(file.bytes / 1048576).toFixed(1)} MiB`;

export function nodePage(lang, release, github) {
  const t = text[lang], other = lang === 'en' ? 'zh-tw' : 'en';
  const command = 'curl -fsSL https://umbra-agent.pages.dev/node | sh';
  const file = (platform, ext = '') => release.files[`umbra-node-${release.version}-${platform}${ext}`];
  const button = (f, title, name = 'download', primary = false) => `<a class="node-button${primary ? ' primary' : ''}" href="${esc(f.url)}" download>${icon(name)}${esc(title)} <small>· ${size(f)}</small></a>`;
  const variants = ext => ['amd64', 'arm64'].map(arch => `<div class="node-variant">${button(file(`linux-${arch}`, ext), arch === 'amd64' ? 'x86_64' : 'arm64', ext === '.tar.gz' ? 'terminal' : 'download')}<a class="node-signature" href="${esc(file(`linux-${arch}`, ext).signature)}">${t.signature}</a></div>`).join('');
  return `<!doctype html><html lang="${lang === 'en' ? 'en' : 'zh-Hant-TW'}" data-theme="light"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>${esc(t.label)} · Umbra</title><meta name="description" content="${esc(t.intro)}">
<link rel="canonical" href="https://umbra-agent.pages.dev/${lang}/nodes/"><link rel="alternate" hreflang="en" href="https://umbra-agent.pages.dev/en/nodes/"><link rel="alternate" hreflang="zh-Hant-TW" href="https://umbra-agent.pages.dev/zh-tw/nodes/">
<link rel="icon" href="/favicon.svg"><link rel="stylesheet" href="/site.css"><link rel="stylesheet" href="/nodes/nodes.css">
</head>
<body class="node-page"><div class="node-wrap"><header class="node-nav"><a class="node-brand" href="/${lang}/">Umbra</a><nav aria-label="${esc(t.label)}"><a href="/${lang}/#story">${t.features}</a><a href="/${lang}/#download">${t.app}</a><a href="/${lang}/nodes/" aria-current="page">${t.label}</a><a href="${github}/tree/remote-nodes/node-go">${t.docs}</a><a class="langsw" href="/${other}/nodes/" data-lang="${other}">${lang === 'en' ? '繁中' : 'EN'}</a></nav></header>
<main><section class="node-intro"><p>${t.label}</p><h1>${t.title}</h1><p>${t.intro}</p></section>
<section class="node-command" aria-label="${t.terminal}"><div><span>${t.terminal}</span><button type="button" data-node-copy="${command}" data-done="${t.copied}">${icon('copy')}<span aria-live="polite">${t.copy}</span></button></div><code>${command}</code><p>${t.install}</p></section>
<section class="node-downloads" aria-label="${t.download}"><article><p>macOS</p><h2>Umbra Node ${esc(release.version)}.dmg</h2><p>${t.mac}</p>${button(file('macos-universal', '.dmg'), t.download, 'download-light', true)}<a class="node-signature" href="${esc(file('macos-universal', '.dmg').signature)}">${t.signature}</a></article><article><p>Linux</p><h2>Umbra Node ${esc(release.version)}.AppImage</h2><p>${t.linux}</p>${variants('.AppImage')}</article><article><p>${t.headless}</p><h2>umbra-node-${esc(release.version)}.tar.gz</h2><p>${t.server}</p>${variants('.tar.gz')}</article></section>
<div class="node-proof"><a href="${release.checksums}">SHA256SUMS</a><a href="${release.checksumsSignature}">${t.signature}</a><a href="/node/minisign.pub">${t.key}</a><a href="${github}/tree/remote-nodes/node-go">${t.source}</a><span>${t.windows}</span></div>
<ol class="node-steps">${t.steps.map(([title,body],i) => `<li><span>${i+1}</span><h2>${title}</h2><p>${body}</p></li>`).join('')}</ol>
<div class="node-notes"><p>${t.macNote}</p><p>${t.privacy}</p><p>${t.requirements}</p></div><footer class="node-proof"><a href="/privacy/">${lang === 'en' ? 'Privacy' : '隱私政策'}</a><a href="/terms/">${lang === 'en' ? 'Terms of service' : '服務條款'}</a><a href="/policy/">${lang === 'en' ? 'Use policy' : '使用政策'}</a><a href="/licenses/">${lang === 'en' ? 'Licenses & notices' : '授權與致謝'}</a></footer></main></div><script src="/nodes/copy.js" defer></script></body></html>`;
}
