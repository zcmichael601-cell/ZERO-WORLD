import fs from 'node:fs';

const palette = {
  app: ['#EDF4FF', '#648FC5'], runtime: ['#EAF6F0', '#64A587'],
  domain: ['#FFF4E7', '#CCA062'], model: ['#F1EDFC', '#9880C6'],
  control: ['#F3F5F8', '#94A3B8'], external: ['#FFF0F0', '#C88178']
};
const escape = value => String(value).replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;'
})[character]);

function graph(name, width, height) {
  const nodes = [];
  const edges = [];
  const ids = new Set(['0', '1']);
  function node(id, title, body, x, y, boxWidth, boxHeight, kind = 'runtime') {
    if (ids.has(id)) throw new Error(`Duplicate node: ${id}`);
    ids.add(id);
    const [fill, stroke] = palette[kind];
    const label = `<b style="font-size:27px">${escape(title)}</b>`
      + (body ? `<br><span style="font-size:21px">${escape(body).replaceAll('\n', '<br>')}</span>` : '');
    nodes.push(`<mxCell id="${id}" value="${escape(label)}" style="rounded=1;arcSize=9;html=1;whiteSpace=wrap;fillColor=${fill};strokeColor=${stroke};fontColor=#19324D;fontFamily=PingFang SC;align=center;verticalAlign=middle;spacing=15;strokeWidth=2;" vertex="1" parent="1"><mxGeometry x="${x}" y="${y}" width="${boxWidth}" height="${boxHeight}" as="geometry"/></mxCell>`);
  }
  function text(id, label, x, y, boxWidth, boxHeight = 60, size = 25) {
    if (ids.has(id)) throw new Error(`Duplicate node: ${id}`);
    ids.add(id);
    nodes.push(`<mxCell id="${id}" value="${escape(label)}" style="text;html=1;whiteSpace=wrap;align=left;verticalAlign=middle;fontFamily=PingFang SC;fontSize=${size};fontColor=#243E5A;" vertex="1" parent="1"><mxGeometry x="${x}" y="${y}" width="${boxWidth}" height="${boxHeight}" as="geometry"/></mxCell>`);
  }
  function edge(source, target, label = '', pins = [0.5, 1, 0.5, 0], points = [], kind = 'online') {
    if (!ids.has(source) || !ids.has(target)) throw new Error(`Invalid endpoints: ${source} → ${target}`);
    const stroke = { online: '#53769A', direct: '#17845C', async: '#8B98A9', delegate: '#9063B0', external: '#BF726A' }[kind];
    if (!stroke) throw new Error(`Unknown edge kind: ${kind}`);
    const route = points.length ? `<Array as="points">${points.map(([x, y]) => `<mxPoint x="${x}" y="${y}"/>`).join('')}</Array>` : '';
    edges.push(`<mxCell id="edge-${edges.length + 1}" value="${escape(label)}" style="edgeStyle=orthogonalEdgeStyle;rounded=0;html=1;endArrow=block;endFill=1;strokeColor=${stroke};strokeWidth=${['direct', 'delegate'].includes(kind) ? 3 : 2};fontFamily=PingFang SC;fontSize=20;fontColor=${stroke};labelBackgroundColor=#FFFFFF;dashed=${kind === 'async' ? 1 : 0};exitX=${pins[0]};exitY=${pins[1]};entryX=${pins[2]};entryY=${pins[3]};" edge="1" parent="1" source="${source}" target="${target}"><mxGeometry relative="1" as="geometry">${route}</mxGeometry></mxCell>`);
  }
  return {
    node, text, edge,
    xml: () => `<mxfile host="app.diagrams.net" type="device"><diagram id="${escape(name)}" name="${escape(name)}"><mxGraphModel grid="1" page="1" pageWidth="${width}" pageHeight="${height}"><root><mxCell id="0"/><mxCell id="1" parent="0"/>\n${edges.join('\n')}\n${nodes.join('\n')}\n</root></mxGraphModel></diagram></mxfile>\n`
  };
}

function architecture() {
  const diagram = graph('01 六层架构与受控多 Agent', 3220, 2420);
  const { node, text, edge } = diagram;
  text('title', '01 / 六层逻辑架构 · 单 Agent 为默认，内部子 Agent 与外部 Agent 按需启用', 70, 20, 3000, 75, 35);
  text('legend', '建议目标态｜蓝：调用依赖　绿：App 直达 Domain　紫：受控委派　红：跨执行 / 信任边界　灰虚线：配置发布｜六层不等于六个微服务', 70, 95, 3040, 65, 23);
  node('l1', 'L1 交互层｜AI 搜应用 / AI 推应用', '产品交互、实验、真实进度、澄清与结果；唯一可信确认页 / 支付入口', 90, 185, 2110, 110, 'app');
  node('l2', 'L2 业务层｜双方独立维护场景 Workflow', '需求解析 / 条件澄清 / 场景路由 / 交付策略\n确定性请求直接调用 Domain；复杂任务进入对应 Runtime；Agent 只是业务流程中的一环', 90, 380, 2110, 140, 'app');
  text('l3label', 'L3 Agent 层｜短期独立迭代、长期共建机制；两套根任务池独立发布，按契约复用能力', 90, 575, 2110, 65);
  node('root', '主 Agent / 根 Runtime', 'search-runtime 或 rec-runtime\nrootRun 唯一 Owner、唯一最终交付者\nContext / Prompt / Skill / LLM Loop', 90, 700, 660, 160);
  node('delegation', 'Delegation Manager（可选）', '本池执行模块，不是另一个模型\n受控任务拆分、子预算与深度\n分发 / 等待 / 取消 / 合并产物', 875, 700, 610, 160);
  node('child', '内部子 Agent / Worker（可选）', 'childRun：局部循环与专用 Skill\n证据研究 / 品类选购 / 适配研究\n独立上下文与检查点，不改主 Memory', 1610, 700, 590, 160);
  node('tool', 'Tool Gateway / Adapter｜主与子 Agent 复用执行机制', '工具 schema、身份、scope、预算、deadline、审计\nRPC / HTTP 为主；MCP 按需适配；普通 API 不包装成 Agent', 90, 1040, 1395, 135);
  node('gateway', 'Agent Gateway（可选）', 'Agent Registry / Card、版本与鉴权\n数据最小化、remoteTask 映射\nRPC / HTTPS task API / 按需 A2A', 1610, 1040, 590, 145, 'model');
  node('external', '外部 Agent｜跨运行 / 权限边界', '其他部门 / 独立平台 / 合作方\n如旅行规划、品牌专业适配研究\n独立模型 / Memory / 工具 / 发布 / SLA\n不直接读写我方全量 Memory 或交易', 2420, 1010, 650, 205, 'external');
  text('l4label', 'L4 领域能力｜专业算法与粗粒度业务契约，普通 Tool 和子 Agent 均复用', 90, 1270, 2110, 55);
  node('search', 'search-domain / Hints', '商品检索、条件编译、过滤与相关性\nrec-domain：召回、排序与集合策略', 90, 1390, 660, 135, 'domain');
  node('rag', 'RAG / 知识服务', '说明、政策、评价 / 测评证据\nACL、SKU、时效、反证与原文引用', 815, 1390, 665, 135, 'domain');
  node('business', '业务事实 / 交易 / 外部信息', '商品、价格、库存、画像、行为、天气\n交易单独报价、确认、幂等与查单', 1545, 1390, 655, 135, 'domain');
  text('l5label', 'L5 数据与知识｜数据资产按来源、账户、租户与生命周期隔离', 90, 1590, 2110, 55);
  node('knowledge', '文档与索引 / 商品和用户数据', '知识原文、倒排 / 向量索引、Feature Store\n权威业务数据与经授权事件，不用模型记忆替代', 90, 1710, 1020, 125, 'model');
  node('state', '会话与任务数据 / Memory 内容', '会话、候选引用、长期偏好分开管理\nrootRun / childRun 分区，主任务验证后合并产物', 1180, 1710, 1020, 125, 'model');
  text('l6label', 'L6 AI 基础能力｜复用现有平台；外部 Agent 的内部底座由对方维护', 90, 1890, 2110, 55);
  node('model', '模型接入与推理平台', '主 / 子 Runtime → LLM；RAG → Embedding / Reranker\n版本、身份、配额、路由、熔断与根任务成本归集', 90, 2000, 1020, 145, 'model');
  node('memory', 'AI Memory / 状态基础服务', '授权会话读取、隔离检查点、受控产物存取\n删除 / TTL / 权限撤销；运行恢复不等于交易回滚', 1180, 2000, 1020, 145, 'model');
  node('control', '横向治理｜agentControl', 'Skill / Prompt / Tool / Agent / Model 版本\n可委派矩阵、能力目录、Owner、SLA\n预算、并发、深度、数据出口与权限\n评测、实验、灰度、回滚、Trace / 审计\n配置预发布；不逐请求充当 Planner', 2420, 185, 650, 275, 'control');
  node('gate', '启用多 Agent 的门槛', '子问题独立、有明确验收产物\n本身需要多步探索，而非一次 API\n专业上下文隔离或已有外部能力\n经评测证明收益覆盖成本与协调\n不满足 → 单 Agent + 并行 Tool', 2420, 590, 650, 265, 'control');
  node('boundary', '边界与职责', '不是两个根 Runtime 互相启动根任务\n一个 rootRun Owner + 有界 childRun\n首期只读、一层委派、最小权限\n外部完成状态 ≠ 业务事实已核实\n不让子 / 外部 Agent 直接对用户下单', 2420, 1390, 650, 265, 'external');
  node('teams', '分工与复用', 'App 团队：总任务与用户体验\n平台团队：委派、网关、执行与恢复\n专业团队：Skill、工具与产物质量\n外部部门：远程任务契约 / 值班 / SLA\n安全与 SRE：隔离、故障与成本门禁', 2420, 1830, 650, 265, 'control');
  node('footer', '理解这张图', '多 Agent 是 L3 的受控执行扩展，不替代 L4 领域服务或 L5 / L6 底座；工具并行不等于 Agent 并行。\n主 / 子 Agent 各自的 LLM、Tool、Memory 依赖详见图 02A；RAG 内部依赖详见 02B。数据和模型线未逐一展开，不表示 Agent 可以绕过底座治理。', 90, 2260, 2980, 130, 'control');
  edge('l1', 'l2');
  edge('l2', 'root', 'Agent 路径', [0.16, 1, 0.5, 0]);
  edge('l2', 'search', '确定性短链路：App → Domain', [0, 0.5, 0, 0.5], [[35, 450], [35, 1458]], 'direct');
  edge('root', 'delegation', '按需委派', [1, 0.3, 0, 0.3], [], 'delegate');
  edge('delegation', 'root', '验收后的产物', [0, 0.8, 1, 0.8], [], 'delegate');
  edge('delegation', 'child', '受限 childRun', [1, 0.25, 0, 0.25], [], 'delegate');
  edge('child', 'delegation', '产物 / 引用 / 状态', [0, 0.8, 1, 0.8], [], 'delegate');
  edge('root', 'tool', '常规工具调用', [0.4, 1, 0.15, 0]);
  edge('child', 'tool', '子任务也走相同治理', [0.35, 1, 0.85, 0], [[1817, 950], [1276, 950]]);
  edge('delegation', 'gateway', '受控跨边界委派', [0.7, 1, 0.15, 0], [[1302, 915], [1699, 915]], 'delegate');
  edge('gateway', 'external', '请求 / 查询 / 取消；产物原路返回', [1, 0.5, 0, 0.5], [], 'external');
  edge('tool', 'search', '', [0.2, 1, 0.5, 0]);
  edge('tool', 'rag', '', [0.7, 1, 0.5, 0]);
  edge('tool', 'business', '', [0.95, 1, 0.5, 0], [[1415, 1235], [1873, 1235]]);
  edge('search', 'knowledge', '检索 / 特征', [0.5, 1, 0.25, 0]);
  edge('rag', 'knowledge', '原文 / 索引', [0.3, 1, 0.85, 0]);
  edge('rag', 'model', '查询编码 / 可选重排', [0.8, 1, 0.8, 0], [[1347, 1555], [1145, 1555], [1145, 1950], [906, 1950]]);
  edge('memory', 'state', '状态读写 API → 持久数据', [0.5, 0, 0.5, 1]);
  edge('control', 'delegation', '预发布目录与委派策略', [0, 0.5, 0.75, 0], [[2330, 323], [2330, 660], [1333, 660]], 'async');
  return diagram.xml();
}

function calls() {
  const diagram = graph('02A 完整模块调用与多 Agent 委派', 3260, 2550);
  const { node, text, edge } = diagram;
  text('title', '02A / 模块调用关系 · 根任务唯一 Owner，子任务与外部委派按需扩展', 70, 20, 3090, 70, 35);
  text('legend', '蓝：在线调用（返回原路）　绿：App 直达 Domain　紫：委派 / 产物回填　红：跨边界调用　灰虚线：配置与治理｜不是每请求都走完全部分支', 70, 95, 3090, 65, 23);
  node('access', '用户 / 客户端 → 公共网关', '认证、风控、可信身份与 scopes、requestId；按入口路由，不把用户身份交给模型伪造', 100, 190, 3000, 110, 'app');
  node('search-app', 'ai-search-app', '搜索 Workflow / 实验 / Reply\n简单直达 Domain，复杂进入搜索池', 100, 385, 660, 130, 'app');
  node('rec-app', 'ai-rec-app', '推荐 Workflow / 实验 / Reply\n简单直达 Domain，复杂进入推荐池', 950, 385, 660, 130, 'app');
  node('search-root', 'search-runtime｜根搜索任务池', 'rootRun Owner；主 Skill / Prompt / LLM Loop\n控制子任务生命周期、总预算与最终交付', 100, 635, 660, 150);
  node('rec-root', 'rec-runtime｜根推荐任务池', 'rootRun Owner；主 Skill / Prompt / LLM Loop\n同一执行机制，独立版本、容量与发布', 950, 635, 660, 150);
  node('delegation', 'Delegation Manager｜每个根 Runtime 内的逻辑模块', '仅接受允许目录与可委派矩阵中的目标；创建 childRun / remoteTask 映射\n分配子预算、并发 / 深度 / deadline；跟踪、查询、取消与回收', 1790, 635, 1310, 150);
  node('tool', 'Tool Gateway / Adapter｜主任务与子任务使用同一治理机制', 'schema / 身份 / scope / deadline / 审计 / 结构化错误\n两个运行池各自装配；不表示新建一个所有请求都必须经过的共享进程', 100, 980, 1510, 145);
  node('child', '内部子 Agent Worker（可选）', '独立 childRun / Skill / Prompt / 局部循环\n证据研究、品类选购、适配研究\n子任务上下文只读投影，局部状态隔离', 1790, 980, 610, 175);
  node('agent-gateway', 'Agent Gateway（可选）', 'Agent Registry / Card、鉴权、数据投影\nA2A 或明确的 RPC / HTTPS task API\n提交 / 查询 / 取消 / 重连 / 产物检查', 2510, 980, 590, 175, 'model');
  node('search-domain', 'search-domain / Hints', '商品召回、过滤、相关性排序\n输出 CandidateSet', 100, 1320, 440, 140, 'domain');
  node('rec-domain', 'rec-domain', '推荐召回、排序、集合约束\n输出 RecommendationSlate', 635, 1320, 440, 140, 'domain');
  node('rag', 'RAG / knowledge-domain', 'SKU / ACL / 有效期过滤与检索\n返回 EvidenceBundle；详见 02B', 1170, 1320, 440, 140, 'domain');
  node('child-context', '子任务运行支撑', '局部 LLM 调用、Memory / 检查点命名空间\n工具执行走左侧 Adapter，不得越权\n外部产物经校验，不直接写入主状态', 1790, 1320, 610, 150);
  node('external-agent', '外部 Agent｜他部门 / 合作方', '独立 remoteTaskId / 状态 / 产物\n自己的 LLM、Memory、Tool 与部署\n旅行规划 / 专业适配研究（示例）\n不直接访问我方用户凭证与交易', 2510, 1320, 590, 190, 'external');
  node('facts', '商品 / 价格 / 库存 / 可售性基础服务', 'Tool、Domain 与 App Reply 按需核价、填充实体卡\nAgent 产物不能替代权威实时事实', 100, 1600, 730, 140, 'domain');
  node('users', '用户 / 行为 / 外部信息接入', '画像、授权特征、天气、景点与网页等\n普通服务仍按 Tool 调用，不一律包装 Agent', 900, 1600, 710, 140, 'domain');
  node('merge', '主任务验收产物｜Delegation Manager → 根 Runtime', '状态、候选引用、证据与反证、来源版本、限制、用量 → 校验 / 去重 / 合并\n可选任务超时可降级；必需证据失败收窄或拒绝结论；等待用户由 App 统一处理', 1790, 1650, 1310, 150);
  node('model', '模型基础服务', '主 / 子 Runtime → LLM\nRAG → Embedding / Reranker\n根任务费用统一归集', 100, 1970, 440, 160, 'model');
  node('memory', 'Memory / 持久状态基础服务', 'root / child 命名空间隔离\n委派账本、检查点、受控引用\n长期偏好不由子任务自由覆盖', 635, 1970, 440, 160, 'model');
  node('data', '索引 / 特征 / 证据存储', '商品 / 知识检索、原文与版本\n按 ACL 与账户隔离\n知识后台摄取与索引发布', 1170, 1970, 440, 160, 'model');
  node('trade', '独立交易域', 'App 可信确认 → 报价复核 → 幂等执行 / 查单\n主 / 子 / 外部 Agent 均不能自证授权\n首期委派仅只读，取消不等于撤销订单', 1790, 1970, 610, 160, 'external');
  node('control', 'agentControl + 配置 / 制品平台', 'Agent / Skill / Prompt / Model 目录与版本\nOwner、SLA、委派矩阵、白名单与数据出口\n评测 / 实验 / 预算 / 审计 / 灰度与回滚', 1790, 385, 1310, 145, 'control');
  node('ops', '横向安全与 SRE', '父子 Trace、配额、告警、证据与版本\n取消确认、未知状态、迟到结果隔离\n权限撤销及时失效；总预算不可复制', 2510, 1970, 590, 160, 'control');
  node('footer', '部署与循环规则', '原 7 个核心部署单元是基线：可选 Worker / Agent Gateway 优先模块化或复用已有平台；外部 Agent 由对方维护。\n禁止根 Runtime 池互相递归启动根请求；允许经审核的能力目录和委派契约创建受限 childRun / remoteTask。\n主 / 子循环均计入根任务预算；并行 Tool 不等于多 Agent；A2A 是可选互操作协议，不是跨团队调用的强制替代品。', 100, 2320, 3000, 170, 'control');
  edge('access', 'search-app', '', [0.12, 1, 0.5, 0]);
  edge('access', 'rec-app', '', [0.4, 1, 0.5, 0]);
  edge('search-app', 'search-root', '复杂任务 / 流式结果');
  edge('rec-app', 'rec-root', '复杂任务 / 流式结果');
  edge('search-app', 'search-domain', '普通搜索：直接 RPC', [0, 0.45, 0, 0.5], [[35, 444], [35, 1390]], 'direct');
  edge('rec-app', 'rec-domain', '普通推荐：直接 RPC', [0, 0.5, 0.5, 0], [[855, 450], [855, 565], [15, 565], [15, 1255], [855, 1255]], 'direct');
  edge('search-root', 'tool', '常规工具', [0.45, 1, 0.2, 0]);
  edge('rec-root', 'tool', '常规工具', [0.55, 1, 0.75, 0]);
  edge('search-root', 'delegation', '按需委派', [0.85, 1, 0.1, 0], [[661, 870], [1680, 870], [1680, 585], [1921, 585]], 'delegate');
  edge('rec-root', 'delegation', '按需委派', [1, 0.4, 0, 0.4], [], 'delegate');
  edge('delegation', 'child', '内部 childRun', [0.2, 1, 0.5, 0], [], 'delegate');
  edge('delegation', 'agent-gateway', '外部委派', [0.8, 1, 0.5, 0], [], 'delegate');
  edge('agent-gateway', 'external-agent', '请求 / 查询 / 取消；产物返回', [0.5, 1, 0.5, 0], [], 'external');
  edge('child', 'child-context', '局部执行');
  edge('child-context', 'tool', '受限 Tool 调用', [0, 0.2, 1, 0.7], [[1715, 1350], [1715, 1082]]);
  edge('tool', 'search-domain', '', [0.12, 1, 0.5, 0]);
  edge('tool', 'rec-domain', '', [0.5, 1, 0.5, 0]);
  edge('tool', 'rag', '', [0.85, 1, 0.5, 0]);
  edge('tool', 'facts', '实时事实', [0, 0.8, 0, 0.5], [[70, 1096], [70, 1670]]);
  edge('tool', 'users', '授权画像 / 场景信息', [1, 0.8, 1, 0.5], [[1650, 1096], [1650, 1670]]);
  edge('child-context', 'merge', '结果 / 证据 / 用量', [0.5, 1, 0.23, 0], [], 'delegate');
  edge('agent-gateway', 'merge', '远程结果经网关校验 / 映射后回填', [1, 0.75, 1, 0.4], [[3115, 1111], [3115, 1710]], 'delegate');
  edge('merge', 'rec-root', '回填所属根任务（图示推荐池）', [0, 0.55, 1, 0.85], [[1690, 1733], [1690, 763]], 'delegate');
  edge('child-context', 'model', '子任务模型调用', [0.1, 1, 0.8, 0], [[1851, 1880], [452, 1880]]);
  edge('child-context', 'memory', '子任务状态 / 检查点', [0.25, 1, 0.8, 0], [[1943, 1910], [987, 1910]]);
  edge('search-root', 'model', '主 LLM（另一池同理）', [0, 0.65, 0.15, 0], [[55, 733], [55, 1860], [166, 1860]]);
  edge('rec-root', 'memory', '根状态 / 委派账本', [1, 0.65, 0.15, 0], [[1735, 733], [1735, 1840], [701, 1840]]);
  edge('rag', 'data', '知识索引 / 原文', [0.8, 1, 0.8, 0], [[1522, 1550], [1630, 1550], [1630, 1820], [1522, 1820]]);
  edge('search-domain', 'data', '商品检索', [0.85, 1, 0.15, 0], [[474, 1505], [850, 1505], [850, 1780], [1236, 1780]]);
  edge('rec-domain', 'data', '召回 / 特征', [0.85, 1, 0.35, 0], [[1009, 1485], [875, 1485], [875, 1800], [1324, 1800]]);
  edge('access', 'trade', '可信 App 确认接口（不经委派）', [1, 0.5, 1, 0.5], [[3180, 245], [3180, 2190], [2440, 2190], [2440, 2050]], 'external');
  edge('control', 'delegation', '预发布委派配置', [0.3, 1, 0.3, 0], [], 'async');
  edge('control', 'agent-gateway', '能力准入 / 出口策略', [1, 0.7, 1, 0.3], [[3135, 487], [3135, 1033]], 'async');
  return diagram.xml();
}

const artifacts = [
  ['docs/diagrams/agent-multi-architecture.drawio', architecture()],
  ['docs/diagrams/agent-multi-calls.drawio', calls()]
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
