// Scroll-driven feature story: the phone on the stage plays the clip of the step in the middle of the viewport.
(() => {
  const steps = [...document.querySelectorAll('.step')];
  const stage = document.querySelector('.stage .screen');
  if (!steps.length || !stage) return;
  const reduce = matchMedia('(prefers-reduced-motion: reduce)').matches;
  const rail = document.querySelector('.rail');
  const clips = steps.map((s, i) => {
    const v = document.createElement('video');
    v.muted = true; v.loop = true; v.playsInline = true; v.preload = 'none';
    v.setAttribute('aria-hidden', 'true'); v.tabIndex = -1;
    v.poster = s.dataset.poster; v.dataset.src = s.dataset.clip;
    stage.appendChild(v);
    const b = document.createElement('button');
    b.type = 'button'; b.setAttribute('aria-label', s.querySelector('h3').textContent);
    b.addEventListener('click', () => s.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'center' }));
    rail.appendChild(b);
    return { v, b };
  });
  const load = (i) => { const c = clips[i]; if (c && !c.v.src) { c.v.src = c.v.dataset.src; c.v.preload = 'auto'; } };
  let current = -1, visible = false;
  const activate = (i) => {
    if (i === current) return;
    current = i;
    steps.forEach((s, k) => s.classList.toggle('active', k === i));
    clips.forEach((c, k) => {
      const on = k === i;
      c.b.setAttribute('aria-current', on ? 'true' : 'false');
      c.v.classList.toggle('on', on);
      if (on) { load(k); load(k + 1); try { c.v.currentTime = 0; } catch (e) {} if (!reduce && visible) c.v.play().catch(() => {}); }
      else c.v.pause();
    });
  };
  const io = new IntersectionObserver((entries) => {
    entries.forEach((e) => { if (e.isIntersecting) activate(steps.indexOf(e.target)); });
  }, { rootMargin: matchMedia('(max-width: 900px)').matches ? '-66% 0px -28% 0px' : '-46% 0px -46% 0px', threshold: 0 });
  steps.forEach((s) => io.observe(s));
  activate(0);
  // play only while the stage is on screen
  new IntersectionObserver((es) => {
    visible = es[0].isIntersecting;
    const c = clips[current];
    if (!c) return;
    if (visible && !reduce && !document.hidden) c.v.play().catch(() => {}); else c.v.pause();
  }, { threshold: 0.1 }).observe(document.querySelector('.stage'));
  // pause everything when the tab is hidden
  document.addEventListener('visibilitychange', () => { const c = clips[current]; if (!c) return; if (document.hidden || !visible) c.v.pause(); else if (!reduce) c.v.play().catch(() => {}); });
})();

// hero clip + copy buttons
document.querySelectorAll('video[data-autoplay]').forEach((v) => { if (!matchMedia('(prefers-reduced-motion: reduce)').matches) v.play().catch(() => {}); });
document.querySelectorAll('[data-copy]').forEach((b) => b.addEventListener('click', () => {
  navigator.clipboard?.writeText(b.dataset.copy).then(() => { const t = b.textContent; b.textContent = b.dataset.done; setTimeout(() => (b.textContent = t), 1600); });
}));
