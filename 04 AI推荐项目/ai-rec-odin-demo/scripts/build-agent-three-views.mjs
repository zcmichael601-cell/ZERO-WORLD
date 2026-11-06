import fs from 'node:fs';

const outputs = new Map();
const architecture = fs.readFileSync('docs/diagrams/agent-multi-architecture.drawio', 'utf8')
  .replaceAll('主 / 子 Agent 各自的 LLM、Tool、Memory 依赖详见图 02A；RAG 内部依赖详见 02B。', '模块协作见图 2；服务边界与 RAG、模型、Memory 的远程调用见图 3。');
outputs.set('docs/diagrams/agent-three-views-layers.drawio', architecture);
const modules = fs.readFileSync('docs/diagrams/agent-system-overview.drawio', 'utf8')
  .replaceAll('01 系统架构总览', '02 模块间调用关系')
  .replaceAll('AI 搜索 × AI 推荐 · 系统架构', '02 / AI 搜索 × AI 推荐 · 模块间调用关系')
  .replaceAll('总览仅展示职责与主关系；详细调用见 02A / 02B。', '本图展示逻辑模块；服务部署与远程调用见图 3。');
outputs.set('docs/diagrams/agent-three-views-modules.drawio', modules);

const panels = [];
const nodes = [];
const edges = [];
const ids = new Set(['0', '1']);
const escape = value => String(value).replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&apos;'
})[character]);
const colors = { app: '#EADAC6', runtime: '#EAF2FC', domain: '#EDF5EE', infra: '#F0F3F6', optional: '#F4EDF8' };
function box(id, title, subtitle, x, y, width, height, kind = 'infra', panel = false) {
  if (ids.has(id)) throw new Error(`Duplicate ID ${id}`);
  ids.add(id);
  const label = `<b>${escape(title)}</b>${subtitle ? `<br><span style="font-size:18px">${escape(subtitle).replaceAll('\n', '<br>')}</span>` : ''}`;
  const style = `rounded=0;whiteSpace=wrap;html=1;fontFamily=PingFang SC;fontSize=23;fontColor=#20313F;strokeColor=#8C9DAA;strokeWidth=1.5;fillColor=${colors[kind]};spacing=12;verticalAlign=${panel ? 'top' : 'middle'};${kind === 'optional' ? 'dashed=1;' : ''}`;
  (panel ? panels : nodes).push(`<mxCell id="${id}" value="${escape(label)}" style="${style}" vertex="1" parent="1"><mxGeometry x="${x}" y="${y}" width="${width}" height="${height}" as="geometry"/></mxCell>`);
}
function text(id, value, x, y, width, height, size = 21) {
  ids.add(id);
  nodes.push(`<mxCell id="${id}" value="${escape(value)}" style="text;html=1;whiteSpace=wrap;align=center;verticalAlign=middle;fontFamily=PingFang SC;fontSize=${size};fontColor=#435463;" vertex="1" parent="1"><mxGeometry x="${x}" y="${y}" width="${width}" height="${height}" as="geometry"/></mxCell>`);
}
function edge(source, target, label = '', pins = [0.5, 1, 0.5, 0], points = [], color = '#58738C', dashed = false) {
  if (!ids.has(source) || !ids.has(target)) throw new Error(`Invalid edge ${source}/${target}`);
  const route = points.length ? `<Array as="points">${points.map(([x, y]) => `<mxPoint x="${x}" y="${y}"/>`).join('')}</Array>` : '';
  edges.push(`<mxCell id="edge-${edges.length}" value="${escape(label)}" style="edgeStyle=orthogonalEdgeStyle;rounded=0;html=1;endArrow=block;strokeWidth=2;strokeColor=${color};fontColor=${color};fontSize=17;fontFamily=PingFang SC;labelBackgroundColor=#FFFFFF;dashed=${dashed ? 1 : 0};exitX=${pins[0]};exitY=${pins[1]};entryX=${pins[2]};entryY=${pins[3]};" edge="1" parent="1" source="${source}" target="${target}"><mxGeometry relative="1" as="geometry">${route}</mxGeometry></mxCell>`);
}

text('title', '03 / 服务间调用关系 · 同核双池，领域复用，底座共享', 50, 20, 1720, 65, 34);
text('legend', '实线：在线请求（响应沿原路返回）　绿色：App 确定性路径　虚线：配置或可选委派　容器：部署分组，非新增网关', 50, 85, 1720, 55, 20);
box('ingress', '用户 / 统一接入网关', '登录鉴权 · 按场景路由 · 流式连接', 620, 160, 510, 75, 'app');
box('apps', '场景应用服务', '', 400, 280, 940, 160, 'app', true);
box('search-app', 'ai-search-app', '搜索 Workflow / 交付 Owner', 425, 335, 425, 80, 'app');
box('rec-app', 'ai-rec-app', '推荐 Workflow / 交付 Owner', 885, 335, 425, 80, 'app');
box('trade', 'trade-service', '可信确认后报价 / 下单\n复用交易域；不由 Agent 授权', 1450, 295, 340, 125, 'domain');
box('runtimes', 'Runtime 服务分组 · 共享内核，独立部署 / 扩容 / 回滚', '', 400, 525, 940, 220, 'runtime', true);
box('search-runtime', 'search-runtime', '搜索根任务池 · 独立场景配置', 425, 585, 425, 75, 'runtime');
box('rec-runtime', 'rec-runtime', 'rec 根任务池 · 独立场景配置', 885, 585, 425, 75, 'runtime');
text('in-process', '池内模块：Skill / Prompt / Context / Loop / Tool Adapter / Delegation Manager', 418, 680, 905, 44, 19);
box('control', 'agent-control', '版本 / 白名单 / 配额 / 委派规则\n发布配置；不充当在线 Planner', 30, 540, 290, 160);
box('worker', 'child-worker（可选）', '内部专业子任务池\n初期可运行在所属 Runtime 池内\n继承模型 / 工具 / Memory 治理', 30, 825, 290, 160, 'optional');
box('agent-gateway', 'external-agent-gateway', '可选部署 / 复用网关\n鉴权 · 数据最小化 · 任务映射', 30, 1060, 290, 130, 'optional');
box('external', '跨部门 / 合作方 Agent', '独立服务与 SLA\n结果仍由根任务验收', 30, 1250, 290, 120, 'optional');
box('model', 'model-service', '统一模型入口\nLLM / Embedding / Reranker', 1450, 535, 340, 110);
box('memory', 'memory-state-service', '授权 Memory · 会话 · 检查点\n租户 / rootRun / childRun 隔离', 1450, 705, 340, 115);
box('domains', '领域服务分组 · 均为真实 RPC / HTTP 服务调用', '', 400, 960, 940, 180, 'domain', true);
box('search-domain', 'search-domain', '条件检索 / 相关性\n搜索团队', 420, 1020, 215, 95, 'domain');
box('rec-domain', 'rec-domain', '候选重排 / 集合策略\n推荐团队', 650, 1020, 215, 95, 'domain');
box('rag', 'knowledge-service', 'RAG / 引用证据\n知识能力 Owner', 880, 1020, 215, 95, 'domain');
box('facts', '商品 / 用户服务', '价格库存 / 规格\n授权画像与行为', 1110, 1020, 215, 95, 'domain');
text('remote', '两池按任务调用所需领域服务；Tool Adapter 为池内模块，不另加统一跳转服务', 410, 900, 920, 38, 19);
box('domain-data', '业务数据 / 检索索引 / 特征与排序基础设施', '各 Domain 维护所属索引及算法依赖', 425, 1245, 440, 115);
box('knowledge-data', '知识库 / 混合检索引擎', '原文 · ACL · 倒排 / 向量索引\n索引流水线独立异步更新', 905, 1245, 420, 115);
box('state-data', '状态库 / 记忆存储', '缓存 / 持久化 / TTL\n不是商品知识库', 1450, 945, 340, 115);
text('ops', '横向复用：IAM / 密钥 / Trace / 指标 / 日志 / 实验平台；各服务异步上报，图中不逐条展开', 400, 1415, 1390, 45, 20);
text('boundary', '边界：不允许两个根任务池递归互调；外部 Agent 不直连我方全量 Memory 或交易；图为建议目标部署，非 Demo 已上线清单。', 55, 1490, 1730, 55, 20);

edge('ingress', 'apps');
edge('search-app', 'search-runtime', '复杂任务 / SSE');
edge('rec-app', 'rec-runtime', '复杂任务 / SSE');
edge('apps', 'trade', '用户确认', [1, 0.2, 0, 0.2], [], '#56806A');
edge('search-app', 'search-domain', '直达 Domain', [0, 0.4, 0, 0.5], [[365, 367], [365, 1067]], '#56806A');
edge('rec-app', 'rec-domain', '直达 Domain', [1, 0.45, 0.5, 0], [[1380, 371], [1380, 940], [757, 940]], '#56806A');
edge('control', 'runtimes', '配置', [1, 0.5, 0, 0.43], [], '#7F8A95', true);
edge('runtimes', 'model', 'LLM', [1, 0.25, 0, 0.4]);
edge('runtimes', 'memory', '读写', [1, 0.82, 0, 0.4]);
edge('runtimes', 'worker', 'childRun', [0, 0.7, 1, 0.3], [[350, 679], [350, 873]], '#9975A9', true);
edge('worker', 'agent-gateway', '共享委派出口', [0.5, 1, 0.5, 0], [], '#9975A9', true);
edge('runtimes', 'agent-gateway', 'remoteTask', [0, 0.92, 1, 0.65], [[335, 727], [335, 1144]], '#9975A9', true);
edge('agent-gateway', 'external', '受控任务', [0.5, 1, 0.5, 0], [], '#9975A9', true);
edge('runtimes', 'search-domain', '检索', [0.13, 1, 0.5, 0]);
edge('runtimes', 'rec-domain', '重排', [0.38, 1, 0.5, 0]);
edge('runtimes', 'rag', '知识证据', [0.63, 1, 0.5, 0]);
edge('runtimes', 'facts', '事实', [0.87, 1, 0.5, 0]);
edge('rag', 'model', 'Embedding / Reranker', [0.8, 0, 0, 0.82], [[1052, 855], [1415, 855], [1415, 625]]);
edge('memory', 'state-data');
edge('search-domain', 'domain-data', '', [0.5, 1, 0.25, 0]);
edge('rec-domain', 'domain-data', '', [0.5, 1, 0.75, 0]);
edge('rag', 'knowledge-data', '检索 / 引用');
outputs.set('docs/diagrams/agent-three-views-services.drawio', `<mxfile host="app.diagrams.net" type="device"><diagram id="agent-three-views-services" name="03 服务间调用关系"><mxGraphModel grid="1" page="1" pageWidth="1830" pageHeight="1570"><root><mxCell id="0"/><mxCell id="1" parent="0"/>\n${panels.join('\n')}\n${edges.join('\n')}\n${nodes.join('\n')}\n</root></mxGraphModel></diagram></mxfile>\n`);

let patch = '*** Begin Patch\n';
for (const [path, content] of outputs) {
  const previous = fs.existsSync(path) ? fs.readFileSync(path, 'utf8') : null;
  if (previous === content) continue;
  patch += previous === null ? `*** Add File: ${path}\n` : `*** Update File: ${path}\n@@\n${previous.trimEnd().split('\n').map(line => '-' + line).join('\n')}\n`;
  patch += content.trimEnd().split('\n').map(line => '+' + line).join('\n') + '\n';
}
process.stdout.write(patch + '*** End Patch\n');
