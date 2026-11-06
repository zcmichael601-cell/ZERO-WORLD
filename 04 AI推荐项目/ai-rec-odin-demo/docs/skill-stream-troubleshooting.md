# 商品推荐停在模型分析阶段的排查

> **2026-10-30 结构调整**：当前仅保留 `skills.html` 的 AI 对话与 Skill 实验室。执行编排位于 `app.application.skill`，工具实现位于 `app.tools`，知识能力位于 `app.agent.rag`；只预加载 `productRagKnowledgeBase`。唯一启动类是 `AiRecOdinDemoApplication`，默认日志到当前进程控制台，不再启动 DongBoot/Magicbox 脚手架。下文的旧启动入口、独立 RAG 页面和平台日志路径属于历史记录；实际开发以 [开发指南](development.md) 为准。


## 2026-10-30：IDEA仍看不到RAG加载日志

本次直接检查IDEA进程48157：线程栈已进入 `RagStartupLifecycle → RagKnowledgeBase → RagEmbeddingTransport`，说明正在真实加载RAG；JVM的 `console_log_level=INFO`、`donglog_console_enabled=true` 也已生效，但当前进程没有输出agent_diag。不能再解释为“未加载RAG”或只让用户找另一个Console。平台控制台开关并不保证业务诊断logger最终有输出，之前轻量入口/测试成功也不能证明IDEA隔离入口正确输出。

两个main入口现通过 `ConsoleLoggingBootstrap` 默认设置JVM属性 `odin.diagnostics.output=console`。`ExecutionLog` 将已经脱敏的INFO/WARN诊断直接、逐行写到System.out，不依赖DongLog/Log4j2级别和Appender，也不再向平台logger重复发送同一条消息。带时间、级别和JSON内pid，可关联startupId/requestId/runId。平台其他日志不变，不打开全局DEBUG或HTTP原文。

重新Build、停止旧进程并从IDEA启动后，在该运行标签的Run/Debug Console搜索 `AGENT_LOGGING_READY`，确认 `diagnosticOutput=DIRECT_CONSOLE`、`infoEnabled=true` 和pid为当前进程，然后看 `SERVICE_STARTUP_LOADING`、`RAG_INDEX_PROGRESS`、`SERVICE_READY`。即使平台loggerInfoEnabled=false也不影响这条直接输出通道。若日志显示的是旧pid，就不是当前运行。按之前要求，RAG完成前HTTP仍不监听。

此模式的agent_diag以进程标准输出为准，**不保证写入 `~/logs/common-default.log`**；该文件继续保存平台日志。需保存诊断时可在IDEA启用保存控制台输出，或用Shell将启动命令重定向到文件。显式JVM参数 `-Dodin.diagnostics.output=logger` 可恢复原日志路由，此时重新受平台级别/Appender约束。`-Dconsole_log_level=OFF` 仅控制平台Console，不能关闭新的独立诊断通道；如需切回平台控制必须显式选择logger模式。

验证使用同一DongBoot/Magicbox隔离加载链路的独立日志探针，不启动用户的业务服务：在平台Console关闭、业务logger INFO关闭、默认字符编码为US-ASCII的情况下，仍完整输出18条诊断，中文UTF-8正常，RAG进度及SERVICE_READY各出现一次。探针使用1份测试资料和测试向量，仅验证日志链路，不代表重新执行1000件真实向量加载；用户原IDEA进程未重启。直接通道按UTF-8字节逐行写出并立即flush，避免依赖系统默认编码。后端283项、前端129项回归通过。

## 2026-10-30：RAG ERROR_ENVELOPE与启动前预加载

`InvalidResponse[ERROR_ENVELOPE]` 说明向量网关返回了HTTP成功状态但正文是error，而不是有效向量。仅凭这一摘要不能判断是鉴权、限流还是其他上游故障。旧实现只识别数字HTTP错误码，字符串业务码和缺失code会直接失败；现在识别字符串分类，对临时或未知错误有界重试当前请求，明确永久错误不重试。保持最多3次尝试和1秒/2秒退避，不重新建整库，不使用假向量或忽略失败片段。

按新的启动要求，预加载改为HTTP启动之前执行：两套索引成功后才开放HTTP端口，最终输出 `SERVICE_READY`。这替代下文历史记录中的“先HTTP后Runner预加载”方案。加载期间无法打开网页是预期行为，必须看控制台进度，不要为访问页面关闭预加载。

按startupId过滤 `agent_diag`：资料读取和切分看 `RAG_DOCUMENT_LOAD_*`；批次范围看 `RAG_VECTOR_BATCH_*`；请求、耗时与重试看 `RAG_EMBEDDING_*`；总体完成比例和预计剩余时间看 `RAG_INDEX_PROGRESS`。错误事件新增 `category`、attempt、批次范围；重试事件新增nextDelayMs。日志不打印密钥、商品原文或上游错误正文。

持续失败时检查分类：`PERMANENT_GATEWAY_ERROR` 需核对模型服务权限/参数/配额；`TRANSIENT_GATEWAY_ERROR` 或 `UNKNOWN_GATEWAY_ERROR` 耗尽预算仍失败时，携带startupId、upstreamCode和时间向向量服务排查。代码不能修复持续不可用的外部服务，不会跳过校验后声明启动成功。IDEA重新Build并重启后生效。

真实bge-m3验证中也出现同类错误：2026-10-30 11:51:27，HTTP 200，error.code=0、外层code=1、type缺失，分类为UNKNOWN_GATEWAY_ERROR。批次577～608中的当前请求等待1秒后第二次成功，返回1024维向量，未重建此前已完成的批次；商品库最终完成1000份文档、2685片段。对应startupId为 `7f94bb6c-ff83-4a3f-98dc-a975c12ec601`。这是本次实测的错误，不能仅凭原摘要确认与此前失败的上游原因完全相同。

本次两套索引均完成1000份文档、2685片段，预加载402.644秒；加载期间轮询116次未开放HTTP。11:57:28依次出现 `RAG_STARTUP_COMPLETED`、`HTTP_SERVER_STARTED`、`SERVICE_READY`，随后Skill/RAG页面均返回200，RAG状态ready=true。全程5373次向量请求、1次失败重试恢复；未改变模型或密钥。耗时仅代表本次环境，不是启动时限承诺。后端280项、前端129项回归通过。

## 2026-10-30：满血版引用不匹配、执行达到10轮

`QUOTE_NOT_IN_SOURCE` 表示指定证据内找不到模型提交的连续原文，不等于RAG未建索引。旧自主规划流程通常需约6轮基础模型调用，再叠加工具协议兼容、JSON格式或引用纠正，可达到8～10轮；继续加重试次数或超时不能解决抄写失真。

- 默认切换 `DEEP_RECOMMENDATION_WORKFLOW`：Runtime 使用原有真实工具加载Skill、画像和行为；模型第1轮决策召回、第2轮选择深读SKU、第3轮生成推荐。前两轮直接使用严格JSON工具决策，避免先失败再切协议；已完成工具不重复执行。
- 模型输出同SKU证据的 `evidenceId + quoteId`，服务端从 `quoteChoices` 回填连续原文，再执行原有身份、预算和引用校验。未知编号、跨SKU、与原文冲突仍拒绝发布；模型结论是否真正得到原文支持仍需评测，不能由摘录匹配单独保证。
- 最终轮仅发送已深读的最多8件候选、证据正文及选项，不重复48件候选和来源元信息；深读完整度与高推理强度不变。正常3轮，发生有界修正仍会额外计轮；12轮配置是上限，不是目标。
- 按RUN查看 `DEEP_WORKFLOW_STARTED`（`modelCallsPlanned=3`）、`DEEP_WORKFLOW_USER_READY`、`MODEL_REQUEST`、`PRODUCT_CITATION_VALIDATION_FAILED`。出现后者时查看具体path/reason，而不是盲目重建索引；`QUOTE_CHOICE_NOT_FOUND` 检查编号，`QUOTE_SELECTION_CONFLICT` 检查是否仍在同时生成quote。

重新构建并重启服务，等待 `SERVICE_READY` 后测试。原版和极速版不受此模式修改影响。

本次隔离验证（2026-10-30，现有JoyAI配置、真实bge-m3、1000件商品/2685片段索引，使用页面相同SSE解析器）：

| 演示用户 | 模型轮数 / 工具次数 | 总耗时 | 推荐商品 / 校验引用 | RUN |
| --- | --- | --- | --- | --- |
| xiaoming | 3 / 5 | 25.835秒 | 3 / 6 | `a1174d3d-76b9-40ae-9eb4-b481eba23d93` |
| xiaoyu | 3 / 5 | 29.437秒 | 2 / 8 | `aa0f558a-9cb5-4329-8f2f-d3d9675cee14` |

两次均收到多条真实流式正文、无模型重试，原始模型结果仅选择quoteId；最终每条quote均与对应SKU/证据的选项原文完全一致。耗时不含启动建索引，演示用户不包含真实AI Memory网络延迟；这只是两次实测，不是P95或延迟保证。后端262项、前端129项回归通过。

## 2026-10-29：校验之后出现 ROUND_MISMATCH

如果浏览器报“模型等待状态与当前轮次不匹配”，且最后进度是 `VALIDATION_STARTED`，应先检查前端协议状态，而不是扩大模型超时或重新构建索引。

服务端的合法事件顺序为：

```text
MODEL_STARTED(6) → content(6) → MODEL_COMPLETED(6)
  → VALIDATION_STARTED(6) → MODEL_RETRYING(6)
  → MODEL_STARTED(7) → content(7) → MODEL_COMPLETED(7)
  → VALIDATION_STARTED(7) → RUN_COMPLETED → result
```

旧页面在 `MODEL_COMPLETED` 清除了 `activeRound`，却要求所有 `MODEL_RETRYING` 都匹配 `activeRound`，导致引用、格式和数量纠正在校验后被误判为错序。`requestSkillProgress` 将此视为不可重试的协议错误，并发送 DELETE 取消会话，表现为服务端仍可正常纠正、页面却执行失败。

修复后分别记录正在生成、刚完成和正在校验的轮次：
- `MODEL_WAITING` 和正文只能属于正在生成的轮次。
- `MODEL_RETRYING` 可属于正在生成的轮次（连接/工具协议重试），或已完成并进入校验的同一轮次（答案修正）。
- 校验后的重试不会重新开放旧轮正文；必须收到下一条 `MODEL_STARTED` 才能接收新内容。迟到正文、其他轮次的重试、失败状态的重试仍被拒绝。
- 同一会话重连会从完整事件回放中重建上述状态；页面回调仍按序号去重，不重新执行工具。

前端入口及整个模块导入链已统一更新为 `v=20261029-round2`。重启应用并刷新 Skill 页面，浏览器 Network 中应加载 `skill-progress.mjs?v=20261029-round2`。若仍为旧版本，请强制刷新或检查静态资源代理缓存。`ROUND_MISMATCH` 是前端诊断码，不等于服务端一定打印了同名事件；用页面 REQUEST/RUN/SESSION 对照服务端事件和取消日志。

## 2026-10-29：仅看到 RAG 启动预加载失败与 startupId

旧异常为了避免泄露上游正文，丢弃了原始 cause，但也导致单看 IDEA 异常栈无法区分 HTTP 错误、超时和响应协议异常。现在保留脱敏的异常类型链与状态码，失败信息同时带上知识库名及 startupId；完整安全诊断仍输出到 Console。

按同一个 startupId 查看 `RAG_EMBEDDING_FAILED` → `RAG_VECTOR_BATCH_FAILED` → `SERVICE_STARTUP_FAILED`：

- `HTTP 401/403`：检查向量服务自己的鉴权配置，不要只检查对话模型 Key；不会盲目重试。
- `HTTP 429/503` 或网络超时：当前向量请求默认有限重试，已成功的请求不会整库重放；持续失败则停止启动。
- `INVALID_DATA_COUNT` / `INVALID_VECTOR_*`：HTTP 成功但向量数据不符合约定；先校验并有限重试，不再让 SDK 因缺失 data 抛空指针。
- `SERVICE_READY` 才表示两套索引均已成功，不以 JVM 仍在运行或 HTTP 已监听代替完整就绪。

本次提供的 `5976ab7e-72d3-4df8-95e1-3eec2997be9f` 未在可访问日志文件中找到，因此不能仅凭包装异常断言当次根因。当前配置的小文本向量请求已验证成功；这只能说明核验时可用，不能证明此前没有短暂异常。

## 2026-10-29：服务进程启动后网页连接被拒绝

本次现场检查发现 8080 没有监听，启动线程停在 `RagStartupLifecycle.afterSingletonsInstantiated` → 向量模型请求。原因是 RAG 预加载阻塞了上下文刷新，内嵌 HTTP 服务还没启动，而不是静态页面丢失或 Skill 协议报错。

预加载改回 HTTP 启动后的高优先级 `ApplicationRunner`，继续启动自动构建两套索引、失败终止启动、完成后才打印 `SERVICE_READY`。新增 `HTTP_SERVER_STARTED` 区分“网页可访问”和“RAG 完整就绪”。页面可以先打开，但知识检索需等初始化完成。修改须 Build 并重启生效。

## 2026-10-29：演示用户第 4 轮出现文本工具调用

`<tool_call>` 出现在正文而不在标准 `tool_calls` 字段时，原实现直接失败，并提示关闭工具流式调用。但 JoyAI 默认已经关闭工具流式调用，非流式响应偶发相同格式时，仅再次设置 `stream-tool-calls=false` 无法恢复。

- 正常工具轮保持原有非流式原生调用方式，不强制 `tool_choice=required`：本次真实调用验证中，该模式也返回过没有原生调用字段的异常响应。全部必需工具完成后仍用 `none` 流式生成最终结果。
- 收到文本工具标记时丢弃该轮响应，保留合法历史及已完成结果，切换为专用的 `JSON_DECISION` 协议。模型仅输出 `{"name":"工具名称","arguments":{...}}`，每轮选择一个未完成工具。每次运行最多纠正两次，并计入原有模型轮数与总超时预算。
- JSON 决策请求不向网关发送 `tools` 或 `role=tool` 历史，而将技能指令、工具定义、用户输入、已完成结果放入普通消息，避开网关的函数调用模板。模型选择和参数生成仍是真实调用，不使用固定推荐或伪造工具结果。
- 只有在显式 JSON 决策模式中才接受这个严格格式。拒绝 XML、Markdown、重复字段、尾随对象、额外字段、未知或已完成的工具，以及截断响应。校验后转换为内部工具调用，继续经过原有用户标识、权限、参数、依赖和次数限制，并使用原工具执行器；不解析执行原始 `<tool_call>` 文本，不重启整次 Skill。
- 页面通过现有 `MODEL_RETRYING` 显示“正在纠正并继续执行”；异常轮不提前发送 `MODEL_COMPLETED`，避免前端因轮次状态不匹配中断 SSE。
- Console 的 `MODEL_PROTOCOL_RETRY` 记录纠正次数、下一步协议、已完成及待完成工具；`MODEL_REQUEST.toolProtocol` 可区分 `NATIVE` 和 `JSON_DECISION`。不记录模型原文、密钥和用户资料。纠正仍失败时明确提示协议校验问题，不再给出已无效的配置建议。

无需修改模型或密钥，重新编译并重启服务后生效。

## 2026-10-29：从 Kimi 切到 JoyAI 后聊天正常、Skill 失败

使用本地当前 `JoyAI-750B-joybuilder` 与相同 API Key、`/v1/chat/completions` 实际对比，确认是工具协议差异，不是简单的密钥失效：

| 请求 | 实际响应 |
| --- | --- |
| 非流式请求工具调用 | HTTP 200，原生 `message.tool_calls`，`finish_reason=tool_calls` |
| 流式请求工具调用 | HTTP 200，但调用以 `<tool_call><function=...>` 普通正文返回，`finish_reason=stop`，没有原生工具调用字段 |
| 已有 tool 消息历史、移除 tools 定义后流式生成 | HTTP 503，网关错误 `BAD_UPSTREAM`，模型模板报 `can only concatenate str (not "dict") to str` |
| 保留工具定义，使用 `tool_choice=none` 流式生成 | HTTP 200，正常返回最终正文 |

基础聊天无需工具协议，因此正常聊天不能证明 Skill 的原生工具调用链可用。极速版已在工作流准备完工具数据，模型输入没有原生 tool 历史，故其单轮流式推荐不受这两个问题影响。

兼容策略：

1. 加载 Skill、查询画像/行为、商品召回、RAG 等**工具决策轮**通过 `ChatModel.call()` 取得标准工具调用，再交给现有工具执行流程；仍有真实进度事件、等待提示、超时、取消和重试。
2. 所有 `requiredTools` 完成后，保留工具定义及原生消息历史，设置 `tool_choice=none`，通过 `ChatModel.stream()` 生成最终回答。正文仍随模型分片立即展示。
3. 最终阶段即使模型违背参数返回工具调用，服务端也拒绝执行，避免重复调用。不会解析 `<tool_call>` 文本并当成授权操作，也不会在部分执行后自动重放整次 Skill。
4. 默认对模型名 `JoyAI-*`（大小写不敏感）启用上述兼容模式；Kimi 等其他模型保留原有流式工具调用路径。不修改 API Key、模型名称或聊天客户端。

可在 `skills/runtime.properties` 或启动参数中显式覆盖：

```properties
odin.skills.model.stream-tool-calls=false
```

`false` 仅关闭工具决策轮的模型流式协议，**不关闭页面 SSE，也不关闭最终正文流式输出**。网关修复后可显式设为 `true` 验证原生流式工具协议。

需重新编译并重启服务才能加载新策略。检查 `MODEL_CLIENT_CONFIGURED` / `RUN_CONFIG` 的 `streamToolCalls=false`；同一个 RUN 内前几轮 `MODEL_REQUEST.transport=CALL`，最终轮 `transport=STREAM` 且 `toolChoice=none`。非流式等待期间记录 `MODEL_WAITING`、结束记录 `MODEL_CALL_STOPPED`。若模型仍返回文本工具标记，按本页的有限次协议纠正流程恢复，不会把 XML 文本误报为工具执行成功。

本地隔离进程使用当前真实 Key/模型、AI Memory 和京东目录验证通过：用户兴趣洞察 4 轮模型 / 3 次工具调用成功；完整版好物推荐 6 轮模型 / 5 次工具调用成功，真实调用 bge-m3 检索后返回 3 件商品并通过引用校验；极速版 1 轮模型 / 4 次工具调用成功，返回 2 件商品、约 3.1 秒完成。完整版隔离验证约 75.8 秒，其中约 51.6 秒在商品知识工具中首次构建测试进程内的全量索引；不是关闭流式后模型等待了 75 秒，也不代表已预热服务的常态延迟。没有替用户重启原业务服务。

## 2026-10-29：执行 Skill 看不到详细服务端日志

诊断调用点没有删除：模型请求与分片、工具执行、AI Memory、RAG、会话重连和最终汇总统一通过 `ExecutionLog` 输出 `agent_diag` 单行 JSON。不要只看 Spring AI SDK 的 `llm-request-detail.log`，也不要把旧测试进程的日志误认为当前服务的日志。

进一步排查发现 DongLog 控制台默认关闭（`console_log_level=OFF`），且存在多套日志门面与类加载器。现在两个 main 入口在日志初始化前默认启用 `console_log_level=ALL` 与 `donglog_console_enabled=true`；保留显式 JVM 覆盖及主入口原有文件日志。`ExecutionLog` 改用平台原生 Log4j2 API，避免诊断日志落入无输出的 SLF4J 绑定；`application.properties` 仍显式设置：

```properties
logging.level.com.jd.diviner.ai.rec.odin.demo.app.diagnostics.ExecutionLog=INFO
```

只开启这一个诊断 logger，不打开 HTTP wire、全局 DEBUG 或模型原文日志；日志仍不记录 API Key、完整画像、提示词、工具原文或推理内容。启动参数或环境配置可以覆盖该级别，不会在运行时强行覆盖运维设置。

启动时新增 `AGENT_LOGGING_READY`，包含 `loggingFacade`、`loggerImplementation`、`loggerFactory`、`infoEnabled`、`consoleLevel` 和 `dongConsoleEnabled`，可确认实际日志绑定。若 INFO 被关闭且 WARN 可输出，会记录 `AGENT_LOGGING_DISABLED`。此事件在单例初始化阶段、RAG 预加载前输出，**不等于服务启动成功**；仍须等 `SERVICE_READY`。`RAG_INDEX_PROGRESS` 每批输出已完成片段数、总数、百分比和预计剩余耗时；两库完成后再报告应用就绪。

重启应用后检查 IDE 控制台；本机完整 DongBoot 入口的公共日志位置是 `~/logs/common-default.log`（其他部署以实际日志配置为准）：

```bash
tail -F ~/logs/common-default.log | grep --line-buffered 'agent_diag'
grep -F '你的 runId' ~/logs/common-default.log
```

轻量入口 `scripts/run-skills.sh` 输出到启动终端；若使用 `> /tmp/skill.log 2>&1` 重定向，应查看该文件。按当前进程 PID、时间、`requestId` 和 `runId` 关联，不要只按类名搜索多次运行混在一起的历史日志。

重点事件：`RUN_CONFIG` → `MODEL_REQUEST` / `MODEL_WAITING` / `MODEL_RESPONSE` → `TOOL_EXECUTION_STARTED` / `TOOL_EXECUTION_COMPLETED` → `RUN_SUMMARY` / `RUN_TIMING`。模型等待时会持续记录分片、正文、推理及工具参数字符数；工具结束记录耗时和精简前后结果大小。模型正文与工具结果本身不会写入诊断日志。

验证包含日志链路回归和使用现有 DongBoot 依赖的隔离启动探针。没有替用户重启当前服务，新代码须重新编译并重启后生效。

## 2026-10-29：商品检索后模型分析慢

超时放大只避免提前失败，不会加快生成。需要按同一个 `runId` 区分真实工具耗时、模型首包等待、推理分片和正文生成，不能仅凭“第几轮”认定是 RAG 或模型故障。

本次对 `shopping-recommendation` 做了以下减负，保留现有模型、密钥、网关和真实工具流程：

- 仅精简发给模型的工具结果；完整结果仍保存在本次运行的候选、知识和调用轨迹中，商品卡与引用校验仍使用原始数据。
- 商品初选仅保留名称、价格及其时效提示、规格选项、库存边界、标签等决策字段，不重复携带完整参数、服务文案和展示字段。详细依据仍必须调用知识工具获取。
- 知识工具的模型视图保留所有证据的 `id`、`productId` 和完整 `text`，不截断原文，不丢掉否定条件或限制；来源链接、文档标题等重复元数据由服务端补齐。
- 非空 AI Memory 的 `memories` 原样保留类型和时间，不再重复携带其派生的 `interests` 或 `signals`；演示用户资料及其他 Skill 不变。
- Skill 默认检索 6 个候选、优先对比 2~3 个相关商品，一次提交知识查询；结论与引用保持简短，不为凑满条数编造缺点。这是模型执行指引，不是硬编码推荐结果，也不保证固定轮数。

查看以下诊断字段（均只记录数量与耗时，不记录用户记忆、提示词或模型推理原文）：

| 日志 | 如何判断 |
| --- | --- |
| `TOOL_EXECUTION_COMPLETED.elapsedMs` | 工具本身耗时；知识工具还可按 `RAG_RETRIEVE_COMPLETED.elapsedMs` 定位向量检索耗时。当前每个 SKU 会单独做一次查询，批量提交参数并不等于向量请求已并行。 |
| `rawResultChars` / `resultChars` / `savedChars` | 原始结果、模型视图和减少的字符数。 |
| `MODEL_REQUEST.toolResponseChars` / `promptPayloadChars` | 工具消息与提示词主要文本负载大小。原先只统计 `Message.getText()` 会漏掉工具结果；这些是字符数，不是 token 或 HTTP 请求字节数。实际 token 看 `MODEL_RESPONSE.promptTokens`。 |
| `MODEL_FIRST_CHUNK.elapsedMs` | 首个模型分片等待。过长时可能涉及网络、网关排队或模型预填充，客户端不能单独确定原因。 |
| `MODEL_FIRST_REASONING`、`MODEL_WAITING.reasoningChars` | 网关通过已识别的 reasoning 元数据返回分片时，仅累计字符数。持续增长但正文为零表示已收到推理输出；计数为零不证明模型未推理。 |
| `MODEL_WAITING.toolArgumentChars` | 正在接收工具参数，不应误判为正文卡住。 |
| `MODEL_FIRST_CONTENT`、`MODEL_STREAM_STOPPED`、`MODEL_RESPONSE` | 首段正文时间、流结束时间、输出大小及 token 用量；可区分正文前等待与正文生成时间。 |

重启后用同一 UUID、相同目录数据重复运行，比较各阶段耗时与输入/输出大小。不能仅用一次测试或空画像请求评估有商品对比的性能；本次离线回归不代表真实网关延迟已达标。

如果精简后仍主要耗在推理阶段，下一步应与 MaaS 确认当前模型支持的低推理预算/非思考调用方式，再单独验证工具调用和最终 JSON；不要直接套用 `enable_thinking=false`，本项目此前该模板参数出现过无有效正文。若首包等待长，应结合网关排队和模型服务指标排查。若 `RAG_RETRIEVE_COMPLETED` 才是主要耗时，再考虑有界并发或按索引版本缓存查询，避免把工具慢和模型慢混为一谈。

## 进入页面一直停在“正在加载本地配置”

这个状态属于页面初始化阶段，不等同于 Skill 执行或 RAG 建库卡住。2026-10-27 检查本机 8080 时，四个初始化接口及预计耗时接口都正常返回，页面模块依赖均为 200、JavaScript MIME 且与源文件一致；由于当时无法连接用户浏览器，不能仅凭截图确认具体浏览器异常。

页面现在用独立的普通脚本 `skill-bootstrap.js` 监测初始化，即使 `skills.js` 或依赖模块加载、解析失败，也能展示错误和“重新加载页面”；15 秒仍未初始化会提示检查 `SERVICE_READY` 和浏览器 Console / Network。初始化接口各限制 10 秒，失败信息含接口名称，保留“重新加载配置”入口。请求超时实现不再依赖 `AbortSignal.timeout`。

入口脚本及整套依赖使用统一版本参数，避免本次更新后继续命中旧模块缓存。版本号必须同步更新所有 Skill 模块引用；`skill-bootstrap.test.mjs` 会检查依赖图版本一致性。新增进度条模块不是 RAG 启动阻塞的证据。

排查顺序：

1. 确认已通过 Java 服务访问 `/skills.html`，不是直接打开本地 HTML 文件。
2. 查看服务端 `SERVICE_READY` 日志，确认启动及 RAG 预加载完成。
3. 刷新页面；如仍异常，点击“重新加载页面”，或使用浏览器强制刷新。
4. 查看页面错误提示；脚本问题查看 Console 中首条错误，资源问题查看 Network 中 `.js` / `.mjs` 是否返回 404、HTML 或错误 MIME。
5. 接口问题查看 `api/skills`、`api/skills/tools`、`api/skills/runtime`、`api/skills/users`。错误会保留具体接口名称，不再无限显示初始加载文案。

加载监测只用于恢复页面，不会创建 Skill 会话、重试工具或额外调用模型。

## 区分两个 SSE 连接

`HttpClientSseClientTransport - SSE stream observed an error` 来自 MCP 客户端。项目 `McpConfig` 为原有 JSF MCP 功能创建该连接，购物 Skill 的工具注册来自 `SkillConfiguration`，使用本地画像、商品目录、RAG 和直接模型调用，不经过该 MCP transport。

`java.io.IOException: chunked transfer encoding, state: READING_LENGTH` 表示读取分块 HTTP 响应时连接异常结束；仅凭这条日志无法判断是服务端主动断开、代理空闲超时还是网络问题，也不能证明购物 Skill 的模型连接同时失败。应按具体 transport、请求时间和 runId 关联日志，不要直接通过关闭 MCP 或扩大所有超时掩盖问题。

## 为什么看起来停在第五轮

模型调用顺序由模型决定，最终分析不一定固定在第五轮。完成候选查询和知识检索之后，当前 GLM 可能长时间没有返回可展示的 `content`。响应流中仍可能只有非正文分片；这些不会作为正文展示，不能冒充结果或泄漏内部推理。

原实现只在模型开始、完成时发送进度，中间长时间没有用户可见状态。原 60 秒 `timeout` 是响应分片空闲超时，不是单轮总时间：持续非正文分片会使该计时不断重置。此前整个 Skill 的流式等待仍有 240 秒总预算，并非无限等待。

2026-10-27 在当时运行的 8080 原进程上复现：同一真实模型完成推荐，6 轮总耗时约 192 秒；最终分析从第 54 秒开始，到第 172 秒才收到首段正文，即约 118 秒没有可展示正文。该次请求最终通过校验，证明“长时间无正文”可出现于成功请求，但不能据此认定用户先前的所有失败都不是网络问题。

## 本次改动

- 每 5 秒发送 `MODEL_WAITING`，展示本轮已经等待多久、是否收到响应分片、距最近响应的时间及是否已经收到正文。它是本地等待状态，不是伪造的模型执行阶段，也不表示模型一定会成功。
- 正文仍在收到时立即输出，等待事件不清空正文、不结束当前模型轮次。
- 2026-10-28 起，模型单轮默认最多 600 秒，同时受 Skill 1200 秒剩余预算约束；即使持续收到非正文分片也不能无限延长。流式响应分片空闲超时默认 180 秒。超时、取消或上游结束时释放计时器并取消上游订阅。
- 模型空闲/总时间超限、连接断开、流未完整结束分别显示安全错误，附模型轮次与 runId；日志包含阶段、耗时、模型名、错误分类，不打印密钥、提示词或上游原始错误正文。
- 模型和会话现已增加有界重试，具体见下文“异常重试与会话恢复”；工具不直接自动重试。不关闭原有 MCP 功能，不修改模型、密钥或网关地址。

## 可选配置

```properties
odin.skills.model.idle-timeout-seconds=180
odin.skills.model.round-timeout-seconds=600
odin.skills.run-timeout-seconds=1200
odin.skills.model.progress-interval-seconds=5
odin.skills.model.max-attempts=3
odin.skills.model.retry-delay-millis=1000
```

默认超时配置放在 `ai-rec-odin-demo-main/src/main/resources/skills/runtime.properties`，由 `SkillModelConfiguration` 加载，也可通过启动参数覆盖。空闲超时上限 600 秒、单轮上限 1800 秒、Skill 总时限上限 3600 秒、进度间隔上限 30 秒，均必须为正值。尝试次数为 1~3，初始重试等待为 0~5000 毫秒。

HTTP 读取等待按单轮时限配置，不再让非流式请求沿用较短的分片空闲时限；非流式调用也受当前轮次及 Skill 剩余预算限制，超时尝试中断本地工作线程，但不能保证远端立即停止或免除计费。流式响应另外按相邻模型分片执行空闲检测，心跳不能重置模型空闲或总预算。

页面通过 `/api/skills/runtime` 读取 `clientTimeoutMs`，取 Skill 总时限 +90 秒（默认 1290 秒）；MVC 异步时限取总时限 +60 秒（默认 1260 秒），旧 `/stream` 无事件超时取总时限 +30 秒（默认 1230 秒）。本次未增加模型尝试次数、工具次数、连接建立超时或 AI Memory 超时。反向代理、网关及模型服务自己的限制仍需另行配置；放大应用预算不等于修复模型延迟。

## 如何验证

重新编译并重启当前应用，浏览器刷新页面以加载新的 SSE 事件解析代码。旧 Java 进程不会自动应用新代码。也可以另起不加载 MCP 的轻量 Skill 入口进行隔离排查：

```bash
bash scripts/run-skills.sh --server.port=18081
node scripts/smoke-skills.mjs http://127.0.0.1:18081 --live --stream --username=xiaoyu
```

执行时检查实际的等待提示和最终结果，而不是只看后台是否有其他连接的 WARN。若失败，记录 UI 返回的 runId，查找包含该 ID 的 `agent_diag` 日志，再核查模型网关及代理。MCP SSE 的连接稳定性需另外核查 `McpConfig` 中对应环境的 MCP 服务及中间代理，目前没有足够证据确认该警告的远端根因。

## 服务端调用链日志

诊断日志统一使用 `agent_diag` 前缀，后面是单行 JSON，写入应用现有日志输出，不单独创建文件。正常阶段记录 INFO，失败记录 WARN。无需开启 HTTP wiretap 或全局 DEBUG。若环境过滤了 INFO，可配置：

```properties
logging.level.com.jd.diviner.ai.rec.odin.demo.app.diagnostics.ExecutionLog=INFO
```

重新编译、重启应用并刷新页面后生效。启动事件 `MODEL_CLIENT_CONFIGURED` 包含实际模型名、连接/空闲/单轮/Skill 总超时、HTTP 读取等待、进度间隔及最大尝试次数，不记录密钥和网关 URL。

### 如何关联一次执行

- `requestId`：HTTP 请求 ID，成功接受的流式接口和非流式执行响应通过 `X-Request-Id` 响应头返回。解析后的无效业务请求会记录 `HTTP_REQUEST_REJECTED`，但沿用原错误响应，不返回该响应头。JSON 无法反序列化的请求尚未进入这些方法。
- `runId`：Skill 执行 ID，与页面进度和最终结果中的 ID 一致。模型、工具和被工具调用的 RAG 日志沿用该 ID。
- `round`、`tool`、`toolCallId`：定位第几轮和哪个工具调用；`ragOperationId` 区分每次索引构建/检索操作。
- `channel`：区分 HTTP、SSE、SKILL、MODEL、TOOL、RAG；`thread` 显示执行线程。关联上下文在异步模型回调中显式保留，工具内的 RAG 调用继承工具上下文，结束后恢复线程上下文。
- RAG 独立页面调用没有 Skill 的 `runId`，仍可按 `ragOperationId` 关联索引构建/检索操作。MCP SDK 自身的日志仍由原 logger 输出，不会被错误归到当前推荐 runId。

```bash
rg -F '页面中的runId' /path/to/application.log
rg -F '响应头中的requestId' /path/to/application.log
rg 'agent_diag.*(MODEL_ERROR|TOOL_EXECUTION_FAILED|RAG_.*FAILED|SSE_ERROR)' /path/to/application.log
```

### 关键事件与排查方向

| 事件 | 记录内容 / 用途 |
| --- | --- |
| `HTTP_REQUEST_STARTED`、`SSE_SUBSCRIBED`、`SSE_WORKER_STARTED` | 请求进入、订阅、工作线程开始，区分尚未执行与排队等待 |
| `RUN_CONFIG`、`RUN_STARTED` | 模型、执行模式、轮数/工具次数上限和超时配置 |
| `MODEL_REQUEST` | 本轮阶段、历史消息数、消息文本字符数、可用工具数及剩余执行预算；字符数不是 token 数，也不代表完整序列化请求大小 |
| `MODEL_FIRST_CHUNK` | 第一个模型响应分片延迟，不等于首个正文延迟 |
| `MODEL_FIRST_CONTENT` | 第一段可展示正文延迟 |
| `MODEL_WAITING` | 每个进度周期记录本轮耗时、真实响应分片数、距最近分片时间、正文字符数及剩余总预算；未收到分片时 `lastChunkAgoMs` 为 null |
| `MODEL_UPSTREAM_TERMINATED`、`MODEL_STREAM_STOPPED` | 上游订阅结束信号、等待预算、已聚合状态及累计响应量；`aggregated=true` 不代表通过结束标记和业务校验 |
| `MODEL_RESPONSE` | 本轮耗时、模型返回的 responseId/model、finishReason、token 用量、工具调用数及正文字符数；模型未提供的用量可能为空或零 |
| `MODEL_ERROR` | 模型失败所在轮次/阶段、耗时、安全异常原因链 |
| `TOOL_EXECUTION_STARTED/COMPLETED/FAILED` | 工具参数字符数、返回给模型的结果字符数、执行耗时和失败位置，不输出参数或结果原文 |
| `RAG_ENSURE_READY_STARTED/COMPLETED` | 索引是否已就绪、是否复用、等待锁耗时，识别首次构建或并发等待 |
| `RAG_INDEX_STARTED/COMPLETED/FAILED` | 索引构建耗时、文档数、切片数和索引版本 |
| `RAG_VECTOR_ADD_STARTED/COMPLETED` | 向量化并写入内存索引的阶段、原文总字节数及耗时；包含 embedding 调用与本地写入，不是纯模型延迟 |
| `RAG_RETRIEVE_STARTED/COMPLETED/FAILED` | 检索问题长度、是否按来源过滤、embedding 模型、topK/阈值、耗时、命中数及索引版本 |
| `VALIDATION_STARTED/COMPLETED`、`RUN_ERROR` | 区分模型已完成但结果校验失败；安全调用栈包含具体校验代码行 |
| `RUN_SUMMARY` | 执行状态、总耗时、模型/工具次数、候选数、知识覆盖商品数及正文事件数 |
| `SSE_RESULT_ENQUEUED`、`SSE_TERMINATED` | 结果入队、流结束信号和累计进度/正文入队数量；并不保证客户端实际收到或渲染 |
| `RUN_CANCELLED`、`SSE_WORKER_CANCELLED`、`SSE_ERROR` | 取消、超时或流异常；`CANCEL` 表示订阅被取消，不单凭它认定是用户关闭页面还是代理/服务端取消 |

例如第五轮长时间无结果：先筛选该 runId 的 `round=5` 日志。如果 `chunks` 持续增加、`contentChars=0`，说明收到非正文响应，不是完全断流；若 `lastChunkAgoMs` 持续增长，应排查上游停发或连接异常。若停在 `TOOL_EXECUTION_STARTED`，继续按 toolCallId 查 RAG 索引、向量化或检索事件。如果已有 `MODEL_RESPONSE`，但之后 `RUN_ERROR`，检查校验阶段而不是只排查模型网络。

### 日志安全与边界

本次新增的 `agent_diag` 日志不记录用户名、画像、历史行为、输入正文、prompt、工具参数/结果原文、RAG 原文、模型正文或内部推理。不记录 API Key、请求头、远端错误正文或异常 message。失败日志保留最多 12 层异常类型、每层最多 8 个堆栈位置，Spring HTTP 响应异常额外记录状态码；这样可区分 401/429/5xx、超时和 I/O 错误，同时避免异常信息夹带凭证。第三方 SDK 原有日志不经过该过滤，不应因此开启全局 DEBUG 或 HTTP 请求/响应原文记录。

日志不逐 token 输出，模型等待日志频率由现有 `progress-interval-seconds` 控制。所有耗时使用单调时钟统计；响应头、入队事件及调用栈仅供诊断。当前重试策略见下文，不直接重复工具调用或伪造业务输出。

## 异常重试与会话恢复

### 模型当前轮重试

生产配置默认每轮最多尝试 3 次（首次 + 2 次重试），退避等待 1 秒、2 秒。可将 `max-attempts` 设置为 1 关闭；SDK 内部 RetryTemplate 保持一次调用，由 SkillEngine 统一控制，避免两层次数相乘。为兼容原调用方，手工创建 `SkillModelClient` 的旧构造函数保持不重试，需要重试时显式传入 `SkillRetryPolicy`。

- 可恢复错误：网络 I/O 异常、响应空闲超时、没有完整结束标记的 EOF，以及 HTTP 408/429/500/502/503/504。
- 不重试：401/403 等鉴权/请求错误、JSON 解析错误、明确的 length/content_filter 结束、工具执行失败、业务校验失败和主动取消。不能通过重试绕过业务校验。
- 重试沿用本轮的同一份 Prompt 和此前工具结果；工具只在某次模型响应完整且协议校验通过后执行。不会重新启动整条 Skill，也不会由重试代码直接重放已完成工具；模型仍可能在后续轮次主动查询更多商品。
- 流式与非流式单轮时间预算均包含所有尝试和退避，且受 Skill 剩余总预算约束，重试不重置时间预算。若退避已超过剩余预算则直接失败。非流式调用还受 HTTP 读取超时约束。
- 重试前发送 `MODEL_RETRYING`。页面清除本轮失败尝试的临时正文，后续片段采用继续递增的序号，不把两次生成拼成一个答案。工具轨迹和先前已完成步骤保留。
- 服务端 `MODEL_ATTEMPT_STARTED/FAILED` 记录 round、attempt 和是否重试。`modelCalls`/`modelTurns` 仍统计逻辑轮次，实际请求次数需查看 attempt 日志。重试可能消耗额外模型 Token。

### 页面断流重连原会话

2026-10-29 排查发现独立的会话截止时间曾硬编码为300秒，即使模型总预算已配置1200秒，满血版仍会在5分钟被取消。现改为直接使用 `engine.clientTimeout()`（Skill总预算加90秒，默认1290秒），不再维护第二套较短的截止时间。`SESSION_CREATED` 记录 `runBudgetMs`、`lifetimeMs`、断线宽限期及缓存上限，便于与模型预算核对。

本轮隔离验证沿用现有真实模型和 bge-m3，使用两名演示用户执行满血版，均完成5次工具调用，返回4件通过校验的推荐。两次分别耗时约40.9秒、88.4秒；均自动恢复了模型文本工具输出，第二次还定位并修正了3处过短引文格式问题，未重跑工具。此结果不代表固定延迟或所有请求必然成功，启动索引耗时不包含在这两个执行耗时内；没有重启IDEA中的原服务。

页面使用专门的 `POST /api/skills/{skillId}/session-stream?sessionId=<UUID>&resume=false` 创建会话；断流时沿用同一 UUID，以 `resume=true` 重新订阅。新路径不会被旧后端误当作普通执行请求。旧 `/stream` 接口保留原行为，断开会取消执行，不具备会话恢复。

- 最多 3 次连接尝试，等待 1 秒、2 秒；每次连接建立阶段最多 5 秒，响应头收到后不会用这 5 秒限制整个响应流。页面整体期限由服务端下发，默认 1290 秒；重连不重置期限。
- 只对网络/读流中断和指定临时 HTTP 错误重连；鉴权失败、会话过期、前端协议/渲染异常、服务端业务失败和主动取消不重连。
- 服务端断开最后一个订阅后保留运行 30 秒，重连只补发同一会话已产生的消息；客户端按各自的进度/正文序号去重。多个订阅共享一份执行，不会各跑一次模型/工具。
- 重连回放从会话开头开始，不是按游标持久化续传；页面只呈现尚未处理的事件。断开期间已完成的结果也可以在保留期内取回。
- 恢复请求的会话不存在时返回 410，不创建新的运行；相同会话 ID 的 Skill 或输入不一致返回 409。首次请求是否被服务器接受不确定时，也不会以新 UUID 自动从头重跑。
- 最后一个订阅断开超过 30 秒、会话超出执行预算或缓存超限时停止执行；页面在放弃重试后尽力调用 `DELETE /api/skills/{skillId}/sessions/{sessionId}` 取消会话。取消请求失败则由服务端宽限期/总时限清理。
- 单进程最多保留 16 个会话，每个最多 32768 条正常事件、8 Mi 字符序列化数据；达到任一上限80%时输出一次 `SESSION_CACHE_PRESSURE`，超限时发送终止错误，不丢掉早期消息后悄悄重跑。完成/取消的会话保留 3 分钟后移除。字符额度不等于实际堆内存占用，部署时还须为对象、索引和模型上下文预留空间。
- 响应头 `X-Skill-Session` 和日志 `SESSION_ATTACHED/SUBSCRIBED/RECONNECT_WAIT/STOPPED` 用于关联重连。页面仍会给出 RUN/REQUEST，并在异常诊断中补充 SESSION。

这是 Demo 的单实例内存恢复机制，不保证进程重启、跨实例路由后恢复，也不是业务交易的“恰好一次”保证。生产环境需要将会话绑定到已认证用户，使用共享持久化会话/事件存储，并为下单等工具单独设计幂等键；不能只凭会话重连保障交易幂等。页面刷新后会创建新的用户操作，不自动恢复上一个页面的会话。

## 2026-10-27：模型超时与下游取消不是一回事

用户提供的 `runId=95c78572-114a-45b7-998e-17b5ee21d87c` 日志中，13:34:06.928 第 3 轮收到 38 个模型响应分片、本轮仅等待约 10 秒；13:34:06.942 先出现 `SSE_TERMINATED signal=CANCEL`，随后才出现 `MODEL_UPSTREAM_TERMINATED signal=CANCEL` 和 `RUN_CANCELLED`。因此该次执行不是“第五轮超时”，而是在第 3 轮由下游取消后停止模型订阅。13:38:35.651 的 MCP EOF 警告晚了约 4 分 29 秒，不能用它解释此前已经结束的请求。数据库指标上报和 JSF 注册日志也不是该 runId 的模型执行结果。

`CANCEL` 本身不能确定是页面刷新/关闭、前端解析或渲染异常、代理断连、Servlet 写出错误还是其他取消操作。不能仅凭此要求调整模型超时，也不能认定是用户手动取消。

同日在用户当时运行的原 8080 进程上，用页面同一份流解析代码进行真实调用：`runId=49a86084-3202-4b90-b526-353db86381b4` 成功执行 7 轮，耗时 228968 ms，收到 324 段正文。第 5 轮在总耗时约 125 秒时返回新的工具调用，最终第 7 轮在约 213 秒时出现首段正文、约 229 秒完成校验。该次调用证明原服务可以跨过第 5 轮，不代表所有浏览器/代理环境都不存在断连，也不代表模型延迟已优化。

为进一步定位取消发起方，新增两处诊断，不改变业务流程或扩大超时：

- 页面失败提示和浏览器控制台 `skill_stream_failed` 包含请求 ID、运行 ID、最后收到的轮次/进度以及错误分类。`PROGRESS_PROTOCOL_ERROR`、`CONTENT_PROTOCOL_ERROR` 等指向协议校验；`CLIENT_RENDER_ERROR` 指向页面更新；`STREAM_READ_ERROR` 指向读流异常或未收到最终结果即 EOF；`CLIENT_TIMEOUT`/`CLIENT_ABORT` 区分浏览器超时与中止；`RUN_FAILED` 是服务端业务失败。诊断对象不包含用户名、正文或原始异常详情。请求尚未收到响应时可能没有这些 ID。
- Servlet 异步链路增加 `SERVLET_ASYNC_TIMEOUT`、`SERVLET_ASYNC_ERROR`、`SERVLET_ASYNC_RESULT_ERROR`、`SERVLET_ASYNC_COMPLETED`，通过 `requestId` 与 `SSE_TERMINATED` 关联。它只观察异常，不吞掉框架错误，不修改完成/取消行为。HTTP 200 或异步完成回调不能单独证明 Skill 成功。

验证时重启后端并强制刷新页面，让页面实际加载新模块。保留浏览器 Network 中失败请求的地址、`X-Request-Id` 和页面完整错误提示，再按同一个 requestId/runId 检索日志。如果页面显示第 5 轮而日志属于第 3 轮，优先核对是否拿到了同一次运行的数据。浏览器自动化连接当次不可用，因此以上真实验证覆盖直连 HTTP 与页面流解析代码，不包含浏览器 DOM 渲染或用户访问代理的验证。
