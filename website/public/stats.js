(async () => {
  const line = document.querySelector('[data-stats]');
  if (!line) return;
  try {
    const response = await fetch('/api/stats', { credentials: 'omit' });
    if (!response.ok) return;
    const stats = await response.json();
    if (![stats.downloads, stats.monthlyActive].every(value => Number.isSafeInteger(value) && value >= 0)) return;
    const format = new Intl.NumberFormat(document.documentElement.lang);
    const parts = [];
    if (stats.downloads) parts.push(line.dataset.downloads.replace('%n', format.format(stats.downloads)));
    if (stats.monthlyActive) parts.push(line.dataset.monthlyActive.replace('%n', format.format(stats.monthlyActive)));
    if (!parts.length) return;
    line.textContent = parts.join(' · ');
    line.hidden = false;
  } catch {}
})();
