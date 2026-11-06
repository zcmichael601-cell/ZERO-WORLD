import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

export const MAX_PRODUCTS = 2000;

export function validateSeeds(seeds) {
  if (!Array.isArray(seeds) || seeds.length < 1 || seeds.length > MAX_PRODUCTS || new Set(seeds.map(seed => seed.id)).size !== seeds.length) throw new Error(`每次采集 1~${MAX_PRODUCTS} 个不重复 SKU`);
  for (const seed of seeds) {
    if (typeof seed.id !== 'string' || !/^[1-9][0-9]{5,19}$/.test(seed.id) || typeof seed.category !== 'string' || !seed.category.trim()
        || !Array.isArray(seed.tags) || !seed.tags.length || seed.tags.length > 12
        || seed.tags.some(tag => typeof tag !== 'string' || !tag.trim() || tag.length > 50)) throw new Error('SKU / 分类 / 检索标签无效');
  }
  return seeds;
}

export function catalogPatch(outputPath, products) {
  if (/[\r\n]/.test(outputPath)) throw new Error('输出路径不能包含换行');
  const content = JSON.stringify({schemaVersion:1,source:'JD_PAGE_SNAPSHOT',products},null,2)+'\n';
  const previous = fs.existsSync(outputPath) ? fs.readFileSync(outputPath,'utf8') : null;
  if (previous === content) return '';
  const updated = content.trimEnd().split('\n');
  if (previous === null) return `*** Begin Patch\n*** Add File: ${outputPath}\n${updated.map(line=>'+'+line).join('\n')}\n*** End Patch\n`;
  const original = previous.trimEnd().split('\n');
  let prefix = 0;
  while (prefix < original.length && prefix < updated.length && original[prefix] === updated[prefix]) prefix++;
  let suffix = 0;
  while (suffix < original.length-prefix && suffix < updated.length-prefix
      && original[original.length-1-suffix] === updated[updated.length-1-suffix]) suffix++;
  const lines = [
    ...original.slice(Math.max(0,prefix-3),prefix).map(line=>' '+line),
    ...original.slice(prefix,original.length-suffix).map(line=>'-'+line),
    ...updated.slice(prefix,updated.length-suffix).map(line=>'+'+line),
    ...original.slice(original.length-suffix,original.length-suffix+3).map(line=>' '+line)
  ];
  return `*** Begin Patch\n*** Update File: ${outputPath}\n@@\n${lines.join('\n')}\n*** End Patch\n`;
}

export function reusableProducts(existing, seeds, now = Date.now()) {
  const selected = new Map(seeds.map(seed => [seed.id, seed]));
  if (existing?.schemaVersion !== 1 || existing?.source !== 'JD_PAGE_SNAPSHOT' || !Array.isArray(existing.products)) throw new Error('续采目录格式无效');
  const seen = new Set();
  return existing.products.filter(product => {
    if (seen.has(product.id)) throw new Error('续采目录包含重复 SKU');
    seen.add(product.id);
    const seed = selected.get(product.id);
    const age = now - Date.parse(product.capturedAt);
    return seed && Number.isFinite(age) && age >= 0 && age < 24 * 60 * 60 * 1000
      && product.sourceUrl === `https://item.jd.com/${seed.id}.html` && product.name
      && product.category === seed.category && JSON.stringify(product.tags) === JSON.stringify(seed.tags);
  });
}

export function skuFromUrl(value) {
  const url = new URL(value);
  if (!['http:', 'https:'].includes(url.protocol) || url.hostname !== 'item.jd.com' || url.port || url.username || url.password) throw new Error('仅接受 item.jd.com 商品链接');
  const match = url.pathname.match(/^\/([1-9][0-9]{5,19})\.html$/);
  if (!match) throw new Error('商品链接缺少有效 SKU');
  return match[1];
}

export function parsePrice(text) {
  const normalized = String(text ?? '').trim().replace(/^[¥￥]\s*/, '');
  if (!/^[0-9]+(?:\.[0-9]{1,2})?$/.test(normalized)) return null;
  const value = Number(normalized);
  return Number.isFinite(value) && value > 0 ? value : null;
}

export function normalizeProduct(raw, seed) {
  const id = skuFromUrl(raw.url);
  if (id !== seed.id || !raw.name || raw.name.length > 500) throw new Error('SKU 重定向或商品标题缺失，拒绝入库');
  const clean = value => String(value ?? '').replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '').trim().slice(0,6000);
  const price = parsePrice(raw.priceText);
  const stockText = clean(raw.stockText);
  const availability = /暂时售完|无货|下柜|下架|售罄/.test(stockText) ? 'UNAVAILABLE' : /有货/.test(stockText) ? 'AVAILABLE' : 'UNKNOWN';
  const capturedAt = raw.capturedAt;
  if (!Number.isFinite(Date.parse(capturedAt))) throw new Error('缺少采集时间');
  const selectedOptions = clean(raw.selectedOptions);
  const specifications = clean(raw.specifications);
  const services = clean(raw.services);
  const sourceUrl = `https://item.jd.com/${id}.html`;
  const knowledgeText = `# ${clean(raw.name)}（京东 SKU ${id}）\n\n来源：${sourceUrl}\n采集时间：${capturedAt}\n来源类型：京东商详页公开可见内容快照；页面宣传不是独立测评。\n\n## 页面商品标题\n${clean(raw.name)}\n\n## 当前选择的规格\n${selectedOptions || '页面未提供可提取的已选规格，需到商详核实。'}\n\n## 商品参数原文\n${specifications || '页面未提供可提取的商品参数，不据此推断缺失性能。'}\n\n## 页面服务说明\n${services || '未取得服务说明，购买前需核实。'}\n\n## 证据边界\n本资料仅适用于上述 SKU 和已选规格。标题中的功能与材质是页面标注，不等于经独立测试证实。未取得独立测评或完整使用限制，不得编造缺点、兼容性、舒适度或性能排名。推荐用途可以从已知规格推断，但必须标为适配判断；未知事项应单独说明。价格、优惠、配送与库存会变化，不作为本知识库的实时事实，应查看商品快照字段并在下单前以京东商详为准。\n`;
  if (Buffer.byteLength(knowledgeText) > 16384) throw new Error('商品知识超过 16KB，请减少参数范围');
  return { id, name: clean(raw.name), description: selectedOptions || clean(raw.name), category: seed.category,
    tags: seed.tags, tagSource: '人工检索标签，不属于商品性能证据', icon: seed.icon || '🛍️',
    price, priceText: clean(raw.priceText), priceType: 'PAGE_SNAPSHOT', currency: 'CNY',
    priceNotice: '采集时页面参考价，非实时报价；优惠资格及下单价以京东为准', availability, stockText,
    stockNotice: '仅反映采集时页面配送上下文，未保存用户地址，不保证其他地区库存',
    sourceUrl, discoveryUrl: seed.discoveryUrl, capturedAt, selectedOptions, specifications, services, knowledgeText };
}

export function browser(command, argument, session = 'jd-catalog') {
  const output = execFileSync('o2', ['launch', 'webcli', 'browser', session, command, ...(argument === undefined ? [] : [argument])],
    { encoding: 'utf8', maxBuffer: 2 * 1024 * 1024, timeout: 90000 });
  if (command === 'close') return {};
  const result = JSON.parse(output);
  if (result?.ok === false || result?.error) throw new Error('浏览器操作失败，请检查 o2 / WebCLI 会话');
  return result;
}

export const extractProduct = `(() => {
  const text = selector => document.querySelector(selector)?.innerText?.trim() || '';
  const selected = [...document.querySelectorAll('.specification-group')].map(group => {
    const label = group.querySelector('.specification-group-label')?.innerText?.trim();
    const value = group.querySelector('.specification-item-sku--selected')?.innerText?.trim();
    return label && value ? label + '：' + value : '';
  }).filter(Boolean).join('\\n');
  return {url:location.href,capturedAt:new Date().toISOString(),name:text('.sku-title-name') || text('.sku-name'),
    accessRestricted: /验证码|安全验证|访问受限|验证后继续|请完成验证/.test(document.title + ' ' + (document.body?.innerText || '').slice(0,1000)),
    priceText:text('.product-price--value') || text('.summary-price .p-price .price'),
    stockText:text('.logistics-delivery-time') || text('#store-prompt'),selectedOptions:selected,
    specifications:[...document.querySelectorAll('.parameter2,.Ptable,.specifications-table,.product-parameter')].map(element=>element.innerText.trim()).filter(Boolean).join('\\n').slice(0,6000),
    services:text('.service-support-tags') || text('#summary-service')};
})()`;

async function main() {
  const [mode, argument, outputPath, ...flags] = process.argv.slice(2);
  if (mode === 'discover') {
    const url = new URL(argument || 'https://www.jd.com/');
    if (url.protocol !== 'https:' || !['www.jd.com', 'search.jd.com'].includes(url.hostname) || url.username || url.password || url.port) throw new Error('仅发现京东首页 / 搜索页链接');
    try {
      browser('open', url.href);
      await new Promise(resolve => setTimeout(resolve, 2200));
      const result = browser('eval', `({url:location.href,items:[...[...document.querySelectorAll('a[href]')].filter(link=>/^https?:\\/\\/item\\.jd\\.com\\/[1-9][0-9]{5,19}\\.html/.test(link.href)).slice(0,60).map(link=>({url:link.href,title:(link.innerText||link.title||link.querySelector('img')?.alt||'').slice(0,200)})),...[...document.querySelectorAll('[data-sku]')].filter(element=>/^[1-9][0-9]{5,19}$/.test(element.getAttribute('data-sku'))).slice(0,60).map(element=>({url:'https://item.jd.com/'+element.getAttribute('data-sku')+'.html',title:element.innerText.slice(0,200)}))]})`);
      if (new URL(result.url).hostname !== url.hostname) throw new Error('页面要求登录或发生重定向，请人工处理后重试');
      const seen = new Set();
      const items = result.items.flatMap(item => { const id = skuFromUrl(item.url); if (seen.has(id)) return []; seen.add(id); return [{id,url:`https://item.jd.com/${id}.html`,title:item.title,discoveryUrl:url.href}]; });
      if (!items.length) throw new Error('页面未取得商品链接；请在正常浏览器登录 / 加载页面后重试，不绕过验证');
      process.stdout.write(JSON.stringify(items, null, 2) + '\n');
    } finally { browser('close'); }
    return;
  }
  if (mode !== 'collect' || !argument || !outputPath) throw new Error('用法：node scripts/jd-catalog.mjs discover [京东页面]；或 collect seeds.json 输出catalog.json（stdout输出apply_patch）');
  if (flags.some(flag => !['--apply', '--resume', '--skip-invalid'].includes(flag) && !/^--target-count=\d+$/.test(flag))) throw new Error('仅支持 --apply / --resume / --skip-invalid / --target-count=N');
  const target = Number(flags.find(flag => flag.startsWith('--target-count='))?.split('=')[1] || MAX_PRODUCTS);
  if (!Number.isInteger(target) || target < 1 || target > MAX_PRODUCTS) throw new Error('目标数量无效');
  if (flags.includes('--resume') && !flags.includes('--apply')) throw new Error('--resume 需要 --apply 逐件保存');
  const seeds = validateSeeds(JSON.parse(fs.readFileSync(argument, 'utf8')));
  const previous = fs.existsSync(outputPath) ? JSON.parse(fs.readFileSync(outputPath, 'utf8')) : null;
  const products = flags.includes('--resume') && previous ? reusableProducts(previous, seeds) : [];
  const completed = new Set(products.map(product => product.id));
  const storedIds = new Set((previous?.products || []).map(product => product.id));
  let skipped = 0;
  const save = () => {
    const merged = new Map((previous?.products || []).map(product => [product.id, product]));
    products.forEach(product => merged.set(product.id, product));
    if (merged.size > MAX_PRODUCTS) throw new Error(`合并目录不得超过 ${MAX_PRODUCTS} 件`);
    const patch = catalogPatch(outputPath, [...merged.values()]);
    if (patch) execFileSync('apply_patch', [], {input: patch, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024, timeout:60000});
  };
  process.stderr.write(`目标 ${seeds.length} 件，复用 24 小时内已采集 ${completed.size} 件；逐件串行采集，不绕过验证\n`);
  try {
    for (const seed of seeds) {
      if (flags.some(flag => flag.startsWith('--target-count=')) && storedIds.size >= target) break;
      if (completed.has(seed.id)) continue;
      browser('open', `https://item.jd.com/${seed.id}.html`);
      let raw;
      for (let attempt = 0; attempt < 4; attempt++) {
        await new Promise(resolve => setTimeout(resolve, 1600));
        raw = browser('eval', extractProduct);
        if (raw.accessRestricted || /passport|login|verify/.test(new URL(raw.url).hostname)) throw new Error('页面要求登录或验证，请人工处理后续采');
        if (raw.name && raw.priceText) break;
      }
      let product;
      try {
        product = normalizeProduct(raw, seed);
        if (flags.includes('--skip-invalid') && product.price === null) throw new Error('当前页面未取得有效参考价');
      } catch (error) {
        if (!flags.includes('--skip-invalid')) throw error;
        process.stderr.write(`[SKIPPED] ${seed.id}：商详或价格校验未通过，未入库\n`);
        skipped++;
        await new Promise(resolve => setTimeout(resolve, 1500));
        continue;
      }
      products.push(product);
      storedIds.add(product.id);
      if (flags.includes('--apply')) save();
      process.stderr.write(`[${products.length}/${seeds.length}] 已读取 ${seed.id}：${products.at(-1).name}；价格=${products.at(-1).price ?? '未知'}；库存=${products.at(-1).availability}\n`);
      await new Promise(resolve => setTimeout(resolve, 1500));
    }
  } finally { browser('close'); }
  process.stderr.write(`采集结束：目录 ${storedIds.size} 件，跳过 ${skipped} 件；目标 ${target} 件\n`);
  if (!flags.includes('--apply')) process.stdout.write(catalogPatch(outputPath, products));
  else process.stderr.write(`采集完成：本次目标 ${products.length} 件，目录 ${JSON.parse(fs.readFileSync(outputPath, 'utf8')).products.length} 件\n`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main().catch(error => { console.error(error.message); process.exitCode=1; });
