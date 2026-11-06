import fs from 'node:fs';

const palette = {
  ink: '#19324D', muted: '#52667D', line: '#7D8FA5',
  web: ['#EDF4FF', '#96B5E2'], prompt: ['#EAF2FF', '#729ED9'],
  skill: ['#ECF7F2', '#70B39A'], tool: ['#E7F7F4', '#55A999'],
  rag: ['#FFF5E3', '#D4A657'], llm: ['#F0EBFC', '#A68ACE'],
  data: ['#FFF8EB', '#CCAD78'], neutral: ['#F4F7FA', '#ADBCCA'],
  success: ['#EAF7ED', '#76B58A'], warning: ['#FFF1E8', '#D89A75']
};

const xml = value => String(value).replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;'
})[character]);
const html = value => xml(value).replaceAll('\n', '<br>');

function diagram(name, width, height) {
  const cells = [];
  const ids = new Set(['0', '1']);
  let edgeNumber = 0;
  const add = (id, value, x, y, boxWidth, boxHeight, style) => {
    if (ids.has(id)) throw new Error(`Duplicate id: ${id}`);
    ids.add(id);
    cells.push(`<mxCell id="${xml(id)}" value="${xml(value)}" style="${xml(style)}" vertex="1" parent="1"><mxGeometry x="${x}" y="${y}" width="${boxWidth}" height="${boxHeight}" as="geometry"/></mxCell>`);
  };
  const text = (id, value, x, y, boxWidth, boxHeight, size = 18, color = palette.ink, bold = false) => {
    add(id, html(value), x, y, boxWidth, boxHeight, `text;html=1;whiteSpace=wrap;align=left;verticalAlign=middle;spacing=0;fontFamily=PingFang SC;fontSize=${size};fontColor=${color};fontStyle=${bold ? 1 : 0};`);
  };
  const group = (id, title, x, y, boxWidth, boxHeight) => {
    add(id, '', x, y, boxWidth, boxHeight, 'rounded=1;arcSize=3;fillColor=#FAFCFE;strokeColor=#DFE7EF;strokeWidth=1.5;');
    text(`${id}-title`, title, x + 28, y + 16, boxWidth - 56, 36, 23, palette.ink, true);
  };
  const box = (id, title, body, x, y, boxWidth, boxHeight, kind = 'neutral', options = {}) => {
    const [fill, stroke] = palette[kind];
    const center = options.center ?? false;
    const label = `<div style="font-size:${options.titleSize ?? 21}px;line-height:1.2;font-weight:600;">${html(title)}</div>`
      + (body ? `<div style="font-size:${options.bodySize ?? 17}px;line-height:1.35;margin-top:8px;">${html(body)}</div>` : '');
    add(id, label, x, y, boxWidth, boxHeight,
      `rounded=1;arcSize=12;html=1;whiteSpace=wrap;fillColor=${fill};strokeColor=${stroke};strokeWidth=1.5;fontColor=${palette.ink};fontFamily=PingFang SC;align=${center ? 'center' : 'left'};verticalAlign=middle;spacing=14;`);
  };
  const decision = (id, value, x, y, boxWidth, boxHeight) => {
    add(id, html(value), x, y, boxWidth, boxHeight,
      `rhombus;html=1;whiteSpace=wrap;fillColor=#FFFFFF;strokeColor=${palette.line};strokeWidth=1.6;align=center;verticalAlign=middle;fontFamily=PingFang SC;fontSize=18;fontStyle=1;fontColor=${palette.ink};spacing=5;`);
  };
  const edge = (source, target, label = '', options = {}) => {
    if (!ids.has(source) || !ids.has(target)) throw new Error(`Missing edge endpoint: ${source} -> ${target}`);
    const pins = options.pins ?? [0.5, 1, 0.5, 0];
    const style = `edgeStyle=orthogonalEdgeStyle;rounded=1;orthogonalLoop=1;jettySize=auto;html=1;endArrow=block;endFill=1;`
      + `startArrow=${options.both ? 'block' : 'none'};startFill=1;strokeColor=${options.color ?? palette.line};strokeWidth=${options.dashed ? 1.6 : 2};dashed=${options.dashed ? 1 : 0};`
      + `fontFamily=PingFang SC;fontSize=15;fontColor=${palette.muted};labelBackgroundColor=#FFFFFF;labelBorderColor=none;`
      + `exitX=${pins[0]};exitY=${pins[1]};exitDx=0;exitDy=0;entryX=${pins[2]};entryY=${pins[3]};entryDx=0;entryDy=0;jumpStyle=arc;jumpSize=8;`;
    const points = options.points?.length ? `<Array as="points">${options.points.map(([x, y]) => `<mxPoint x="${x}" y="${y}"/>`).join('')}</Array>` : '';
    cells.push(`<mxCell id="edge-${++edgeNumber}" value="${xml(label)}" style="${xml(style)}" edge="1" parent="1" source="${source}" target="${target}"><mxGeometry relative="1" as="geometry">${points}</mxGeometry></mxCell>`);
  };
  add('background', '', 0, 0, width, height, 'fillColor=#FFFFFF;strokeColor=none;');
  return { text, group, box, decision, edge, output: () => `<?xml version="1.0" encoding="UTF-8"?>\n<mxfile host="app.diagrams.net" agent="Odin project diagrams" type="device"><diagram id="${xml(name)}" name="${xml(name)}"><mxGraphModel dx="${width}" dy="${height}" grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="1" pageScale="1" pageWidth="${width}" pageHeight="${height}" math="0" shadow="0"><root><mxCell id="0"/><mxCell id="1" parent="0"/>\n${cells.join('\n')}\n</root></mxGraphModel></diagram></mxfile>\n` };
}

function architecture() {
  const graph = diagram('01 项目架构', 2320, 1890);
  const { text, group, box, edge } = graph;
  text('title', '01  /  ODIN 项目架构', 60, 30, 1600, 55, 40, palette.ink, true);
  text('subtitle', '核心 AI 链路：入口 → 编排与上下文 → 模型 / 工具 / 知识库；按当前代码绘制，不把设计愿景当作已实现能力。', 60, 90, 2180, 35, 19, palette.muted);
  group('ui-group', '交互层  ·  ai-rec-odin-demo-main / static', 40, 145, 2240, 170);
  box('ui-chat', 'AI 对话  ·  index.html', '消息 + chatId；接收 delta / done / error', 80, 210, 600, 78, 'web');
  box('ui-skill', 'Skill 实验室  ·  skills.html', '选择技能与用户；过程提示 + 内容流 + 结果卡片', 855, 210, 610, 78, 'web');
  box('ui-rag', '知识库问答  ·  rag.html', '构建索引 / 提问；原文依据 + 流式回答 + 引用', 1630, 210, 600, 78, 'web');
  group('api-group', '接口层  ·  ai-rec-odin-demo-web', 40, 355, 2240, 155);
  box('api-chat', 'ChatController', 'POST /chat/stream；普通对话及扩展示例入口', 80, 415, 600, 72, 'web');
  box('api-skill', 'SkillController', '/api/skills/{id}/stream；另保留同步 /run', 855, 415, 610, 72, 'web');
  box('api-rag', 'RagController', '/api/rag/index · /search · /ask · /stream', 1630, 415, 600, 72, 'web');
  group('chat-group', '普通对话与既有扩展', 40, 565, 690, 790);
  group('skill-group', 'SkillEngine  ·  模型驱动的工具循环', 815, 565, 690, 790);
  group('rag-group', 'RagService  ·  独立检索增强问答', 1590, 565, 690, 790);
  box('chat-agent', 'ChatClientAgent  +  ChatClient', '调用默认 Prompt，追加用户消息\nMessageChatMemoryAdvisor 按 chatId 维护会话\n使用已有 JoyAI / Spring AI 自动配置模型', 80, 645, 610, 140, 'neutral');
  box('chat-prompt', 'PROMPT  ·  普通对话上下文', 'PromptService / 可配置模板\n默认提示 + UserMessage + 会话记忆', 80, 850, 610, 110, 'prompt');
  box('chat-extensions', '既有接口扩展  ·  不等于默认对话流程', '/chat/tools → 本地 @Tool\n/chat/mcp → ToolCallbackProvider\n/chat/vector → RetrievalAugmentationAdvisor → Vearch\n/agent/* → ReAct / 串行 / 并行 / 路由示例', 80, 1050, 610, 220, 'neutral', { bodySize: 17 });
  box('skill-engine', 'SkillEngine  ·  单次运行隔离', '先仅暴露 Skill 加载器，再开放配置中的业务工具\n校验允许工具、调用 ID、轮次及调用预算', 855, 645, 610, 110, 'skill');
  box('skill-prompt', 'PROMPT  ·  本轮模型上下文', 'SystemMessage 约束 + UserMessage 输入\nAssistant tool_calls + ToolResponseMessage 历史\n已加载的 SKILL.md 指令包含在工具返回中', 855, 810, 610, 130, 'prompt');
  box('tools', 'TOOL  ·  ToolCallingManager / ToolCallback', '模型决定工具名与参数，Java 回调真正执行\n画像 / 行为 / 商品 → 本地业务数据\n天气 → Open-Meteo；热门景点 → 携程公开榜单\n结果回填 ToolResponseMessage，交给下一轮模型', 855, 1020, 610, 190, 'tool');
  box('skill-config', 'SKILL  ·  本地配置 + 按需加载', 'skills/recommendation.json：启停、白名单、预算\n相邻 SKILL.md：任务步骤、输出要求、业务约束', 855, 1250, 610, 85, 'data', { titleSize: 19, bodySize: 15 });
  box('rag-kb', 'RagKnowledgeBase  ·  索引与检索', 'Document → TokenTextSplitter → 向量化 → 内存索引\nSimpleVectorStore + VectorStoreDocumentRetriever\n默认 Top-K = 4、阈值 = 0.5；每次问答读取一个快照', 1630, 645, 600, 140, 'rag', { bodySize: 16 });
  box('rag-prompt', 'PROMPT  ·  检索增强上下文', 'SystemMessage：仅基于资料、忽略资料中的指令\nUserMessage：question + 命中原文 sources（C1…）', 1630, 850, 600, 110, 'prompt');
  box('rag-validation', '结果与引用校验', '完整模型响应 → JSON 解析 → 引用 ID 与正文标记校验\n无命中不请求对话模型；模型也可判断资料不足', 1630, 1030, 600, 110, 'rag', { bodySize: 16 });
  box('knowledge-files', '本地知识资料  ·  knowledge/*.md', '商品手册与服务说明；支持配置外部本地目录\n独立内存索引，不写入既有 Vearch；重启后需重建', 1630, 1225, 600, 110, 'data', { bodySize: 16 });
  group('foundation', 'Spring AI 1.1 底座与现有模型服务', 40, 1400, 2240, 235);
  box('model-config', '现有服务端模型配置', 'profile/ai.properties：模型名 / 地址 / 路径 / Key\nSkillModelConfiguration + RagConfiguration\n复用配置，密钥不下发浏览器', 80, 1470, 600, 125, 'neutral', { bodySize: 16 });
  box('llm', 'LLM  ·  现有 GLM 对话模型', 'Skill / 新 RAG：OpenAiChatModel → ChatModel.stream()\n普通对话：ChatClient → 已配置 ChatModel\n接收 Prompt；返回正文或原生 tool_calls', 855, 1470, 610, 125, 'llm', { bodySize: 16 });
  box('embedding', 'EMBEDDING  ·  现有 bge-m3', 'OpenAiEmbeddingModel → 真实向量接口\n对文档片段及用户问题向量化\n用于检索，不负责生成自然语言答案', 1630, 1470, 600, 125, 'llm', { bodySize: 16 });
  box('boundaries', '关键边界', 'LLM 只提出工具调用，真正的动作由服务端 Tool 执行。新 RAG 是固定的“先检索再回答”，目前不是 Skill 自动调用的工具。\n流式展示的是公开正文，不是内部思维链；引用标识通过校验不等于每句事实都正确，仍应对照原文。', 60, 1670, 2200, 90, 'warning', { titleSize: 19, bodySize: 16 });
  text('startup', '启动范围：完整 AiRecOdinDemoApplication 包含既有 /chat、/agent 与 Vearch 相关能力；轻量 SkillDemoApplication 仅装配 Skill + 新 RAG（不含 ChatController）。\n图例：实线＝调用 / 数据依赖；双向箭头＝模型请求与返回；虚线＝配置或本地资料输入。本图聚焦 AI 核心，不展开订单与基础中间件。', 65, 1790, 2190, 75, 16, palette.muted);
  for (const suffix of ['chat', 'skill', 'rag']) edge(`ui-${suffix}`, `api-${suffix}`, '同源 HTTP / SSE', { both: true });
  edge('api-chat', 'chat-agent');
  edge('api-skill', 'skill-engine');
  edge('api-rag', 'rag-kb', '索引 / 检索 / 问答');
  edge('chat-agent', 'chat-prompt', '组装上下文');
  edge('skill-engine', 'skill-prompt', '每轮组装');
  edge('skill-engine', 'tools', '', { both: true, pins: [1, 0.5, 1, 0.5], points: [[1530, 700], [1530, 1115]], color: '#55A999' });
  edge('skill-config', 'tools', 'Skill 加载器读取', { dashed: true, pins: [0.5, 0, 0.5, 1], color: '#C1A16B' });
  edge('rag-kb', 'rag-prompt', '原文 + 来源 + 检索分数');
  edge('rag-prompt', 'rag-validation', '交给模型后的最终响应');
  edge('knowledge-files', 'rag-kb', '', { dashed: true, pins: [1, 0.5, 1, 0.8], points: [[2255, 1280], [2255, 757]], color: '#C1A16B' });
  edge('chat-prompt', 'llm', '', { both: true, pins: [1, 0.5, 0.4, 0], points: [[770, 905], [770, 1375], [1099, 1375]], color: '#A68ACE' });
  edge('skill-prompt', 'llm', '', { both: true, pins: [0, 0.5, 0, 0.5], points: [[790, 875], [790, 1532]], color: '#A68ACE' });
  edge('rag-prompt', 'llm', '', { both: true, pins: [0, 0.5, 1, 0.5], points: [[1555, 905], [1555, 1532]], color: '#A68ACE' });
  edge('rag-kb', 'embedding', '', { both: true, pins: [1, 0.3, 1, 0.5], points: [[2300, 687], [2300, 1532]], color: '#A68ACE' });
  edge('model-config', 'llm', '', { dashed: true, pins: [1, 0.25, 0, 0.25] });
  edge('model-config', 'embedding', '', { dashed: true, pins: [0.5, 1, 0.5, 1], points: [[380, 1620], [1930, 1620]] });
  return graph.output();
}

function execution() {
  const graph = diagram('02 执行流程', 2320, 2380);
  const { text, group, box, decision, edge } = graph;
  const step = (id, title, body, x, y, width, height, kind = 'neutral') => box(id, title, body, x, y, width, height, kind, { titleSize: 20, bodySize: 16 });
  text('title', '02  /  从用户输入到可验证结果', 60, 30, 2000, 55, 40, palette.ink, true);
  text('subtitle', '两条独立链路：左侧由模型选择工具，右侧按固定 RAG 流程先检索再生成。不是 Skill → RAG 的串行流水线。', 60, 95, 2190, 36, 19, palette.muted);
  group('skill-flow', 'A  ·  Skill 执行：模型 → Tool → 模型', 40, 165, 1120, 1910);
  group('rag-flow', 'B  ·  RAG：先建库，再检索增强问答', 1210, 165, 1070, 1910);
  step('s-start', '选择 Skill、用户及必要输入', 'skills.html 提交本次执行', 340, 255, 400, 85, 'web');
  step('s-request', 'POST /api/skills/{id}/stream', '校验技能是否启用、必填项与用户名', 340, 390, 400, 85, 'web');
  step('s-init', '初始化 Prompt 与可用工具', 'SystemMessage + UserMessage\n首次仅暴露 Skill 加载工具', 340, 525, 400, 110, 'prompt');
  step('s-llm', '调用现有 LLM 的 stream()', '提交本轮 Prompt + 可用工具定义', 340, 690, 400, 95, 'llm');
  step('s-preview', 'SSE content', '正文分片立即显示\n当前只是未校验预览', 850, 685, 270, 100, 'web');
  decision('s-has-tools', '完整响应包含\ntool_calls？', 440, 865, 200, 115);
  step('s-validate-call', '校验完整的工具调用', '工具白名单 / 参数 / 调用 ID\n模型轮次与工具调用次数预算', 340, 1050, 400, 110, 'skill');
  decision('s-is-loader', '调用的是\nSkill 加载器？', 440, 1230, 200, 105);
  step('s-load', '读取指定 SKILL.md', '返回完整技能指令\n开放配置中的业务工具', 150, 1400, 320, 120, 'data');
  step('s-tool', '真正执行业务 Tool', '查询画像 / 行为 / 商品\n或调用真实天气 / 景点接口\n发出工具开始、完成或失败事件', 605, 1390, 360, 140, 'tool');
  step('s-history', '工具结果写回对话历史', 'ToolCallingManager 执行回调\n追加 Assistant 与 ToolResponseMessage\n下一轮模型读取结果再决策', 340, 1610, 400, 135, 'prompt');
  step('s-validate-result', '校验最终结果', 'Skill 已加载、必需工具已完成\nJSON 必需字段与业务事实引用\n产品 / 行为 / 天气等约束', 850, 865, 270, 160, 'skill');
  step('s-result', 'SSE result', '返回完整 Run、output 与轨迹\n只有校验通过才报告成功', 850, 1135, 270, 110, 'success');
  step('s-page', '页面展示结构化结果', '进度记录、推荐 / 预测 / 出游卡片', 850, 1290, 270, 80, 'web');
  step('s-note', '配置决定边界，模型决定下一步', '本地 JSON 控制工具与预算；SKILL.md 描述任务和输出。\n支持好物推荐、兴趣洞察、购物行为预测、北京出游推荐。\n不会在每轮重复初始化历史，也不会执行不完整的参数分片。', 170, 1850, 880, 140, 'neutral');
  text('s-loop-label', '下一轮\n携带工具结果\n重新请求模型', 130, 920, 170, 105, 18, '#397C6E', true);
  text('r-index-caption', '① 独立触发建库：POST /api/rag/index（不是每次提问都重建）', 1245, 220, 990, 32, 18, palette.muted, true);
  step('r-md', '本地 Markdown', 'knowledge/*.md\n或配置的本地目录', 1245, 280, 215, 115, 'data');
  step('r-split', '读取与切分', 'Document\nTokenTextSplitter', 1510, 280, 215, 115, 'rag');
  step('r-embed', 'bge-m3', '真实向量化\n文档片段', 1775, 280, 200, 115, 'llm');
  step('r-store', '内存向量索引', 'SimpleVectorStore\n形成完整新快照', 2015, 280, 215, 115, 'rag');
  step('r-index-note', '索引生命周期', '构建成功后切换新快照；重建失败保留旧索引；服务重启后需重新构建。', 1245, 430, 985, 70, 'neutral');
  step('r-question', '② 用户提问', 'rag.html → POST /api/rag/stream', 1320, 550, 480, 85, 'web');
  decision('r-ready', '索引已就绪？', 1460, 700, 200, 100);
  step('r-not-ready', 'HTTP 409', '提示先构建索引\n本次不打开回答流', 1960, 700, 280, 95, 'warning');
  step('r-retrieve', '问题向量化 + 向量检索', 'bge-m3 → VectorStoreDocumentRetriever\nTop-K = 4、阈值 = 0.5（默认配置）\nSSE sources 返回片段及索引版本，可为空', 1320, 865, 480, 130, 'rag');
  text('r-snapshot-label', '当前完整索引快照', 1960, 855, 280, 34, 17, '#977437');
  decision('r-hits', '有命中片段？', 1460, 1055, 200, 105);
  step('r-no-hits', '知识库资料不足', '不请求对话模型\n返回 answerable=false', 1960, 1055, 280, 110, 'warning');
  step('r-prompt', '组装 RAG Prompt', '系统约束：仅基于资料、不执行资料中的指令\n用户消息：question + 命中原文 sources\n保留来源与引用标识 C1、C2…', 1320, 1220, 480, 130, 'prompt');
  step('r-llm', '现有 GLM 真实流式生成', 'ChatModel.stream(Prompt)\n不调用工具，不使用其他事实补全答案', 1320, 1410, 480, 105, 'llm');
  step('r-preview', 'SSE delta', '实时追加模型正文\n完整 JSON 前仅供预览', 1960, 1410, 280, 105, 'web');
  step('r-parse', '聚合完整响应并解析 JSON', '正常 stop 后才继续；检查字段及引用 ID', 1320, 1570, 480, 85, 'rag');
  decision('r-answerable', 'answerable\n为 true？', 1450, 1710, 220, 105);
  step('r-decline', '模型判断资料不足', '统一拒答提示，不给出引用\n不把一般查询指引当作确定答案', 1960, 1710, 280, 110, 'warning');
  step('r-citations', '校验回答与引用', '只能引用本次命中的 C1…\n正文引用标记必须与引用列表一致', 1320, 1870, 480, 100, 'rag');
  step('r-result', 'SSE result → 页面显示答案 / 资料不足', '引用可定位原文；显示耗时与是否调用了模型', 1320, 2005, 480, 60, 'success');
  step('chat-strip', '补充  ·  普通对话（需要完整应用入口）', 'POST /chat/stream  →  ChatClientAgent.flux()  →  默认 Prompt + 用户消息 + 会话记忆  →  ChatClient.stream()  →  SSE delta / done / error', 60, 2120, 2200, 90, 'web');
  step('failure-strip', '任意阶段失败时：停止本次流程，不自动重试', '开流前输入错误返回 HTTP 错误；模型 / 工具失败、超时、断流或校验失败，不把已收到的片段当作成功答案。\nSkill 保留执行轨迹；RAG 返回安全错误。页面不展示内部思维链。引用校验不替代事实核验。', 60, 2250, 2200, 100, 'warning');
  edge('s-start', 's-request');
  edge('s-request', 's-init', '校验通过');
  edge('s-init', 's-llm');
  edge('s-llm', 's-preview', '正文分片', { pins: [1, 0.5, 0, 0.5], color: '#729ED9' });
  edge('s-llm', 's-has-tools', '完整聚合后判定');
  edge('s-has-tools', 's-validate-call', '有');
  edge('s-has-tools', 's-validate-result', '无：最终文本', { pins: [1, 0.5, 0, 0.5] });
  edge('s-validate-call', 's-is-loader', '合法');
  edge('s-is-loader', 's-load', '是', { pins: [0.5, 1, 0.5, 0], points: [[540, 1365], [310, 1365]] });
  edge('s-is-loader', 's-tool', '否', { pins: [1, 0.5, 0.5, 0], points: [[785, 1282]] });
  edge('s-load', 's-history', '', { points: [[310, 1565], [540, 1565]] });
  edge('s-tool', 's-history', '', { points: [[785, 1565], [540, 1565]] });
  edge('s-history', 's-llm', '', { pins: [0, 0.5, 0, 0.5], points: [[95, 1677], [95, 737]], color: '#55A999' });
  edge('s-validate-result', 's-result', '校验通过');
  edge('s-result', 's-page');
  edge('r-md', 'r-split', '', { pins: [1, 0.5, 0, 0.5], color: '#C1A16B' });
  edge('r-split', 'r-embed', '', { pins: [1, 0.5, 0, 0.5], color: '#C1A16B' });
  edge('r-embed', 'r-store', '', { pins: [1, 0.5, 0, 0.5], color: '#C1A16B' });
  edge('r-store', 'r-retrieve', '', { pins: [1, 0.5, 1, 0.3], points: [[2300, 337], [2300, 904]], dashed: true, color: '#C1A16B' });
  edge('r-question', 'r-ready');
  edge('r-ready', 'r-not-ready', '否', { pins: [1, 0.5, 0, 0.5] });
  edge('r-ready', 'r-retrieve', '是');
  edge('r-retrieve', 'r-hits');
  edge('r-hits', 'r-no-hits', '否', { pins: [1, 0.5, 0, 0.5] });
  edge('r-hits', 'r-prompt', '是');
  edge('r-prompt', 'r-llm');
  edge('r-llm', 'r-preview', '正文分片', { pins: [1, 0.5, 0, 0.5], color: '#729ED9' });
  edge('r-llm', 'r-parse', '流完整结束');
  edge('r-parse', 'r-answerable');
  edge('r-answerable', 'r-decline', '否', { pins: [1, 0.5, 0, 0.5] });
  edge('r-answerable', 'r-citations', '是');
  edge('r-citations', 'r-result', '通过');
  edge('r-no-hits', 'r-result', '', { pins: [1, 0.5, 1, 0.5], points: [[2260, 1110], [2260, 2035]], color: '#D89A75' });
  edge('r-decline', 'r-result', '', { pins: [1, 0.5, 1, 0.5], points: [[2260, 1765], [2260, 2035]], color: '#D89A75' });
  return graph.output();
}

const outputs = new Map([
  ['docs/diagrams/project-architecture.drawio', architecture()],
  ['docs/diagrams/execution-flow.drawio', execution()]
]);
let patch = '*** Begin Patch\n';
for (const [path, content] of outputs) {
  const lines = content.trimEnd().split('\n');
  if (fs.existsSync(path)) {
    const previous = fs.readFileSync(path, 'utf8');
    if (previous === content) continue;
    patch += `*** Update File: ${path}\n@@\n${previous.trimEnd().split('\n').map(line => '-' + line).join('\n')}\n`;
  } else {
    patch += `*** Add File: ${path}\n`;
  }
  patch += lines.map(line => '+' + line).join('\n') + '\n';
}
process.stdout.write(patch + '*** End Patch\n');
