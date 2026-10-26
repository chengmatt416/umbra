const escape = text => text.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
const documents = { 'PRIVACY.md': '/privacy/', 'TERMS.md': '/terms/', 'USAGE_POLICY.md': '/policy/', 'THIRD_PARTY_NOTICES.md': '/licenses/', 'LICENSE': '/legal/LICENSE' };

// Only the headings, paragraphs, lists and inline formatting used by our policy documents.
export function legalBody(markdown, github) {
  const inline = text => escape(text)
    .replace(/\[([^\]\n]+)\]\(([^\s)]+)\)/g, (_, label, target) => {
      const href = documents[target] || (/^https:\/\//.test(target) ? target : `${github}/blob/main/${target}`);
      return `<a href="${href}">${label}</a>`;
    })
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>');
  return markdown.trim().split(/\n\s*\n/).filter(block => !block.startsWith('# ')).map(block => {
    if (block.startsWith('## ')) return `<h2>${inline(block.slice(3))}</h2>`;
    if (block.startsWith('- ')) return `<ul>${block.split(/\n(?=- )/).map(item => `<li>${inline(item.slice(2).replace(/\n\s*/g, ' '))}</li>`).join('')}</ul>`;
    return `<p>${inline(block.replace(/\n/g, ' '))}</p>`;
  }).join('\n');
}
