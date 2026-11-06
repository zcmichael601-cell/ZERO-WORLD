import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { validateSeeds } from './jd-catalog.mjs';

const [categoriesPath = 'scripts/jd-discovery-categories.json', outputPath = 'scripts/jd-product-seeds.json'] = process.argv.slice(2);
const categories = JSON.parse(fs.readFileSync(categoriesPath, 'utf8'));
if (!Array.isArray(categories) || !categories.length || categories.length > 64) throw new Error('需要 1~64 个发现品类');
const previous = fs.existsSync(outputPath) ? fs.readFileSync(outputPath, 'utf8') : null;
const seeds = previous ? validateSeeds(JSON.parse(previous)) : [];
const seen = new Set(seeds.map(seed => seed.id));
for (const category of categories) {
  if (!category.keyword || !Number.isInteger(category.limit) || category.limit < 1 || category.limit > 30) throw new Error('品类需 keyword 及 1~30 的 limit');
  const url = `https://search.jd.com/Search?keyword=${encodeURIComponent(category.keyword)}`;
  const items = JSON.parse(execFileSync('node', ['scripts/jd-catalog.mjs', 'discover', url], {encoding:'utf8',timeout:120000,maxBuffer:2*1024*1024}));
  let added = 0;
  for (const item of items) {
    if (seen.has(item.id)) continue;
    seeds.push({id:item.id,category:category.category,tags:category.tags,icon:category.icon,discoveryUrl:item.discoveryUrl});
    seen.add(item.id);
    if (++added === category.limit) break;
  }
  process.stderr.write(`${category.keyword}：发现 ${items.length}，新增 ${added}，合计 ${seeds.length}\n`);
  await new Promise(resolve => setTimeout(resolve, 1500));
}
validateSeeds(seeds);
if (/[\r\n]/.test(outputPath)) throw new Error('输出路径无效');
const content = JSON.stringify(seeds, null, 2)+'\n';
if (content !== previous) {
  const header = previous === null ? `*** Add File: ${outputPath}\n` : `*** Update File: ${outputPath}\n@@\n${previous.trimEnd().split('\n').map(line=>'-'+line).join('\n')}\n`;
  process.stdout.write('*** Begin Patch\n'+header+content.trimEnd().split('\n').map(line=>'+'+line).join('\n')+'\n*** End Patch\n');
}
