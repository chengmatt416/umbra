(() => {
  const root = document.getElementById('app');
  let state, step = 0, busy = false, error = '', en = false, fingerprint = '';
  const t = (zh, english) => en ? english : zh;
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const icon = (name, source = '43-4312') => `<img alt="" src="assets/${source}-imgIcon${name}.svg">`;
  const pill = (text, action, alt = false) => `<button class="pill${alt ? ' alt' : ''}" data-action="${action}" ${busy ? 'disabled' : ''}>${esc(text)}</button>`;
  const row = (title, detail, good = true, extra = '') => `<div class="row"><span class="status-icon${good ? '' : ' warn'}">${icon(good ? 'Check' : 'Alert')}</span><div class="body"><strong>${esc(title)}</strong><small>${esc(detail)}</small></div>${extra}</div>`;
  const toggle = (title, detail, value, action) => `<div class="row"><div class="body"><strong>${esc(title)}</strong><small>${esc(detail)}</small></div><button class="toggle" role="switch" aria-label="${esc(title)}" aria-checked="${value}" data-action="${action}" ${busy ? 'disabled' : ''}><span></span></button></div>`;
  const addresses = () => state.addresses.map(a => `${a.kind === 'lan' ? t('區網', 'LAN') : a.kind} ${a.host}:${a.port}`).join(' · ');
  function render() {
    if (!state) { root.innerHTML = `<p class="loading">${esc(error || t('正在連接本機節點…', 'Connecting to the local node…'))}</p>`; return; }
    const s = state, labels = en ? ['Check', 'Pair', 'Screen access', 'Reach', 'Background', 'Done'] : ['檢查', '配對', '畫面存取', '連線', '背景執行', '完成'];
    let title, intro, content, note = '';
    switch (step) {
      case 0:
        title = t('先看看這台電腦', 'First, check this computer');
        intro = t('umbra-node 會在這台電腦替手機跑工作，也可以讓手機上的代理看這台的畫面。下面是它找到的東西。', 'Umbra Node runs jobs for your phone and can share this computer’s screen with its agents. Here is what it found.');
        content = row(`${s.os} (${s.arch})`, t('支援', 'Supported')) + row(t('網路', 'Network'), addresses()) + row(s.screen.available ? t('畫面分享已開', 'Screen sharing ready') : t('畫面分享還沒開', 'Screen sharing is off'), t('下一步「畫面存取」會帶你打開；不開也能只跑工作', 'Screen access will guide you through it. You can also run jobs only.'), s.screen.available) + row(t('磁碟', 'Disk'), `${s.freeGB} GB · ${s.workspace}`);
        break;
      case 1: {
        title = t('用手機掃這個 QR', 'Scan this QR with your phone');
        intro = t('在手機打開 Umbra → 節點 → 新增節點（所有模式）。10 分鐘內有效，最多試 5 次。', 'On your phone: Umbra → Nodes → Add node, in any mode. Valid for 10 minutes, with 5 attempts.');
        const expired = Date.now() >= s.invitation.expiresAtMs || s.attempts <= 0;
        const paired = s.phones.length > 0 && !s.pending;
        content = `<div class="pair"><div class="qr">${expired ? esc(t('配對碼已過期', 'Code expired')) : `<img alt="${esc(t('節點配對 QR', 'Node pairing QR'))}" src="${esc(s.qr)}">`}</div><div class="pair-info"><p class="note">${esc(t('或輸入配對碼', 'Or enter the pairing code'))}</p><div class="code">${esc(s.invitation.code)}</div><div class="fingerprint">SHA-256 ${esc(s.invitation.fingerprint.slice(0, 8))}…${esc(s.invitation.fingerprint.slice(-8))}</div><button class="copy" data-action="copy">${esc(t('複製邀請連結', 'Copy invitation link'))}</button><div class="pending">${s.pending ? `<strong>${esc(t('「', '“') + s.pending.name + t('」想配對', '” wants to pair'))}</strong><p>${esc(t('手機上也會顯示這台的名字。兩邊都對才按配對。', 'Check the computer and phone names on both devices before pairing.'))}</p><div class="pending-buttons">${pill(t('拒絕', 'Decline'), 'decline', true)}${pill(t('配對', 'Pair'), 'approve')}</div>` : `<strong>${esc(paired ? t('已配對的手機', 'Paired phones') : t('等待手機…', 'Waiting for your phone…'))}</strong><p>${esc(paired ? s.phones.map(p => p.name).join(' · ') : t('請先在手機上確認這台電腦。', 'Confirm this computer on the phone.'))}</p>${pill(t('重新產生', 'New code'), 'pair-open', true)}`}</div></div></div>`;
        note = t('配對只證明身分，不是安全檢查：手機的工作會以你的使用者身分在這台執行。', 'Pairing proves identity. Jobs run as your user on this computer.');
        break;
      }
      case 2: {
        title = t('讓手機看得到這台的畫面', 'Let your phone see this screen');
        intro = t('畫面走在已配對的加密連線裡，不會在網路上開 5900 埠。你也可以略過，只跑工作。', 'The screen travels inside the paired encrypted connection. Port 5900 is never exposed. You can skip this and run jobs only.');
        const choices = s.os === 'darwin' ? [['native', t('umbra-node 自己的畫面伺服器（建議）', 'Umbra’s own screen server (recommended)'), t('ScreenCaptureKit，macOS 14 以上，只在手機連上時擷取', 'ScreenCaptureKit, macOS 14+, captures only while a phone is connected')]] : [['x11vnc', 'x11vnc', t('X11 桌面；只聽 127.0.0.1', 'X11 desktops; loopback only')], ['wayvnc', 'wayvnc', t('wlroots 桌面（Sway、Hyprland）；只聽本機', 'wlroots desktops (Sway, Hyprland); loopback only')]];
        content = choices.map(([value, label, detail]) => `<button class="radio-row" data-action="screen:${value}" aria-pressed="${s.config.screen === value}"><span class="radio-mark"></span><span>${esc(label)}<small>${esc(detail)}</small></span></button>`).join('');
        if (s.os === 'darwin') content += row(t('螢幕錄製', 'Screen Recording'), s.permissions?.screenRecording ? t('已允許', 'Allowed') : t('尚未允許', 'Not allowed yet'), !!s.permissions?.screenRecording, pill(t('打開系統設定', 'Open Settings'), 'permission', true)) + row(t('輔助使用（讓手機點擊、打字）', 'Accessibility (pointer and keyboard)'), s.permissions?.accessibility ? t('已允許', 'Allowed') : t('尚未允許', 'Not allowed yet'), !!s.permissions?.accessibility, pill(t('打開系統設定', 'Open Settings'), 'accessibility', true));
        else content += row(t('可用的後端', 'Installed backends'), s.backends.join(' · ') || t('尚未安裝 x11vnc 或 wayvnc', 'Install x11vnc or wayvnc with your package manager'), s.backends.length > 0) + `<p class="note">${esc(t('GNOME Wayland 若只有 RDP 就無法使用 VNC；仍可只跑工作。精靈不會將系統螢幕共享開放到網路。', 'GNOME Wayland installations with RDP only can run jobs without VNC. The wizard never exposes system screen sharing to the network.'))}</p>`;
        content += `<button class="copy" data-action="screen:">${esc(t('關閉畫面分享，只跑工作', 'Disable screen sharing; jobs only'))}</button>`;
        note = t('不開也可以：這台就只跑工作。', 'Optional: run jobs without screen access.');
        break;
      }
      case 3:
        title = t('手機怎麼找到這台', 'How your phone finds this computer');
        intro = t('同一個網路直接連；出門在外用 Tailscale（或你自己的 VPN）。QR 裡已經放了所有位址。', 'Connect directly on the same network, or use Tailscale or your own VPN while away. The QR contains every detected address.');
        content = s.addresses.map(a => row(a.kind === 'lan' ? t('區網', 'LAN') : a.kind, `${a.host}:${a.port}`)).join('') + row(t('從手機測試', 'Test from the phone'), s.lastSeenMs ? `${t('最近回應', 'Last contact')}: ${new Date(s.lastSeenMs).toLocaleTimeString()}` : t('尚未測試：在手機開啟節點詳情，會自動測試', 'Not tested yet. Open node details on the phone to test.'), !!s.lastSeenMs, pill(t('重新整理', 'Refresh'), 'refresh', true));
        break;
      case 4:
        title = t('在背景一直開著', 'Keep it running in the background');
        intro = t('登入電腦後自動啟動，不需要開著這個視窗。設定後下次登入生效；目前節點已在背景執行。', 'Start automatically at login without keeping this window open. This takes effect next login; the current node is already running in the background.');
        content = toggle(t('登入時啟動', 'Start at login'), s.servicePath, s.background, 'background') + toggle(t('讓手機上的代理看畫面', 'Let phone agents access this screen'), t('仍須在手機上允許，操作前照審核規則', 'Also requires permission on the phone; input follows approval rules'), s.config.agentScreen, 'agent') + toggle(t('電腦閒置時不要睡眠', 'Keep awake while working'), t('有工作在跑時才阻止睡眠', 'Prevents sleep only while jobs are running'), s.config.keepAwake, 'awake');
        break;
      case 5:
        title = t('好了', 'Ready');
        intro = `${s.name} · ${s.phones.length ? s.phones.map(p => p.name).join(' · ') : t('尚未配對手機，可以之後再配對', 'No paired phone yet; pair later with setup')}`;
        content = `<dl class="summary"><dt>${esc(t('狀態', 'Status'))}</dt><dd>${esc(t('線上・背景執行中', 'Online · running in background'))}</dd><dt>${esc(t('畫面', 'Screen'))}</dt><dd>${esc(s.screen.detail)}</dd><dt>${esc(t('位址', 'Reach'))}</dt><dd>${esc(addresses())}</dd><dt>${esc(t('信任', 'Trust'))}</dt><dd>${esc(t('有限（在手機上調高才會放工作）', 'Limited; raise trust on the phone before sending jobs'))}</dd></dl><pre class="commands">$ umbra-node status\n$ umbra-node setup\n$ umbra-node uninstall --confirm</pre>`;
        break;
    }
    root.innerHTML = `<aside><div class="brand"><span class="brand-icon">${icon('Server')}</span><div><strong>Umbra Node</strong><small>${esc(t('設定精靈', 'Setup wizard'))} · ${esc(s.version)}</small></div></div>${labels.map((label, i) => `<button class="step ${i === step ? 'current' : i < step ? 'done' : ''}" data-step="${i}" aria-current="${i === step ? 'step' : 'false'}"><span class="mark">${i < step ? '✓' : i + 1}</span>${esc(label)}</button>`).join('')}<div class="host">${esc(s.os)} · ${esc(s.arch)}</div><button class="language" data-action="language">${en ? '繁體中文' : 'English'}</button></aside><main><h1>${esc(title)}</h1><p class="intro${step === 0 ? ' check' : ''}">${esc(intro)}</p><div class="content">${content}</div>${error ? `<p class="error" role="alert">${esc(error)}</p>` : ''}<footer class="actions"><small>${esc(note)}</small>${step ? pill(t('返回', 'Back'), 'back', true) : ''}${pill(step === 5 ? t('完成', 'Done') : t('下一步', 'Next'), 'next')}</footer></main>`;
  }
  async function refresh() { try { const value = await window.go.main.Wizard.State(); const next = JSON.stringify(value); if (next !== fingerprint) { state = value; fingerprint = next; render(); } } catch (e) { error = String(e); render(); } }
  root.addEventListener('click', async e => {
    const button = e.target.closest('button'); if (!button || busy) return;
    if (button.dataset.step) { step = Number(button.dataset.step); render(); return; }
    const a = button.dataset.action; let command;
    if (a === 'language') { en = !en; document.documentElement.lang = en ? 'en' : 'zh-Hant'; render(); return; }
    if (a === 'back') { step = Math.max(0, step - 1); render(); return; }
    if (a === 'next') { if (step === 5) { window.runtime?.Quit(); return; } step++; render(); return; }
    if (a === 'refresh') { await refresh(); return; }
    if (a === 'copy') { try { await navigator.clipboard.writeText(state.uri); } catch (e) { error = String(e); render(); } return; }
    if (a === 'approve' || a === 'decline') command = {action:'pair-decision',id:state.pending?.id,allow:a === 'approve'};
    else if (a === 'pair-open') command = {action:a};
    else if (a.startsWith('screen:')) command = {action:'screen',backend:a.slice(7)};
    else if (a === 'permission' || a === 'accessibility') command = {action:'permission',kind:a === 'permission' ? 'screen' : 'accessibility'};
    else if (a === 'background') command = {action:'background',enabled:!state.background};
    else if (a === 'agent' || a === 'awake') command = {action:'settings',agentScreen:a === 'agent' ? !state.config.agentScreen : state.config.agentScreen,keepAwake:a === 'awake' ? !state.config.keepAwake : state.config.keepAwake};
    if (!command) return;
    busy = true; error = ''; render();
    try { state = await window.go.main.Wizard.Action(JSON.stringify(command)); fingerprint = ''; }
    catch (e) { error = String(e); }
    finally { busy = false; render(); }
  });
  refresh(); setInterval(refresh, 1500);
})();
