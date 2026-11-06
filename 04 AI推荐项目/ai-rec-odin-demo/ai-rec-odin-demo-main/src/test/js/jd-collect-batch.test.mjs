import test from 'node:test';
import assert from 'node:assert/strict';
import { batchExtraction, validateBatch } from '../../../../scripts/jd-collect-batch.mjs';
import { validateSeeds, MAX_PRODUCTS } from '../../../../scripts/jd-catalog.mjs';

const seed = { id: '100266036816', category: '蓝牙耳机', tags: ['音频'], discoveryUrl: 'https://www.jd.com/' };
const raw = { url: 'https://item.jd.com/100266036816.html', name: '测试页面标题', priceText: '359.00',
  capturedAt: '2026-10-29T08:00:00Z', selectedOptions: '颜色：白色', specifications: '', services: '', stockText: '' };

test('batch accepts only matching real page snapshots and preserves original source fields', () => {
  const [product] = validateBatch([{id:seed.id, raw}], [seed]);
  assert.equal(product.id, seed.id);
  assert.equal(product.price, 359);
  assert.equal(product.sourceUrl, raw.url);
  assert.equal(product.capturedAt, raw.capturedAt);
  assert.equal(product.specifications, '');
  assert.ok(product.knowledgeText.includes(raw.name));
  assert.ok(product.knowledgeText.includes(raw.selectedOptions));
});

test('batch stops on access restriction or inconsistent response instead of saving partial data', () => {
  assert.throws(() => validateBatch([], [seed]));
  assert.throws(() => validateBatch([{id:'100266036817', raw}], [seed]));
  assert.throws(() => validateBatch([{id:seed.id, blocked:true, raw}], [seed]));
  assert.throws(() => validateBatch([{id:seed.id, raw:{...raw, accessRestricted:true}}], [seed]));
});

test('unknown price, invalid page and redirected SKU cannot enter recommendation catalog', () => {
  for (const invalid of [null, {...raw, name:''}, {...raw, priceText:'登录后查看'},
    {...raw, url:'https://item.jd.com/100266036817.html'}, {...raw, capturedAt:'unknown'}]) {
    assert.deepEqual(validateBatch([{id:seed.id, raw:invalid}], [seed]), [null]);
  }
});

test('bounded browser windows keep concurrency at three and reject oversized batches', () => {
  const seeds = Array.from({length:9}, (_, index) => ({...seed, id:String(100000000000 + index)}));
  const code = batchExtraction(seeds);
  assert.doesNotThrow(() => new Function('return ' + code));
  assert.ok(code.includes('offset += 3'));
  assert.ok(code.includes('frame.remove()'));
  assert.throws(() => batchExtraction([...seeds, {...seed, id:'100000000099'}]));
});

test('discovery supports a thousand unique seeds without disabling a hard capacity bound', () => {
  const seeds = Array.from({length:1000}, (_, index) => ({...seed, id:String(100000000000 + index)}));
  assert.equal(validateSeeds(seeds).length, 1000);
  assert.throws(() => validateSeeds([...seeds, seeds[0]]));
  assert.throws(() => validateSeeds(Array.from({length:MAX_PRODUCTS + 1}, (_, index) => ({...seed, id:String(100000000000 + index)}))));
});
