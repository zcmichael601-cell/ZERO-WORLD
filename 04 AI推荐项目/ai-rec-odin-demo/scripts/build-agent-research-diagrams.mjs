import fs from 'node:fs';

const palette = { blue: ['#EDF4FF', '#789CCD'], green: ['#EAF6F0', '#75AA92'], purple: ['#F2EDFC', '#A28BC8'], orange: ['#FFF4E7', '#CDAC79'], red: ['#FCEDEA', '#CB9991'], gray: ['#F5F8FA', '#AAB9C8'] };
const escape = value => String(value).replace(/[&<>"']/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;' })[character]);
function diagram(name, width, height) {
  const cells = [];
  const ids = new Set(['0', '1']);
  let sequence = 0;
  const box = (id, title, body, x, y, boxWidth, boxHeight, kind = 'blue') => {
    if (ids.has(id)) throw new Error(`Duplicate cell ${id}`);
    ids.add(id);
    const [fill, stroke] = palette[kind];
    const label = `<b style="font-size:24px">${escape(title)}</b><br><span style="font-size:19px">${escape(body).replaceAll('\n', '<br>')}</span>`;
    cells.push(`<mxCell id="${id}" value="${escape(label)}" style="rounded=1;arcSize=8;html=1;whiteSpace=wrap;fillColor=${fill};strokeColor=${stroke};fontColor=#19324D;fontFamily=PingFang SC;align=center;verticalAlign=middle;spacing=18;strokeWidth=1.5;" vertex="1" parent="1"><mxGeometry x="${x}" y="${y}" width="${boxWidth}" height="${boxHeight}" as="geometry"/></mxCell>`);
  };
  const edge = (source, target, title = '', pins = [0.5, 1, 0.5, 0], points = [], dashed = false) => {
    if (!ids.has(source) || !ids.has(target)) throw new Error(`Missing endpoint ${source}/${target}`);
    const route = points.length ? `<Array as="points">${points.map(([x, y]) => `<mxPoint x="${x}" y="${y}"/>`).join('')}</Array>` : '';
    cells.push(`<mxCell id="edge-${++sequence}" value="${escape(title)}" style="edgeStyle=orthogonalEdgeStyle;rounded=1;html=1;endArrow=block;strokeColor=#74889E;strokeWidth=2;fontFamily=PingFang SC;fontSize=17;fontColor=#314F6D;labelBackgroundColor=#FFFFFF;dashed=${dashed ? 1 : 0};exitX=${pins[0]};exitY=${pins[1]};entryX=${pins[2]};entryY=${pins[3]};" edge="1" parent="1" source="${source}" target="${target}"><mxGeometry relative="1" as="geometry">${route}</mxGeometry></mxCell>`);
  };
  return { box, edge, output: () => `<?xml version="1.0" encoding="UTF-8"?>\n<mxfile host="app.diagrams.net" type="device"><diagram id="${escape(name)}" name="${escape(name)}"><mxGraphModel grid="1" page="1" pageWidth="${width}" pageHeight="${height}"><root><mxCell id="0"/><mxCell id="1" parent="0"/>\n${cells.join('\n')}\n</root></mxGraphModel></diagram></mxfile>\n` };
}

function landscape() {
  const graph = diagram('行业Agent公开架构模式对比', 1900, 1700);
  const { box } = graph;
  box('title', '01 / 行业 Agent 架构模式对比', '基于官方公开资料重绘，不是厂商完整生产部署图｜调研日期 2026-10-08', 60, 30, 1780, 110, 'gray');
  const panels = [
    ['claude', 'Claude Code｜执行型 Harness', '用户任务 → 收集上下文 → 工具行动 → 验证 → 循环\nCLAUDE.md / Skills / MCP / Subagents / Hooks\n按需上下文、执行权限与文件检查点\n边界：文件回退不等于远端业务回滚〔S1–S3〕', 'green'],
    ['codex', 'Codex｜协议化运行与客户端分离', '客户端 ↔ App Server ↔ Agent 工作与执行环境\nThread / Turn / Item + 流式事件 + 审批\nAGENTS.md / Skills / MCP / Sandbox\n边界：借鉴协议；不把实验接口直接当 C 端后端〔S4〕', 'green'],
    ['doubao', '豆包助手 API｜托管黑盒能力', '业务请求 → Responses / 功能选择 → 托管执行 → 回复\nchat / deep_chat / ai_search / reasoning_search\n同源能力公开，App 内部调度和存储拓扑未公开\n当前 API 单功能且不混用自定义工具〔S5〕', 'purple'],
    ['ark', '方舟 + Coze｜开放能力与搭建平台', '方舟搜索：可与函数、MCP 组合的检索工具\nCoze：Prompt / Plugin / RAG / Workflow 资源与编排\n二者可以用于建设 Agent，但不是同一套架构\n更不能据此断言豆包 App 的内部实现〔S6、S7〕', 'purple'],
    ['research', 'Claude Research｜研究型多 Agent', 'LeadResearcher → 并行 Subagents → 汇总 → 引用处理\n研究计划 / Memory / 迭代搜索 / 恢复与评测\n适合高价值、可独立拆分的广度研究\n2025-06 公开快照，不代表全部当前实现〔S8〕', 'orange'],
    ['manus', 'Manus｜上下文与长任务工程', '模型行动循环 ↔ 工具 / 环境 ↔ 文件与计划\n稳定前缀、动作约束、大结果外存、保留失败反馈\n重点披露 Context Engineering，而非完整微服务\n2025-07 经验文，不据此猜测当前多 Agent 拓扑〔S9〕', 'orange'],
    ['pplx', 'Perplexity Agent API｜检索工具化', '请求 / Preset → 有界循环 → web_search / fetch_url\n发现网页与读取正文分离；支持过滤与上下文预算\n模型读检索结果并形成答案\nAPI 文档不能证明消费端内部索引/排序细节〔S10–S12〕', 'blue'],
    ['rufus', 'Amazon Rufus｜购物知识 + 实体交付', '购物问答 → RAG / 商城 API → 领域 LLM → 流式展示\n商品目录、评价、问答等多源依据\n展示时查询内部系统填充真实数据（hydration）\n2024-10 公开设计，不推断其当前下单实现〔S13〕', 'blue']
  ];
  panels.forEach(([id, title, body, kind], index) => box(id, title, body, 60 + (index % 2) * 910, 190 + Math.floor(index / 2) * 320, 870, 270, kind));
  box('lesson', '可迁移的共性：受控循环 + 有效上下文 + 可执行能力 + 证据 / 实体交付 + 评测恢复', '搜索、推荐、交易仍需各自的领域系统；并非所有请求都需要多 Agent、RAG 或长推理。', 60, 1505, 1780, 110, 'gray');
  return graph.output();
}

function architecture() {
  const graph = diagram('统一Harness下的AI搜索与AI推荐', 1900, 1670);
  const { box, edge } = graph;
  box('title', '02 / 建议架构：统一 Harness，复用搜索推荐数据面', '以下为本项目目标设计，不是任一厂商部署事实；实线为在线依赖，虚线为离线产物 / 反馈。', 60, 25, 1780, 110, 'gray');
  box('entry', '消费者入口｜搜索框 / 会话 / 商品页 / 推荐流', '登录态、页面实体、实时意图；可信商品卡、追问、反馈和确认页', 70, 190, 1260, 100);
  box('intent', '统一任务意图与上下文', '显式硬约束 + 会话目标 + 授权偏好 + 证据 / 候选引用\n按复杂度分流：快速检索、受控多步搜索、深度研究', 70, 350, 1260, 120);
  box('harness', 'Harness｜LLM + Prompt + Skill + Runtime', '按需披露、状态机、工具白名单、轮次/成本预算、取消恢复\n模型决定下一步；程序与业务域决定能否执行、何时算完成', 70, 530, 1260, 130, 'green');
  box('search', 'AI 搜索能力', 'Query Plan / 改写 / 约束编译\n商品多路检索、混合检索\n证据获取与不足时继续查', 70, 750, 370, 150, 'blue');
  box('rec', 'AI 推荐能力', '授权偏好 + 当前任务\n多路召回 / 排序 / 集合重排\n适配解释与可控探索', 515, 750, 370, 150, 'purple');
  box('rag', 'RAG 与外部知识', '政策/说明/评价证据\nACL、有效期、引用、撤回\n网页发现与正文读取分离', 960, 750, 370, 150, 'orange');
  box('data', '共享搜推与商品数据面', '倒排 / 向量 / 行为 / 图谱召回 + 排序模型 + Feature Store\n商品实体服务、实时价格库存、实验与曝光日志；最终硬约束过滤', 70, 995, 1260, 125, 'orange');
  box('reply', 'Reply｜证据与实体双重校验后交付', '文字解释引用 evidenceRef；商品卡按 candidateSetId + skuId 填充\n进度来自真实事件；“点击/喜欢/不喜欢”写入可追溯反馈', 70, 1200, 1260, 120);
  box('offline', '离线学习与知识管道', '数据同意/治理 → 文档/特征/标签 → 召回与排序训练 / LLM 蒸馏 → 评测 → 发布\n语义 ID 生成式召回仅作候选路线实验；不允许在线自动改生产策略', 70, 1400, 1260, 120, 'gray');
  box('policy', '控制平面', 'Prompt / Skill / Tool / Model\nIndex / Ranker 统一版本\n证据覆盖、线上实验、成本\n安全与发布门禁', 1430, 190, 380, 245, 'gray');
  box('tools', 'Tool Gateway', '所有业务 Tool 调用统一校验\n身份、schema、scope\n超时、重试、审计与契约\n禁止任意数据库/网络访问', 1430, 525, 380, 245, 'green');
  box('trade', '独立交易状态机', '选购建议 ≠ 交易授权\n报价 → 用户确认 → 幂等执行\n结果未知 → 查单/对账\n支付交给可信支付系统', 1430, 870, 380, 245, 'red');
  box('metrics', '双指标闭环', '检索相关性 / 推荐效用\n+ 事实一致性 / 安全\n+ 时延 / 成本 / 长期满意\n不只看对话轮次或 CTR', 1430, 1210, 380, 245, 'gray');
  edge('entry', 'intent'); edge('intent', 'harness');
  edge('harness', 'search', '搜索 Skill', [0.15, 1, 0.5, 0]);
  edge('harness', 'rec', '推荐 Skill');
  edge('harness', 'rag', '知识 / 研究 Skill', [0.85, 1, 0.5, 0]);
  edge('search', 'data', '检索', [0.5, 1, 0.15, 0]); edge('rec', 'data', '召回与排序');
  edge('rag', 'data', '实体关联', [0.5, 1, 0.85, 0]); edge('data', 'reply', '候选 + 实时事实');
  edge('reply', 'offline', '曝光与授权反馈', [0.5, 1, 0.5, 0], [], true);
  edge('offline', 'data', '受控发布', [0, 0.5, 0, 0.5], [[30, 1460], [30, 1057]], true);
  edge('tools', 'harness', '统一执行策略', [0, 0.5, 1, 0.5]);
  edge('reply', 'trade', '仅传已验证购买意图', [1, 0.35, 0, 0.85]);
  edge('policy', 'harness', '版本 / 预算', [0, 0.5, 1, 0.1], [[1370, 312], [1370, 543]], true);
  return graph.output();
}

function flow() {
  const graph = diagram('AI搜索和AI推荐的执行分工', 1900, 1610);
  const { box, edge } = graph;
  box('title', '03 / 执行分工：搜索解决“找什么”，推荐解决“对谁、此刻怎么选”', '共享任务状态、候选集合和证据；推荐流可以使用低延迟路径，不要求每次曝光都调用 LLM。', 60, 25, 1780, 110, 'gray');
  box('input', '用户：周末北京徒步，500 元以内，轻便防雨；不要我已买过的装备', '抽取显式约束；不明确的日期/用途先澄清；画像只补充，不覆盖本轮要求', 110, 185, 1680, 105);
  box('search', 'A1 搜索计划｜确定性查询为默认', '结构化字段 + 关键词/语义改写\n确需天气/新信息时调用相应 Tool', 110, 380, 740, 110, 'blue');
  box('profile', 'B1 推荐上下文｜用户授权与当前目标', '任务向量 + 短期行为 + 长期偏好 + 场景\n记录来源、时间、置信度与排除项', 1050, 380, 740, 110, 'purple');
  box('retrieve', 'A2 检索与证据获取', '倒排 + 向量 + 类目属性过滤；RAG 查说明/政策\n记录 candidateSetId / evidenceRef；不编造 SKU', 110, 580, 740, 120, 'blue');
  box('recall', 'B2 候选扩展与用户效用排序', '内容/协同/序列/热门等多路召回\n排除已购、不适配、不可售；排序系统负责效用估计', 1050, 580, 740, 120, 'purple');
  box('evidence', 'A3 证据是否足够？', '足够则输出；不足时有界改写/补查或澄清\n高价值复杂比较才启用受限研究子任务', 110, 790, 740, 115, 'blue');
  box('rerank', 'B3 集合级重排与约束复核', '多样性、互补性、曝光控制、预算与反感反馈\nLLM 仅对小候选集补充适配判断/解释，允许弃权', 1050, 790, 740, 115, 'purple');
  box('verify', '共享：权威实体与实时报价校验', '读取最终候选的 SKU / 规格 / 价格 / 库存；检查全部硬约束\n检索证据支持的理由与行为推测分别呈现；不把预测当作已知事实', 110, 1030, 1680, 120, 'orange');
  box('reply', '共享：流式答案 + 可信商品卡 + 下一步', '支持追问、改条件、换一批、查看来源和关闭个性化\n购买动作单独进入确认与交易状态机；曝光/点击/拒绝关联本次候选', 110, 1240, 1680, 120, 'green');
  box('end', '学习闭环：任务成功 / 长期满意 / 事实与安全 / 时延成本 → 分层实验 → 人工审批发布', '硬约束冲突不静默放宽；搜索零结果不等于没有需求；未曝光商品不能被当作用户不喜欢。', 110, 1450, 1680, 95, 'gray');
  edge('input', 'search', '显式意图', [0.22, 1, 0.5, 0]); edge('input', 'profile', '授权个性化', [0.78, 1, 0.5, 0]);
  edge('search', 'retrieve'); edge('retrieve', 'evidence'); edge('profile', 'recall'); edge('recall', 'rerank');
  edge('retrieve', 'recall', '复用候选 / 统一去重', [1, 0.5, 0, 0.5]);
  edge('evidence', 'search', '有限补查', [0, 0.5, 0, 0.5], [[65, 847], [65, 435]]);
  edge('evidence', 'verify', '证据', [0.5, 1, 0.22, 0]); edge('rerank', 'verify', '排序候选', [0.5, 1, 0.78, 0]);
  edge('verify', 'reply'); edge('reply', 'end', '埋点 / 反馈');
  return graph.output();
}

let patch = '*** Begin Patch\n';
for (const [path, content] of [
  ['docs/diagrams/agent-industry-landscape.drawio', landscape()],
  ['docs/diagrams/agent-search-recommendation-architecture.drawio', architecture()],
  ['docs/diagrams/agent-search-recommendation-flow.drawio', flow()]
]) {
  if (fs.existsSync(path)) {
    const previous = fs.readFileSync(path, 'utf8');
    if (previous === content) continue;
    patch += `*** Update File: ${path}\n@@\n${previous.trimEnd().split('\n').map(line => '-' + line).join('\n')}\n`;
  } else patch += `*** Add File: ${path}\n`;
  patch += content.trimEnd().split('\n').map(line => '+' + line).join('\n') + '\n';
}
process.stdout.write(patch + '*** End Patch\n');
