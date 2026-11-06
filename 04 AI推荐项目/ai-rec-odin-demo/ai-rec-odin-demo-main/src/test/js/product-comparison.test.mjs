import test from 'node:test';
import assert from 'node:assert/strict';
import { productComparisonMarkup } from '../../main/resources/static/product-comparison.mjs';

const comparison = { status: 'SUPPORTED', strengths: [{ text: '日常缓震', quote: '缓震结构适合慢跑', evidenceId: 'K1' }],
  limitations: [{ text: '不防水', quote: '鞋面不具备防水能力', evidenceId: 'K1' }], fit: '适合慢跑', uncertainty: '需要试穿' };
const sources = [{ id: 'K1', title: '跑鞋说明', source: 'SKU-001.md', version: 'v1' }];

test('renders strengths, limitations, fit and verifiable quotes', () => {
  const html = productComparisonMarkup(comparison, sources);
  for (const value of ['优势', '限制', '缓震结构适合慢跑', '鞋面不具备防水能力', 'SKU-001.md', 'v1', '需要试穿']) assert.ok(html.includes(value));
});

test('no evidence is presented as unknown rather than an empty ranking', () => {
  const html = productComparisonMarkup({ status: 'INSUFFICIENT_EVIDENCE', fit: '需确认', uncertainty: '资料不足' });
  assert.ok(html.includes('暂不作性能优劣判断'));
  assert.ok(!html.includes('<h5>优势</h5>'));
  assert.equal(productComparisonMarkup(undefined), '');
});

test('escapes generated claims and source metadata', () => {
  const attack = '<img src=x onerror=alert(1)>';
  const html = productComparisonMarkup({ ...comparison, fit: attack, uncertainty: attack,
    strengths: [{ text: attack, quote: attack, evidenceId: 'K1' }] }, [{ ...sources[0], title: attack, source: attack, version: attack }]);
  assert.ok(!html.includes('<img'));
  assert.ok(html.includes('&lt;img'));
});

test('does not invent evidence details when reference is unavailable', () => {
  assert.ok(!productComparisonMarkup(comparison).includes('<blockquote>'));
});

test('deep comparison renders dimensions with source quotes and escapes dimension labels', () => {
  const html = productComparisonMarkup({ ...comparison,
    dimensions: [{ dimension: '<img src=x>', text: '页面标注的具体规格', quote: '完整的原文规格说明', evidenceId: 'K1' }]
  }, sources);
  assert.ok(html.includes('多维对比依据'));
  assert.ok(html.includes('页面标注的具体规格'));
  assert.ok(html.includes('<blockquote>完整的原文规格说明</blockquote>'));
  assert.ok(html.includes('&lt;img'));
  assert.ok(!html.includes('<img'));
});
