import fs from 'node:fs';

const panels = [];
const nodes = [];
const edges = [];
const ids = new Set(['0', '1']);
const escape = value => String(value).replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;'
})[character]);
const palette = {
  platform: ['#F1F8FC', '#8F9DA6'], app: ['#E6D4BF', '#9C8060'],
  white: ['#FFFFFF', '#A0A7AD'], loop: ['#FCEEEE', '#BD9D9D'],
  skill: ['#EFF6EF', '#8DA58F'], external: ['#F5EFF9', '#A28BAF']
};

function cell(id, label, x, y, width, height, style, background = false) {
  if (ids.has(id)) throw new Error(`Duplicate ID: ${id}`);
  ids.add(id);
  const xml = `<mxCell id="${id}" value="${escape(label)}" style="${style}" vertex="1" parent="1"><mxGeometry x="${x}" y="${y}" width="${width}" height="${height}" as="geometry"/></mxCell>`;
  (background ? panels : nodes).push(xml);
}

function panel(id, title, x, y, width, height, kind = 'platform', dashed = false) {
  const [fill, stroke] = palette[kind];
  cell(id, title, x, y, width, height, `rounded=0;html=1;whiteSpace=wrap;fillColor=${fill};strokeColor=${stroke};strokeWidth=1.7;dashed=${dashed ? 1 : 0};fontFamily=PingFang SC;fontColor=#172630;fontSize=23;fontStyle=1;align=left;verticalAlign=top;spacingLeft=18;spacingTop=13;`, true);
}

function box(id, title, subtitle, x, y, width, height, kind = 'white', size = 22) {
  const [fill, stroke] = palette[kind];
  const value = `<b>${escape(title)}</b>${subtitle ? `<br><span style="font-size:17px;color:#485660">${escape(subtitle)}</span>` : ''}`;
  cell(id, value, x, y, width, height, `rounded=0;html=1;whiteSpace=wrap;fillColor=${fill};strokeColor=${stroke};strokeWidth=1.3;fontFamily=PingFang SC;fontColor=#172630;fontSize=${size};align=center;verticalAlign=middle;spacing=6;`);
}

function text(id, label, x, y, width, height, size = 19, bold = false) {
  cell(id, label, x, y, width, height, `text;html=1;whiteSpace=wrap;align=center;verticalAlign=middle;fontFamily=PingFang SC;fontColor=#485660;fontSize=${size};fontStyle=${bold ? 1 : 0};`);
}

function edge(source, target, label = '', pins = [0.5, 1, 0.5, 0], points = [], options = {}) {
  if (!ids.has(source) || !ids.has(target)) throw new Error(`Unknown edge: ${source} → ${target}`);
  const route = points.length ? `<Array as="points">${points.map(([x, y]) => `<mxPoint x="${x}" y="${y}"/>`).join('')}</Array>` : '';
  const color = options.color || '#68747D';
  edges.push(`<mxCell id="edge-${edges.length + 1}" value="${escape(label)}" style="edgeStyle=orthogonalEdgeStyle;rounded=0;html=1;endArrow=block;endFill=1;strokeColor=${color};strokeWidth=${options.width || 2};dashed=${options.dashed ? 1 : 0};fontFamily=PingFang SC;fontSize=17;fontColor=${color};labelBackgroundColor=#FFFFFF;exitX=${pins[0]};exitY=${pins[1]};entryX=${pins[2]};entryY=${pins[3]};" edge="1" parent="1" source="${source}" target="${target}"><mxGeometry relative="1" as="geometry">${route}</mxGeometry></mxCell>`);
}

text('title', 'AI 搜索 × AI 推荐 · 系统架构', 360, 12, 860, 48, 32, true);
text('subtitle', '业务流程独立 · 执行机制共建 · 专业能力复用', 380, 62, 820, 32, 19);

box('entry', '用户 / APP / Web', '搜索入口 · 推荐流 · 商品详情 · 对话', 460, 118, 660, 72);
panel('app', '应用层 / BFF  ·  ai-search-app / ai-rec-app', 430, 237, 720, 116, 'app');
box('scene', '场景 Workflow', '', 452, 293, 154, 40, 'white', 19);
box('route', '请求分流', '', 626, 293, 154, 40, 'white', 19);
box('reply', 'Reply / 卡片', '', 800, 293, 154, 40, 'white', 19);
box('release', '实验 / 降级', '', 974, 293, 154, 40, 'white', 19);

panel('engineering', 'Agent 工程平台', 40, 410, 280, 552);
box('agent-control', 'agentControl', '配置 · 注册 · 版本发布', 62, 474, 236, 70);
box('assets', '能力与 Agent 管理', 'Prompt / Skill / Tool', 62, 572, 236, 70);
box('trace', 'Trace / Debug', '运行观测 · 问题回放', 62, 670, 236, 70);
box('evaluation', 'Evaluation', '评测 · 实验 · 灰度', 62, 768, 236, 70);
box('governance', '运营与治理', '权限 · 预算 · 成本', 62, 866, 236, 70);

panel('harness', 'Agent Harness', 430, 410, 720, 552);
text('pools', 'search-runtime / rec-runtime  ·  两个独立根任务池', 455, 452, 670, 32, 18);
panel('policy', '行为控制', 452, 502, 676, 180, 'white', true);
box('intent', '任务理解与规划', '', 472, 552, 196, 45, 'white', 21);
box('context', 'Context / Prompt', '', 692, 552, 196, 45, 'white', 21);
box('knowledge', '记忆与知识策略', '', 912, 552, 196, 45, 'white', 21);
box('skills', 'Skill 加载', '', 472, 619, 196, 45, 'white', 21);
box('tools', 'Tool 发现', '', 692, 619, 196, 45, 'white', 21);
box('delegation', '子 Agent 委派', '', 912, 619, 196, 45, 'white', 21);

panel('runtime', '执行运行  /  Runtime', 452, 716, 676, 224, 'white', true);
box('assemble', '组装上下文', '', 472, 765, 139, 42, 'loop', 20);
box('llm', 'LLM 推理', '', 637, 765, 139, 42, 'loop', 20);
box('execute', '动作执行', '', 803, 765, 139, 42, 'loop', 20);
box('result', '结果校验', '', 969, 765, 139, 42, 'loop', 20);
box('session', '任务状态', '', 472, 855, 139, 42, 'white', 19);
box('budget', '预算控制', '', 637, 855, 139, 42, 'white', 19);
box('recover', '恢复 / 取消', '', 803, 855, 139, 42, 'white', 19);
box('confirm', '人工确认', '', 969, 855, 139, 42, 'white', 19);
text('hooks', '真实进度 · 检查点 · Trace / Hooks', 477, 905, 626, 24, 17);

panel('foundation', 'Agent 基础设施', 1270, 410, 300, 552);
box('model-service', '模型服务', 'LLM / Embedding / Reranker', 1292, 474, 256, 88, 'white', 23);
box('memory-service', 'Memory / 状态服务', '会话 · 偏好 · 检查点', 1292, 594, 256, 88, 'white', 22);
box('rag-service', 'RAG / 数据与检索', '知识证据 · 向量索引 · 特征', 1292, 714, 256, 88, 'white', 22);
box('security', '身份与运行安全', '授权 · 隔离 · 配额 · 审计', 1292, 834, 256, 88, 'white', 22);

panel('domain', '业务能力  /  Tool Adapter → Domain', 40, 1040, 730, 126, 'app');
box('search-domain', '搜索 / Hints', '', 60, 1100, 160, 44, 'white', 20);
box('rec-domain', '推荐 / 排序', '', 237, 1100, 160, 44, 'white', 20);
box('facts', '商品 / 价格库存', '', 414, 1100, 160, 44, 'white', 18);
box('trade', '订单 / 交易', '', 591, 1100, 160, 44, 'white', 20);
panel('agents', 'Agent 协作（按需）', 840, 1040, 730, 126, 'app');
box('child', '内部专业子 Agent', '', 860, 1100, 220, 44, 'skill', 20);
box('agent-gateway', 'Agent Gateway', '', 1100, 1100, 190, 44, 'external', 20);
box('external', '外部 Agent', '其他部门 / 合作方', 1330, 1091, 220, 62, 'external', 20);

text('note', '总览仅展示职责与主关系；详细调用见 02A / 02B。一个根任务负责交付，子任务受限；交易仍需可信确认。', 110, 1200, 1420, 46, 19);

edge('entry', 'app', '', [0.5, 1, 0.5, 0], [], { width: 3 });
edge('app', 'harness', '', [0.5, 1, 0.5, 0], [], { width: 3 });
edge('engineering', 'harness', '配置下发', [1, 0.33, 0, 0.33], [], { dashed: true });
edge('harness', 'engineering', '事件上报', [0, 0.69, 1, 0.69], [], { dashed: true });
edge('harness', 'foundation', '运行依赖', [1, 0.49, 0, 0.49], [], { width: 3 });
edge('policy', 'runtime');
edge('assemble', 'llm', '', [1, 0.5, 0, 0.5]);
edge('llm', 'execute', '', [1, 0.5, 0, 0.5]);
edge('execute', 'result', '', [1, 0.5, 0, 0.5]);
edge('result', 'assemble', 'Loop', [0.5, 1, 0.5, 1], [[1039, 830], [542, 830]]);
edge('harness', 'domain', '工具调用', [0.23, 1, 0.76, 0], [], { width: 3 });
edge('harness', 'agents', '受控委派', [0.8, 1, 0.21, 0], [], { width: 3 });
edge('agent-gateway', 'external', '', [1, 0.5, 0, 0.5], [], { color: '#9476A4' });
edge('app', 'domain', 'App 可直达 Domain', [0, 0.5, 0.5, 0], [[368, 295], [368, 1004], [405, 1004]], { color: '#56806A', dashed: true });

const path = 'docs/diagrams/agent-system-overview.drawio';
const content = `<mxfile host="app.diagrams.net" type="device"><diagram id="agent-system-overview" name="01 系统架构总览"><mxGraphModel grid="1" page="1" pageWidth="1610" pageHeight="1270"><root><mxCell id="0"/><mxCell id="1" parent="0"/>\n${panels.join('\n')}\n${edges.join('\n')}\n${nodes.join('\n')}\n</root></mxGraphModel></diagram></mxfile>\n`;
let patch = '*** Begin Patch\n';
if (fs.existsSync(path)) {
  const previous = fs.readFileSync(path, 'utf8');
  if (previous === content) {
    process.stdout.write(patch + '*** End Patch\n');
  } else {
    patch += `*** Update File: ${path}\n@@\n${previous.trimEnd().split('\n').map(line => '-' + line).join('\n')}\n`;
    process.stdout.write(patch + content.trimEnd().split('\n').map(line => '+' + line).join('\n') + '\n*** End Patch\n');
  }
} else {
  process.stdout.write(patch + `*** Add File: ${path}\n` + content.trimEnd().split('\n').map(line => '+' + line).join('\n') + '\n*** End Patch\n');
}
