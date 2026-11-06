import { escapeHtml as escape } from './skill-utils.mjs?v=20261103-parallel-recall';
import { jdProductUrl } from './product-source.mjs?v=20261103-parallel-recall';

export function productComparisonMarkup(comparison, sources = []) {
  if (!comparison) return '';
  const evidence = new Map(sources.map(source => [source.id, source]));
  const claims = (items = []) => items.map(claim => {
    const source = evidence.get(claim.evidenceId);
    const url = source && jdProductUrl(source.sourceUrl, source.productId);
    return `<li>${claim.dimension ? `<strong>${escape(claim.dimension)}：</strong>` : ''}${escape(claim.text)}${source ? `<details class="knowledge-quote"><summary>查看依据 · ${escape(source.title)}</summary><blockquote>${escape(claim.quote)}</blockquote><small>${escape(source.source)} · 索引 ${escape(source.version)}</small>${url ? `<p><a href="${escape(url)}" target="_blank" rel="noopener noreferrer">京东商详原始来源 ↗</a><br><small>采集时间：${escape(source.capturedAt)}；页面标注不等于独立测评。</small></p>` : ''}</details>` : ''}</li>`;
  }).join('');
  return `<section class="product-comparison"><h4>知识依据与取舍</h4>${comparison.status === 'INSUFFICIENT_EVIDENCE'
    ? '<p class="knowledge-warning">资料不足，暂不作性能优劣判断。</p>'
    : `${comparison.status === 'PARTIAL_EVIDENCE' ? '<p class="knowledge-warning">仅有部分页面依据，不作完整性能优劣判断。</p>' : ''}<h5>优势</h5>${comparison.strengths?.length ? `<ul>${claims(comparison.strengths)}</ul>` : '<p class="field-hint">暂无充分依据。</p>'}<h5>限制</h5>${comparison.limitations?.length ? `<ul>${claims(comparison.limitations)}</ul>` : '<p class="field-hint">未取得可证实的使用限制，不等于没有缺点。</p>'}`}
    ${comparison.dimensions?.length ? `<h5>多维对比依据</h5><ul>${claims(comparison.dimensions)}</ul>` : ''}
    <p><strong>适合你的原因：</strong>${escape(comparison.fit)}</p><p class="field-hint">${escape(comparison.uncertainty)}</p></section>`;
}
