import fs from 'node:fs';

const backgrounds = [];
const foregrounds = [];
const edges = [];
const identifiers = new Set(['0', '1']);
const colors = {
  business: ['#F7EEE2', '#BD9E74'], agent: ['#EAF2FC', '#86A9D0'],
  domain: ['#ECF6EF', '#86AE94'], foundation: ['#F1EDFA', '#A292BE'],
  control: ['#F1F5F8', '#9AAEBE'], optional: ['#FCF0F5', '#BE91AA'], white: ['#FFFFFF', '#ABB7C1']
};
const escape = value => String(value).replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;'
})[character]);

function cell(id, label, x, y, width, height, style, background = false) {
  if (identifiers.has(id)) throw new Error(`Duplicate ID: ${id}`);
  identifiers.add(id);
  (background ? backgrounds : foregrounds).push(`<mxCell id="${id}" value="${escape(label)}" style="${style}" vertex="1" parent="1"><mxGeometry x="${x}" y="${y}" width="${width}" height="${height}" as="geometry"/></mxCell>`);
}

function panel(id, title, x, y, width, height, kind, size = 26) {
  const [fill, stroke] = colors[kind];
  cell(id, title, x, y, width, height, `rounded=0;html=1;whiteSpace=wrap;fillColor=${fill};strokeColor=${stroke};strokeWidth=2;fontFamily=PingFang SC;fontSize=${size};fontStyle=1;fontColor=#1D3244;align=left;verticalAlign=top;spacingLeft=18;spacingTop=12;`, true);
}

function box(id, title, detail, x, y, width, height, kind = 'white', size = 23) {
  const [fill, stroke] = colors[kind];
  const label = `<b>${escape(title)}</b>${detail ? `<br><span style="font-size:19px">${escape(detail).replaceAll('\n', '<br>')}</span>` : ''}`;
  cell(id, label, x, y, width, height, `rounded=0;html=1;whiteSpace=wrap;fillColor=${fill};strokeColor=${stroke};strokeWidth=1.5;fontFamily=PingFang SC;fontSize=${size};fontColor=#1D3244;align=center;verticalAlign=middle;spacing=8;`);
}

function text(id, label, x, y, width, height, size = 20, color = '#536675') {
  cell(id, escape(label).replaceAll('\n', '<br>'), x, y, width, height, `text;html=1;whiteSpace=wrap;align=center;verticalAlign=middle;fontFamily=PingFang SC;fontSize=${size};fontColor=${color};`);
}

function edge(id, source, target, label = '', pins = [0.5, 1, 0.5, 0], points = [], options = {}) {
  if (!identifiers.has(source) || !identifiers.has(target)) throw new Error(`Unknown endpoint: ${source} / ${target}`);
  identifiers.add(id);
  const color = options.color || '#56738B';
  const route = points.length ? `<Array as="points">${points.map(([x, y]) => `<mxPoint x="${x}" y="${y}"/>`).join('')}</Array>` : '';
  edges.push(`<mxCell id="${id}" value="${escape(label)}" style="edgeStyle=orthogonalEdgeStyle;rounded=0;html=1;endArrow=block;endFill=1;startArrow=${options.both ? 'block' : 'none'};startFill=1;strokeWidth=${options.width || 2.5};strokeColor=${color};dashed=${options.dashed ? 1 : 0};fontFamily=PingFang SC;fontSize=19;fontColor=${color};labelBackgroundColor=#FFFFFF;jumpStyle=arc;jumpSize=8;exitX=${pins[0]};exitY=${pins[1]};entryX=${pins[2]};entryY=${pins[3]};" edge="1" parent="1" source="${source}" target="${target}"><mxGeometry relative="1" as="geometry">${route}</mxGeometry></mxCell>`);
}

text('title', 'AI 搜索 × AI 推荐｜Workflow + Agent 四层系统架构', 140, 20, 1980, 70, 38, '#1D3244');
text('subtitle', '业务流程独立迭代 · Agent 按需参与 · 公共能力复用 · 百策统一治理', 200, 92, 1870, 40, 24);
box('entry', '端上各场景', '搜索框 / 筛选 / 推荐流 / 商品卡 / 对话追问 / 服务入口', 400, 150, 990, 65, 'business', 24);

panel('business-layer', 'L1 业务层｜两个应用，各自负责交互与 Workflow', 160, 265, 1390, 310, 'business');
panel('search-app', 'AI 搜应用  ·  搜索团队独立迭代', 185, 320, 655, 230, 'white', 23);
panel('rec-app', 'AI 推应用  ·  推荐团队独立迭代', 865, 320, 655, 230, 'white', 23);
box('search-interaction', '交互模块', '行为路由 · 响应封装 · 流式 / 非流式', 205, 369, 615, 62, 'business', 22);
box('rec-interaction', '交互模块', '行为路由 · 响应封装 · 流式 / 非流式', 885, 369, 615, 62, 'business', 22);
box('search-workflow', '业务执行模块 / 搜索 Workflow', '本地逻辑节点 · Agent RPC 节点 · 领域 RPC 节点', 205, 474, 615, 60, 'business', 22);
box('rec-workflow', '业务执行模块 / 推荐 Workflow', '本地逻辑节点 · Agent RPC 节点 · 领域 RPC 节点', 885, 474, 615, 60, 'business', 22);

panel('agent-layer', 'L2 Agent 层｜独立 Runtime + 公共资产 + 共建 Tool Adaptor', 160, 660, 1390, 400, 'agent');
panel('runtime-group', '① 各自的 Runtime  ·  独立任务池 / 发布 / 扩容', 185, 715, 1335, 130, 'white', 23);
box('search-runtime', 'search-runtime', 'Context 编排 · LLM Loop · 状态 / 预算 / 校验', 205, 764, 615, 62, 'agent', 23);
box('rec-runtime', 'rec-runtime', 'Context 编排 · LLM Loop · 状态 / 预算 / 校验', 885, 764, 615, 62, 'agent', 23);
panel('shared-assets', '② 公共 Prompt / LLM / Skill 资产', 185, 901, 655, 130, 'white', 23);
box('prompt', 'Prompt 模板', '版本 / 参数规范', 201, 956, 193, 56, 'agent', 21);
box('llm-config', 'LLM 模型配置', '版本 / 路由 / 参数', 410, 956, 207, 56, 'agent', 21);
box('skill', 'Skill 能力包', '任务方法 / 工具声明', 633, 956, 190, 56, 'agent', 21);
panel('tool-adaptor', '③ 共建 Tool Adaptor', 865, 901, 655, 130, 'white', 23);
box('tool-contract', '公共 Tool Schema + 远程服务适配', '参数转换 · 身份 / 权限 · 超时 / 错误 · 结果归一', 885, 956, 615, 56, 'agent', 21);

panel('domain-layer', 'L3 领域能力层｜可由 Workflow 直调，也可封装为 Tool', 160, 1155, 1390, 155, 'domain');
box('search-service', '搜索服务', '检索 / 过滤 / 相关性', 185, 1217, 245, 65, 'white');
box('rec-service', '推荐服务', '召回 / 重排 / 集合策略', 455, 1217, 245, 65, 'white');
box('hint-service', 'Hint 服务', '联想 / 引导 / 下一步', 725, 1217, 245, 65, 'white');
box('product-service', '商品信息服务', '规格 / 价格 / 库存', 995, 1217, 245, 65, 'white');
box('user-service', '用户信息服务等', '授权画像 / 用户行为', 1265, 1217, 260, 65, 'white');

panel('ai-foundation', 'L4 AI 公共能力层｜为 Agent 提供基础服务；按授权供其他层复用', 160, 1410, 1390, 155, 'foundation');
box('context-service', 'Context 服务', '会话 / 工作上下文 / 检查点', 185, 1475, 300, 65, 'white');
box('memory-service', 'AI Memory', '跨会话偏好 / 授权记忆', 510, 1475, 300, 65, 'white');
box('rag-service', 'RAG 服务', '知识检索 / 证据 / 引用', 835, 1475, 300, 65, 'white');
box('maas-service', 'MaaS 服务', 'LLM / Embedding / Reranker 推理', 1200, 1475, 325, 65, 'white', 23);

panel('platform', '横向平台｜开发入口与生命周期治理', 1730, 265, 440, 795, 'control', 25);
box('developer-platform', '开发者平台', 'Prompt / Skill / Tool 注册\n沙箱试运行 · 调试 / 回放 · 效果预览', 1755, 325, 390, 125, 'white', 26);
panel('baice', '百策平台（公共控制面）', 1755, 520, 390, 515, 'white', 24);
box('asset-management', 'Prompt / LLM 配置 / Skill', '生命周期 · 依赖校验 · 命名空间', 1775, 580, 350, 80, 'control', 23);
box('evaluation', '评测 / AB 实验 / 调试', '实验分组 · Trace / Debug · 结果归因', 1775, 685, 350, 80, 'control', 23);
box('release', '注册 → 审核 → 发布', '版本锁定 · 灰度 · 回滚 · 权限策略', 1775, 790, 350, 80, 'control', 23);
text('baice-boundary', '向 L1 / L2 / L3 提供配置与实验能力\n接收运行事件；不承担逐轮推理\n模型执行由 MaaS 提供', 1775, 900, 350, 90, 20);

panel('optional-agents', '可选扩展｜多 Agent 与外部服务协作', 1730, 1155, 440, 410, 'optional', 24);
box('delegation', '受控子 Agent / 委派管理', '局部循环 · 子预算 · 产物验收\n可先同池运行，不强制新增微服务', 1755, 1220, 390, 83, 'white', 23);
box('agent-adaptor', 'Agent Adaptor', '外部任务协议 / 身份 / 状态 / 取消', 1755, 1325, 390, 70, 'white', 23);
box('external-agent', '外部 Agent', '下单 / 家政 / 外卖等\n涉及交易须可信确认，不由模型授权', 1755, 1450, 390, 85, 'optional', 23);

edge('entry-business', 'entry', 'business-layer', '请求 / 业务结果', [0.5, 1, 0.5, 0], [], { both: true });
edge('search-interaction-workflow', 'search-interaction', 'search-workflow', '路由 / 结果', [0.5, 1, 0.5, 0], [], { both: true });
edge('rec-interaction-workflow', 'rec-interaction', 'rec-workflow', '路由 / 结果', [0.5, 1, 0.5, 0], [], { both: true });
edge('search-agent-rpc', 'search-workflow', 'search-runtime', '按需 Agent RPC', [0.5, 1, 0.5, 0]);
edge('rec-agent-rpc', 'rec-workflow', 'rec-runtime', '按需 Agent RPC', [0.5, 1, 0.5, 0]);
edge('runtime-assets', 'runtime-group', 'shared-assets', '两池加载已发布资产', [0.24, 1, 0.5, 0]);
edge('runtime-tools', 'runtime-group', 'tool-adaptor', '两池执行 Tool 调用', [0.76, 1, 0.5, 0]);
edge('tools-domain', 'tool-adaptor', 'domain-layer', 'RPC / HTTP → 领域服务', [0.5, 1, 0.74, 0]);
edge('workflow-direct-domain', 'business-layer', 'domain-layer', 'Workflow 直达领域服务', [0, 0.8, 0, 0.62], [[75, 513], [75, 1251]], { color: '#438761', width: 3 });
edge('runtime-foundation', 'agent-layer', 'ai-foundation', '', [1, 0.55, 1, 0.55], [[1605, 880], [1605, 1495]], { color: '#8271A5' });
text('foundation-call-label', 'Runtime / Tool Adaptor → AI 公共服务', 1180, 1344, 465, 38, 19, '#8271A5');
edge('rag-maas', 'rag-service', 'maas-service', '推理', [1, 0.5, 0, 0.5], [], { color: '#8271A5' });
edge('developer-baice', 'developer-platform', 'baice', '注册 / 调试 / 发布申请');
edge('baice-l1', 'baice', 'business-layer', 'L1', [0, 0.12, 1, 0.37], [[1660, 582], [1660, 380]], { dashed: true, both: true, color: '#8997A4' });
edge('baice-l2', 'baice', 'agent-layer', 'L2', [0, 0.45, 1, 0.23], [[1640, 752]], { dashed: true, both: true, color: '#8997A4' });
edge('baice-l3', 'baice', 'domain-layer', 'L3', [0, 0.87, 1, 0.38], [[1670, 968], [1670, 1214]], { dashed: true, both: true, color: '#8997A4' });
edge('runtime-delegate', 'agent-layer', 'optional-agents', '', [1, 0.87, 0, 0.15], [[1635, 1008], [1635, 1217]], { dashed: true, color: '#AF7696' });
text('delegation-label', '按需委派', 1570, 1085, 150, 34, 19, '#AF7696');
edge('external-delegate', 'agent-adaptor', 'external-agent', '远程任务 / 状态 / 产物', [0.5, 1, 0.5, 0], [], { both: true, color: '#AF7696' });

text('legend', '蓝：在线执行　绿：Workflow 直接调用领域服务　紫：AI 基础服务　灰虚线：百策配置 / 实验 / Trace　粉虚线：可选委派', 180, 1600, 1960, 45, 21);
text('note', '分层表示职责，不是必须逐层转发。实线响应沿调用返回；公共框表示能力分组，不代表新增统一代理。平台能力按目标设计接入，现网支持项需核验。', 180, 1650, 1960, 45, 20);

const content = `<mxfile host="app.diagrams.net" type="device"><diagram id="workflow-agent-four-layer" name="Workflow + Agent 四层系统架构"><mxGraphModel grid="1" gridSize="10" page="1" pageWidth="2220" pageHeight="1740"><root><mxCell id="0"/><mxCell id="1" parent="0"/>\n${backgrounds.join('\n')}\n${edges.join('\n')}\n${foregrounds.join('\n')}\n</root></mxGraphModel></diagram></mxfile>\n`;
const path = 'docs/diagrams/workflow-agent-four-layer.drawio';
const previous = fs.existsSync(path) ? fs.readFileSync(path, 'utf8') : null;
let patch = '*** Begin Patch\n';
if (previous !== content) {
  patch += previous === null ? `*** Add File: ${path}\n` : `*** Update File: ${path}\n@@\n${previous.trimEnd().split('\n').map(line => '-' + line).join('\n')}\n`;
  patch += content.trimEnd().split('\n').map(line => '+' + line).join('\n') + '\n';
}
process.stdout.write(patch + '*** End Patch\n');
