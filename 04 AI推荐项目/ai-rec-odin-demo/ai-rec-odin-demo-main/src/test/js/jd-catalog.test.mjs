import test from 'node:test';
import assert from 'node:assert/strict';
import { skuFromUrl, parsePrice, normalizeProduct, validateSeeds, reusableProducts, catalogPatch, MAX_PRODUCTS } from '../../../../scripts/jd-catalog.mjs';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const seed = { id: '100266036816', category: '蓝牙耳机', tags: ['音频'], discoveryUrl: 'https://www.jd.com/' };
const raw = { url: 'https://item.jd.com/100266036816.html', name: '页面标题：测试耳机', priceText: '359.00',
  capturedAt: '2026-10-27T03:00:00Z', stockText: '', selectedOptions: '颜色：白色', specifications: '', services: '查看服务说明' };

test('expanded bundled catalog covers all discovered seeds and new categories with traceable SKU evidence', () => {
  const seeds = validateSeeds(JSON.parse(fs.readFileSync(new URL('../../../../scripts/jd-product-seeds.json', import.meta.url), 'utf8')));
  const categories = JSON.parse(fs.readFileSync(new URL('../../../../scripts/jd-discovery-expansion.json', import.meta.url), 'utf8'));
  const catalog = JSON.parse(fs.readFileSync(new URL('../../main/resources/catalog/jd-products.json', import.meta.url), 'utf8'));
  assert.equal(catalog.source, 'JD_PAGE_SNAPSHOT');
  assert.ok(catalog.products.length >= 1000);
  assert.equal(new Set(catalog.products.map(product => product.id)).size, catalog.products.length);
  assert.deepEqual(new Set(catalog.products.map(product => product.id)), new Set(seeds.map(item => item.id)));
  for (const category of categories) {
    const products = catalog.products.filter(product => product.category === category.category);
    assert.ok(products.length >= category.limit, category.category);
    for (const product of products) {
      assert.equal(skuFromUrl(product.sourceUrl), product.id);
      assert.ok(product.knowledgeText.includes(product.sourceUrl));
      assert.ok(product.knowledgeText.includes(product.capturedAt));
      assert.ok(Number.isFinite(Date.parse(product.capturedAt)));
      assert.ok(product.name.trim());
      assert.ok(product.price === null || product.price > 0);
      assert.equal(new URL(product.discoveryUrl).hostname, 'search.jd.com');
    }
  }
});

test('discovers only canonical JD item IDs and strips tracking query', () => {
  assert.equal(skuFromUrl(`${raw.url}?tracking=example`), seed.id);
  for (const url of ['https://item.jd.com.evil.test/100266036816.html', 'https://evil.test/item.jd.com/100266036816.html',
    'https://item.jd.com:444/100266036816.html', 'https://user:password@item.jd.com/100266036816.html',
    'file:///100266036816.html', 'https://item.jd.com/../../test', 'https://item.jd.com/SKU-001.html']) {
    assert.throws(() => skuFromUrl(url));
  }
});

test('mainstream categories have distinct collected SKUs with matching discovery tags and traceable facts', () => {
  const categories = JSON.parse(fs.readFileSync('scripts/jd-discovery-mainstream.json', 'utf8'));
  const seeds = validateSeeds(JSON.parse(fs.readFileSync('scripts/jd-product-seeds.json', 'utf8')));
  const catalog = JSON.parse(fs.readFileSync('ai-rec-odin-demo-main/src/main/resources/catalog/jd-products.json', 'utf8'));
  const byId = new Map(catalog.products.map(product => [product.id, product]));
  assert.equal(byId.size, catalog.products.length);
  assert.equal(seeds.length, catalog.products.length);
  assert.ok(catalog.products.length >= 300);
  for (const category of categories) {
    const products = catalog.products.filter(product => product.category === category.category);
    assert.ok(products.length >= 6, category.category);
    assert.ok(products.some(product => product.price > 0 && product.availability !== 'UNAVAILABLE'), category.category);
    for (const product of products) {
      const discovery = seeds.find(seed => seed.id === product.id);
      assert.ok(discovery, product.id);
      assert.deepEqual(product.tags, category.tags);
      assert.deepEqual(product.tags, discovery.tags);
      assert.equal(product.sourceUrl, `https://item.jd.com/${product.id}.html`);
      assert.equal(new URL(product.discoveryUrl).searchParams.get('keyword'), category.keyword);
      assert.equal(normalizeProduct({url:product.sourceUrl,name:product.name,priceText:product.priceText,
        capturedAt:product.capturedAt,stockText:product.stockText,selectedOptions:product.selectedOptions,
        specifications:product.specifications,services:product.services}, discovery).knowledgeText, product.knowledgeText);
    }
  }
});

test('unknown, promotional and ambiguous prices are not invented', () => {
  assert.equal(parsePrice('￥273.20'), 273.2);
  assert.equal(parsePrice('359'), 359);
  for (const text of ['', null, '登录查看', '券后359 原价599', '359\n00', '-1', '0', 'NaN', '1e3', '1,299', '299.999']) {
    assert.equal(parsePrice(text), null);
  }
});

test('captures only selected public fields and retains unknown stock and limits', () => {
  const result = normalizeProduct({ ...raw, reviews: 'customer-private-data', address: 'private-address' }, seed);
  assert.equal(result.availability, 'UNKNOWN');
  assert.equal(result.sourceUrl, raw.url);
  assert.equal(result.priceType, 'PAGE_SNAPSHOT');
  assert.equal(result.specifications, '');
  assert.ok(result.knowledgeText.includes('未提供可提取的商品参数'));
  assert.ok(result.knowledgeText.includes(raw.capturedAt));
  assert.ok(!JSON.stringify(result).includes('private'));
  assert.equal(normalizeProduct({ ...raw, stockText: '暂时售完' }, seed).availability, 'UNAVAILABLE');
  assert.equal(normalizeProduct({ ...raw, stockText: '有货' }, seed).availability, 'AVAILABLE');
  assert.equal(normalizeProduct({ ...raw, priceText: '登录查看价格' }, seed).price, null);
});

test('rejects login redirects, another SKU, missing title or timestamp', () => {
  assert.throws(() => normalizeProduct({ ...raw, url: 'https://passport.jd.com/' }, seed));
  assert.throws(() => normalizeProduct({ ...raw, url: 'https://item.jd.com/100130785706.html' }, seed));
  assert.throws(() => normalizeProduct({ ...raw, name: '' }, seed));
  assert.throws(() => normalizeProduct({ ...raw, capturedAt: undefined }, seed));
});

test('accepts hundred-SKU plans but rejects duplicate IDs and the bounded maximum', () => {
  const seeds = Array.from({length:100}, (_, index) => ({...seed, id:String(100000000000 + index)}));
  assert.equal(validateSeeds(seeds).length, 100);
  assert.throws(() => validateSeeds([...seeds, seeds[0]]));
  assert.throws(() => validateSeeds(Array.from({length:MAX_PRODUCTS+1}, (_, index) => ({...seed,id:String(100000000000+index)}))));
  assert.throws(() => validateSeeds([{...seed,tags:[42]}]));
  assert.throws(() => validateSeeds([{...seed,id:100266036816}]));
});

test('resume reuses recent matching SKU snapshots but refreshes stale or changed seeds', () => {
  const product = normalizeProduct(raw, seed);
  const now = Date.parse(raw.capturedAt) + 3600000;
  const existing = {schemaVersion:1,source:'JD_PAGE_SNAPSHOT',products:[product]};
  assert.deepEqual(reusableProducts(existing, [seed], now), [product]);
  assert.deepEqual(reusableProducts(existing, [seed], now+24*3600000), []);
  assert.deepEqual(reusableProducts(existing, [seed], now-24*3600000), []);
  assert.deepEqual(reusableProducts(existing, [{...seed,tags:['办公']}], now), []);
  assert.deepEqual(reusableProducts(existing, [{...seed,id:'100130785706'}], now), []);
  assert.deepEqual(reusableProducts({...existing,products:[{...product,capturedAt:'invalid'}]}, [seed], now), []);
  assert.throws(() => reusableProducts({...existing,products:[product,product]}, [seed], now));
  assert.throws(() => reusableProducts({products:[]}, [seed], now));
});

test('catalog patch reports no-op and creates a reviewable bounded file update', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'jd-patch-test-'));
  const filename = path.join(directory, 'catalog.json');
  const product = normalizeProduct(raw, seed);
  try {
    const added = catalogPatch(filename, [product]);
    assert.ok(added.startsWith(`*** Begin Patch\n*** Add File: ${filename}`));
    fs.writeFileSync(filename, JSON.stringify({schemaVersion:1,source:'JD_PAGE_SNAPSHOT',products:[product]},null,2)+'\n');
    assert.equal(catalogPatch(filename, [product]), '');
    const updated = catalogPatch(filename, [{...product,price:300}]);
    assert.ok(updated.includes(`*** Update File: ${filename}`));
    assert.ok(updated.includes('+      "price": 300'));
    assert.throws(() => catalogPatch('bad\npath', [product]));
  } finally { fs.rmSync(directory, {recursive:true,force:true}); }
});

test('appending to a hundred-product catalog emits only the changed tail, not the whole history', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'jd-incremental-test-'));
  const filename = path.join(directory, 'catalog.json');
  const products = Array.from({length:100}, (unused, index) => ({...normalizeProduct(raw, seed),id:String(100000000000+index)}));
  try {
    fs.writeFileSync(filename, JSON.stringify({schemaVersion:1,source:'JD_PAGE_SNAPSHOT',products},null,2)+'\n');
    const patch = catalogPatch(filename, [...products, {...products[0],id:'100000000100'}]);
    assert.ok(patch.includes('+      "id": "100000000100"'));
    assert.ok(!patch.includes('"id": "100000000000"'));
    assert.ok(Buffer.byteLength(patch) < 12000);
    assert.ok(Buffer.byteLength(patch) < fs.statSync(filename).size / 10);
  } finally { fs.rmSync(directory, {recursive:true,force:true}); }
});
