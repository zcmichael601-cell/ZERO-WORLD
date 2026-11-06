# 模型驱动的 Skill 实验室

> **2026-10-30 结构调整**：当前仅保留 `skills.html` 的 AI 对话与 Skill 实验室。执行编排位于 `app.application.skill`，工具实现位于 `app.tools`，知识能力位于 `app.agent.rag`；只预加载 `productRagKnowledgeBase`。唯一启动类是 `AiRecOdinDemoApplication`，默认日志到当前进程控制台，不再启动 DongBoot/Magicbox 脚手架。下文的旧启动入口、独立 RAG 页面和平台日志路径属于历史记录；实际开发以 [开发指南](development.md) 为准。


## 个性化好物推荐满血版

新增独立的 `shopping-recommendation-deep`。在 Skill 页面选择“个性化好物推荐满血版”，可填写真实用户 UUID，也可选择演示用户。默认使用 `DEEP_RECOMMENDATION_WORKFLOW`：Runtime 加载本地指令及用户上下文，现有配置的真实模型决策检索参数、选择深读商品并生成推荐，不预置推荐答案。原版和极速版不变。

```text
Runtime 通过原有工具执行器加载 shopping-recommendation-deep/SKILL.md
  → 获取用户画像 + 历史行为（真实用户走 AI Memory，无需模型决定是否查用户）
  → 模型第1轮：提炼最多12个需求词，决策 search_products 参数
  → 满血检索实现：全目录词汇匹配 + 每个需求向量召回最多64片段
  → 按SKU去重、名次融合、多兴趣轮流选取，最多48件候选
  → 模型第2轮：选择最多8个SKU，决策 retrieve_product_knowledge 参数
  → 展开这8个SKU在同一版索引中的全部片段
  → 模型第3轮：流式推荐最多6件，说明用户适配、多维取舍及资料缺口，选择引用编号
  → 服务端按 SKU + evidenceId + quoteId 回填原文，再校验预算/引用并补齐商品事实
```

| 项目 | 原版 | 满血版 |
| --- | --- | --- |
| 召回 | 精确标签匹配，单次最多12件 | 标题/品类/标签匹配 + 向量语义召回，单次最多48件 |
| 商品知识 | 单次最多4件，每件取相关片段 | 单次最多8件，读取每件全部已索引片段 |
| 最终推荐 | 最多4件 | 最多6件，宁缺毋滥 |
| 对比 | 优势、限制各最多3项 | 各最多6项，另有最多8项带引用的比较维度 |
| 模型配置 | 沿用默认推理强度 | `reasoningEffort: high`，质量优先 |
| 正常模型轮数 | 随工具规划变化 | 3轮，5次真实工具执行（含Skill加载）；异常修正额外计轮 |
| 执行上限 | 8轮模型 / 8次工具 | 12轮模型 / 20次工具，含Skill加载 |

实现说明：
- 三轮是正常路径，不是成功保证或强制截断。12轮是含协议/输出修正的安全上限，不是目标轮数。前两轮从一开始就使用严格 `JSON_DECISION`，每阶段只暴露下一项未完成工具，避免原生工具协议兼容重试和重复获取用户数据。用户标识、权限、参数与工具预算仍由原执行器检查。
- Skill 保留 `search_products` / `retrieve_product_knowledge` 业务工具契约；Runtime 仅对满血版映射到 `search_products_deep` / `retrieve_product_knowledge_deep`。底层工具在 `SkillConfiguration` 注册，原版参数上限和压缩策略不变。
- 深度召回遍历全部有效目录商品，仅保留存在于索引的 SKU；融合词汇与向量名次，不把相似度解释为购买概率。多兴趣轮流选取有助于避免候选都集中在同一品类，但不保证穷尽所有潜在适配商品。候选仅供模型初选，最终仍需依据用户信息和知识判断。
- 对照预算、采集有效期、参考价有效性及明确缺货信息进行过滤，不为凑数量放宽约束。没有用户兴趣依据时不生成候选。商品覆盖统计由工具生成、服务端回填，页面展示索引范围、有效商品、相关候选和初选数量。
- 深读直接展开已发布向量索引保存的文档片段，不重新构建索引，也不把未索引文件混入证据；一次展开锁定同一快照版本。语义检索发生在候选发现阶段，深读无需为8件商品重复请求Embedding。候选阶段若索引并发切换则明确失败，避免混合版本排序。
- 证据包含 SKU、chunkId、索引版本、原文、商详链接及采集时间。默认模型输出 `text/evidenceId/quoteId`（维度项另有 `dimension`），服务端最终结构补齐 `quote`。最后一轮仅传递已深读的最多8件候选与完整证据正文、引用选项，不再重复48件候选和冗余来源元信息。来源保存在服务端并在校验后回填。引用校验只能验证来源与摘录，不等同于独立语义或事实核验；指令要求结论不超出页面依据。
- 页面参数缺失不等于商品缺点；只取得部分证据时使用 `PARTIAL_EVIDENCE`，完全不足使用 `INSUFFICIENT_EVIDENCE`。不编造测评、当前价格、库存、兼容性或购买意愿，不把跨品类商品排成统一性能榜。
- 不承诺固定完成时间；沿用全局超时、取消、SSE流式结果、进度与历史耗时校准。默认单轮600秒、整次1200秒、响应空闲180秒（可被现有配置覆盖），并非无限等待。默认工作流的最终分析初始估计为60秒（旧AGENT模式仍为240秒），随后按实际历史校准；估计值不等于超时或性能承诺。
- 新增诊断事件 `DEEP_PRODUCT_SEARCH_COMPLETED`、`RAG_PRODUCT_DISCOVERY_COMPLETED`、`RAG_SOURCE_EXPANSION_COMPLETED`，只打印数量、耗时及索引版本，不打印用户原文或密钥；继承现有请求/RUN关联和IDEA Console日志配置。

本地配置：`skills/recommendation.json` 以及 `skills/shopping-recommendation-deep/SKILL.md`。修改后重新构建并重启应用即可加载；使用当前启动RAG预加载，不需要网页手动重新建索引。

### 满血版引用校验与自动修正

默认流程不再让模型抄写长引文。每份证据提供 `quoteChoices: [{"id":"Q1","text":"连续原文"}]`，模型选编号，由服务器回填原文：

- `quoteId` 只在指定SKU的指定 `evidenceId` 内有效，不跨商品或跨片段猜测；未知编号报 `QUOTE_CHOICE_NOT_FOUND`。
- 服务端再次检查选项文字确实存在于原始证据且长度合法；模型同时提交不一致的 `quote` 会报 `QUOTE_SELECTION_CONFLICT`，不会覆盖后悄悄放行。
- 没有支持结论的选项就删除结论并调整证据状态，不用无关引用凑数；错误仍走有界修正，不关闭验证。
- 可将 `executionMode` 改回 `AGENT` 保留旧自主工具规划流程。以下原文空白兼容规则主要服务旧协议，正常工作流不需要模型复制 `quote`：

- 仅对空格、换行、制表符等空白序列的排版差异作兼容匹配，且必须属于指定SKU、指定证据。匹配成功后，将 quote 回填为索引中的连续原文；不改变数字、标点、否定词、规格，不跨片段或跨商品查找替代引文。
- 无法匹配时记录具体 JSON 路径、SKU、原因和引文长度。原因包括 `EVIDENCE_NOT_FOUND`、`PRODUCT_MISMATCH`、`QUOTE_LENGTH_INVALID`、`QUOTE_NOT_IN_SOURCE`；日志不打印完整引文、用户记忆或完整答案。
- 满血版最多追加两轮引用修正，沿用已经取得的用户资料、候选与完整知识，只让现有模型重新生成最终 JSON。修正轮设置 `tool_choice=none`，即使模型仍返回 tool_calls，服务端也不会执行；仍受 Skill 总时限、轮数和取消控制。
- 如果原结论没有依据，要求模型删除该结论，同步修改推荐理由、总结及证据状态，而不是随便换一段无关引文。两次修正仍无效则明确失败，不发布未经校验的结果。其他 Skill 不增加此自动重试。
- 页面复用 `MODEL_RETRYING` 提示“引用需核对，正在请模型修正”，清除未通过的旧草稿，再流式显示新一轮答案，不重复商品检索或构建索引。

排查时按 RUN/REQUEST 过滤 `PRODUCT_CITATION_VALIDATION_FAILED`、`PRODUCT_CITATION_REPAIR_REQUESTED` 和 `PRODUCT_CITATION_WHITESPACE_RESTORED`。其中 `issues[].path` 可定位到具体商品的 strengths、limitations 或 dimensions 条目。引用匹配验证的是来源和摘录一致性，仍不等同于对模型结论做独立语义核验。

### 最终答案格式异常恢复

当画像、商品召回、知识检索等必需工具均已完成后，模型仍可能将 `retrieve_product_knowledge` 等写为 `<tool_call><function=...>` 文本。旧逻辑在此阶段跳过工具协议纠正，直接解析 JSON，因此报“模型返回了无效 JSON”。这不是知识检索工具执行失败，也不能通过解析并执行这些 XML 来解决。

- 最终输出的无效 JSON、非对象、重复键、XML 工具文本或 `name/arguments` 工具决策，进入独立的答案格式纠正流程，最多两次，不与工具选择阶段的 `JSON_DECISION` 混用。
- 用实际已完成结果、候选商品及累计知识证据构造 `FINAL_ANSWER` 上下文，明确技能的工具步骤已经完成；不将错误XML复制成待执行指令。仍用原模型，修正答案继续流式输出。
- 工具 schema 保留用于网关兼容，但设置 `tool_choice=none`；模型若仍返回真正的 tool_calls，服务端拒绝执行。不得重复查询、重新建索引或访问其他用户。
- 格式纠正通过后仍校验必需字段、商品、预算和引用；满血版后续遇到引用问题仍可使用引用修正机制。所有纠正共享原 Skill 轮数、总时限和取消控制，不重置预算；极速版的一轮限制不会被绕过。
- 页面显示“模型最终输出格式异常，正在重新生成推荐答案”，清除旧草稿。服务端记录 `FINAL_ANSWER_FORMAT_INVALID` 和 `FINAL_ANSWER_FORMAT_RETRY`，包含轮次、原因、字符数、重试次数及已完成工具，不打印用户原文或错误正文。

### 满血版完整输出契约与恢复

满血版从首轮就提供统一 JSON 结构契约，明确必填字段、数组上限、0~100 整数分值、文本长度及证据状态。最终校验一次收集最多32条字段路径与原因（`validationIssues`），缺字段、类型错误、分值错误、空文本和证据状态冲突进入已有最多两轮格式修正，不再只改好第一处后因下一处直接失败。数量限制与契约使用同一份服务端定义；商品身份、预算和原文引用校验仍然保留。

修正上下文中，候选商品和原文证据各保留一份；引用修正也重建事实上下文，仅携带最近的失败草稿，避免多轮累计相同原文。证据ID简化为 `K-商品ID-片段序号`，原始 `chunkId`、索引版本和来源仍保留。每片段提供最多3条 `quoteOptions` 原文短句供模型核对，绝不将改写结论伪装成原文，引用匹配也不等同于语义正确性校验。

这些处理不会重跑已完成工具、解析执行文本工具指令或放宽事实校验；所有修正仍受 Skill 总轮数、总时限和取消控制约束。无法修复时返回明确失败，不发布未经校验的推荐。

### 满血版推荐数量超限恢复

深读最多8件是候选证据覆盖范围，最终推荐仍最多6件。模型有时会把7~8件已检索候选全部放入 `products`；原先这类业务约束错误不属于JSON格式或引用错误，会直接终止运行。

- 最终商品数量与每件商品的优势/限制/维度上限由 `ProductComparisonValidator.limits` 统一提供给校验器和运行时提示词。满血版保持 `products≤6`、`strengths≤6`、`limitations≤6`、`dimensions≤8`，原版仍最多4件。
- 满血版超出数组数量限制或对比字段不是数组时，进入已有最终答案纠正流程，与格式纠正共用最多两次重试，不增加无限循环。上下文携带 `outputLimits` 和 `constraintError`，后者包括 `path`、`reason`、`actualItems` 和 `maxItems`。
- 模型必须根据用户适配和证据充分度重新优选，并同步更新推荐理由及对比总结。服务端不简单截取前6件，不为了通过校验放宽数量、商品真实性、预算或引用限制。
- 页面提示“推荐结果条目超限（8项，上限6项），正在请模型重新优选”。修正期间沿用已有工具结果、继续流式输出；取消、总时限、模型轮数及禁止重复工具调用规则不变。仍超限则安全失败，不发布部分结果。
- 日志 `FINAL_ANSWER_FORMAT_INVALID` 的 `reason=COMPARISON_CONSTRAINT_INVALID`，`constraint` 可定位超限数组及实际数量；不打印商品原文或用户信息。商品身份、价格和预算校验仍保持失败即拒绝，不能借数量修正绕过。

## 个性化好物推荐·极速版

新增 `shopping-recommendation-fast`，保留原有完整推荐。页面可选择“个性化好物推荐·极速版”，支持手动填写真实用户 UUID（默认 `315c70c8f4a25681`）或切换演示用户；商品始终来自真实京东本地采集目录。

执行方式是 **Workflow 准备工具数据 + 一轮真实模型推荐**，不是提前返回固定答案：

```text
加载本地 SKILL.md（不请求模型）
  → get_user_profile → get_user_behaviors
  → 从用户资料中匹配目录已有标签 → search_products（最多 6 个候选）
  → 当前模型一次筛选、评分并生成简短理由
  → 校验真实 SKU、预算、重复、价格及库存 → 回填商品详情
```

- 不调用 `retrieve_product_knowledge`，不请求 Embedding，不输出深度优劣对比或原文引用。原有 RAG 启动加载策略不变。
- 复用既有 ToolCallback、取消/失败处理、流式回复、会话恢复、阶段耗时与诊断日志。工具准备阶段的 `modelRound=0` 表示工作流执行，不是模型调用；页面显示“准备流程”。
- 低成本召回采用目录标签的字面匹配，不增加一轮模型做查询改写，因此不如完整版擅长同义词、隐含需求。标签命中仅用于召回，最终模型仍需排除否定兴趣、无关或无依据的推荐；无匹配不编造商品。
- 为减少输入，每类 Memory 取接口返回顺序的前 6 条、每条最多 400 字，带 `partial`/`truncated` 标记；仅根据这份可见资料召回。原始返回仍保留供展示与校验。不输出用户身份、记忆原文或完整 Prompt 到日志。
- 最多推荐 2 件，理由每件不超过 25 字，不再生成独立需求分析长文（`needs: []`）。模型只生成 ID/理由/分数，商品详情由服务器回填。
- `recommendation.json` 中 `executionMode: PREPARED_RECOMMENDATION` 启用该准备流程；`maxModelCalls: 1`、`maxToolCalls: 4`（含 Skill 加载），三个业务工具必须按配置声明。模型传输错误仍遵守既有重试预算，重试不重新执行已完成工具。
- 极速版单独配置 `reasoningEffort: low`，对应模型请求的 `reasoning_effort`。已用 `kimi-k3-local-joybuilder` 和当前 `JoyAI-750B-joybuilder` 网关实际验证可正常返回正文；不修改现有 API Key/模型，也不改变完整版的默认推理强度。参数被网关接受不代表每个模型都实际使用同一种推理机制；以后换成不支持该参数的模型时可删除此字段，不静默换模型或伪造成功。

**5 秒是优化目标，不是超时阈值或完成保证。** 超过目标继续展示真实进度，不假报 100%，不自动降级为固定推荐。进度条使用阶段实耗和相同 Skill/模型/配置/用户模式的历史样本更新；模型排队、生成速度和 Memory 网络时间仍会影响总时长。

### 本地实测（2026-10-29）

基于真实 `SkillEngine.executeStreaming`、真实 Spring AI HTTP 模型调用、真实 AI Memory（REAL 模式）与真实京东目录；使用隔离测试进程，不重启正在运行的业务服务、不重复构建 RAG。总时长统计包含 Skill 加载、用户工具、召回、完整模型输出和结果校验；不包含 JVM 启动及浏览器网络。

| 模型 | 用户模式 | 完整执行耗时 | 首个正文片段 | 结果 |
| --- | --- | --- | --- | --- |
| JoyAI-750B-joybuilder | REAL（默认 UUID） | 3.700 s / 2.841 s | 1.329 s / 0.479 s | 均成功，各 2 件商品 |
| JoyAI-750B-joybuilder | DEMO（小雨） | 2.771 s / 1.943 s | 0.422 s / 0.299 s | 均成功，各 2 件商品 |

以上四次均在 5 秒内完成，但样本量有限，不是 P95/SLA 承诺。测试期间本地模型配置在 15:12 更新为 JoyAI，最终验证读取的是该配置，并非极速版代码替换了模型。此前使用 Kimi、低推理强度与短输出但尚未要求紧凑单行 JSON 时，四次实测为 6.448～8.183 s；模型差异和输出策略都影响速度，不能把所有改善归因于删除 RAG。

接口示例（JSON 字段 `username` 为兼容保留，内容是真实 UUID）：

```http
POST /api/skills/shopping-recommendation-fast/run
Content-Type: application/json

{"input":{"username":"315c70c8f4a25681","userDataMode":"REAL"}}
```

Web 页面使用同一 Skill 的 `session-stream` 接口。排查时查找 `WORKFLOW_STARTED`、`WORKFLOW_PREPARED`、`MODEL_REQUEST`、`MODEL_FIRST_CONTENT`、`RUN_TIMING`；`WORKFLOW_PREPARED` 记录工具准备耗时、候选数和模型输入字符数，正常成功只有一轮 `MODEL_REQUEST`。

## 商品推荐结合 RAG

完整版“个性化好物推荐”要求模型在查询候选之后调用 `retrieve_product_knowledge`，读取与 SKU 对应的商品知识，基于原文比较优势、限制和适用场景。服务启动时加载 RAG，执行时复用，不需要先到 RAG 页面操作；极速版不执行这一步。

每件推荐包含 `comparison`，对比结论包含 `comparisonSummary`；服务端验证引用商品和原文摘录，并回填 `knowledgeSources`，页面可以展开查看依据。未命中时明确资料不足，向量服务失败时停止，不返回固定对比答案。仅原始示例商品资料是本地数据，模型规划、Tool 执行、向量化、检索、结果回填和流式回复均为真实流程。

当前推荐商品资料来自 `ai-rec-odin-demo-main/src/main/resources/catalog/jd-products.json`，由 `JdProductCatalog` 生成对应 SKU 的知识文档。知识内容不提供实时价格/库存；`knowledge/products/` 中的旧示例资料不再作为真实京东推荐目录。配置和边界详见 [RAG 文档](rag.md)及 [商品采集说明](jd-products.md)。

## 真实执行与 Mock 边界

默认 AGENT 模式由大模型读取技能、生成原生 `tool_calls`，由 Spring AI 执行工具并将结果回传模型，最后由模型生成分析 JSON。极速版是上述明确声明的准备工作流，复用真实工具并交给模型生成推荐，不在 Java 中固定拼接最终推荐。

| 能力 | 实现 |
| --- | --- |
| 模型调用 | 复用当前配置的模型、网关及 API Key，真实 HTTP 请求 |
| 加载技能 | 模型调用 `Skill`，按需返回本地 `SKILL.md` 正文 |
| 查询画像 | `get_user_profile`，真实模式调用 AI Memory，演示模式使用预置画像 |
| 查询行为 | `get_user_behaviors`，真实模式调用 AI Memory，演示模式使用预置行为 |
| 需求预测 | 由大模型结合画像与行为分析，无固定规则预测 Tool |
| 商品检索 | `search_products`，模型提供标签与预算，查询真实京东 SKU 本地采集目录，返回来源、时间和参考价 |
| 北京天气 | `get_beijing_weather`，真实 HTTP 调用 Open-Meteo 预报接口 |
| 热门景点 | `get_beijing_hot_attractions`，真实 HTTP 读取携程北京公开榜单 |
| 排序与解释 | 由大模型选择商品、评估匹配分并撰写推荐理由 |
| 事实校验 | 服务端使用工具原始画像和商品事实，拒绝未知、重复或超预算商品 |

模型不可用、越权调用、超出调用预算、提前结束或输出格式错误均返回失败，不会回退到本地规则结果。返回的模型分数不是校准后的购买概率。

## 用户展示与内部诊断

用户页面采用业务文案：选择用户、兴趣与行为、个性化推荐、执行进度，不再显示 Mock、模拟资料或内部运行模式。Skill 的指令和工具描述不要求模型解释数据实现方式，回复聚焦结论、依据及必要的不确定性。

- 真实用户画像与历史行为来自 AI Memory，演示用户来自预置样本；商品候选来自真实京东页面快照，天气和景点真实联网；没有将样本或页面快照改称已经核验的实时业务接口。
- `SkillDataView.businessData` 在工具结果回传模型前移除本地样本的 `source=MOCK` 和说明性 `notice`，不改变兴趣、行为、预算及商品数据；最终画像也使用此业务视图。
- 服务端保留原始 `traces`、`mode`、工具 `dataMode` 供开发诊断。Web 的执行详情和 JSON 展示使用独立视图隐藏这些实现标记，不改写原始接口响应或自然语言内容。
- 商品标识为真实数字京东 SKU，候选查询与商品知识共用 `JdProductCatalog`。`SKU-001` 等只保留于旧测试夹具，不再被默认推荐工具加载。采集和更新见 `jd-products.md`。
- 保留 Open-Meteo / 携程来源、天气适用范围、预约提醒，以及预测不是购买概率等限制。隐藏实现标签不等于验证了数据真实性，也不保证模型推断一定正确。

本文档是开发说明，仍明确记录真实调用与样本数据的边界。扩展自定义 Skill 时，请沿用业务化表达，不向用户承诺数据已被独立核验。

## 运行

需要 JDK 19+、Maven，构建需要项目原有京东依赖仓库；模型执行需要现有模型网关可访问。无需配置新的 Key 或安装前端依赖。

```bash
bash scripts/run-skills.sh
```

页面：`http://127.0.0.1:8080/skills.html`。从“选择用户”下拉框选择已有用户，完整流程可能需要几十秒。

### Mock 用户选择

`GET /api/skills/users` 返回配置的真实用户 UUID，`GET /api/skills/demo-users` 返回演示用户。真实模式填写 AI Memory 用户 UUID（不是登录 PIN），默认值为 `315c70c8f4a25681`；接口为兼容现有 Skill 仍使用 `username` 字段，去除首尾空白后原样传入 AI Memory 的 `user_id`，不哈希、不转换账号，查询失败或无记忆不会自动回退到演示数据。配置键 `odin.memory.user-pins` 保留兼容，但其值应填写 UUID。

演示用户使用 `MockRecommendationTools` 中的独立画像与行为样本，仅支持下表用户及既有中文别名、英文用户名大小写，接口统一为规范用户名；未配置的演示用户名返回 400，不发起模型请求。

| 用户 | 日常兴趣 | 默认出游兴趣 | 差异化历史行为示例 |
| --- | --- | --- | --- |
| 小明 `xiaoming` | 跑步、户外、健康 | 自然风光、户外徒步、摄影 | 浏览跑鞋、搜索长城徒步、收藏湖畔摄影攻略 |
| 小雨 `xiaoyu` | 数码、办公、音频 | 主题乐园、科技体验、演出 | 浏览降噪耳机、环球度假区攻略、收藏科技展览 |
| 老王 `laowang` | 家居、咖啡、厨房 | 历史文化、园林、轻松步行 | 浏览咖啡器具、文物展览、收藏园林慢游攻略 |

每个用户有独立的五条模拟历史行为。选择后页面显示兴趣和可展开的历史行为预览；出游兴趣自动填入且可编辑。切换用户会替换兴趣和预览、清除旧运行结果；切换 Skill 保留所选用户。用户列表只提供数据预览，不绕过模型的画像和行为工具调用。

- 修改端口：`bash scripts/run-skills.sh --server.port=18080`。
- Maven 不在 PATH：`MVN="/path/to/maven/bin/mvn" bash scripts/run-skills.sh`。
- macOS 当前 JDK 过低时会尝试选择已安装的 JDK 19，也可显式设置 `JAVA_HOME`。
- 依赖已缓存时可加 `SKILL_DEMO_OFFLINE=1` 禁止 Maven 联网；这仅表示构建离线，**真实模型调用仍需联网**。
- 默认仅监听 `127.0.0.1`。轻量入口只提供 Skill API，不提供聊天 API；原有 `AiRecOdinDemoApplication` 也会加载 Skill 功能。

## 新增：下一步购物行为预测

在页面选择“下一步购物行为预测”，从下拉框选择用户即可运行 `shopping-behavior-prediction`。仍然使用现有模型与 API Key，真实调用 `Skill`、`get_user_profile`、`get_user_behaviors`，不开放商品检索、加购或下单工具。

该 Skill 输出：
- `predictions`：最多 3 个可能的下一步动作，包括浏览、对比、收藏、加购、购买或复购；包含品类、相对观察窗口、信心分、依据和另一种可能。这里只预测，不执行购物动作。
- `preferences`：最多 4 个购物偏好，如品类、功能、预算约束或决策方式，以及各自的依据与信心分。
- `uncertainties`：1~4 条数据缺口或不确定性。证据不足时允许不输出具体动作或偏好，而不是硬编预测。

每条结论的 evidence 引用实际工具结果的 source/path；服务端校验引用并补入原始 value，拒绝不存在或越权的路径。这保证证据值可核对，但不表示模型对证据的解释一定正确。信心分是模型主观评估，不是校准的购买概率，不能将浏览直接等同于确定购买，也不能由预算推断收入。行为数据只是样本，缺少购买记录不代表用户没有购买，指令明确禁止据此判断“未购买”或稳定的决策风格。

本地指令：`ai-rec-odin-demo-main/src/main/resources/skills/shopping-behavior-prediction/SKILL.md`。不需要新增数据工具；后续替换画像、行为数据源即可用于真实业务数据。

```bash
node scripts/smoke-skills.mjs http://127.0.0.1:8080 --live --prediction
```

## 新增：北京天气与兴趣出游推荐

页面选择“北京天气与兴趣出游推荐”，从下拉框选择用户，使用自动填入的出游兴趣或自行修改。Skill ID 为 `beijing-weather-travel`，指令位于 `skills/beijing-weather-travel/SKILL.md`。无需新的 Key，沿用已配置大模型，通过原生工具循环生成建议。

调用示例：

```bash
curl -X POST http://127.0.0.1:8080/api/skills/beijing-weather-travel/run \
  -H 'Content-Type: application/json' \
  -d '{"input":{"username":"xiaoming","interests":"历史文化、摄影、园林，喜欢轻松步行"}}'
```

模型必须加载 Skill，并调用四个工具：
- `get_user_profile`：补充画像，仍为 Mock。本次明确填写的兴趣优先，不能将模拟购物预算当作旅行预算。
- `get_user_behaviors`：查询所选用户的独立历史行为，仍为 Mock；模型结合旅游相关行为解释推荐。
- `get_beijing_weather({"days":5})`：真实 GET `https://api.open-meteo.com/v1/forecast`，固定北京市中心经纬度 39.9042 / 116.4074；工具支持 1~7 天，默认 Skill 请求从北京时间明天起五天。返回天气代码、最低/最高温、降水概率、雨量、最大风速，校验时区、单位、日期及数组完整性；拒绝缺失或过期预报。
- `get_beijing_hot_attractions({"limit":7})`：真实 GET `https://m.ctrip.com/webapp/xranking/cranking/crankingCity/100001406540.html`，解析公开页面的 `__NEXT_DATA__` JSON，不执行脚本。返回首屏至多 7 个北京景点、平台名次/热度/评分、评论数量展示值、摘要及来源链接。不是官方稳定 API，也不是全量景点或实时客流数据。

输出 `recommendations`（0~4 项）和 `travelTips`，逐条解释推荐日期、兴趣匹配、天气依据、出行准备及备选安排。服务端拒绝未查到的景点 ID、日期和重复景点，将工具原始景点、天气及用户填写兴趣回填结果。页面展示天气卡片、推荐、完整候选榜单、来源与抓取时间，日期为北京时间，抓取时间为 UTC ISO 时间。建议和自然语言理由由模型生成，引用校验不保证每句话的正确性。

### 数据来源、许可与运行限制

- 天气来自 Open-Meteo，数据采用 CC BY 4.0，页面保留来源归属与链接。免费 API 仅供非商业使用；商用前须按其许可订阅商业服务并调整天气客户端，不能复用大模型 API Key 作为天气凭据。文档 `https://open-meteo.com/en/docs`，条款 `https://open-meteo.com/en/terms`。本例不配置独立天气 Key。
- 携程为其平台公开榜单，页面首屏条数可能变化；结构变化、风控、限流或网络错误会使该次运行明确失败，没有静态景点或天气回退，不绕过验证码或登录。生产接入需确认数据使用授权，建议替换为获授权的稳定旅游数据 API。
- HTTP 使用 JDK HttpClient，不引入额外 SDK。外部 URL 固定且模型不能覆盖；禁止重定向；连接超时 8 秒、整个请求（含响应体）上限 20 秒、响应体上限 2 MiB，不自动重试。当前没有共享缓存或全局限流，生产应补充授权、限流、缓存、监控和费用控制。
- 北京城区网格预报不能代表长城山区，也不是官方预警。模型须考虑恶劣天气，优先室内或延期；不查询也不承诺营业时间、余票或预约成功，需出发前向官方确认。
- 输入、工具返回的精选摘要会发送至当前模型网关。未将整页 HTML、广告、脚本或账号凭据发送给模型。

## 模型配置复用

`SkillModelConfiguration` 读取主模块 `profile/ai.properties` 中的既有配置：

```properties
dong.spring.ai.joyai.chat.base-url=现有模型网关
dong.spring.ai.joyai.chat.completions-path=现有完成接口路径
dong.spring.ai.joyai.chat.api-key=现有Key
dong.spring.ai.joyai.chat.options.model=现有模型名称
```

没有独立的 Skill API Key。若 chat.api-key 未配置则读取父级 `dong.spring.ai.joyai.api-key`。实际文件和密钥不需要修改；不要将密钥放入 Skill 清单、指令或工具参数。

使用项目已有的 Spring AI `OpenAiChatModel` 兼容协议客户端连接**同一个 JoyAI 网关及当前配置的模型**，不是改用 OpenAI 的模型或地址。原因是 JoyAI SDK 直接依赖 Pfinder/DongBoot 运行时，兼容客户端可在独立演示中避免引入这些内部组件。原有聊天客户端、模型配置及记忆功能不变。Skill 使用独立客户端包装对象，不注册新的通用 ChatModel Bean，避免干扰原有自动装配。

## 本地配置

默认入口：`ai-rec-odin-demo-main/src/main/resources/skills/recommendation.json`。版本 2 使用以下格式，旧版 steps/outputs 规则流水线配置会明确报错：

```json
{
  "version": 2,
  "skills": [{
    "id": "user-insight",
    "name": "用户兴趣洞察",
    "description": "读取画像与行为，由大模型预测需求",
    "enabled": true,
    "requiredInputs": ["username"],
    "instructions": "user-insight/SKILL.md",
    "tools": ["get_user_profile", "get_user_behaviors"],
    "requiredTools": ["get_user_profile", "get_user_behaviors"],
    "requiredOutputs": ["summary", "needs"],
    "maxModelCalls": 6,
    "maxToolCalls": 5
  }]
}
```

- `tools` 是白名单，不代表 Java 执行顺序；模型根据指令决定调用顺序和参数。
- `requiredTools` 必须包含在白名单中，必须实际成功调用后才能返回成功。
- `requiredOutputs` 是最终模型 JSON 必须包含的字段，所有 Skill 还必须返回非空 summary。
- AGENT 模式 `maxModelCalls` 为 2~12；`maxToolCalls` 为 1~20，包括加载 Skill 的调用。极速准备模式固定一轮模型和四次工具调用。
- `instructions` 是配置文件目录下的相对 `SKILL.md` 路径，不允许路径穿越或远程资源。
- 指令使用 name/description front matter，name 必须匹配配置 id，正文指导模型操作、分析和输出格式。

例如，修改 `shopping-recommendation/SKILL.md` 中的推荐数量、排序偏好、分析重点即可改变模型执行要求，不需要修改引擎代码。配置中的次数上限和工具权限由服务端强制执行；自然语言指令本身并非确定性校验器。

外部配置要复制整个技能目录，保证相对路径存在：

```bash
cp -R ai-rec-odin-demo-main/src/main/resources/skills /tmp/odin-skills
bash scripts/run-skills.sh --odin.skills.location=file:/tmp/odin-skills/recommendation.json
```

也可设置 `ODIN_SKILLS_LOCATION`。清单仅支持 `file:` / `classpath:`。所有配置及指令在启动时读取，修改后重启生效；文件缺失、非法版本或未知工具均阻止启动。Web“查看配置”是只读展示。

## Spring AI 实现方式

参考 Spring AI Community `SkillsTool` 的渐进加载模式，做了适配现有 Spring AI 1.1 依赖的本地实现，**没有直接引入该社区组件**：

1. 初始请求仅包含所选 Skill 的名称、摘要及 `Skill` 工具 schema，不提前塞入完整指令。
2. 模型返回原生 `tool_calls: Skill(command)`；`ToolCallback` 返回 SKILL.md 正文及可用工具列表。
3. 下一轮才开放该 Skill 的业务工具。模型生成参数；`ToolCallingManager` 调用对应 callback，生成带 tool_call_id 的 `ToolResponseMessage`。
4. 工具结果连同会话历史继续发送给同一模型，直到模型输出最终 JSON。关闭模型内部自动工具执行，由外层显式循环管理轮数、记录真实模型响应与执行轨迹。
5. 模型的需求、评分、排序及理由原样参与展示；画像和商品价格由工具事实补充，而非模型生成。请求间隔离，不复用聊天记忆。

参考源码及文档：

```text
https://github.com/spring-ai-community/spring-ai-agent-utils/blob/main/spring-ai-agent-utils/src/main/java/org/springaicommunity/agent/tools/SkillsTool.java
https://docs.spring.io/spring-ai/reference/api/tools.html
```

当前只加载和使用明确配置的指令及数据工具，不自动提供 shell、任意文件读写或 SKILL.md 内的脚本执行能力。

## 新增工具

实现 `SkillTool` 的 `name()`、`description()`、`inputSchema()`、`execute(JsonNode)`，在 `SkillConfiguration` 注册 Bean，把名称加入 JSON tools，并在 SKILL.md 描述使用方法。真实数据工具还需覆盖 `dataMode()` 为 `LIVE`（现有 Mock 工具默认 `MOCK`），工具清单会暴露此标记。

`inputSchema` 是传给模型的 JSON Schema，实际参数和权限校验仍需在工具实现中完成。可将现有 Mock 数据源替换为真实业务接口，不需要更换模型调用引擎。新 SDK 若不在轻量启动脚本的依赖组白名单中，需调整脚本或使用完整应用启动。

通用 Skill 最终返回 JSON 对象和 summary；页面还识别 needs/products 等示例结构。`SkillEngine.finish` 对示例推荐结果做字段与商品事实校验，其他领域若需要更强的结构约束或专门视图，需相应扩展。

## API 和边界

### 实时执行进度与模型内容

Web 页面默认使用 `POST /api/skills/{skillId}/stream`（JSON 请求体与 `/run` 相同），通过 Fetch 读取 SSE 流。原有 `/run` 同步 JSON 接口保留兼容。

流式接口使用现有模型配置调用 `ChatModel.stream()`，上游请求为 `stream=true`，不是等待 `call()` 完成后拆字播放。Spring AI 先聚合原生工具调用的名称和参数分片，执行器在该轮完整结束后才校验并执行工具；正文分片则直接转发给页面。最终响应通过 `MessageAggregator` 汇总，保留原生调用 ID、模型元数据和 Token 用量，并继续执行既有结果校验。上游未提供正常结束原因、输出截断、超时或连接错误均不会继续执行工具或判定结果成功；不回退为同步请求或自动重试。

进度由执行器在真实节点直接发出，不轮询、不按定时器模拟步骤，也不展示模型内部思维链：
- `RUN_STARTED`：本次执行已经开始。
- `MODEL_STARTED` / `MODEL_COMPLETED` / `MODEL_FAILED`：真实模型请求开始、响应完成或失败。已响应不等于工具已完成。
- `TOOL_STARTED` / `TOOL_COMPLETED` / `TOOL_FAILED`：实际回调开始、返回或失败；包含真实 `toolCallId`、工具名、模型轮次。
- `TOOLS_RETURNED`：工具结果已写入对话；下一轮才提交给模型。
- `VALIDATION_STARTED`、`RUN_COMPLETED` / `RUN_FAILED`：结果校验和执行终态。校验失败不会提示成功。

SSE 的 `progress` 事件携带 `runId`、`skillId`、递增 `sequence`、上述 `type`、`status`、中文消息、服务端累计耗时和时间戳；只传流程信息，不传内部推理或密钥。最终 `result` 事件携带原有 Result 包装及完整 Run。流开始前的输入错误仍为 HTTP 400/404；流已经开始后的业务失败在 HTTP 200 流内以 `RUN_FAILED` 和 `result.code=500` 表达，保留已产生的调用轨迹。传输或服务异常使用 `error` 事件。

新增 SSE `content` 事件：`{"runId":"…","skillId":"…","sequence":1,"modelRound":5,"text":"{\"summary\":\"…"}`。只发送模型正文，不发送 reasoning 元数据、工具参数或工具结果。`sequence` 是本次运行独立于进度序号的内容序号，从 1 连续递增、跨轮次不重置；同一轮的内容事件位于 `MODEL_STARTED` 与 `MODEL_COMPLETED` 之间。浏览器校验运行标识、Skill、轮次及序号，并通过文本节点立即追加，保留空格、换行和 Unicode，不将模型内容解析为 HTML。

页面“大模型实时输出”区展示当前轮次内容。Skill 要求最终输出 JSON，因此生成中可能看到尚未闭合的 JSON 片段；新一轮开始清空上一轮预览，校验通过后自动切换为最终结果卡片。断流或校验失败时保留已收到的预览，并明确标为“未完成、不是有效结果”。首段内容出现前仍展示真实工具进度；模型未返回正文时不会伪造文字或展示内部思维链。

页面持续展示“正在查询画像”“已完成历史行为查询”“正在请求真实天气”等信息，同步更新工具卡片；完整过程保留到切换用户、Skill 或下次运行。只有收到完整 `result` 才确认成功；断线时保留已收到的过程记录，未完成工具标为“状态未确认”，不自动重发请求，以免重复调用模型产生费用。

执行任务使用有界弹性线程池，客户端取消订阅时尝试中断工作线程，并在下一次模型/工具调用前检查取消状态。已经发送给上游的请求不保证立即中止或免除计费；服务端也可能要到下一次写出时才发现浏览器断开。模型空闲、单轮、Skill 总时限默认分别为 180、600、1200 秒，可在 `skills/runtime.properties` 调整。服务端进度流无事件超时为总时限 +30 秒，MVC 异步超时为总时限 +60 秒，页面从 `/api/skills/runtime` 读取总时限 +90 秒作为整体等待预算；重试不重置预算。响应包含 `Cache-Control: no-store` 与 `X-Accel-Buffering: no`，如部署在反向代理后，还需关闭 SSE 响应缓冲并配置足够长的读取超时。

| 接口 | 用途 |
| --- | --- |
| `GET /api/skills` | 已启用技能清单 |
| `GET /api/skills/tools` | 已注册数据工具 |
| `GET /api/skills/users` | 可选 Mock 用户及对应兴趣、出游兴趣和历史行为，无模型调用 |
| `GET /api/skills/runtime` | 模型名称与执行模式，不含 Key |
| `GET /api/skills/{skillId}/instructions` | 只读查看本地指令 |
| `POST /api/skills/{skillId}/run` | 执行，body 为 `{"input":{"username":"xiaoming"}}` |
| `POST /api/skills/{skillId}/stream` | SSE 执行进度、模型正文分片及最终结果，请求体同上 |

响应沿用 Result 的 code/msg/data。成功 HTTP 200，data 包含 output、traces、modelTurns、modelCalls、model、mode。画像 / 预测技能 mode 为 `LIVE_MODEL_MOCK_DATA`；购物推荐及出游技能为 `LIVE_MODEL_MIXED_DATA`（样本画像与历史行为 + 京东商品快照 / 真实向量服务或天气 / 景点）；运行时概览为 `LIVE_MODEL_MIXED_DATA`。商品查询工具 dataMode 为 `JD_PAGE_SNAPSHOT`，知识检索为 `LIVE`。输入错误 400；不存在或禁用的 Skill 404；模型/工具/结果校验失败 500，保留已发生的交互轨迹。页面不展示内部思维链。

模型 HTTP 连接超时 10 秒、读取超时 60 秒，不自动重试；流式模型额外设置分片等待上限 60 秒，以及本次运行剩余时限（总计 240 秒）约束，取消时释放模型流订阅。每轮开始检查总运行时限 240 秒，正在执行的工具 HTTP 仍受其读取超时约束；浏览器等待上限 330 秒。旅游工具每次网络请求上限 20 秒。流式接口发现取消后阻止后续模型/工具调用；同步 `/run` 不具备订阅取消机制。

模型每轮请求都会消耗 Token；查看页面/清单/配置不调用模型。工具参数、指令与数据会发送到已有网关，不要放入密码或无权使用的数据。无鉴权的演示不应暴露公网；生产需要权限、脱敏、审计、流量和费用控制。

## 验证

```bash
mvn -pl ai-rec-odin-demo-main -am test \
  -Dtest=SkillEngineTests,SkillControllerTests,SkillModelProtocolTests,ShoppingPredictionTests,BeijingTravelTests,MockUserTests,SkillProgressTests,SkillStreamingTests,SkillPresentationTests,ChatControllerTests \
  -Dsurefire.failIfNoSpecifiedTests=false
node --test ai-rec-odin-demo-main/src/test/js/*.test.mjs
node scripts/smoke-skills.mjs http://127.0.0.1:8080
node scripts/smoke-skills.mjs http://127.0.0.1:8080 --live
node scripts/smoke-skills.mjs http://127.0.0.1:8080 --live --insight
node scripts/smoke-skills.mjs http://127.0.0.1:8080 --live --prediction
node scripts/smoke-skills.mjs http://127.0.0.1:8080 --live --travel
node scripts/smoke-skills.mjs http://127.0.0.1:8080 --live --travel --username=xiaoyu
node scripts/smoke-skills.mjs http://127.0.0.1:8080 --live --stream --travel --username=xiaoyu
```

Java 测试使用显式模型测试替身及本地 HTTP 协议服务，验证按需加载、工具协议、历史回传、失败不降级和调用上限，不消耗真实模型额度。冒烟脚本默认只验证静态资源、配置及非法输入；仅显式 `--live` 才调用现有真实模型。Node 测试要求 Node.js 22.7+，页面运行不需要 Node.js。
