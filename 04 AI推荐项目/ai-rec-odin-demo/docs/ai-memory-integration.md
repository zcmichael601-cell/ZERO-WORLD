# AI Memory 用户画像与行为工具接入

> **2026-10-30 结构调整**：当前仅保留 `skills.html` 的 AI 对话与 Skill 实验室。执行编排位于 `app.application.skill`，工具实现位于 `app.tools`，知识能力位于 `app.agent.rag`；只预加载 `productRagKnowledgeBase`。唯一启动类是 `AiRecOdinDemoApplication`，默认日志到当前进程控制台，不再启动 DongBoot/Magicbox 脚手架。下文的旧启动入口、独立 RAG 页面和平台日志路径属于历史记录；实际开发以 [开发指南](development.md) 为准。


依据用户提供的 JoySpace《AI Memory 接入文档》（文档 ID：`XTokTuLGZj9qIlWQCDxD`），2026-10-27 接入。本次使用 AI 搜 Memory `/search`，不是此前需要 clientId 的 `joy-tool /v1/memory/query`。不伪造 clientId，不更换现有大模型或密钥。

## 执行链路

2026-10-29 标识口径修正：AI Memory 的 `user_id` 实际使用用户 UUID，不是登录 PIN。页面统一显示“用户 UUID”，原样传递该标识，不做 PIN→UUID 转换。为兼容现有 Skill 和调用方，入参字段仍叫 `username`；真实模式填写 UUID，演示模式仍填写原来的演示账号。

`页面输入/选择 UUID → Skill runtime → LLM 调用用户工具 → AiMemoryClient POST /search → 归一化结果 → LLM 分析 → 校验 → 页面`

- `get_user_profile` 查询 `long_term_memory`；`get_user_behaviors` 查询 `origin_memory`。两个工具各发一次 HTTP 请求，均声明 `LIVE`。
- 参数仍为 `username`，值为原始用户 UUID，转换为 HTTP 字段 `user_id`；保留大小写，不把昵称转换为其他账号。模型只能查询本次输入的 UUID。
- 默认注册的两个用户工具仍为真实 AI Memory 工具；没有自动样本回退或跨用户缓存。个性化好物推荐仅在请求明确选择演示模式时，按本次执行切换这两个工具，不修改共享注册表。

## 个性化好物推荐：两种用户来源

- **真实用户 UUID**：页面可手动输入任意获授权测试 UUID，默认填入配置中的首个账号；保留大小写，去除首尾空白，限 1～64 字符。画像和行为请求 AI Memory；无数据则明确说明，接口失败则失败，不自动改用演示用户。
- **演示用户**：下拉选择原来的小明（运动生活）、小雨（数码效率）、老王（品质居家），展示各自兴趣和历史行为。两个用户工具读取对应的本地样本，不请求 AI Memory。
- 两种模式均使用当前配置的大模型执行 Skill 和 Tool Calling，商品候选仍取自真实京东 SKU 快照，商品比较仍走 RAG；不会切换到虚构商品工具或固定模型答案。
- 其他 Skill 保持现有真实用户查询逻辑。两种模式分别积累耗时样本，避免演示查询速度影响真实模式的进度预估。

调用 `POST /api/skills/shopping-recommendation/run` 或 `/stream`，请求体分别为：

```json
{"input":{"username":"315c70c8f4a25681","userDataMode":"REAL"}}
```

```json
{"input":{"username":"xiaoming","userDataMode":"DEMO"}}
```

省略 `userDataMode` 默认使用真实工具，即便 UUID 是 `xiaoming` 也不会自动进入演示模式。`DEMO` 只接受 `xiaoming`、`xiaoyu`、`laowang`，未知模式或在其他 Skill 传入此字段会在调用模型之前拒绝。模型不能改变请求的数据来源或查询其他用户。

`GET /api/skills/demo-users` 提供演示列表；原 `/users` 仍仅返回配置的真实账号。`GET /api/skills/shopping-recommendation/estimate?userDataMode=DEMO` 可取得演示模式估时，默认仍为 `REAL`。

## 本地配置

文件：`ai-rec-odin-demo-main/src/main/resources/skills/ai-memory.properties`。

```properties
odin.memory.endpoint=http://ai-memory.jd.local/search
odin.memory.timeout-ms=10000
odin.memory.limit=20
odin.memory.user-pins=315c70c8f4a25681
```

`odin.memory.user-pins` 是为兼容旧部署保留的配置键名，值实际为用户 UUID；用英文逗号配置多个**获授权测试用户 UUID**。下拉接口仅返回 UUID 和来源，不在页面加载时查询画像；选项不保证账号有数据。两个启动入口共用配置，可用 Spring 命令行参数覆盖。修改后重启服务并刷新前端。

UUID 按 AI Memory 的业务标识处理，例如 `315c70c8f4a25681`；只校验 1～64 字符、不能包含控制字符，去除首尾空白并保留大小写，不强制要求标准的 36 位带连字符 UUID，也不进行哈希或加密转换。

文档所述接口无需额外鉴权，仅用于有访问权限的内网。当前 Demo 的 UUID 和下拉框不是权限系统：正式上线必须由登录态确定 UUID、限制调用者访问范围，不允许公网匿名查询任意用户。建议保持本地监听，不公开运行结果或调试轨迹。

## 请求与响应

```json
{
  "user_id": "315c70c8f4a25681",
  "query": "",
  "search_cfg": {
    "long_term_memory": {
      "memory_type": "long_term_memory",
      "filters": {"role": "user"},
      "limit": 20,
      "vsearch": false
    }
  }
}
```

行为查询将分组和 memory_type 改为 `origin_memory`。本次使用标量查询，不写回或刷新记忆，不调用 AdaptMem；query + vsearch 的相似行为检索暂未接入。

支持 `results.<分组>.memories`、顶层 `<分组>.memories`、分组直接返回数组。顶层和分组存在 code 时均须为 0（数字或字符串）。缺失分组、错误状态或非法结构属于失败，不当作空数据。

| 工具字段 | 来源及约束 |
| --- | --- |
| `memories[].memory` | 返回的长期语义画像/行为原文，不生成替代内容 |
| `_id`、时间、source、score | 有值则保留，不补造 |
| `metadata` | 白名单保留 subtype、value、skuid、cid1/2/3；默认排除 reason，不暴露 OneID |
| 画像 `interests` | subtype2 为 4.1 / 4.2 的真实偏好原文；完整画像仍见 memories |
| `city`、`segment`、`budget` | 未提供可直接可靠映射的结构化值，返回 null，不由购买力或历史金额推断预算 |
| `travelInterests` | 未映射的结构化出游偏好为空，本次出游需求由用户填写 |
| 行为 `signals` | 原始行为类型 + 原始时间（如有）+ memory 原文，不把点击转换为购买，不编造次数 |
| `dataStatus` | 成功非空 `AVAILABLE`，成功空数组 `EMPTY` |

文档介绍了静态画像，但 `/search` 枚举未单列其类型。本次以公开的 `long_term_memory` 提供画像工具，不猜造额外 memory_type。时间保留原值，不擅自判断毫秒/秒或时区。Memory 内容是待分析数据，不是指令；可能包含推断、过期或缺失信息，不代表实时用户事实已被核实。

## 空数据与推荐

- 两份记忆均为空：洞察 needs、购物预测 predictions/preferences、个性化 products 均应为空；服务端拒绝无依据的非空结果。空数据不等于用户没有行为。
- 商品搜索支持 `budget: null`：不按预算上限筛选，但保留有效价格、快照有效期和缺货过滤；不能声称满足用户预算。模型不能自行填入预算。
- 无用户记忆时查询商品传 `interests: []`，返回空候选，并继续完成要求的商品/RAG 工具流程，不编造需求凑商品。
- 购物预测支持 `/memories/{index}/memory` 原文证据，null 预算和空兴趣不能作为有效证据。
- 北京出游可以基于用户本次明确填写的兴趣和真实天气/景点推荐，不编造历史偏好。

## 可靠性与日志

- 默认总等待 10 秒，最多可配置 30 秒；超时或中断取消请求，不自动重试、不跟随重定向。
- 只接受 HTTP 200，响应最多 1 MiB、记录数不超过 limit；归一化记忆最多 24000 字符。超限失败，不静默使用截断事实。
- 事件：`MEMORY_REQUEST_STARTED`、`MEMORY_HTTP_RESPONSE`、`MEMORY_REQUEST_COMPLETED`、`MEMORY_BUSINESS_REJECTED`、`MEMORY_REQUEST_FAILED`，记录类型、状态、条数和耗时，不打印 UUID、OneID、请求/响应正文。
- 用户可见工具轨迹包含该用户的真实记忆，属于用户数据，不应存入公开仓库或未授权日志系统。

## 验证

2026-10-27 使用测试输入 `ctlihua` 查询：顶层和两类记忆分组均为 `code: 0`，两个数组均为空。当时标识口径误认为 PIN；这条历史记录仅说明该原始输入的返回，不能用于判断对应用户 UUID 的记忆数据覆盖情况。

同日使用独立服务和现有 `GLM-5.3-Local-joybuilder` 执行 `shopping-recommendation` 完整联调：5 轮模型请求、5 次工具调用（含 Skill 加载器），约 13 秒返回 `SUCCESS`。画像和行为来源均为 `AI_MEMORY`、状态 `EMPTY`，预算为 null，商品查询及知识工具正常执行，最终商品数为 0；未使用预置用户数据。该验证没有改动模型配置，临时验证服务已关闭。

同日新增双模式后，以 `xiaoyu + DEMO` 调用页面使用的会话流式接口，现有模型完成 5 轮交互、5 次工具调用并返回 `SUCCESS`，收到 400 个正文事件。商品检索和 bge-m3 RAG 均真实执行，最终推荐 4 个真实 SKU（100130785706、100323358084、100190157082、10124744154756），含推荐理由与经过证据校验的对比。158 项后端回归测试、111 项前端测试通过。另一次非流式 `/run` 联调在第 5 轮遇到模型调用超时；本次未修改原超时配置，不能把流式成功解释为非流式也已通过。临时联调服务已关闭。

`AiMemoryTests` 用本地 HTTP 服务验证请求、兼容结构、真实网络工具循环、错误状态、超时、体积上限、空数据、预算未知和证据校验；测试数据不用于生产。

```sh
mvn -pl ai-rec-odin-demo-main -am -Dtest=AiMemoryTests,SkillControllerTests,SkillStreamingTests,JdProductCatalogTests,ShoppingPredictionTests -Dsurefire.failIfNoSpecifiedTests=false test
node --test ai-rec-odin-demo-main/src/test/js/*.test.mjs
```
