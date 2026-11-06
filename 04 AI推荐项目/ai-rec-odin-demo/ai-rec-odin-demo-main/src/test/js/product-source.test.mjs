import test from 'node:test';
import assert from 'node:assert/strict';
import { jdProductUrl, productSourceMarkup } from '../../main/resources/static/product-source.mjs';
import { productComparisonMarkup } from '../../main/resources/static/product-comparison.mjs';

const product = { id: '100266036816', sourceUrl: 'https://item.jd.com/100266036816.html', availability: 'UNKNOWN', capturedAt: '2026-10-27T03:00:00Z' };

test('renders true SKU source and captured reference price caveat', () => {
  const html = productSourceMarkup(product);
  for (const text of [product.id, product.sourceUrl, product.capturedAt, '采集参考价', '非实时报价', '库存待核实', 'noopener noreferrer']) assert.ok(html.includes(text));
  assert.equal(productSourceMarkup({ id: 'SKU-001' }), '');
});

test('rejects unsafe, deceptive or cross-SKU source URLs', () => {
  for (const url of ['javascript:alert(1)', 'https://item.jd.com.evil/100266036816.html', '//item.jd.com/100266036816.html',
    'https://item.jd.com/100266036816.html?next=evil', 'https://item.jd.com/100130785706.html']) {
    assert.equal(jdProductUrl(url, product.id), '');
    assert.equal(productSourceMarkup({ ...product, sourceUrl: url }), '');
  }
  assert.ok(!productSourceMarkup({ ...product, capturedAt: '<script>bad</script>' }).includes('<script>'));
});

test('partial evidence shows supported features and unknown limitations without fabricating defects', () => {
  const comparison = { status: 'PARTIAL_EVIDENCE', strengths: [{ text: '页面标注', evidenceId: 'K1', quote: '页面的已知说明' }], limitations: [], fit: '用途适配', uncertainty: '缺少独立测评' };
  const sources = [{ id: 'K1', productId: product.id, sourceUrl: product.sourceUrl, capturedAt: product.capturedAt, title: '商详', source: `${product.id}.md`, version: 'v1' }];
  const html = productComparisonMarkup(comparison, sources);
  for (const text of ['仅有部分页面依据', '未取得可证实的使用限制', '页面的已知说明', product.sourceUrl, product.capturedAt]) assert.ok(html.includes(text));
  assert.ok(!productComparisonMarkup(comparison, [{ ...sources[0], sourceUrl: 'javascript:alert(1)' }]).includes('href='));
});
