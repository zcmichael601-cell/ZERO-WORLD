# 通用 Skill Runtime：执行器、业务扩展与开发入口

## 1. 分层与调用链

`runWithContext()` 不再判断“极速推荐/满血推荐”，也不再直接依赖商品、用户或旅游的领域实现。

`SkillController` → `SkillStreamSessions` → `SkillEngine.executeStreaming()` → 本次执行的 `SkillBehavior` + `SkillExecutor`。

| 层次 | 代码目录 | 分工 |
| --- | --- | --- |
| 通用 Runtime | `application/skill/SkillEngine.java` | 生命周期、模型请求、预算、取消、工具白名单、协议修复、事件、结果汇总。 |
| 通用执行器 | `agent/runtime/execution/` | `AgentLoopExecutor` 提供模型自主工具循环；`WorkflowExecutor` 执行注册的顺序步骤。 |
| 扩展契约 | `agent/runtime/SkillExtension.java`、`SkillBehavior.java`、`SkillContext.java` | 注册业务工厂，创建每次运行独立的行为对象；开放受保护的工具调用、事实上下文和校验钩子。 |
| 内置业务编排 | `application/commerce/RecommendationWorkflows.java` | 极速版和满血版的工具顺序、模型决策节点、本地上下文整理。 |
| 内置业务约束 | `application/commerce/CommerceSkillBehavior.java` | 用户/商品边界、深度工具和演示用户映射、推荐上下文、结果校验及引用修复。 |
| 普通版循环约束 | `application/commerce/RecommendationLoopPolicy.java` | 按实际调用结果限制重复、分工具次数和剩余预算；不指定下一步必须调用哪个工具。 |
| 上下文压缩 | `application/commerce/RecommendationModelContext.java` | 推荐场景专用数据裁剪；通用 `SkillModelContext` 只统计请求体大小。 |
| 领域与工具 | `domain/`、`tools/`、`infrastructure/` | 继续承载商品规则、Tool 适配、RAG/用户信息等真实访问。 |

当前保留旧 `executionMode` 名称和 `SkillDefinition` 中的兼容查询方法，避免破坏已有配置和估时。它们由内置业务扩展解释，不再用于引擎中的推荐分支。

## 2. runWithContext 的主线

~~~text
behavior.initialHistory()：准备本次业务的初始上下文
  ↓
startExecution()：发布 RUN_STARTED，executor.start(context)
  ↓
每轮循环：
  checkRunBudget()：检查取消与总耗时
    ↓
  planRound()：executor.nextStep(context) 决定本轮类型
    ↓
  requestRound()：请求模型、记录耗时和响应
    ↓
  repairToolProtocol()：非法工具决策在预算内纠正，进入下一轮
    ↓
  MODEL_COMPLETED
    ├─ 有工具调用：受保护执行 → 记录事实 → 推进执行器 → 下一轮
    └─ 无工具调用：validateFinalAnswer()
                       ├─ 通用 JSON 解析
                       ├─ behavior.finish() 业务校验与规范化
                       ├─ 通用 requiredOutputs 兜底检查
                       ├─ 成功 → RUN_COMPLETED → 返回结果
                       └─ 可修复 → behavior.repairAnswer() → 下一轮
~~~

`startExecution()` 只发布事件并启动选中的执行器，不知道业务要查询画像还是包裹。`planRound()` 只认识 Agent、受限工具决策和最终回答三类模型步骤，不硬编码商品工具名称。

## 3. Workflow 节点

这是轻量的**顺序 Workflow**，不是分布式工作流平台；当前不提供并行 DAG、持久化恢复或外部 Agent 调度。

| 节点 | 作用 |
| --- | --- |
| `Tool(name, arguments)` | 代码确定工具和参数，通过 Runtime 工具边界执行；不消耗模型轮次，但计入工具预算。 |
| `Local(action)` | 本地业务逻辑，例如整理上下文或计算规则参数；节点前检查取消与总时限。 |
| `Decision(tool)` | 限定允许的工具，让模型决定参数；使用受限 JSON 决策，校验后由 Runtime 执行。 |
| `Answer(includeToolSchemas, rebuildContext)` | 最终模型生成节点，必须放在末尾；必需工具未完成时拒绝进入。 |

`includeToolSchemas` 只用于网关兼容；为 true 时仍设置 `tool_choice=none`，不代表允许最终阶段再调用工具。`rebuildContext=true` 会在首次进入答案节点时调用业务的 `finalAnswerHistory()`，以已有事实重建上下文；答案修复时不再次覆盖修复指令。

所有工具节点都必须走 `SkillContext.invokeTool()`，不能为了图方便直接调用远程服务来绕过 Runtime 的白名单、预算、取消和日志。`Local` 节点不是规避工具治理的后门；长任务需响应中断或主动调用 `context.checkActive()`。

## 4. 三个推荐 Skill 的运行方式

| Skill | 执行器 | 业务流程 |
| --- | --- | --- |
| 普通版 | `AgentLoopExecutor`（配置 `AGENT`） | 模型先加载 Skill，然后自主选择用户工具、商品搜索与 RAG，按证据决定补查或输出；不是固定三轮。 |
| 极速版 | `WorkflowExecutor` | 固定加载 Skill、画像、行为、商品 → 本地整理事实 → 1 轮模型生成。 |
| 满血版 | `WorkflowExecutor` | 固定加载 Skill、画像、行为 → 模型决定召回 → 模型选择深读 → 生成比较；正常约 3 轮模型。 |

执行器选择位于 `RecommendationWorkflows.executor()`；普通版返回通用 `AgentLoopExecutor`，不执行 Workflow 的预取数节点。极速/满血版固定步骤改在这里，普通版循环约束改 `RecommendationLoopPolicy`，不向 `SkillEngine` 增加业务条件。

普通版仍由模型规划，可交换画像与行为顺序、同轮请求二者、先查知识再补搜商品；不强制三轮或每个工具一次。动态白名单只反映依赖和预算：画像/行为各至多一次，搜索/知识各至多两次，相同 JSON 参数（忽略对象字段顺序）不重复执行。接近上限时为尚未完成的必需工具及最终回答收紧可用工具。批量请求先整体审查，任一请求重复或超预算则整批不执行，最多两次要求模型重新规划，日志事件为 `TOOL_PLAN_RETRY`。模型持续违反约束会安全失败，不保证所有模型输出都能成功。

开启流式工具协议时，Agent Loop 仍实时转发模型文本。使用 `stream-tool-calls=false` 的兼容模式时，仍可继续规划工具；如果模型在一次完整响应中直接给出最终 JSON，完整文本作为内容预览，校验通过后再发布最终结果，不为流式展示额外请求一次模型。内容事件始终早于当前轮的 `MODEL_COMPLETED`，保持网页流协议一致。仅在收敛、格式或引用修复阶段强制停止工具并进入最终回答。

`SkillBehavior.availableTools()`、`reviewToolCalls()` 和 `allowFurtherTools()` 是通用扩展点，默认不改变其他 Skill 行为；普通版在业务扩展中实现约束。已完成必需工具但仍有预算时，模型可以补查，也可以立即回答；预算收紧或答案纠正期间不再开放额外工具。总上限仍为 8 轮模型 / 8 次工具，没有靠放大预算掩盖循环。`StandardSkillTests` 验证真实默认 Agent 配置，原有 Agent 协议、取消、重试和 RAG 测试不再覆盖成测试专用模式。

`CommerceSkillBehavior` 接管原有商品引用校验、quoteId 原文回填、格式与引用修复，继续委托 `domain/recommendation` 下的校验器。普通版与满血版的修复差异仍保留。

普通版知识引用也使用 `K-<SKU>-<片段序号>`，避免让模型抄写长 UUID；原始 `chunkId` 和索引 `version` 仍保留用于追溯。编号只在本次工具返回的商品证据中有效，不能跨 SKU 借用。普通版 Agent Loop 保留最多两次引用纠正及最多两次格式纠正，仍受总模型轮数限制，只重写答案，不重跑工具或放松校验。

## 5. 新增 Skill 的两条路径

### 5.1 只需要 Agent 自主调用工具

实现并注册 `SkillTool`，新增本地 JSON 和 `SKILL.md`。使用 `executionMode: AGENT`，不填 `handler` 或填写 `generic`，无需 Java 业务扩展。

通用行为只强制最终结果是 JSON 对象并包含 `requiredOutputs`，不会强制包裹、售后等业务输出商品或 summary。具体字段类型和事实校验需要业务扩展负责，不能把“JSON 合法”当成“业务正确”。

### 5.2 需要固定 Workflow 或专业校验

新增 `application/<业务>/XxxSkillExtension.java`，实现 `SkillExtension`；由它为每次运行创建独立的 `SkillBehavior`，返回自己的执行器。不要把带游标的执行器或候选数据放在单例工厂字段里。

以下是包裹查询的示意扩展；`lookup_parcel` 需另外实现为 Tool。完整可运行版本在 `SkillExtensionTests` 中，不会自动加入生产 Skill 清单。

~~~java
public final class ParcelSkillExtension implements SkillExtension {
    public String name() { return "parcel"; }

    public SkillBehavior create(SkillContext context) {
        return new SkillBehavior(context) {
            public SkillExecutor executor() {
                return new WorkflowExecutor(List.of(
                        new WorkflowExecutor.Tool("Skill", run -> run.mapper()
                                .createObjectNode().put("command", run.skill().id())),
                        new WorkflowExecutor.Tool("lookup_parcel", run -> run.mapper()
                                .createObjectNode().put("parcelId", run.input().path("parcelId").asText())),
                        new WorkflowExecutor.Answer(true, true)));
            }
        };
    }
}
~~~

在扫描范围内的 Spring 配置类注册工厂 Bean：

~~~java
@Bean
public SkillExtension parcelSkillExtension() {
    return new ParcelSkillExtension();
}
~~~

`SkillConfiguration` 自动收集 `SkillExtension` Bean，并与 classpath SPI 扩展合并；无需修改 `SkillEngine` 或 `SkillCatalog`。内置 commerce 工厂通过 `META-INF/services/...SkillExtension` 注册。重名 handler 会在启动时拒绝，不能静默覆盖已有实现。

本地配置示例（作为 `version: 2` 配置中的一个 Skill）：

~~~json
{
  "id": "parcel-status",
  "name": "包裹状态",
  "description": "查询包裹并解释配送状态",
  "enabled": true,
  "handler": "parcel",
  "executionMode": "WORKFLOW",
  "instructions": "parcel-status/SKILL.md",
  "requiredInputs": ["parcelId"],
  "tools": ["lookup_parcel"],
  "requiredTools": ["lookup_parcel"],
  "requiredOutputs": ["status"],
  "maxModelCalls": 1,
  "maxToolCalls": 2
}
~~~

`maxToolCalls` 包含内置 Skill 加载。`SKILL.md` 仍需 name/description front matter，name 与配置 id 一致。配置了未知 handler 或不被该工厂支持的模式时，加载配置即失败，不会退回其他执行方式。

独立单元测试可以显式使用 `new SkillExtensions(List.of(new ParcelSkillExtension()))`，传给 `SkillCatalog` 的四参数构造器，不需要启动 Spring 或依赖内置 commerce 扩展。

## 6. 扩展钩子与安全边界

- `validateDefinition / validateInput`：工厂校验业务配置、输入；通用目录仍检查路径、标识符、工具存在性和预算范围。
- `initialHistory / finalAnswerHistory / toolData`：业务上下文、最终事实和模型输入裁剪；不要把不可信工具数据当作系统指令。
- `resolveTool / beforeTool / afterTool`：工具映射、业务参数约束、保存本次候选事实；必须保留用户隔离与候选范围等业务授权检查。
- `finish`：在发布成功前校验并补充业务输出。不可恢复的问题抛 `SkillExecutionException`；可纠正问题抛带格式/证据类别的 `AnswerFailure`。
- `repairAnswer`：提供下一轮修复上下文；Runtime 强制检查剩余轮次、总时限和每类最多两次的预算。业务实现可通过 `canRetry=false` 给出更具体的错误；即使扩展忽略它，也不能让 Runtime 超额继续修复。

网络重试留在 `requestModel()` 内，输出修复消耗下一模型轮次，网页续接留在 `SkillStreamSessions` 中，三者不混为一谈。流式正文仍是待校验草稿，不应提前当作有效业务结果。

## 7. 调试与验证

### 普通版重复生成与截断恢复

普通版保持 `AgentLoopExecutor`。每轮通过业务扩展 `prepareAgentHistory()` 写入一份最新执行状态：已完成工具、尚缺工具、可用工具、剩余预算与候选数量；旧状态被替换而非累计。要求模型直接选择能填补信息缺口的工具，已有充分依据就输出一次简洁 JSON，不展示思考过程或重复计划。正常情况下仍可补搜、补查，不按固定轮次串行执行工具。

`ModelOutputGuard` 在流式正文与 reasoning 两个通道独立检测连续重复片段；至少 6 次重复且覆盖不少于 768 字符才拦截，同时分别限制 48,000 字符，工具参数总量限制 65,536 字符。触发即取消上游，未完整生成的工具调用不会执行。完整响应模式也会校验正文与结束原因，但只能在上游响应返回后检测重复，等待期间仍依靠原有单轮和总时限。

遇到重复、输出量超限或 `finish_reason=length` 时，不再用同一份历史盲目重试。Runtime 丢弃本轮草稿，保留真实工具结果，由 `recoverGeneration()` 重整上下文：必需事实不足则继续自主 Agent 规划；事实已足够则仅重新生成答案，不重放工具。普通版最终上下文只放一份候选/商品证据，避免工具历史和事实副本重复。每次修正占用一个模型轮次，最多 2 次，仍受 8 轮模型、8 次工具和总时限约束。过滤终止不重试；没有正常终止标志的断流仍走原有连接恢复，绝不把截断 JSON 当成功结果。

日志定位：`MODEL_OUTPUT_REJECTED.reason` 区分 `LENGTH`、`REPETITIVE_CONTENT`、`REPETITIVE_REASONING` 等；`MODEL_GENERATION_RETRY` 记录修正次数、保留工具和下一阶段；`MODEL_STREAM_STOPPED` 包含结束原因、正文/reasoning/工具参数字数。日志不打印模型思考正文。网页收到既有 `MODEL_RETRYING` 事件清空无效草稿，下一轮重新展示，SSE 轮次与序号继续递增。

- 阶段错误：看 `planRound()` 的 ModelStep 和执行器游标。
- 工具异常：看 `executeRoundTools()`、`SkillContext.invokeTool()`、业务 `beforeTool()`。
- 商品输出错误：看 `CommerceSkillBehavior.finish() / repairAnswer()`，而不是向通用引擎添加商品判断。
- 日志继续按 requestId/runId 关联；MODEL、TOOL、VALIDATION、RUN 事件和 SSE 协议保留。

`SkillExtensionTests` 验证非购物 Workflow、默认通用 Agent、并发状态隔离、答案修复不重放工具/本地节点、白名单和取消。原有普通/极速/满血推荐与引用修复测试继续验证兼容性。
