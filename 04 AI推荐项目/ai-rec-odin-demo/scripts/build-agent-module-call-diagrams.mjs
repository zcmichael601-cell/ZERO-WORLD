import fs from 'node:fs';

const palette = {
  app: ['#EDF4FF', '#648FC5'], runtime: ['#EAF6F0', '#64A587'],
  domain: ['#FFF4E7', '#CCA062'], foundation: ['#F1EDFC', '#9880C6'],
  control: ['#F3F5F8', '#94A3B8'], safety: ['#FCEFEB', '#C88178']
};
const escape = value => String(value).replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;'
})[character]);

function graph(name, width, height) {
  const nodes = [];
  const edges = [];
  const identifiers = new Set(['0', '1']);
  const node = (id, title, body, x, y, boxWidth, boxHeight, kind = 'app') => {
    if (identifiers.has(id)) throw new Error(`Duplicate identifier: ${id}`);
    identifiers.add(id);
    const [fill, stroke] = palette[kind];
    const label = `<b style="font-size:25px">${escape(title)}</b>`
      + (body ? `<br><span style="font-size:20px">${escape(body).replaceAll('\n', '<br>')}</span>` : '');
    nodes.push(`<mxCell id="${id}" value="${escape(label)}" style="rounded=1;arcSize=9;html=1;whiteSpace=wrap;fillColor=${fill};strokeColor=${stroke};fontColor=#19324D;fontFamily=PingFang SC;align=center;verticalAlign=middle;spacing=15;strokeWidth=2;" vertex="1" parent="1"><mxGeometry x="${x}" y="${y}" width="${boxWidth}" height="${boxHeight}" as="geometry"/></mxCell>`);
  };
  const text = (id, label, x, y, boxWidth, boxHeight = 50, size = 24) => {
    if (identifiers.has(id)) throw new Error(`Duplicate identifier: ${id}`);
    identifiers.add(id);
    nodes.push(`<mxCell id="${id}" value="${escape(label)}" style="text;html=1;whiteSpace=wrap;align=left;verticalAlign=middle;fontFamily=PingFang SC;fontSize=${size};fontColor=#243E5A;" vertex="1" parent="1"><mxGeometry x="${x}" y="${y}" width="${boxWidth}" height="${boxHeight}" as="geometry"/></mxCell>`);
  };
  const edge = (source, target, label = '', pins = [0.5, 1, 0.5, 0], points = [], kind = 'online') => {
    if (!identifiers.has(source) || !identifiers.has(target)) throw new Error(`Invalid edge: ${source} → ${target}`);
    const stroke = { online: '#53769A', direct: '#17845C', async: '#9580B7', config: '#8B98A9', write: '#BF726A' }[kind];
    const route = points.length ? `<Array as="points">${points.map(([x, y]) => `<mxPoint x="${x}" y="${y}"/>`).join('')}</Array>` : '';
    edges.push(`<mxCell id="edge-${edges.length + 1}" value="${escape(label)}" style="edgeStyle=orthogonalEdgeStyle;rounded=0;html=1;endArrow=block;endFill=1;strokeColor=${stroke};strokeWidth=${kind === 'direct' ? 3.5 : 2};fontFamily=PingFang SC;fontSize=19;fontColor=${stroke};labelBackgroundColor=#FFFFFF;dashed=${['async', 'config'].includes(kind) ? 1 : 0};exitX=${pins[0]};exitY=${pins[1]};entryX=${pins[2]};entryY=${pins[3]};" edge="1" parent="1" source="${source}" target="${target}"><mxGeometry relative="1" as="geometry">${route}</mxGeometry></mxCell>`);
  };
  return {
    node, text, edge,
    xml: () => `<mxfile host="app.diagrams.net" type="device"><diagram id="${escape(name)}" name="${escape(name)}"><mxGraphModel grid="1" page="1" pageWidth="${width}" pageHeight="${height}"><root><mxCell id="0"/><mxCell id="1" parent="0"/>\n${edges.join('\n')}\n${nodes.join('\n')}\n</root></mxGraphModel></diagram></mxfile>\n`
  };
}

function overview() {
  const diagram = graph('02A 完整模块调用关系', 2740, 2100);
  const { node, text, edge } = diagram;
  text('title', '02A / AI 搜索 × AI 推荐 · 完整模块调用关系（目标设计）', 70, 25, 2580, 65, 34);
  text('legend', '箭头：调用方 → 被调用方（响应原路返回）　蓝线：在线调用　绿粗线：App 直达 Domain　灰虚线：配置发布　红线：可信交易', 70, 95, 2600, 55, 23);
  node('client', '消费者客户端｜搜索框 / 推荐流 / 对话 / 可信确认页', '展示流式内容、证据与商品卡；提交明确反馈；不直连内部服务', 420, 175, 1630, 105);
  node('access', '公共接入 Gateway｜认证 / 风控 / 限流 / 路由', '服务端注入 userId、scopes、requestId；搜索与推荐流量独立配额', 420, 330, 1630, 100);
  node('search-app', 'ai-search-app', '业务 Workflow / 分流 / 实验 / Reply\n普通检索直达 Domain；复杂任务进入 Runtime', 420, 505, 680, 130);
  node('rec-app', 'ai-rec-app', '业务 Workflow / 分流 / 反馈 / Reply\n普通刷新直达 Domain；深度选购进入 Runtime', 1370, 505, 680, 130);
  node('search-runtime', 'search-runtime｜搜索运行池', '同一共建内核；独立版本、容量与发布\nAgent Loop / Context / Prompt / Skill\nTool 执行、预算、检查点、真实进度', 420, 730, 680, 165, 'runtime');
  node('rec-runtime', 'rec-runtime｜推荐运行池', '同一共建内核；独立版本、容量与发布\nAgent Loop / Context / Prompt / Skill\nTool 执行、预算、检查点、真实进度', 1370, 730, 680, 165, 'runtime');
  node('tool', 'Tool Gateway / Adapter｜两个 Runtime 各自装配同一机制，不表示新增共享网关实例', '工具目录与 schema / 身份与权限 / deadline / 调用幂等与审计\n内部 RPC 为主；按需适配 MCP；模型提出调用，服务端真正执行', 420, 1000, 1630, 125, 'runtime');
  node('search-domain', 'search-domain', 'Query 编译 / 商品多路召回\n过滤 / 相关性排序 / Hints\n返回 CandidateSet', 80, 1260, 455, 145, 'domain');
  node('rec-domain', 'rec-domain', '推荐召回 / 排序 / 集合重排\n用户效用、约束与多样性\n返回 RecommendationSlate', 595, 1260, 455, 145, 'domain');
  node('rag', 'RAG / knowledge-domain', '说明 / 政策 / 评价 / 问答\n检索、重排、ACL、时效与引用\n返回 EvidenceBundle，详见图 02B', 1110, 1260, 455, 145, 'domain');
  node('facts', '权威业务基础服务', '商品 / 价格 / 库存 / 可售性\n用户画像 / 行为 / 授权\n返回实体快照与授权特征', 1625, 1260, 455, 145, 'domain');
  node('external', '外部信息接入服务', '天气 / 地点 / 热榜 / 网页\n出口白名单、缓存与来源时间\n不得把用户画像发给任意站点', 2140, 1260, 455, 145, 'domain');
  node('model', '模型基础服务', '统一模型接入 / 路由 / 配额 / 熔断\nLLM · Embedding · Reranker\n召回 / 排序推理（可独立专用端点）', 80, 1580, 555, 160, 'foundation');
  node('memory', 'Memory / 状态基础服务', '会话与约束 / 候选引用 / 检查点\n经授权的长期偏好、TTL 与删除\nApp 读会话；Runtime 管理任务状态', 735, 1580, 555, 160, 'foundation');
  node('data', '共享数据与检索基础设施', '倒排 / 向量检索 · Feature Store\n证据原文 / 实体与候选存储\n领域服务使用 SDK / RPC 受控访问', 1390, 1580, 555, 160, 'foundation');
  node('trade', '独立交易域 / 交易状态机', '报价 → 可信确认 → 幂等执行\n订单 / 查单对账 / 支付系统\nAgent 只能提出意图，不能替用户授权', 2045, 1580, 555, 160, 'safety');
  node('control', 'agent-control｜控制面', 'Prompt / Skill / Tool / Model\nACL / 预算 / 版本 / 实验\n审批、发布、回滚\n制品与配置快照异步分发', 2210, 505, 410, 185, 'control');
  node('ops', '横向治理与运行支撑', 'IAM / 密钥 / 服务发现\nTrace / 指标 / 审计 / 告警\n各调用点埋点与配额隔离\n异步反馈 / 评测 / 发布门禁', 2210, 760, 410, 200, 'control');
  text('scope', '基础依赖说明：蓝色 M / S 边连接 Runtime 与模型、Memory；App → Memory、领域 → 模型及 RAG 内部依赖在图 02B 展开。', 80, 1810, 2520, 55, 23);
  node('rules', '边界与部署口径', '一个 run 只有一个主循环，两个 Runtime 池不互调；搜索与推荐 Domain 不双向嵌套编排。\nApp 的确定性 Workflow 可直接组合 Domain；Agent 模式经 Tool Adapter 调用同一 Domain，均执行身份、配额与业务校验。\nRAG、Memory、模型是显式逻辑能力，不等于必须新增独立微服务；原“7 个部署单元”只统计核心应用，不含复用基础平台。', 80, 1910, 2520, 155, 'control');
  edge('client', 'access');
  edge('access', 'search-app', '搜索入口', [0.2, 1, 0.5, 0]);
  edge('access', 'rec-app', '推荐入口', [0.8, 1, 0.5, 0]);
  edge('search-app', 'search-runtime', '复杂任务 / SSE', [0.5, 1, 0.5, 0]);
  edge('rec-app', 'rec-runtime', '复杂任务 / SSE', [0.5, 1, 0.5, 0]);
  edge('search-app', 'search-domain', '普通搜索：直接 RPC', [0, 0.35, 0, 0.4], [[35, 550], [35, 1318]], 'direct');
  edge('rec-app', 'rec-domain', '普通推荐：直接 RPC', [0, 0.75, 0.5, 0], [[1240, 603], [1240, 675], [340, 675], [340, 1215], [823, 1215]], 'direct');
  edge('search-runtime', 'tool', '受控工具调用', [0.65, 1, 0.27, 0]);
  edge('rec-runtime', 'tool', '受控工具调用', [0.35, 1, 0.73, 0]);
  for (const [target, position] of [['search-domain', 0.03], ['rec-domain', 0.24], ['rag', 0.55], ['facts', 0.83], ['external', 0.98]]) {
    edge('tool', target, '', [position, 1, 0.5, 0]);
  }
  edge('search-runtime', 'model', 'M：LLM 调用', [0, 0.65, 0.18, 0], [[60, 837], [60, 1495], [180, 1495]]);
  edge('rec-runtime', 'model', 'M：LLM 调用', [0, 0.75, 0.4, 0], [[1195, 854], [1195, 947], [65, 947], [65, 1515], [302, 1515]]);
  edge('rec-runtime', 'memory', 'S：状态读写', [1, 0.65, 0.8, 0], [[2110, 837], [2110, 1490], [1179, 1490]]);
  edge('search-runtime', 'memory', 'S：状态读写', [1, 0.5, 0.25, 0], [[1150, 812], [1150, 965], [2085, 965], [2085, 1470], [874, 1470]]);
  edge('search-domain', 'data', '商品索引', [0.65, 1, 0.15, 0], [[376, 1440], [1473, 1440]]);
  edge('rec-domain', 'data', '召回 / 特征', [0.75, 1, 0.4, 0], [[936, 1460], [1612, 1460]]);
  edge('rag', 'data', '知识索引 / 原文', [0.75, 1, 0.7, 0], [[1451, 1440], [1779, 1440]]);
  edge('tool', 'trade', '仅准备购买意图', [1, 0.75, 0, 0.5], [[2660, 1094], [2660, 1515], [1995, 1515], [1995, 1660]], 'write');
  edge('access', 'trade', '可信 App 确认 / 执行接口', [1, 0.5, 1, 0.65], [[2700, 380], [2700, 1684]], 'write');
  edge('control', 'search-runtime', '签名场景包 / 配置快照', [0, 0.25, 0.85, 0], [[2140, 551], [2140, 700], [998, 700]], 'config');
  edge('control', 'rec-runtime', '独立灰度', [0, 0.7, 1, 0.2], [[2160, 635], [2160, 763]], 'config');
  return diagram.xml();
}

function foundations() {
  const diagram = graph('02B RAG Memory 模型与数据依赖', 2600, 2260);
  const { node, text, edge } = diagram;
  text('title', '02B / 基础服务依赖展开 · RAG ≠ Memory ≠ 向量数据库', 65, 25, 2450, 65, 34);
  text('legend', '蓝线：在线 / 作业内同步调用　紫虚线：异步摄取、事件或发布　箭头均从调用方指向被调用方；分区不是强制部署边界。', 65, 100, 2450, 55, 23);
  node('rag-callers', 'RAG 调用方', 'Runtime → retrieveKnowledge Tool\n或 search-domain 的知识接口单向委托', 80, 210, 660, 115, 'runtime');
  node('memory-callers', 'Memory / 状态调用方', 'App：会话读取与管理 API\nRuntime：上下文、候选引用、检查点与恢复', 920, 210, 660, 115, 'runtime');
  node('model-callers', '其他模型调用方', 'Runtime：LLM 生成 / 原生 tool_calls\nsearch / rec Domain：编码与专业排序推理', 1760, 210, 660, 115, 'runtime');
  node('rag-service', 'RAG 检索服务 / 模块', '查询改写 → 混合检索 → 重排 → 证据包\nSKU / ACL / 地域 / 生效时间过滤\n不负责 Agent 主循环；默认不额外生成答案', 80, 445, 660, 155, 'domain');
  node('memory-service', 'Memory / 状态服务', '账户 + 场景隔离；授权范围、TTL、删除\n会话 Memory、长期偏好分开管理\n检查点为持久任务状态，不是模型记忆', 920, 445, 660, 155, 'foundation');
  node('model-gateway', '模型接入 / 推理平台', '统一身份、模型版本、配额、超时、熔断\nLLM / Embedding / Reranker 接口\n专业排序 Serving 可保留独立端点', 1760, 445, 660, 155, 'foundation');
  node('knowledge-index', '知识检索与索引服务', '倒排 + 向量 + metadata / ACL 过滤\n索引版本、Embedding 版本与维度绑定\n商品知识索引 ≠ 私人记忆索引', 80, 825, 660, 150, 'foundation');
  node('session-store', '会话 / 任务持久存储', '会话消息、约束、候选集引用、run 检查点\n数据库 / 对象存储为持久依据\nRedis 作缓存；内存状态不能替代持久恢复', 920, 825, 660, 150, 'foundation');
  node('llm-serving', 'LLM Serving', '对话生成 / 结构化输出 / tool_calls\n可受控生成摘要；不直接执行业务 Tool', 1760, 780, 660, 125, 'foundation');
  node('embedding-serving', 'Embedding Serving', '文档 / 查询 / 授权记忆文本 → 向量\n当前 Demo 示例：远程 bge-m3', 1760, 1010, 660, 125, 'foundation');
  node('ranking-serving', 'Reranker / Ranker Serving', '知识相关性重排；商品召回 / 排序模型\n两类模型契约不同，不等于 LLM 生成文案', 1760, 1240, 660, 125, 'foundation');
  node('evidence-store', '证据原文 / 版本与元数据', '官方说明、政策、评价 / 问答与原文引用\n来源类型、版本、撤回、有效期、实体关联\n证据片段回给 Runtime，由 LLM 形成解释', 80, 1110, 660, 150, 'foundation');
  node('preference-store', '授权偏好 / 记忆存储', '来源、时间、用户纠正与删除记录\n结构化偏好为主；语义检索可选私有向量索引\n缓存 / 向量索引 / 原文联动失效', 920, 1110, 660, 150, 'foundation');
  node('ingestion', '知识摄取 / 索引构建 Worker', '解析、切分、脱敏、ACL、去重与质量检查\n调用 Embedding → 建库 / 增量更新\n评测后发布版本；撤回触发索引与缓存失效', 80, 1460, 660, 155, 'domain');
  node('profile', '画像 / 行为 / Feature Store', '权威行为事实、授权特征与时效\n推荐 Domain 直接读取；Memory 按授权引用\n长期偏好不是全量画像副本，推断不当作事实', 920, 1460, 660, 155, 'domain');
  node('realtime', '实时商品 / 价格 / 库存服务', 'App Reply / Tool / Domain 按需批量回源\n实体卡片、可售性与报价以业务系统为准\nRAG 与 Memory 都不能代替实时事实', 1760, 1460, 660, 155, 'safety');
  node('fact-callers', '事实调用方｜App Reply / Tool / Domain', '同图 02A；按 skuId 获取权威快照', 1760, 1660, 660, 80, 'app');
  node('sources', '来源系统 / 业务事件', '商品内容、政策、评价 / 问答更新与撤回\n授权曝光 / 点击 / 拒绝 / 购买事件', 80, 1780, 660, 130, 'control');
  node('events', '事件平台 / 数据加工 / 评测', 'Outbox / 消息队列 → 去重、治理、特征更新\n反馈归因、索引与模型评测、实验分析\nTelemetry 异步采集；关键审计可靠落盘', 920, 1780, 660, 130, 'control');
  node('control', 'agent-control + 配置 / 制品平台', '审核 Prompt、Skill、Tool、模型与索引版本\n签名快照异步发布；消费者本地应用\n权限撤销仍需实时校验或短 TTL 失效', 1760, 1780, 660, 130, 'control');
  node('boundary', '调用与数据边界', 'RAG 给外部知识证据，Memory 给本用户上下文，模型服务提供推理，向量库只负责一种检索存储。\n搜索 / 推荐 / RAG / Memory 的资源命名空间、ACL 与配额独立；向量相似不等于事实正确。\n离线建库与在线检索分开：生产不在用户首个请求中全量构建索引；本地内存向量库仅适合 Demo 或小规模单实例。', 80, 2040, 2340, 160, 'control');
  edge('rag-callers', 'rag-service', 'retrieveKnowledge');
  edge('memory-callers', 'memory-service', '按 scope 读写');
  edge('model-callers', 'model-gateway', '模型调用');
  edge('rag-service', 'knowledge-index', '查询 / 过滤 / 检索', [0.4, 1, 0.4, 0]);
  edge('rag-service', 'evidence-store', '原文校验 / 证据读取', [0, 0.65, 0, 0.5], [[30, 546], [30, 1185]]);
  edge('rag-service', 'model-gateway', '查询向量 / 按需改写与重排', [1, 0.85, 0, 0.85], [[805, 577], [805, 680], [1690, 680], [1690, 577]]);
  edge('memory-service', 'session-store', '会话与检查点读写', [0.35, 1, 0.35, 0]);
  edge('memory-service', 'preference-store', '偏好读写 / 可选检索', [1, 0.7, 1, 0.5], [[1640, 554], [1640, 1185]]);
  edge('memory-service', 'model-gateway', '可选摘要 / 记忆向量化', [1, 0.3, 0, 0.3]);
  edge('memory-service', 'profile', '仅授权字段 / 引用', [0, 0.55, 0, 0.5], [[860, 530], [860, 1538]]);
  edge('model-gateway', 'llm-serving', '生成', [0.25, 1, 0.25, 0]);
  edge('model-gateway', 'embedding-serving', '编码', [1, 0.35, 1, 0.5], [[2480, 499], [2480, 1073]]);
  edge('model-gateway', 'ranking-serving', '打分', [1, 0.7, 1, 0.5], [[2530, 554], [2530, 1303]]);
  edge('fact-callers', 'realtime', '', [0.5, 0, 0.5, 1]);
  edge('ingestion', 'knowledge-index', '构建 / 增量写入', [0.15, 0, 0.15, 1], [[179, 1375], [50, 1375], [50, 1020], [179, 1020]]);
  edge('ingestion', 'evidence-store', '原文与版本写入', [0.65, 0, 0.65, 1]);
  edge('ingestion', 'model-gateway', '文档 Embedding', [1, 0.25, 0.1, 1], [[780, 1499], [780, 725], [1826, 725]]);
  edge('sources', 'ingestion', '变更 / 撤回事件', [0.5, 0, 0.5, 1], [], 'async');
  edge('sources', 'events', '授权行为事件', [1, 0.5, 0, 0.5], [], 'async');
  edge('events', 'profile', '特征加工 / 发布', [0.5, 0, 0.5, 1], [], 'async');
  edge('events', 'control', '评测报告 / 候选版本', [1, 0.5, 0, 0.5], [], 'async');
  edge('control', 'ingestion', '经审批的构建与发布策略', [0.5, 1, 0.5, 1], [[2090, 1970], [410, 1970]], 'config');
  return diagram.xml();
}

const artifacts = [
  ['docs/diagrams/agent-module-calls.drawio', overview()],
  ['docs/diagrams/agent-foundation-calls.drawio', foundations()]
];
const sourcePath = 'docs/agent-module-call-relationships.md';
if (fs.existsSync(sourcePath)) {
  let content = fs.readFileSync(sourcePath, 'utf8');
  for (const [path, xml] of artifacts) {
    const imagePath = path.replace('docs/', '').replace('.drawio', '.png');
    const imageLine = content.split('\n').find(line => line.startsWith('![') && line.endsWith(`(${imagePath})`));
    if (!imageLine) throw new Error(`Missing diagram marker: ${imagePath}`);
    content = content.replace(imageLine, `\`\`\`drawio\n${xml.trim()}\n\`\`\``);
  }
  artifacts.push(['docs/agent-module-call-relationships.joyspace.md', content]);
}
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
