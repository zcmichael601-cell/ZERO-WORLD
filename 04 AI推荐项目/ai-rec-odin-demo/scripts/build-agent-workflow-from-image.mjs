import fs from 'node:fs';

const panels = [];
const nodes = [];
const connectors = [];
const identifiers = new Set(['0', '1']);
const palette = {
  user: { background: '#F0FAEF', header: '#E7F6E7', fill: '#F5FFF4', stroke: '#92C9A2', accent: '#0AA574' },
  agent: { background: '#EDF8FE', header: '#E0F2FE', fill: '#F0FAFF', stroke: '#87BDDA', accent: '#008FD3' },
  skill: { background: '#FFF9EA', header: '#FFF3D5', fill: '#FFFAE9', stroke: '#F4CE7C', accent: '#F39800' },
  llm: { background: '#F7F1FC', header: '#F0E6FA', fill: '#FAF5FF', stroke: '#CBB5E5', accent: '#9152CE' },
  mcp: { background: '#ECFBFE', header: '#DEF7FE', fill: '#F0FCFF', stroke: '#83C3DA', accent: '#009ED0' },
  tool: { background: '#FFF1F2', header: '#FFE8EC', fill: '#FFF5F4', stroke: '#EAB5BA', accent: '#DE4D61' }
};
const escape = value => String(value).replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;'
})[character]);

function shape(id, value, x, y, width, height, style, layer = nodes, parent = '1') {
  if (identifiers.has(id)) throw new Error(`Duplicate ID: ${id}`);
  identifiers.add(id);
  layer.push(`<mxCell id="${id}" value="${escape(value)}" style="${style}" vertex="1" parent="${parent}"><mxGeometry x="${x * 2}" y="${y * 2}" width="${width * 2}" height="${height * 2}" as="geometry"/></mxCell>`);
}

function text(id, value, x, y, width, height, size = 12, bold = false, color = '#111820', align = 'center', parent = '1') {
  shape(id, escape(value).replaceAll('\n', '<br>'), x, y, width, height,
    `text;html=1;whiteSpace=wrap;overflow=hidden;align=${align};verticalAlign=middle;fontFamily=PingFang SC;fontSize=${size * 2};fontStyle=${bold ? 1 : 0};fontColor=${color};spacing=0;`, nodes, parent);
}

function icon(id, type, x, y, color) {
  const solid = `ellipse;html=1;fillColor=${color};strokeColor=none;`;
  const outlined = `rounded=1;arcSize=30;html=1;fillColor=none;strokeColor=${color};strokeWidth=4;`;
  if (type === 'user') {
    shape(`${id}-head`, '', x + 10, y, 14, 14, solid);
    shape(`${id}-body`, '', x + 6, y + 17, 22, 17, `rounded=1;arcSize=55;fillColor=${color};strokeColor=none;`);
  } else if (type === 'agent') {
    shape(`${id}-antenna`, '', x + 16, y + 3, 2, 8, `fillColor=${color};strokeColor=none;`);
    shape(`${id}-top`, '', x + 14, y, 6, 6, solid);
    shape(`${id}-face`, '', x + 2, y + 11, 30, 23, outlined);
    shape(`${id}-left`, '', x + 8, y + 17, 5, 6, solid);
    shape(`${id}-right`, '', x + 21, y + 17, 5, 6, solid);
    shape(`${id}-mouth`, '', x + 11, y + 27, 12, 2, `fillColor=${color};strokeColor=none;`);
  } else if (type === 'skill') {
    shape(`${id}-base`, '', x + 7, y + 9, 23, 24, `rounded=1;arcSize=10;fillColor=${color};strokeColor=none;`);
    shape(`${id}-top`, '', x + 14, y + 2, 10, 11, solid);
    shape(`${id}-right`, '', x + 26, y + 16, 10, 10, solid);
    shape(`${id}-cutout`, '', x + 2, y + 15, 12, 11, `ellipse;fillColor=#FFF3D5;strokeColor=none;`);
  } else if (type === 'llm') {
    shape(`${id}-left`, '', x + 1, y + 5, 16, 27, `shape=cloud;fillColor=none;strokeColor=${color};strokeWidth=4;`);
    shape(`${id}-right`, '', x + 18, y + 5, 16, 27, `shape=cloud;fillColor=none;strokeColor=${color};strokeWidth=4;flipH=1;`);
  } else if (type === 'mcp') {
    shape(`${id}-left`, '', x + 3, y + 16, 22, 11, `${outlined}rotation=-45;`);
    shape(`${id}-right`, '', x + 15, y + 5, 22, 11, `${outlined}rotation=-45;`);
  } else {
    shape(`${id}-handle`, '', x + 6, y + 18, 27, 8, `rounded=1;arcSize=50;rotation=-45;fillColor=${color};strokeColor=none;`);
    shape(`${id}-head`, '', x + 21, y + 1, 17, 17, solid);
    shape(`${id}-notch`, '', x + 28, y - 1, 9, 11, 'rotation=35;fillColor=#FFE8EC;strokeColor=none;');
    shape(`${id}-hole`, '', x + 9, y + 26, 4, 4, 'ellipse;fillColor=#FFE8EC;strokeColor=none;');
  }
}

text('title', 'AI Agent 运行全流程', 105, 1, 575, 51, 35, true, '#050505');
shape('subtitle-background', '', 207, 55, 354, 32, 'rounded=1;arcSize=80;fillColor=#E2F5FF;strokeColor=none;', panels);
text('subtitle', '从用户评论到结果返回的', 224, 58, 232, 24, 18, true, '#14284E');
text('subtitle-count', '20', 453, 58, 30, 24, 19, true, '#EE1422');
text('subtitle-tail', '步拆解', 485, 58, 59, 24, 18, true, '#14284E');

const lanes = [
  ['user', 8, 117, '用户'],
  ['agent', 127, 159, '智能体\n(Agent)'],
  ['skill', 288, 118, 'Agent Skills'],
  ['llm', 408, 121, '大模型\n(LLM)'],
  ['mcp', 531, 115, 'MCP'],
  ['tool', 648, 112, '工具']
];
for (const [name, x, width, label] of lanes) {
  const colors = palette[name];
  shape(`lane-${name}`, '', x, 94, width, 912, `rounded=1;arcSize=3;fillColor=${colors.background};strokeColor=none;`, panels);
  shape(`header-${name}`, '', x, 94, width, 59, `rounded=1;arcSize=8;fillColor=${colors.header};strokeColor=none;`, panels);
  shape(`footer-${name}`, '', x, 947, width, 59, `rounded=1;arcSize=8;fillColor=${colors.header};strokeColor=none;`, panels);
  for (const [suffix, y] of [['top', 103], ['bottom', 956]]) {
    if (name === 'skill') {
      icon(`${name}-${suffix}`, name, x + 42, y - 5, colors.accent);
      text(`${name}-${suffix}-label`, label, x + 4, y + 25, width - 8, 22, 14, true);
    } else {
      icon(`${name}-${suffix}`, name, x + (name === 'agent' ? 26 : 10), y + 3, colors.accent);
      text(`${name}-${suffix}-label`, label, x + (name === 'agent' ? 72 : 49), y + 2, width - (name === 'agent' ? 77 : 51), 38, 14, true);
    }
  }
}

function step(id, number, label, x, y, width, height, kind, fontSize = 12) {
  const colors = palette[kind];
  shape(id, '', x, y, width, height, `rounded=1;arcSize=15;html=1;fillColor=${colors.fill};strokeColor=${colors.stroke};strokeWidth=2;`, panels);
  const badgeWidth = number.length > 4 ? 27 : number.length > 2 ? 25 : 23;
  const badgeY = number.length > 4 ? 8 : 6;
  shape(`${id}-badge`, number, 6, badgeY, badgeWidth, 23, `ellipse;html=1;fillColor=${colors.accent};strokeColor=none;fontFamily=Arial;fontColor=#FFFFFF;fontSize=${number.length > 4 ? 17 : number.length > 2 ? 20 : 28};fontStyle=1;`, nodes, id);
  const labelX = badgeWidth + (kind === 'llm' || kind === 'tool' ? 7 : 11);
  text(`${id}-label`, label, labelX, 4, width - labelX - 5, height - 8, fontSize, false, '#161A20', 'center', id);
}

step('user-question', '1', '提出问题\n(输入指令)', 15, 162, 98, 48, 'user', 12);
step('agent-input', '2', '接收用户输入', 137, 165, 140, 37, 'agent');
step('agent-intent', '3', '理解用户意图', 137, 221, 140, 39, 'agent');
step('agent-context', '4', '构建对话上下文\n(历史记录 + 记忆)', 137, 278, 140, 51, 'agent', 11.5);
step('agent-plan', '5', '意图识别与任务拆解', 137, 348, 140, 40, 'agent', 11);
step('agent-select', '6', '决定调用 Skills', 137, 407, 137, 38, 'agent');
step('llm-context', '5.1', '将任务与上下文\n发送给 LLM', 416, 176, 109, 48, 'llm', 10.2);
step('llm-plan', '5.2', '生成计划 / 推理\n(思维链 / 方案)', 416, 242, 109, 51, 'llm', 9.3);
step('llm-actions', '5.3', '输出需要的\nSkills 或操作', 416, 311, 109, 50, 'llm', 10.5);
step('skill-select', '7', '选择合适的 Skill', 301, 396, 135, 36, 'skill', 11.5);
step('skill-input', '8', '准备 Skill 输入\n(参数提取 / 检验)', 301, 447, 135, 49, 'skill', 11.5);
step('skill-execute', '9', '执行 Skill 逻辑', 301, 509, 135, 35, 'skill');
shape('skill-tools', '', 306, 557, 128, 70, 'rhombus;html=1;fillColor=#FFF7DF;strokeColor=#E9A044;strokeWidth=2;', panels);
text('skill-tools-number', '10', 350, 563, 37, 21, 18, true, '#F19A00');
text('skill-tools-label', '是否需要外部\n工具支持？', 321, 583, 99, 32, 11.5);
step('skill-direct', '11', '直接返回\nSkill 结果', 308, 648, 122, 54, 'skill');
step('mcp-call', '10.1', '通过 MCP\n调用合适的工具', 499, 435, 132, 51, 'mcp', 11);
step('mcp-package', '10.2', '封装请求\n(协议适配 / 鉴权)', 499, 502, 132, 51, 'mcp', 11);
step('mcp-dispatch', '10.3', '调度到目标工具\n并发送请求', 499, 570, 132, 52, 'mcp', 11);
step('tool-select', '10.1.1', '选择适合工具\n查询检索', 650, 434, 107, 54, 'tool', 10.5);
step('tool-execute', '10.1.2', '执行工具操作', 650, 504, 107, 47, 'tool', 10.5);
step('tool-result', '10.1.3', '返回原始数据\n/ 文本', 650, 571, 107, 58, 'tool', 10.5);
step('mcp-response', '12', '接收工具响应\n(MCP 协议格式)', 499, 646, 132, 52, 'mcp', 11);
step('mcp-normalize', '12.1', '结果解析与标准化', 499, 711, 132, 37, 'mcp', 10.5);
step('llm-result', '13', '接收工具返回结果\n作为上下文补充\n重新推理 / 判断是否\n需要进一步操作', 438, 765, 140, 86, 'llm', 11);
step('agent-reply', '14', '整合结果生成\n最终回复', 136, 655, 130, 50, 'agent', 11.5);
step('skill-result', '15', '返回 Skill 结果\n(结构化数据)', 302, 728, 133, 48, 'skill', 11.5);
step('skill-memory', '16', '更新记忆 / 上下文', 299, 800, 137, 44, 'skill', 11);
step('agent-evaluate', '17', '评估结果质量', 136, 729, 130, 38, 'agent', 11.5);
shape('agent-satisfied', '结果满意？', 146, 787, 114, 70, 'rhombus;html=1;fillColor=#F0FAFF;strokeColor=#87BDDA;strokeWidth=2;fontFamily=PingFang SC;fontSize=26;', panels);
step('user-result', '20', '接收结果\n满意 / 继续提问', 15, 885, 108, 51, 'user', 9.5);

function connect(id, source, target, options = {}) {
  if (!identifiers.has(source) || !identifiers.has(target)) throw new Error(`Unknown endpoint: ${source} / ${target}`);
  const { label = '', color = '#202B34', dashed = false, pins = [0.5, 1, 0.5, 0], points = [] } = options;
  const routing = points.length ? `<Array as="points">${points.map(([x, y]) => `<mxPoint x="${x * 2}" y="${y * 2}"/>`).join('')}</Array>` : '';
  connectors.push(`<mxCell id="${id}" value="${escape(label)}" style="edgeStyle=${id.startsWith('legend-') ? 'none' : 'orthogonalEdgeStyle'};rounded=0;html=1;endArrow=block;endFill=1;strokeColor=${color};strokeWidth=${color === '#F21F35' ? 3 : 2};dashed=${dashed ? 1 : 0};dashPattern=5 4;fontFamily=PingFang SC;fontSize=23;fontColor=${color};labelBackgroundColor=#FFFFFF;exitX=${pins[0]};exitY=${pins[1]};entryX=${pins[2]};entryY=${pins[3]};" edge="1" parent="1" source="${source}" target="${target}"><mxGeometry relative="1" as="geometry">${routing}</mxGeometry></mxCell>`);
}

connect('question-input', 'user-question', 'agent-input', { pins: [1, 0.46, 0, 0.5] });
for (const [source, target] of [
  ['agent-input', 'agent-intent'], ['agent-intent', 'agent-context'], ['agent-context', 'agent-plan'], ['agent-plan', 'agent-select'],
  ['llm-context', 'llm-plan'], ['llm-plan', 'llm-actions'], ['skill-select', 'skill-input'], ['skill-input', 'skill-execute'],
  ['skill-execute', 'skill-tools'], ['mcp-call', 'mcp-package'], ['mcp-package', 'mcp-dispatch'], ['tool-select', 'tool-execute'],
  ['tool-execute', 'tool-result'], ['mcp-response', 'mcp-normalize'], ['agent-reply', 'agent-evaluate'], ['agent-evaluate', 'agent-satisfied']
]) connect(`${source}-to-${target}`, source, target);
connect('intent-to-llm', 'agent-plan', 'llm-context', { dashed: true, pins: [1, 0.43, 0, 0.5], points: [[396, 365], [396, 200]] });
connect('plan-to-skill', 'llm-actions', 'skill-select', { dashed: true, pins: [0.5, 1, 0.47, 0], points: [[470, 380], [364, 380]] });
connect('select-to-skill', 'agent-select', 'skill-select', { pins: [1, 0.3, 0, 0.6] });
connect('skill-needs-tool', 'skill-tools', 'mcp-call', { color: '#F21F35', pins: [1, 0.5, 0, 0.46], points: [[477, 596], [477, 459]] });
text('needs-tool-yes', '是', 439, 570, 22, 21, 13, true, '#F21F35');
connect('skill-no-tool', 'skill-tools', 'skill-direct');
text('needs-tool-no', '否', 378, 627, 23, 19, 12, true);
connect('direct-to-reply', 'skill-direct', 'agent-reply', { dashed: true, pins: [0, 0.56, 1, 0.46] });
connect('mcp-to-tool-discovery', 'mcp-call', 'tool-select', { dashed: true, pins: [1, 0.45, 0, 0.45] });
connect('mcp-tool-dispatch', 'mcp-dispatch', 'tool-execute', { dashed: true, pins: [1, 0.5, 0, 0.6], points: [[641, 596], [641, 532]] });
connect('tool-to-mcp-response', 'tool-result', 'mcp-response', { dashed: true, pins: [0.5, 1, 1, 0.52], points: [[703, 673]] });
connect('normalized-to-llm', 'mcp-normalize', 'llm-result', { dashed: true, pins: [0.5, 1, 1, 0.35], points: [[565, 756], [590, 756], [590, 795]] });
connect('llm-to-skill-result', 'llm-result', 'skill-result', { dashed: true, pins: [0, 0.26, 1, 0.52], points: [[442, 788], [442, 753]] });
connect('skill-output-to-reply', 'skill-result', 'agent-reply', { dashed: true, pins: [0, 0.52, 1, 0.65], points: [[285, 753], [285, 688]] });
connect('reevaluate-memory', 'agent-satisfied', 'skill-memory', { label: '否', pins: [1, 0.5, 0, 0.5] });
connect('memory-to-result', 'skill-memory', 'skill-result', { pins: [0.5, 0, 0.5, 1] });
connect('memory-retry-skill', 'skill-memory', 'agent-select', { dashed: true, pins: [0.15, 0, 0.53, 1], points: [[291, 785], [280, 785], [280, 454], [210, 454]] });
connect('approved-result', 'agent-satisfied', 'user-result', { color: '#F21F35', pins: [0.35, 0.85, 0.6, 0], points: [[185, 865], [80, 865]] });
text('approved-yes', '是', 211, 850, 20, 22, 12);
connect('approved-memory', 'agent-satisfied', 'skill-memory', { dashed: true, pins: [0.54, 1, 0.5, 1], points: [[208, 884], [367, 884]] });
connect('next-question', 'user-result', 'user-question', { dashed: true, pins: [0.46, 0, 0.5, 1] });
text('question-loop-label', '未结束，继续提问\n回到用户输入侧', 12, 494, 115, 45, 11.5);

shape('legend', '', 584, 829, 172, 107, 'rounded=1;arcSize=10;fillColor=#FFFFFF;strokeColor=#AAB4BF;strokeWidth=1.5;', panels);
text('legend-title', '图例说明：', 592, 835, 109, 18, 12, true, '#101820', 'left');
const legendRows = [
  ['main', 862, '主流程（信息流）', '#202B34', false],
  ['cross', 881, '跨模块调用 / 数据流', '#202B34', true],
  ['loop', 900, '循环分支（信息不足时重新调用工具）', '#F21F35', false]
];
for (const [id, y, label, color, dashed] of legendRows) {
  shape(`legend-${id}-start`, '', 592, y, 1, 1, 'fillColor=none;strokeColor=none;');
  shape(`legend-${id}-end`, '', 619, y, 1, 1, 'fillColor=none;strokeColor=none;');
  connect(`legend-${id}-line`, `legend-${id}-start`, `legend-${id}-end`, { color, dashed, pins: [1, 0.5, 0, 0.5] });
  text(`legend-${id}-label`, label, 625, y - 8, 125, 17, id === 'loop' ? 7 : 9, false, '#202B34', 'left');
}
text('legend-mcp', 'MCP: Model Context Protocol（模型上下文协议）', 591, 916, 160, 15, 6.5, false, '#202B34', 'left');

const content = `<mxfile host="app.diagrams.net" type="device"><diagram id="agent-workflow-from-image" name="AI Agent 运行全流程"><mxGraphModel dx="1536" dy="2048" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="1536" pageHeight="2048" math="0" shadow="0"><root><mxCell id="0"/><mxCell id="1" parent="0"/>\n${panels.join('\n')}\n${connectors.join('\n')}\n${nodes.join('\n')}\n</root></mxGraphModel></diagram></mxfile>\n`;
const path = 'docs/diagrams/agent-workflow-from-image.drawio';
const previous = fs.existsSync(path) ? fs.readFileSync(path, 'utf8') : null;
let patch = '*** Begin Patch\n';
if (previous !== content) {
  patch += previous === null ? `*** Add File: ${path}\n` : `*** Update File: ${path}\n@@\n${previous.trimEnd().split('\n').map(line => '-' + line).join('\n')}\n`;
  patch += content.trimEnd().split('\n').map(line => '+' + line).join('\n') + '\n';
}
process.stdout.write(patch + '*** End Patch\n');
