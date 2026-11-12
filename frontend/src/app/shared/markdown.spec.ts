import { renderMarkdown } from './markdown';

describe('renderMarkdown', () => {
  it('escapes HTML so model output cannot inject markup', () => {
    const html = renderMarkdown('<img src=x onerror=alert(1)> **bold**');
    expect(html).not.toContain('<img');
    expect(html).toContain('&lt;img');
    expect(html).toContain('<strong>bold</strong>');
  });

  it('renders lists and keeps only safe links', () => {
    const html = renderMarkdown('- [Tender](/opportunities/abc)\n- [Docs](https://example.test/a)\n- [Bad](javascript:alert(1))');
    expect(html).toContain('<ul>');
    expect(html).toContain('<a href="/opportunities/abc"');
    expect(html).toContain('target="_blank" rel="noopener noreferrer"');
    expect(html).not.toContain('javascript:');
  });
});
