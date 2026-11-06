import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { pathToFileURL } from 'node:url';
import { browser, catalogPatch, extractProduct, normalizeProduct, validateSeeds, MAX_PRODUCTS } from './jd-catalog.mjs';

export function batchExtraction(seeds) {
  validateSeeds(seeds);
  if (seeds.length > 9) throw new Error('每窗口最多9件，每组最多同时读取3个正常商详页面');
  return `(async () => {
    if (location.origin !== 'https://item.jd.com') throw new Error('需要正常打开京东主站商详');
    const extract = (document, location) => ${extractProduct};
    const frames = [];
    try {
      const ids = ${JSON.stringify(seeds.map(seed => seed.id))};
      const results = [];
      for (let offset = 0; offset < ids.length; offset += 3) {
        const group = await Promise.all(ids.slice(offset, offset + 3).map(async id => {
          const frame = document.createElement('iframe');
          frame.style.cssText = 'width:100%;height:500px;border:1px solid #ddd';
          frame.src = 'https://item.jd.com/' + id + '.html';
          frames.push(frame);
          document.body.prepend(frame);
          let raw;
          for (let attempt = 0; attempt < 8; attempt++) {
            await new Promise(resolve => setTimeout(resolve, 1000));
            try {
              if (frame.contentWindow.location.href === 'about:blank') continue;
              raw = extract(frame.contentDocument, frame.contentWindow.location);
              if (raw.accessRestricted) return {id, blocked:true, reason:'ACCESS_RESTRICTED'};
              if (raw.name && raw.priceText) return {id, raw};
            } catch { return {id, blocked:true, reason:'FRAME_ACCESS_FAILED'}; }
          }
          return {id, raw:raw || null};
        }));
        results.push(...group);
        frames.splice(0).forEach(frame => frame.remove());
        if (group.some(result => result.blocked)) return results;
        await new Promise(resolve => setTimeout(resolve, 1500));
      }
      return results;
    } finally { frames.forEach(frame => frame.remove()); }
  })()`;
}

export function validateBatch(results, seeds) {
  const blocked = Array.isArray(results) ? results.filter(result => result?.blocked || result?.raw?.accessRestricted) : [];
  if (blocked.length) throw new Error('页面受限或无法同源读取，停止采集并人工核实（不绕过限制）：' + blocked.map(result => `${result.id}:${result.reason || 'ACCESS_RESTRICTED'}`).join(','));
  if (!Array.isArray(results) || results.length !== seeds.length) throw new Error('浏览器返回数量不匹配，停止采集');
  if (results.some((result, index) => !result || result.id !== seeds[index].id)) {
    throw new Error('浏览器结果与请求SKU不匹配，停止采集');
  }
  return results.map((result, index) => {
    if (!result.raw) return null;
    try {
      const product = normalizeProduct(result.raw, seeds[index]);
      return product.price === null ? null : product;
    } catch { return null; }
  });
}

async function main() {
  const [seedsPath, catalogPath, targetValue = '1000'] = process.argv.slice(2);
  const target = Number(targetValue);
  if (!seedsPath || !catalogPath || !Number.isInteger(target) || target < 1 || target > MAX_PRODUCTS) throw new Error('用法：node scripts/jd-collect-batch.mjs seeds.json catalog.json [target=1000]');
  const seeds = validateSeeds(JSON.parse(fs.readFileSync(seedsPath, 'utf8')));
  const previous = JSON.parse(fs.readFileSync(catalogPath, 'utf8'));
  if (previous.schemaVersion !== 1 || previous.source !== 'JD_PAGE_SNAPSHOT' || !Array.isArray(previous.products)) throw new Error('目录格式无效');
  const stored = new Map(previous.products.map(product => [product.id, product]));
  if (stored.size !== previous.products.length) throw new Error('已有目录SKU重复');
  if (stored.size >= target) { process.stderr.write(`目录已有 ${stored.size} 件，达到目标 ${target}，无需采集\n`); return; }
  const pending = seeds.filter(seed => !stored.has(seed.id));
  const session = 'jd-bulk';
  let skipped = 0;
  try {
    browser('open', `https://item.jd.com/${seeds[0].id}.html`, session);
    await new Promise(resolve => setTimeout(resolve, 2000));
    for (let offset = 0; offset < pending.length && stored.size < target;) {
      const selected = pending.slice(offset, offset + Math.min(9, target - stored.size));
      offset += selected.length;
      process.stderr.write(`[BATCH] ${selected.map(seed => seed.id).join(',')}\n`);
      const products = validateBatch(browser('eval', batchExtraction(selected), session), selected);
      for (let index = 0; index < products.length; index++) {
        const product = products[index];
        if (!product) { skipped++; process.stderr.write(`[SKIPPED] ${selected[index].id}，未取得合规商详及价格\n`); continue; }
        stored.set(product.id, product);
      }
      const patch = catalogPatch(catalogPath, [...stored.values()]);
      if (patch) execFileSync('apply_patch', [], {input:patch, encoding:'utf8', maxBuffer:32*1024*1024, timeout:60000});
      process.stderr.write(`[COLLECTED] ${stored.size}/${target}，本批 ${products.filter(Boolean).length} 件，累计跳过 ${skipped} 件\n`);
      await new Promise(resolve => setTimeout(resolve, 1500));
      if (stored.size < target) browser('open', `https://item.jd.com/${seeds[0].id}.html`, session);
    }
  } catch (error) {
    process.stderr.write(`采集已停止，保留浏览器会话 ${session} 用于检查；不会自动重试未确认完成的浏览器操作\n`);
    throw error;
  }
  browser('close', undefined, session);
  process.stderr.write(`目录实际 ${stored.size} 个唯一SKU；目标 ${target}；未达目标不声明成功\n`);
  if (stored.size < target) process.exitCode = 2;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}
