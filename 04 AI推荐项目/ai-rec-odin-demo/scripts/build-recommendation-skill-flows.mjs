import fs from 'node:fs';

const colors = { runtime: '#FFF4DF', model: '#EAF1FF', tool: '#E8F5EE' };
const escape = value => String(value).replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;'
})[character]);
const diagrams = [
  {
    id: 'standard', title: '普通版：模型自主规划的 Agent Loop', height: 950,
    subtitle: 'shopping-recommendation · AGENT · commerce handler',
    nodes: [
      ['request', 'runtime', 140, 72, ['校验输入 → AgentLoopExecutor', '首次只开放 Skill 工具；不预先执行用户查询']],
      ['load', 'model', 250, 76, ['LLM 请求加载 Skill → Runtime 返回指令', '根据真实事实和本轮可用工具自主规划']],
      ['decide', 'model', 370, 96, ['LLM 选择下一步：工具调用或最终答案', '每轮提供事实状态；重复/截断有界恢复', '可调整用户查询顺序、合并请求、补搜与补查']],
      ['tools', 'tool', 510, 120, ['整体审查工具计划 → 执行受控工具', '画像/行为、商品搜索、RAG；写回真实结果', '检查依赖、重复和预算，保护最终回答机会']],
      ['finish', 'runtime', 750, 84, ['finish()：校验最终推荐后发布', '校验 SKU、预算和原文引用；答案纠正不重跑工具']]
    ],
    edges: [
      ['request', 'load'], ['load', 'decide'], ['decide', 'tools', '需要工具'],
      ['tools', 'decide', '工具结果\n或重新规划\n继续下一轮', 'right', 900],
      ['decide', 'finish', '事实已足够\n且必需工具\n均已完成', 'left', 52]
    ],
    footer: ['模型轮数不固定；画像/行为各至多一次，搜索/知识各至多两次。', '总上限仍为 8 / 8；拒绝相同参数的重复请求，允许有依据的补查。']
  },
  {
    id: 'fast', title: '极速版：程序召回，模型筛选与有界修正', height: 900,
    subtitle: 'shopping-recommendation-fast · PREPARED_RECOMMENDATION',
    nodes: [
      ['request', 'runtime', 140, 72, ['RecommendationWorkflows → WorkflowExecutor', 'Runtime 主动加载 Skill；不请模型决定是否取数']],
      ['users', 'tool', 248, 76, ['顺序执行 get_user_profile → get_user_behaviors', '真实 UUID 查 AI Memory；演示用户读取固定画像与行为']],
      ['search', 'tool', 360, 88, ['代码准备参数 → search_products', 'ProductSearchService 匹配目录标签；召回最多 6 件', '过滤价格未知、过期、明确缺货和超预算商品']],
      ['context', 'runtime', 484, 72, ['prepareFastAnswer()：压缩事实，构建 Prompt', '用户资料 + 候选与标签 + outputSchema']],
      ['generate', 'model', 592, 80, ['LLM 相对优选并生成简短推荐', '有候选选 1~2 件；无候选才可为空；不做 RAG']],
      ['finish', 'runtime', 712, 94, ['校验 SKU、数量、分值和预算 → 回填商品卡', '无效则复用事实让模型修正一次；不重跑工具', '仍无效明确失败；不按目录顺序代选商品']]
    ],
    footer: ['模型正常 1 轮，最多 2 轮；工具固定 4 次，含 Skill 加载；用户工具顺序执行。', '目标约 5 秒不是 SLA；跳过的是请求内 RAG，不是全服务的启动索引预加载。']
  },
  {
    id: 'deep', title: '满血版：三轮决策，扩大召回与证据', height: 1130,
    subtitle: 'shopping-recommendation-deep · DEEP_RECOMMENDATION_WORKFLOW',
    nodes: [
      ['prepare', 'runtime', 140, 82, ['RecommendationWorkflows → WorkflowExecutor', 'Runtime 加载 Skill → 画像 → 行为；不消耗模型轮次']],
      ['plan', 'model', 262, 78, ['LLM 第 1 轮：决定需求词与召回参数', '受限 JSON 决策；Runtime 校验后转成工具调用']],
      ['search', 'tool', 380, 104, ['search_products → DeepProductSearchTool', '目录词汇匹配 + 多需求语义召回 → 融合、去重', '基于有效快照/预算筛选，候选最多 48 件']],
      ['select', 'model', 524, 78, ['LLM 第 2 轮：选择最多 8 件商品深读', '受限 JSON 决策，只能选本次已召回的 SKU']],
      ['knowledge', 'tool', 642, 88, ['retrieve_product_knowledge → 深读实现', '展开所选 SKU 在同版索引中的全部原文片段', '不是再次用一个 question 做少量片段召回']],
      ['generate', 'model', 770, 84, ['LLM 第 3 轮：生成推荐与多维对比', '最多 6 件；选择 evidenceId + quoteId，不重抄原文']],
      ['finish', 'runtime', 904, 88, ['回填原文 → finish() 校验 → 发布', '检查格式、SKU、预算、数量和引用；不合格不发布', '允许有界修正最终 JSON，保留已完成的工具结果']]
    ],
    edges: [['prepare', 'plan'], ['plan', 'search'], ['search', 'select'], ['select', 'knowledge'],
      ['knowledge', 'generate'], ['generate', 'finish'], ['finish', 'generate', '校验需修正\n且预算允许\n不重复检索', 'right', 900]],
    footer: ['正常路径约 3 轮模型、5 次工具（含 Skill）。模型/工具配置上限为 12 / 20，不是目标次数。', '耗时主要取决于向量召回、模型推理和长答案生成；完整证据不等于独立测评。']
  }
];

function textSvg(lines, center, top, size, boldFirst = false, color = '#253C4B') {
  return `<text text-anchor="middle" font-family="PingFang SC,Microsoft YaHei,Arial,sans-serif" font-size="${size}" fill="${color}">${lines.map((line, index) => `<tspan x="${center}" y="${top + index * 24}" font-weight="${boldFirst && index === 0 ? 600 : 400}">${escape(line)}</tspan>`).join('')}</text>`;
}

function render(diagram) {
  const nodes = diagram.nodes.map(([id, kind, top, height, lines]) => ({ id, kind, top, height, lines, left: 160, width: 640 }));
  const byId = new Map(nodes.map(node => [node.id, node]));
  const edges = diagram.edges ?? nodes.slice(1).map((node, index) => [nodes[index].id, node.id]);
  const cells = ['<mxCell id="0"/>', '<mxCell id="1" parent="0"/>'];
  const labels = [];
  const shapes = [];
  function cell(id, label, left, top, width, height, style) {
    const target = id.startsWith('label-') ? labels : cells;
    target.push(`<mxCell id="${id}" value="${escape(label).replaceAll('\n', '&#xa;')}" style="${style}" vertex="1" parent="1"><mxGeometry x="${left}" y="${top}" width="${width}" height="${height}" as="geometry"/></mxCell>`);
  }
  const textStyle = 'text;html=0;whiteSpace=wrap;align=center;verticalAlign=middle;fontFamily=PingFang SC;fontColor=#253C4B;';
  shapes.push(textSvg([diagram.title], 500, 42, 29, true));
  shapes.push(textSvg([diagram.subtitle], 500, 72, 16, false, '#607788'));
  cell('title', diagram.title, 40, 15, 920, 40, textStyle + 'fontSize=29;fontStyle=1;');
  cell('subtitle', diagram.subtitle, 40, 54, 920, 30, textStyle + 'fontSize=16;');
  for (const [index, [kind, label]] of [['runtime', 'Runtime / 代码'], ['model', 'LLM 模型'], ['tool', 'Tool / 数据']].entries()) {
    const left = 220 + index * 200;
    shapes.push(`<rect x="${left}" y="94" width="170" height="28" rx="8" fill="${colors[kind]}"/>`, textSvg([label], left + 85, 114, 14));
    cell(`legend-${kind}`, label, left, 94, 170, 28, textStyle + `fontSize=14;rounded=1;fillColor=${colors[kind]};strokeColor=none;`);
  }
  for (const node of nodes) {
    const firstBaseline = node.top + node.height / 2 - (node.lines.length - 1) * 12 + 6;
    shapes.push(`<rect x="${node.left}" y="${node.top}" width="${node.width}" height="${node.height}" rx="12" fill="${colors[node.kind]}" stroke="#C2D0DC" stroke-width="1.5"/>`);
    shapes.push(textSvg(node.lines, node.left + node.width / 2, firstBaseline, 17, true));
    cell(node.id, node.lines.join('\n'), node.left, node.top, node.width, node.height,
      textStyle + `rounded=1;arcSize=15;fillColor=${colors[node.kind]};strokeColor=#C2D0DC;fontSize=17;spacing=8;`);
  }
  const arrows = [];
  edges.forEach(([sourceId, targetId, label, side, via], index) => {
    const source = byId.get(sourceId), target = byId.get(targetId);
    if (!source || !target) throw new Error('Unknown graph endpoint');
    let points, ports, waypoints = '';
    if (side) {
      const right = side === 'right';
      const sourceX = source.left + (right ? source.width : 0), targetX = target.left + (right ? target.width : 0);
      const sourceY = source.top + source.height / 2, targetY = target.top + target.height / 2;
      points = [[sourceX, sourceY], [via, sourceY], [via, targetY], [targetX, targetY]];
      ports = `exitX=${right ? 1 : 0};exitY=0.5;entryX=${right ? 1 : 0};entryY=0.5;`;
      waypoints = `<Array as="points"><mxPoint x="${via}" y="${sourceY}"/><mxPoint x="${via}" y="${targetY}"/></Array>`;
      if (label) {
        const center = right ? 920 : 73, top = (sourceY + targetY) / 2 - 24;
        shapes.push(`<rect x="${center - 68}" y="${top - 20}" width="136" height="81" rx="8" fill="white"/>`, textSvg(label.split('\n'), center, top, 14));
        cell(`label-${index}`, label, center - 68, top - 20, 136, 81, textStyle + 'fontSize=14;fillColor=#FFFFFF;');
      }
    } else {
      points = [[source.left + source.width / 2, source.top + source.height], [target.left + target.width / 2, target.top]];
      ports = 'exitX=0.5;exitY=1;entryX=0.5;entryY=0;';
      if (label) {
        shapes.push(textSvg([label], 548, (points[0][1] + points[1][1]) / 2 + 5, 14));
        cell(`label-${index}`, label, 506, (points[0][1] + points[1][1]) / 2 - 14, 92, 30, textStyle + 'fontSize=14;');
      }
    }
    arrows.push(`<polyline points="${points.map(point => point.join(',')).join(' ')}" fill="none" stroke="#5B7488" stroke-width="2" marker-end="url(#arrow)"${side === 'right' ? ' stroke-dasharray="6 4"' : ''}/>`);
    cells.push(`<mxCell id="edge-${index}" style="html=0;edgeStyle=none;endArrow=block;endFill=1;strokeWidth=2;strokeColor=#5B7488;dashed=${side === 'right' ? 1 : 0};${ports}" edge="1" parent="1" source="${sourceId}" target="${targetId}"><mxGeometry relative="1" as="geometry">${waypoints}</mxGeometry></mxCell>`);
  });
  shapes.push(textSvg(diagram.footer, 500, diagram.height - 45, 15, false, '#607788'));
  cell('footer', diagram.footer.join('\n'), 30, diagram.height - 69, 940, 64, textStyle + 'fontSize=15;');
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="${diagram.height}" viewBox="0 0 1000 ${diagram.height}" role="img" aria-labelledby="title description"><title id="title">${escape(diagram.title)}</title><desc id="description">${escape(diagram.subtitle)}</desc><defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="#5B7488"/></marker></defs><rect width="1000" height="${diagram.height}" fill="white"/>${arrows.join('')}${shapes.join('')}</svg>\n`;
  const page = `<diagram id="${diagram.id}" name="${escape(diagram.title)}"><mxGraphModel dx="1000" dy="${diagram.height}" grid="1" gridSize="10" page="1" pageWidth="1000" pageHeight="${diagram.height}"><root>${cells.join('')}${labels.join('')}</root></mxGraphModel></diagram>`;
  return { svg, page };
}

const outputs = new Map();
const pages = diagrams.map(diagram => {
  const { svg, page } = render(diagram);
  outputs.set(`docs/diagrams/recommendation-skill-${diagram.id}.svg`, svg);
  return page;
});
outputs.set('docs/diagrams/recommendation-skills-code-flows.drawio', `<mxfile host="app.diagrams.net" type="device">${pages.join('')}</mxfile>\n`);
let patch = '*** Begin Patch\n';
let changed = false;
for (const [path, content] of outputs) {
  const previous = fs.existsSync(path) ? fs.readFileSync(path, 'utf8') : null;
  if (previous === content) continue;
  changed = true;
  patch += previous === null ? `*** Add File: ${path}\n` : `*** Update File: ${path}\n@@\n${previous.trimEnd().split('\n').map(line => '-' + line).join('\n')}\n`;
  patch += content.trimEnd().split('\n').map(line => '+' + line).join('\n') + '\n';
}
if (changed) process.stdout.write(patch + '*** End Patch\n');
