document.querySelectorAll('[data-node-copy]').forEach(button => {
  button.addEventListener('click', async () => {
    const label = button.querySelector('span');
    const original = label.textContent;
    try {
      await navigator.clipboard.writeText(button.dataset.nodeCopy);
      label.textContent = button.dataset.done;
      setTimeout(() => { label.textContent = original; }, 1600);
    } catch {
      const selection = window.getSelection();
      const range = document.createRange();
      range.selectNodeContents(button.closest('section').querySelector('code'));
      selection.removeAllRanges();
      selection.addRange(range);
    }
  });
});
