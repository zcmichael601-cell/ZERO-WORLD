import fs from 'node:fs';

const colors = {
  blue: ['#EDF4FF', '#779ED2'], green: ['#EAF6F0', '#6AA68A'],
  purple: ['#F2EDFC', '#A08BC8'], orange: ['#FFF4E7', '#CEAA71'],
  red: ['#FCEDEA', '#D49991'], gray: ['#F4F7FA', '#9BAFBF']
};
const escape = value => String(value).replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;'
})[character]);

function diagram(name, width, height) {
  const cells = [];
  const ids = new Set(['0', '1']);
  let sequence = 0;
  const node = (id, title, body, x, y, nodeWidth, nodeHeight, kind = 'blue', shape = 'rounded=1;arcSize=10;') => {
    if (ids.has(id)) throw new Error(`Duplicate node: ${id}`);
    ids.add(id);
    const [fill, stroke] = colors[kind];
    const label = `<b style="font-size:23px">${escape(title)}</b>`
      + (body ? `<br><span style="font-size:18px">${escape(body).replaceAll('\n', '<br>')}</span>` : '');
    cells.push(`<mxCell id="${id}" value="${escape(label)}" style="${shape}html=1;whiteSpace=wrap;fillColor=${fill};strokeColor=${stroke};fontColor=#19324D;fontFamily=PingFang SC;align=center;verticalAlign=middle;spacing=14;strokeWidth=1.5;" vertex="1" parent="1"><mxGeometry x="${x}" y="${y}" width="${nodeWidth}" height="${nodeHeight}" as="geometry"/></mxCell>`);
  };
  const text = (id, title, x, y, nodeWidth, nodeHeight = 45) => node(id, title, '', x, y, nodeWidth, nodeHeight, 'gray', 'text;fillOpacity=0;strokeOpacity=0;');
  const edge = (source, target, title = '', pins = [0.5, 1, 0.5, 0], points = [], dashed = false) => {
    if (!ids.has(source) || !ids.has(target)) throw new Error(`Missing endpoint: ${source} -> ${target}`);
    const route = points.length ? `<Array as="points">${points.map(([x, y]) => `<mxPoint x="${x}" y="${y}"/>`).join('')}</Array>` : '';
    cells.push(`<mxCell id="edge-${++sequence}" value="${escape(title)}" style="edgeStyle=orthogonalEdgeStyle;rounded=1;html=1;endArrow=block;endFill=1;strokeColor=#68819B;strokeWidth=2;fontFamily=PingFang SC;fontSize=17;fontColor=#314F6D;labelBackgroundColor=#FFFFFF;dashed=${dashed ? 1 : 0};exitX=${pins[0]};exitY=${pins[1]};entryX=${pins[2]};entryY=${pins[3]};" edge="1" parent="1" source="${source}" target="${target}"><mxGeometry relative="1" as="geometry">${route}</mxGeometry></mxCell>`);
  };
  return { node, text, edge, output: () => `<?xml version="1.0" encoding="UTF-8"?>\n<mxfile host="app.diagrams.net" type="device"><diagram id="${escape(name)}" name="${escape(name)}"><mxGraphModel grid="1" page="1" pageWidth="${width}" pageHeight="${height}"><root><mxCell id="0"/><mxCell id="1" parent="0"/>\n${cells.join('\n')}\n</root></mxGraphModel></diagram></mxfile>\n` };
}

function architecture() {
  const graph = diagram('C端购物Agent生产目标架构', 1900, 1680);
  const { node, text, edge } = graph;
  text('title', '01 / C 端购物 Agent · 生产目标架构（建议方案，非当前部署）', 40, 20, 1820, 65);
  text('subtitle', 'LLM 提出决策 · Harness 管理执行 · 业务系统掌握事实与交易权威', 70, 90, 1300);
  node('client', '渠道与体验｜APP / H5 / Web', '对话 + 商品卡片 + 可信确认页 / 支付页\n流式进度、取消、重连、人工客服入口', 80, 155, 1300, 100);
  node('bff', '接入层｜API Gateway + BFF', '登录态、账户隔离、风控、限流、请求去重\n服务端派生 userId / scopes；生成 requestId / runId', 80, 300, 1300, 100);
  node('context', 'Context + Prompt', 'Base / Business / 请求快照\n多轮约束、实体、Memory\n知识是证据，不是指令', 80, 465, 390, 130);
  node('runtime', 'Harness / Agent Runtime', '持久化状态机 + Agent Loop\n预算、暂停 / 恢复、Hooks\n鉴权、确认、结果和错误回填', 525, 465, 410, 130, 'green');
  node('skills', '能力注册与渐进披露', 'Skill：经审核的 SOP\nTool：带权限的 schema\nSubAgent：受限专业委派', 990, 465, 390, 130, 'green');
  node('model', 'LLM Gateway｜Spring AI 适配', '复用现有模型接入起步\n流式生成、工具调用协议\n模型路由 / 配额 / 超时 / 熔断', 80, 675, 390, 140, 'purple');
  node('tool', 'Tool Gateway｜策略执行点', 'schema + 身份 + scopes\n只读并行 / 写操作确认与幂等\ndeadline、审计、结构化结果', 525, 675, 410, 140, 'green');
  node('reply', 'Reply｜可信交付', '文本安全分段输出\n商品实体引用 → 业务卡片\n事实 / 状态校验 + SSE 事件', 990, 675, 390, 140);
  node('search', '商品发现与推荐域', '搜索 / 召回 / 排序 / 画像\n权威 SKU、价格、库存\nLLM 解释，不替代排序引擎', 80, 920, 390, 135, 'orange');
  node('knowledge', '知识与记忆域', 'RAG：混合检索 + 重排 + ACL\n文档版本、引用、有效期\nMemory：授权偏好与任务状态', 525, 920, 410, 135, 'orange');
  node('transaction', '交易与专业服务域', '购物车 / 报价 / 订单 / 售后\n风控、幂等、查单、补偿\n外部天气等经受控适配器', 990, 920, 390, 135, 'orange');
  node('state', '在线状态与数据底座', '会话 / 任务 / 实体快照：持久化数据库 + 缓存\n执行账本、幂等记录、确认单、Outbox / 事件总线；缓存不是交易唯一真相', 80, 1160, 1300, 110, 'gray');
  node('pipeline', '离线数据与 RAG 管道', '授权数据 → 清洗 / 质检 / 审核 → Document 分块 → Embedding → 持久向量 + 关键词索引\n商品 / 政策更新与撤回驱动增量索引；画像更新需同意、期限和删除传播', 80, 1320, 1300, 115, 'orange');
  node('control', '控制平面｜研发协作', '产品：场景、体验、业务验收\nAgent：Runtime / Prompt / Skill\n算法：模型、评测、策略\n业务域：Tool 与事实契约\n数据：知识、画像与血缘\n安全：权限、隐私与风控', 1470, 155, 340, 280, 'gray');
  node('release', '版本与发布', '统一 Release Manifest\nPrompt / Skill / Tool / 模型\nPolicy / 索引 / 卡片 schema\n离线评测 → 灰度 → 回滚', 1470, 505, 340, 235, 'gray');
  node('operate', '运行平面｜SRE + 值班', '全链路 Trace / 指标 / 脱敏日志\nSLO、容量、成本与依赖治理\n按能力熔断、读写隔离\n故障接管、查单与对账', 1470, 815, 340, 235, 'gray');
  node('security', '横切治理', '输入 / 检索 / 调用 / 输出检查\n最小权限、数据分级\n安全确认、人工复核\n默认拒绝与可追溯审计', 1470, 1120, 340, 235, 'red');
  text('legend', '实线：在线调用 / 数据依赖；虚线：控制或离线发布。返回结果沿原调用路径回填，图中省略反向线。', 80, 1480, 1730, 55);
  text('boundary', '建议先以模块化服务落地；图中能力域不等于每框必须拆一个微服务。交易写入不得由模型直接访问数据库。', 80, 1550, 1730, 55);
  edge('client', 'bff'); edge('bff', 'runtime');
  edge('context', 'runtime', '装配', [1, 0.5, 0, 0.5]);
  edge('skills', 'runtime', '按需加载', [0, 0.5, 1, 0.5]);
  edge('runtime', 'model', 'Prompt / Messages', [0.12, 1, 0.7, 0]);
  edge('runtime', 'tool', 'tool_calls');
  edge('runtime', 'reply', '回答 / 实体引用', [0.88, 1, 0.3, 0]);
  edge('tool', 'search', '发现 / 校价', [0.2, 1, 0.5, 0], [[607, 870], [275, 870]]);
  edge('tool', 'knowledge', '检索 / 记忆');
  edge('tool', 'transaction', '操作 / 查单', [0.8, 1, 0.5, 0], [[853, 870], [1185, 870]]);
  edge('runtime', 'state', '运行检查点', [0.05, 1, 0, 0.5], [[545.5, 610], [40, 610], [40, 1215]]);
  edge('transaction', 'state', '执行账本', [0.5, 1, 0.85, 0]);
  edge('pipeline', 'knowledge', '增量索引发布', [0, 0.5, 0.5, 1], [[60, 1377], [60, 1100], [730, 1100]], true);
  edge('release', 'skills', '固定版本', [0, 0.15, 1, 0.35], [], true);
  edge('reply', 'client', 'SSE', [1, 0.5, 1, 0.5], [[1420, 745], [1420, 205]]);
  return graph.output();
}

function execution() {
  const graph = diagram('C端购物Agent执行与交易确认流程', 1900, 1970);
  const { node, text, edge } = graph;
  text('title', '02 / 执行流程 · 受控推理循环 + 可恢复交易（建议方案）', 40, 20, 1820, 65);
  text('left-title', 'A. 发现、理解、检索与推荐', 90, 105, 770);
  text('right-title', 'B. 用户确认、交易与恢复', 1030, 105, 770);
  const left = (id, title, body, y, kind = 'blue', height = 95) => node(id, title, body, 160, y, 660, height, kind);
  const right = (id, title, body, y, kind = 'blue', height = 95) => node(id, title, body, 1080, y, 660, height, kind);
  left('input', '① 用户输入 / 追问', '例如：周末北京徒步，推荐 500 元内的装备', 180);
  left('access', '② 接入与上下文准备', '鉴权、限流、runId；加载会话、页面实体和授权偏好', 325);
  left('assemble', '③ 选择 Skill 并装配 Prompt', 'Base + Business + 请求快照 + Messages\nSOP 按需加载；工具白名单由服务端限定', 470, 'green', 110);
  left('llm', '④ LLM 流式推理', '模型提议：澄清 / 只读调用 / 写意图 / 回答\nRuntime 控制轮次、deadline 和 token 预算', 640, 'purple', 110);
  left('dispatch', '⑤ Runtime 路由与校验', '解析完整 tool_calls；校验 schema、身份、权限和依赖', 810, 'green');
  left('tools', '⑥ 只读 Tool / 受限 SubAgent 执行', '画像 → 天气 → 搜索排序 → RAG 规则 / 商品说明\n价格库存回源；可独立查询才并行', 990, 'orange', 115);
  left('feedback', '⑦ 写入事实、实体引用与调用结果', 'Assistant tool_calls + ToolResponseMessage\n成功 / 失败均回填；不足可追问；达到预算则降级', 1170, 'green', 115);
  left('reply', '⑧ Reply 校验并流式交付', '安全文本逐段输出；卡片引用权威实体\n证据不足明确说明；绝不宣称未完成的交易成功', 1380, 'blue', 120);
  left('finish', '⑨ 推荐任务完成 / 等待用户追问', '落盘检查点与事件；返回可恢复 runId\n用户选择商品后进入右侧写操作流程', 1580, 'gray', 115);
  right('intent', '⑩ 用户点击加购 / 发起购买', '模型只能生成操作意图，不拥有交易执行权限', 180, 'blue');
  right('quote', '⑪ 确定性预校验与报价', '校验账户、资格、SKU、数量、库存、优惠与地址\n生成报价快照 / intentId / 过期时间', 325, 'orange', 110);
  right('confirm', '⑫ 可信确认页 → 等待用户确认', '明确商品、数量、实付金额和配送信息\n确认绑定快照与用户；拒绝 / 超时即终止，不下单', 495, 'red', 120);
  right('revalidate', '⑬ 恢复任务并再次校验', '核验确认凭证、账户、有效期、最新报价和风控\n任一重要字段变化：旧确认作废，重新报价确认', 685, 'red', 120);
  right('execute', '⑭ 交易域幂等执行', '执行账本 + 唯一业务幂等键 + Outbox\n加购 / 建单交给业务服务；支付跳转可信支付页', 885, 'orange', 120);
  right('status', '⑮ 判断权威业务结果', '成功 / 明确失败 / 未知（超时不代表失败）', 1080, 'green', 95);
  right('reconcile', '⑯ 未知结果 → 查单 / 对账', '按幂等键查状态，有限退避；仍未知转人工跟进\n不创建新幂等键盲重试、不提示已经成功', 1240, 'red', 110);
  right('result', '⑰ 结果交付与持久化', '仅凭业务状态提示成功 / 失败 / 处理中\n记录订单引用、任务状态和审计事件；可继续售后', 1430, 'blue', 110);
  right('observe', '⑱ 反馈与运行闭环', '指标 / Trace → 归因 → 评测 → 审批 → 灰度\n上线受门禁约束，不允许模型自动修改生产策略', 1600, 'gray', 110);
  node('footer', '所有阶段的统一中断规则', '越权 / 风险 → 拒绝或人工接管；只读故障 → 有限重试 / 降级；客户端断开 ≠ 交易撤销\n进度由真实执行事件产生，不展示隐式思维链；关键事实和交易状态先验证再显示', 100, 1800, 1700, 115, 'gray');
  edge('input', 'access'); edge('access', 'assemble'); edge('assemble', 'llm'); edge('llm', 'dispatch');
  edge('dispatch', 'tools', '只读调用'); edge('tools', 'feedback');
  edge('feedback', 'llm', '结果回填，继续循环', [0, 0.5, 0, 0.5], [[95, 1227], [95, 695]]);
  edge('dispatch', 'reply', '回答 / 澄清 / 降级', [1, 0.5, 1, 0.5], [[900, 857], [900, 1440]]);
  edge('reply', 'finish');
  edge('finish', 'intent', '用户主动操作 / 受控写意图', [1, 0.5, 0, 0.5], [[980, 1637], [980, 227]]);
  edge('intent', 'quote'); edge('quote', 'confirm'); edge('confirm', 'revalidate', '用户明确确认');
  edge('revalidate', 'quote', '信息变化', [1, 0.5, 1, 0.5], [[1810, 745], [1810, 380]]);
  edge('revalidate', 'execute', '确认有效且全部校验通过'); edge('execute', 'status');
  edge('status', 'reconcile', '未知'); edge('reconcile', 'result');
  edge('status', 'result', '已知结果', [1, 0.5, 1, 0.5], [[1805, 1127], [1805, 1485]]);
  edge('result', 'observe');
  return graph.output();
}

let patch = '*** Begin Patch\n';
for (const [path, content] of [
  ['docs/diagrams/shopping-production-architecture.drawio', architecture()],
  ['docs/diagrams/shopping-production-execution.drawio', execution()]
]) {
  if (fs.existsSync(path)) {
    const previous = fs.readFileSync(path, 'utf8');
    if (previous === content) continue;
    patch += `*** Update File: ${path}\n@@\n${previous.trimEnd().split('\n').map(line => '-' + line).join('\n')}\n`;
  } else {
    patch += `*** Add File: ${path}\n`;
  }
  patch += content.trimEnd().split('\n').map(line => '+' + line).join('\n') + '\n';
}
process.stdout.write(patch + '*** End Patch\n');
