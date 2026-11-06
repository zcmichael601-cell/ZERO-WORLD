import fs from 'node:fs';

const palette = {
  app: ['#EDF4FF', '#648FC5'], runtime: ['#EAF6F0', '#64A587'],
  domain: ['#FFF4E7', '#CCA062'], model: ['#F1EDFC', '#9880C6'],
  control: ['#F3F5F8', '#94A3B8'], safety: ['#FCEFEB', '#C88178']
};
const escape = value => String(value).replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;'
})[character]);
const nodes = [];
const edges = [];
const ids = new Set(['0', '1']);

function node(id, title, body, x, y, width, height, kind = 'runtime') {
  if (ids.has(id)) throw new Error(`Duplicate node: ${id}`);
  ids.add(id);
  const [fill, stroke] = palette[kind];
  const label = `<b style="font-size:26px">${escape(title)}</b><br><span style="font-size:21px">${escape(body).replaceAll('\n', '<br>')}</span>`;
  nodes.push(`<mxCell id="${id}" value="${escape(label)}" style="rounded=1;arcSize=9;html=1;whiteSpace=wrap;fillColor=${fill};strokeColor=${stroke};fontColor=#19324D;fontFamily=PingFang SC;align=center;verticalAlign=middle;spacing=15;strokeWidth=2;" vertex="1" parent="1"><mxGeometry x="${x}" y="${y}" width="${width}" height="${height}" as="geometry"/></mxCell>`);
}

function text(id, label, x, y, width, height = 60, size = 25) {
  if (ids.has(id)) throw new Error(`Duplicate node: ${id}`);
  ids.add(id);
  nodes.push(`<mxCell id="${id}" value="${escape(label)}" style="text;html=1;whiteSpace=wrap;align=left;verticalAlign=middle;fontFamily=PingFang SC;fontSize=${size};fontColor=#243E5A;" vertex="1" parent="1"><mxGeometry x="${x}" y="${y}" width="${width}" height="${height}" as="geometry"/></mxCell>`);
}

function edge(source, target, label = '', pins = [0.5, 1, 0.5, 0], points = [], kind = 'online') {
  if (!ids.has(source) || !ids.has(target)) throw new Error(`Invalid endpoints: ${source} → ${target}`);
  const stroke = { online: '#53769A', loop: '#17845C', async: '#8B98A9', warning: '#BF726A' }[kind];
  const route = points.length ? `<Array as="points">${points.map(([x, y]) => `<mxPoint x="${x}" y="${y}"/>`).join('')}</Array>` : '';
  edges.push(`<mxCell id="edge-${edges.length + 1}" value="${escape(label)}" style="edgeStyle=orthogonalEdgeStyle;rounded=0;html=1;endArrow=block;endFill=1;strokeColor=${stroke};strokeWidth=${kind === 'loop' ? 3 : 2};fontFamily=PingFang SC;fontSize=20;fontColor=${stroke};labelBackgroundColor=#FFFFFF;dashed=${kind === 'async' ? 1 : 0};exitX=${pins[0]};exitY=${pins[1]};entryX=${pins[2]};entryY=${pins[3]};" edge="1" parent="1" source="${source}" target="${target}"><mxGeometry relative="1" as="geometry">${route}</mxGeometry></mxCell>`);
}

text('title', '03 / 找到“对我更划算”的商品 · Agent 业务流程与模块协作', 70, 20, 2470, 70, 35);
text('subtitle', '目标设计示例：500 元内无线耳机 / 地铁通勤 / 安卓 / 重视降噪与舒适｜满足硬条件后比较收益、价格与限制，不等于最低价', 70, 100, 2470, 65, 23);
node('control', '⓪ 上线前｜agentControl 发布版本化场景包', '业务团队提交 Skill / Prompt / Tool 契约 → 评测与审核 → 模型路由、白名单、预算与灰度版本 → 各 Runtime 池本地快照\n控制面不参与逐请求主循环；知识管道提前完成摄取、Embedding、索引与发布', 70, 190, 2400, 125, 'control');
node('app', '① 用户 → 网关 → App 分流', '“想找性价比高的耳机，500 元内，地铁通勤”\n身份校验 / requestId；复杂比较交给一个 Runtime 池', 850, 390, 740, 140, 'app');
node('context', '② Runtime 初始化任务与上下文', '加载选购 Skill；读取授权 Memory\n当前输入优先；维护预算、用途、排除项与候选引用\n组装 Prompt + Tool schema，创建 run / 版本快照', 850, 620, 740, 170);
node('skill', 'Skill｜高性价比选购 SOP', '澄清 → 搜索 → 核价 / 查证 → 比较 → 解释取舍\n声明允许工具、必需输出与证据不足处理\n是指令与契约，不直接操作业务系统', 80, 555, 600, 170, 'control');
node('memory', 'Memory / 状态基础服务', '会话约束、授权偏好、已看 / 排除商品\ncandidateSetId、证据引用与持久检查点\n本轮预算覆盖旧预算；长期偏好需授权', 80, 835, 600, 170, 'model');
node('llm', '③ Runtime 请求 LLM', '输入：Prompt + 当前目标 + 必要记忆 + 工具结果\n模型提议：追问 / tool_calls / 有依据的比较\n模型提议不等于工具已执行，也不是事实证明', 850, 930, 740, 160);
node('models', '模型基础服务 ≠ LLM 本身', 'Runtime → 模型接入 / 路由 / 配额 → LLM\nRAG → Embedding（如 bge-m3） / 可选 Reranker\nLLM 做选择与解释；向量模型做编码', 1750, 915, 720, 175, 'model');
node('route', '④ Runtime 判断模型动作', '追问 → 等待用户；tool_calls → 受控执行\n回答 → 结果校验；超时 / 超预算 → 收敛或降级\n一个 run 只有一个主循环', 850, 1210, 740, 150);
node('clarify', '需要澄清｜App 等待用户', '缺品类 / 预算 / 用途时再问，不猜购物目标\n用户补充后恢复上下文；等待不消耗模型空转\nMemory 不能替用户确认预算与意图', 80, 1210, 600, 150, 'app');
node('tools', '⑤ Tool Gateway / Adapter 真正调用', '校验工具名、schema、身份、scope 与 deadline\n候选相关调用必须先拿到有效 SKU\n⑤a～⑤d 按需求选择、组合；不是固定顺序', 850, 1470, 740, 160);
node('search', '⑤a searchProducts → search-domain', '商品召回 / 属性过滤 / 预算预筛\n输出真实 CandidateSet；不是模型生成商品名', 1750, 1190, 720, 145, 'domain');
node('rec', '⑤b recommendProducts → rec-domain', '按本轮目标、授权特征与已核验事实重排\n输出 Slate 与理由特征；不盲信 LLM 自评分', 1750, 1450, 720, 145, 'domain');
node('rag', '⑤c retrieveKnowledge → RAG', '按 SKU 检索规格、说明、政策与适用评价\n查询向量 → ACL / 时效过滤 → 可选重排\n返回原文依据 EvidenceBundle；不另起 Agent', 1750, 1705, 720, 165, 'domain');
node('facts', '⑤d getProductFacts → 权威事实服务', '商品 / 实时价格 / 库存 / 优惠资格 / 可售性\n返回 EntitySnapshot；只计用户可获得的优惠\nRAG 与 Memory 不能代替核价', 1750, 1970, 720, 165, 'domain');
node('observe', '⑥ Runtime 回填结果 / 保存状态', 'tool_call_id 对应结果、错误、耗时与引用\n形成新的对话上下文 → 再次请求 LLM\n可有限补查；工具失败不能声称成功', 850, 1750, 740, 160);
node('progress', '全过程｜真实进度与可恢复状态', '“已找到候选，正在核对价格”\n“已获取商品资料，正在比较差异”\n从执行事件产生，不公开模型内部思维链\n各步骤保存受控状态，不只在结尾落盘', 80, 1670, 600, 185, 'control');
node('validate', '⑦ Runtime 校验比较结论', 'SKU 来源、预算、兼容性、证据型号与有效期\n缺证据 → 说明不足；矛盾 → 收窄结论\n修正有预算，不能无限循环', 850, 2170, 740, 160);
node('fallback', '异常 / 证据不足｜可解释降级', '只展示已核验事实或请求补充条件\n仍不通过则不给未经证实的“性价比最高”\n模型不可用可回到 App → Domain 短链路', 80, 2160, 600, 170, 'safety');
node('reply', '⑧ App Reply 流式展示比较与商品卡', '为什么够用 / 加钱换来什么 / 接受哪些限制\n正文增量安全处理；结论与关键事实校验后输出\n商品卡回源核价；价格变化需重筛或告知', 850, 2440, 740, 165, 'app');
node('persist', '⑨ 会话续问 / 授权反馈', '本轮条件、候选与排除项写 Memory\n用户“不要入耳式” → 更新约束并开始下一轮\n选购结果不是购买授权，购买另走可信交易', 850, 2720, 740, 160, 'app');
node('evaluation', '事后｜事件 / Trace → 评测与改进', '约束满足、事实一致、证据忠实、时延与成本\n曝光与反馈按授权采集，不把点击等同偏好\n经审核才由 agentControl 发布下一版', 1750, 2720, 720, 160, 'control');
node('short', '保留短链路｜App → Domain', '普通筛选 / 分页 / 刷新推荐无需多轮 LLM\n确定性 Workflow 也可以直接组合搜推能力', 1750, 390, 720, 140, 'app');
text('legend', '蓝实线：流程推进 / 服务调用（工具结果原路返回）　绿线：同一 run 内模型循环　灰虚线：事前发布 / 事后闭环　红线：有界降级', 70, 2990, 2440, 65, 23);
node('boundary', '协作要点', 'Skill 告诉“怎样做”；Memory 保存“这个用户的上下文”；RAG 提供“外部知识依据”；Tool 实际“查业务系统”。\nLLM 选择与解释，模型服务提供推理，Runtime 保证受控执行，agentControl 管理版本与上线；任何一层都不能伪造事实或交易授权。', 70, 3090, 2400, 140, 'control');

edge('control', 'skill', '预发布场景包', [0.12, 1, 0.5, 0], [[358, 355], [380, 355]], 'async');
edge('control', 'context', '模型 / 工具 / 预算快照', [0.6, 1, 1, 0.15], [[1650, 340], [1650, 645]], 'async');
edge('app', 'context');
edge('app', 'short', '简单请求直达', [1, 0.5, 0, 0.5]);
edge('context', 'skill', '按需加载', [0, 0.18, 1, 0.65]);
edge('context', 'memory', '读取授权状态', [0, 0.75, 1, 0.25], [[725, 748], [725, 878]]);
edge('context', 'llm', '组装 Prompt');
edge('llm', 'models', '模型请求 / 返回', [1, 0.5, 0, 0.5]);
edge('llm', 'route');
edge('route', 'clarify', '缺少条件', [0, 0.45, 1, 0.45]);
edge('clarify', 'context', '用户补充后恢复', [0, 0.5, 0, 0.45], [[30, 1285], [30, 765], [745, 765], [745, 697]], 'loop');
edge('route', 'tools', '请求 tool_calls');
edge('route', 'validate', '请求回答 / 比较结论', [1, 0.75, 1, 0.2], [[1625, 1323], [1625, 2202]]);
edge('tools', 'search', '查候选', [1, 0.15, 0, 0.5], [[1690, 1494], [1690, 1263]]);
edge('tools', 'rec', '排候选', [1, 0.4, 0, 0.5]);
edge('tools', 'rag', '查证据', [1, 0.65, 0, 0.5], [[1670, 1574], [1670, 1788]]);
edge('tools', 'facts', '核价 / 资格', [1, 0.85, 0, 0.5], [[1700, 1606], [1700, 2053]]);
edge('rag', 'models', 'Embedding / 可选重排', [1, 0.5, 1, 0.5], [[2520, 1788], [2520, 1003]]);
edge('tools', 'observe', '业务结果 / 结构化错误');
edge('observe', 'llm', '结果回填 → 下一轮（预算内）', [0, 0.5, 0, 0.5], [[785, 1830], [785, 1010]], 'loop');
edge('observe', 'memory', '检查点 / 引用写入', [0, 0.8, 1, 0.75], [[750, 1878], [750, 963]]);
edge('observe', 'progress', '真实执行事件', [0, 0.25, 1, 0.8]);
edge('validate', 'llm', '有限修正 / 必要补查', [0, 0.25, 0, 0.75], [[705, 2210], [705, 1135], [820, 1135], [820, 1050]], 'loop');
edge('validate', 'fallback', '失败且无法再修正', [0, 0.7, 1, 0.7], [], 'warning');
edge('validate', 'reply', '校验通过');
edge('fallback', 'reply', '安全降级结果', [0.5, 1, 0, 0.5], [[380, 2523]], 'warning');
edge('reply', 'facts', '最终卡片回源', [1, 0.5, 0.5, 1], [[1635, 2523], [1635, 2370], [2110, 2370]]);
edge('reply', 'persist');
edge('persist', 'memory', '会话续存 / 下轮读取', [0, 0.45, 0, 0.65], [[50, 2792], [50, 946]], 'loop');
edge('persist', 'evaluation', '授权事件 / 评测样本', [1, 0.5, 0, 0.5], [], 'async');
edge('evaluation', 'control', '评测 → 审核 → 下一版', [1, 0.5, 1, 0.5], [[2570, 2800], [2570, 253]], 'async');

const xml = `<mxfile host="app.diagrams.net" type="device"><diagram id="value-shopping-flow" name="03 高性价比选购 Agent 业务流程"><mxGraphModel grid="1" page="1" pageWidth="2640" pageHeight="3280"><root><mxCell id="0"/><mxCell id="1" parent="0"/>\n${edges.join('\n')}\n${nodes.join('\n')}\n</root></mxGraphModel></diagram></mxfile>\n`;
const sourcePath = 'docs/value-shopping-agent-walkthrough.md';
const marker = '![03 高性价比选购 Agent 业务流程](diagrams/value-shopping-agent-flow.png)';
const source = fs.readFileSync(sourcePath, 'utf8');
if (!source.includes(marker)) throw new Error('Diagram placeholder missing');
const artifacts = [
  ['docs/diagrams/value-shopping-agent-flow.drawio', xml],
  ['docs/value-shopping-agent-walkthrough.joyspace.md', source.replace(marker, `\`\`\`drawio\n${xml.trim()}\n\`\`\``)]
];
let patch = '*** Begin Patch\n';
for (const [path, content] of artifacts) {
  if (fs.existsSync(path)) {
    const previous = fs.readFileSync(path, 'utf8');
    if (previous === content) continue;
    patch += `*** Update File: ${path}\n@@\n${previous.trimEnd().split('\n').map(line => '-' + line).join('\n')}\n`;
  } else patch += `*** Add File: ${path}\n`;
  patch += content.trimEnd().split('\n').map(line => '+' + line).join('\n') + '\n';
}
process.stdout.write(patch + '*** End Patch\n');
