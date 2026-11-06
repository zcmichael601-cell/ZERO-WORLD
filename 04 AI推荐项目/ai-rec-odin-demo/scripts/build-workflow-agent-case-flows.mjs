import fs from 'node:fs';

const outputs = new Map();
const escape = value => String(value).replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;'
})[character]);
const colors = { business: '#F8EDDE', agent: '#E9F2FE', domain: '#EAF5ED', ai: '#F1EBFA', external: '#FBEAF2' };

function sequence(name, title, subtitle, lanes, footer) {
  const backgrounds = [];
  const shapes = [];
  const edges = [];
  const ids = new Set(['0', '1']);
  const width = lanes.length * 400 + 140;
  const positions = lanes.map((lane, index) => 270 + index * 400);
  let cursor = 395;
  let serial = 0;
  function shape(id, value, x, y, boxWidth, height, style, background = false) {
    if (ids.has(id)) throw new Error(`Duplicate ${id}`);
    ids.add(id);
    (background ? backgrounds : shapes).push(`<mxCell id="${id}" value="${escape(value)}" style="${style}" vertex="1" parent="1"><mxGeometry x="${x}" y="${y}" width="${boxWidth}" height="${height}" as="geometry"/></mxCell>`);
  }
  function text(id, label, x, y, boxWidth, height, size = 21, color = '#243A4A') {
    shape(id, escape(label).replaceAll('\n', '<br>'), x, y, boxWidth, height, `text;html=1;whiteSpace=wrap;align=center;verticalAlign=middle;fontFamily=PingFang SC;fontSize=${size};fontColor=${color};`);
  }
  function point(id, x, y) {
    shape(id, '', x - 0.5, y - 0.5, 1, 1, 'fillColor=none;strokeColor=none;');
  }
  function arrow(id, source, target, dashed = false, color = '#496C89', route = []) {
    const waypoint = route.length ? `<Array as="points">${route.map(([x, y]) => `<mxPoint x="${x}" y="${y}"/>`).join('')}</Array>` : '';
    edges.push(`<mxCell id="${id}" style="html=1;endArrow=block;endFill=1;strokeWidth=2;strokeColor=${color};dashed=${dashed ? 1 : 0};edgeStyle=none;" edge="1" parent="1" source="${source}" target="${target}"><mxGeometry relative="1" as="geometry">${waypoint}</mxGeometry></mxCell>`);
  }
  text('title', title, 60, 15, width - 120, 62, 34);
  text('subtitle', subtitle, 70, 80, width - 140, 45, 22, '#586B79');
  shape('control-band', '', 70, 141, width - 140, 82, 'rounded=0;fillColor=#F1F5F8;strokeColor=#A6B6C4;', true);
  text('control', '横向百策：开发者注册 / 评测 / 发布 → Runtime 加载 Prompt、Skill、LLM 配置与 Tool 权限快照\nL1 / L2 / L3 传播实验与 Trace；百策不是逐轮模型调用入口，模型推理由 MaaS 提供', 85, 149, width - 170, 66, 21);
  for (const [index, lane] of lanes.entries()) {
    shape(`lane-title-${index}`, `<b>${escape(lane.title)}</b><br><span style="font-size:20px">${escape(lane.detail).replaceAll('\n', '<br>')}</span>`, positions[index] - 188, 249, 376, 98,
      `rounded=0;html=1;whiteSpace=wrap;fillColor=${colors[lane.kind]};strokeColor=#A9B8C4;fontFamily=PingFang SC;fontSize=22;fontColor=#243A4A;align=center;verticalAlign=middle;spacing=7;`);
  }
  function phase(label) {
    shape(`phase-${serial++}`, escape(label), 78, cursor - 12, width - 156, 40, 'rounded=0;html=1;fillColor=#F4F7FA;strokeColor=none;fontFamily=PingFang SC;fontSize=21;fontStyle=1;fontColor=#3C566A;align=left;spacingLeft=14;', true);
    cursor += 100;
  }
  function message(from, to, label, options = {}) {
    const id = `message-${serial++}`;
    const left = Math.min(positions[from], positions[to]);
    const distance = Math.abs(positions[from] - positions[to]);
    const color = options.color || (options.return ? '#7C8A95' : '#496C89');
    if (from === to) {
      const y = cursor + 11;
      point(`${id}-from`, positions[from], y);
      point(`${id}-to`, positions[from], y + 18);
      arrow(id, `${id}-from`, `${id}-to`, Boolean(options.return), color, [[positions[from] + 104, y], [positions[from] + 104, y + 18]]);
      text(`${id}-label`, label, positions[from] - 180, cursor - 45, 360, 52, 20, color);
    } else {
      point(`${id}-from`, positions[from], cursor + 17);
      point(`${id}-to`, positions[to], cursor + 17);
      arrow(id, `${id}-from`, `${id}-to`, Boolean(options.return), color);
      text(`${id}-label`, label, left + 9, cursor - 42, distance - 18, 53, 20, color);
    }
    cursor += options.height || 85;
  }
  function note(label, kind = 'normal') {
    const fill = kind === 'warning' ? '#FFF4E7' : '#F4F7FA';
    shape(`note-${serial++}`, escape(label).replaceAll('\n', '<br>'), 88, cursor - 10, width - 176, 61,
      `rounded=0;html=1;whiteSpace=wrap;fillColor=${fill};strokeColor=#CCD4DC;align=center;verticalAlign=middle;fontFamily=PingFang SC;fontSize=20;fontColor=#536371;spacing=8;`);
    cursor += 83;
  }
  function finish() {
    const height = cursor + 135;
    for (const [index, lane] of lanes.entries()) {
      shape(`lane-background-${index}`, '', positions[index] - 197, 239, 394, cursor - 215, `rounded=0;fillColor=${colors[lane.kind]};fillOpacity=24;strokeColor=none;`, true);
      const source = `lifeline-start-${index}`;
      const target = `lifeline-end-${index}`;
      point(source, positions[index], 350);
      point(target, positions[index], cursor + 10);
      edges.unshift(`<mxCell id="lifeline-${index}" style="endArrow=none;strokeColor=#CBD4DC;strokeWidth=1;dashed=1;" edge="1" parent="1" source="${source}" target="${target}"><mxGeometry relative="1" as="geometry"/></mxCell>`);
    }
    text('legend', '时间从上到下；实线为请求 / 执行动作，虚线为结果返回；同列回环为模块内部处理。分组服务不代表新增统一代理。', 80, cursor + 28, width - 160, 36, 20);
    text('footer', footer, 80, cursor + 69, width - 160, 50, 20);
    outputs.set(`docs/diagrams/${name}.drawio`, `<mxfile host="app.diagrams.net" type="device"><diagram id="${name}" name="${escape(title)}"><mxGraphModel grid="1" page="1" pageWidth="${width}" pageHeight="${height}"><root><mxCell id="0"/><mxCell id="1" parent="0"/>\n${backgrounds.reverse().join('\n')}\n${edges.join('\n')}\n${shapes.join('\n')}\n</root></mxGraphModel></diagram></mxfile>\n`);
  }
  return { phase, message, note, finish };
}

const standard = runtime => [
  { title: 'L1 · 交互模块', detail: runtime === 'search-runtime' ? 'AI 搜应用\n用户 / 商卡 / 文本 / 流式协议' : 'AI 推应用\n用户 / 商卡 / 文本 / 流式协议', kind: 'business' },
  { title: 'L1 · 业务执行模块', detail: 'Workflow\n本地逻辑 / 领域 RPC / Agent RPC', kind: 'business' },
  { title: `L2 · ${runtime}`, detail: 'Prompt / Skill / LLM Loop\n状态 / 预算 / 校验', kind: 'agent' },
  { title: 'L2 · Tool Adaptor', detail: '工具参数 / 权限校验\nRPC / HTTP 适配', kind: 'agent' },
  { title: 'L3 · 领域服务', detail: '搜索 / 推荐 / 商品\n价格 / 库存 / 用户', kind: 'domain' },
  { title: 'L4 · AI 公共服务', detail: 'Context / AI Memory\nRAG / MaaS', kind: 'ai' }
];

const shopping = sequence('workflow-case-3-value-shopping', '案例三｜高性价比选购：Workflow 与 Agent 联合执行', '500 元以内无线耳机 · 安卓手机 · 地铁通勤 · 证据支持的收益与成本比较', standard('search-runtime'), '责任边界：Workflow 控制业务交付；Runtime 控制智能节点；领域服务提供事实；RAG 提供证据；MaaS 执行推理。');
shopping.phase('A. 业务接入与 Agent 节点准备');
shopping.message(0, 1, '1. 选购咨询 → shopping-assist\n可信身份 / businessRunId / 实验');
shopping.message(1, 1, '2. 校验输入与资格\n设定 deadline / 预算');
shopping.message(1, 2, '3. Agent RPC：目标与约束\n创建 agentRunId / 输出 schema');
shopping.message(2, 2, '4. 加载百策发布快照\n选购 Skill / Prompt / 模型配置');
shopping.message(2, 5, '5. Context：当前条件与会话；AI Memory：必要的授权偏好');
shopping.message(5, 2, '返回工作上下文与偏好投影；本轮显式需求优先', { return: true });
shopping.phase('B. 有界 Agent Loop：工具不是模型口头描述，而是真实服务调用');
shopping.message(2, 5, '6. 组装 Prompt → MaaS / LLM：判断下一步');
shopping.message(5, 2, '返回 tool_calls 或可交付结果', { return: true });
shopping.message(2, 3, '7. 校验参数 / 权限 / 预算\n执行允许的 Tool');
shopping.message(3, 4, '8. 搜索候选 + 查询商品事实');
shopping.message(4, 3, 'CandidateSet / EntitySnapshot', { return: true });
shopping.message(3, 5, '9. retrieveKnowledge → RAG：按 SKU / ACL / 时效检索证据');
shopping.message(5, 5, 'RAG → MaaS：向量 / 可选重排\n查询已发布知识索引');
shopping.message(5, 3, 'EvidenceBundle：出处、适用条件、冲突与缺失', { return: true });
shopping.message(3, 4, '9b. 依赖满足后调用推荐服务\n按可信特征重排有效候选');
shopping.message(4, 3, '指定集合的 RankedSet', { return: true });
shopping.message(3, 2, '10. 按 toolCallId 回填结果\n失败亦按真实状态回填', { return: true });
shopping.note('Loop：Runtime 回到步骤 6，再经 MaaS 推理；需要补查则重复允许的工具步骤。工具顺序按数据依赖组织，不要求每轮全部执行。\n信息不足 → need_input；证据缺失 → 缩小结论；模型 / 工具失败或预算耗尽 → partial / failed，禁止无限循环。', 'warning');
shopping.phase('C. Agent 节点返回后，Workflow 继续执行业务');
shopping.message(2, 1, '11. 校验输出 / 候选 / 引用\n返回 AgentResult 与状态', { return: true });
shopping.message(1, 4, '12. Workflow 直接 RPC：展示前复核最新价格与库存', { color: '#428762' });
shopping.message(4, 1, '返回权威事实；失效或超预算候选由业务节点处理', { return: true });
shopping.message(1, 0, '13. 业务结果：商卡 / 取舍 / 引用\n交互模块封装流式或非流式响应', { return: true });
shopping.note('执行期间：Runtime 的真实事件经 Workflow → 交互模块发送 progress；文本 / 卡片按校验粒度释放，不必等全部步骤结束。\n澄清由 Workflow 挂起后交互提问；故障可回到传统搜索卡片。Context 保存任务状态，授权反馈进入百策评测。');
shopping.finish();

const compare = sequence('workflow-case-4-product-compare', '案例四｜跨轮商品比较：Context 定对象，RAG 提依据', '这几款冲锋衣，哪款更适合雨天徒步？ · 默认只比较既有候选，不重新搜索全部商品', standard('rec-runtime'), '责任边界：Context 解决“这几款”；Memory 提供授权偏好；商品服务核对型号参数；RAG 支持差异解释，不替代事实。');
compare.phase('A. 识别追问对象，进入已有候选比较 Workflow');
compare.message(0, 1, '1. compare → product-compare\nsessionId / candidateSetId');
compare.message(1, 1, '2. 校验卡片引用与条件版本\n失效或缺失则澄清');
compare.message(1, 2, '3. Agent RPC：限定比较 SKU\n目标 / 版本 / 预算 / schema');
compare.message(2, 2, '4. 加载百策发布快照\nproduct-compare Skill / Prompt');
compare.message(2, 5, '5. Context：恢复候选及本轮条件；AI Memory：读取必要授权偏好');
compare.message(5, 2, '返回受控上下文；新输入优先于旧条件', { return: true });
compare.phase('B. 选择比较维度，真实获取型号参数与证据');
compare.message(2, 5, '6. MaaS / LLM：防水、透湿、重量、使用场景与测试口径');
compare.message(5, 2, '返回合法工具意图或澄清需求', { return: true });
compare.message(2, 3, '7. 执行允许的比较工具\n禁止擅自扩大候选范围');
compare.message(3, 4, '8. getProductFacts\n核对 SKU / 型号 / 规格');
compare.message(4, 3, '同型号参数及查询时点', { return: true });
compare.message(3, 5, '9. RAG：获取使用说明、适用条件、对比口径与反证');
compare.message(5, 5, 'RAG → MaaS / 知识索引\n型号 / ACL / 时效过滤');
compare.message(5, 3, '返回 EvidenceBundle；明确冲突、限制与缺失项', { return: true });
compare.message(3, 2, '10. 回填事实与证据', { return: true });
compare.message(2, 5, '11. MaaS / LLM：基于证据生成差异与适用性；预算内按需补查');
compare.message(5, 2, '返回对比结论 / 引用 / 不确定性，不把“防泼水”夸大为“完全防水”', { return: true });
compare.phase('C. 返回比较结果；新条件是下一次业务路由，而非擅自改任务');
compare.message(2, 1, '12. 校验候选与引用\n返回对比结果 / partial', { return: true });
compare.message(1, 0, '13. 装配对比卡与依据\n流式解释 / 非流式聚合', { return: true });
compare.message(0, 1, '可选下一轮：用户要求替代品\n如“换成更轻的”', { color: '#428762' });
compare.message(1, 4, '14. 新条件分支直接 RPC 搜索 / 推荐：补候选并重新校验', { color: '#428762' });
compare.message(4, 1, '新 CandidateSet → 继续本场景 Workflow；不是调用另一个根 Runtime', { return: true });
compare.note('异常处理：候选引用失效 → 让用户重新选择；证据不足 / 口径冲突 → 明确限制；工具失败 → 部分结果或业务降级。\n本例默认单 Agent + 多 Skill / Tool 即可，不因跨团队调用 RAG 而强制启用子 Agent。');
compare.finish();

const serviceLanes = [
  { title: 'L1 · 交互模块 / 用户', detail: '搜 / 推所属应用\n可信确认页与操作状态', kind: 'business' },
  { title: 'L1 · 业务 Workflow', detail: 'service-assist\n业务状态 / 确认 / 交易分支', kind: 'business' },
  { title: 'L2 · 所属 Runtime', detail: 'Prompt / Skill / Loop\n可选内部子 Agent', kind: 'agent' },
  { title: 'L2 · Agent Adaptor', detail: '身份 / 协议 / remoteTaskId\n超时 / 取消 / 产物归一', kind: 'agent' },
  { title: 'L3 · 业务与交易服务', detail: '资格 / 报价 / 预约 / 订单\n订单状态的权威来源', kind: 'domain' },
  { title: 'L4 · AI 公共服务', detail: 'Context / Memory / RAG / MaaS\n内部任务共用受控底座', kind: 'ai' },
  { title: '外部 · 专业 Agent', detail: '家政 / 安装 / 下单等\n独立平台与 SLA', kind: 'external' }
];
const service = sequence('workflow-case-5-external-service', '案例五｜外部服务协作：先取得方案，再可信确认与执行', '选购家电后咨询周六安装 / 家政预约 · 外部 Agent 只在独立多步任务确有价值时启用', serviceLanes, '责任边界：Agent 研究方案；Workflow 控制交易阶段；用户确认授予限定操作；业务服务给出权威结果。外部“完成”不等于订单成功。');
service.phase('A. 业务接入与授权：先明确任务及可披露信息');
service.message(0, 1, '1. service-assist 请求\n服务意愿 / 城市 / 必要信息');
service.message(1, 4, '2. 直接领域 RPC：用户资格、服务范围及必要业务事实', { color: '#428762' });
service.message(4, 1, '返回校验结果；无授权不读取完整地址和无关历史', { return: true });
service.message(1, 2, '3. 方案研究 Agent 节点\n最小数据 / deadline / 预算');
service.message(2, 2, '4. 百策加载发布快照\nSkill / Prompt / 委派白名单');
service.message(2, 5, '5. Context / 授权 Memory；经 MaaS 理解任务并规划必要动作');
service.message(5, 2, '返回上下文 / 推理结果；模型只提议动作，不授予权限', { return: true });
service.note('可选内部子 Agent：仅在适配研究确需独立多步探索时创建 childRun，隔离上下文并分配子预算。\n子任务沿用 MaaS / Context / RAG 与受控 Tool Adaptor，产物由父任务验收；普通领域 API 不包装成子 Agent。');
service.phase('B. 外部委派：传任务、收产物，不直接完成用户交易');
service.message(2, 3, '6. 发起受限 remoteTask\n任务目标 / 数据范围 / 时限');
service.message(3, 6, '7. 身份与任务协议映射 → 外部 Agent：研究方案 / 时段 / 价格条件');
service.message(6, 3, '8. 返回 remoteTaskId、进度及最终方案产物；外部状态不等于订单状态', { return: true });
service.message(3, 2, '9. 归一状态 / 校验产物\n超时取消，迟到结果受控', { return: true });
service.message(2, 1, '10. 返回方案与证据\n不替用户确认预约 / 下单', { return: true });
service.phase('C. 可信确认：Workflow 重新核对方案，并等待用户决定');
service.message(1, 4, '11. 直接 RPC：复核资格、可预约时段、报价及有效期', { color: '#428762' });
service.message(4, 1, '返回可确认的方案 / 报价引用；过期则重新报价', { return: true });
service.message(1, 0, '12. 展示服务 / 时间 / 地址 / 金额\n等待用户在可信页面确认', { return: true });
service.message(0, 1, '13. 用户确认绑定具体操作\n未确认 / 拒绝则不执行', { color: '#B86C33' });
service.phase('D. 确认后的写操作：权威状态闭环，不盲目重放');
service.message(1, 4, '14. 交易 / 预约 RPC：限定授权 + 幂等键；执行并查询最终状态', { color: '#428762' });
service.message(4, 1, '15. 返回权威订单 / 预约状态；超时先查单，必要时对账', { return: true });
service.message(1, 0, '16. 业务状态汇合并展示\n支付进入可信支付流程', { return: true });
service.note('可选协议分支：如必须经外部 Agent 执行，确认后的 Workflow → Runtime 受限执行节点 → Agent Adaptor → 外部 Agent；\n携带绑定操作的有限凭证 / 幂等键，仍须查询对应权威交易系统并对账。外部超时不盲目重新下单。', 'warning');
service.note('全过程真实进度经 Runtime → Workflow → 交互模块交付；取消连接不等于撤销已提交交易。\n外部能力不可用可退回普通服务列表或人工；不默认分享全量 Memory，也不允许内部 / 外部 Agent 扩大用户授权。');
service.finish();

let patch = '*** Begin Patch\n';
for (const [path, content] of outputs) {
  const previous = fs.existsSync(path) ? fs.readFileSync(path, 'utf8') : null;
  if (previous === content) continue;
  patch += previous === null ? `*** Add File: ${path}\n` : `*** Update File: ${path}\n@@\n${previous.trimEnd().split('\n').map(line => '-' + line).join('\n')}\n`;
  patch += content.trimEnd().split('\n').map(line => '+' + line).join('\n') + '\n';
}
process.stdout.write(patch + '*** End Patch\n');
