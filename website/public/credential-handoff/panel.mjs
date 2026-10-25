export function panelIntent(fragment) {
  const token = typeof fragment === 'string' && fragment.startsWith('#') ? fragment.slice(1) : '';
  return /^[A-Za-z0-9_-]{32,100}$/.test(token)
    ? `intent://credential-handoff/${token}#Intent;scheme=umbra;package=app.umbra.agent;end`
    : null;
}

export function preparePanel(document, location, history, language = '') {
  const intent = panelIntent(location.hash);
  // Remove the nonce from the visible URL; this launcher keeps it only in memory.
  history.replaceState(null, '', location.pathname);
  const zh = language.toLowerCase().startsWith('zh');
  document.documentElement.lang = zh ? 'zh-Hant-TW' : 'en';
  document.title = zh ? '安全輸入 · Umbra' : 'Secure input · Umbra';
  document.getElementById('title').textContent = zh ? '請在手機上繼續' : 'Continue on your phone';
  document.getElementById('message').textContent = intent
    ? (zh ? '請在向 Umbra 提出安全輸入請求的手機上開啟此連結。' : 'Open this request on the phone where you asked Umbra for secure input.')
    : (zh ? '請在提出請求的手機上，開啟 Umbra 提供的有效連結。' : 'Open a current link from Umbra on the requesting phone.');
  document.getElementById('hint').textContent = zh ? '請在手機的原生面板中輸入密碼及驗證碼。' : 'Enter passwords and verification codes in the native phone panel.';
  const button = document.getElementById('open-input');
  button.hidden = !intent;
  if (intent) {
    button.href = intent;
    button.textContent = zh ? '開啟安全輸入' : 'Open secure input';
    button.rel = 'noreferrer';
  } else button.removeAttribute('href');
}

if (typeof document !== 'undefined') preparePanel(document, location, history, navigator.language || '');
