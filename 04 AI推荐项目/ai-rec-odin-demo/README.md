# Odin · AI 工作空间

基于现有 Spring AI 底座，使用本地配置的真实模型执行对话和 Skill。唯一页面是 `skills.html`，包含 **AI 对话** 和 **Skill 实验室** 两个视图；切换视图不会重建页面或中断执行。

## 从哪里开始开发

| 开发内容 | 目录（Java 包位于 app 模块） |
| --- | --- |
| 新增 Skill 指令、工具白名单、执行预算 | main 模块 `src/main/resources/skills/<id>/SKILL.md`、`skills/recommendation.json` |
| 新增 Tool 名称、参数协议及适配 | `app/tools/{user,product,travel}` |
| 业务 Workflow、上下文和结果校验接入 | `app/application/<业务>`，当前为 `app/application/commerce` |
| 通用模型循环、执行预算与事件 | `app/application/skill`；执行器与扩展契约在 `app/agent/runtime` |
| 商品检索、推荐约束、对比引用等业务规则 | `app/domain/{product,recommendation,travel,user}` |
| Skill 加载、工具注册、模型配置对象、RAG 基础能力 | `app/agent/{skill,tool,model,rag,runtime}` |
| 外部 AI Memory / 天气等客户端 | `app/infrastructure/{memory,travel}` |
| Bean 注册、模型装配、RAG 启动预加载 | `app/bootstrap` |
| HTTP / SSE / 会话恢复 | web 模块 `web/controller` |

先看 [三种推荐 Skill 流程与效果对照](docs/recommendation-skills-developer-sharing.md)（三张流程图、普通 Agent Loop / 极速与满血 Workflow、空结果和引用排查）。最小 Tool/Skill/Workflow 开发示例、分层边界、启动与 SSE 调试见 [开发指南](docs/development.md)。两篇按 2026-11-02 当前代码重写，区分已实现能力与生产规划。

Joyspace 新版：[开发指南](https://joyspace.jd.com/pages/yPOTkgX9wDbVXPmBK2jE) · [三种推荐 Skill 介绍](https://joyspace.jd.com/pages/7betoo8mmmghMVRjIAxw)。

新增业务不再修改 `runWithContext()`：普通 Agent 只需 Tool + 本地 Skill 配置；固定 Workflow 或专业校验通过 `SkillExtension` Bean 和 `handler` 注册。见 [通用 Runtime 与可运行扩展示例](docs/skill-engine-execution.md)。

## 启动

需要 JDK 19+、Maven 和可访问的模型/向量服务。使用现有 Maven 父 BOM 管理 Spring Boot / Spring AI 版本，不再自动启动订单、JSF、MCP、数据库等脚手架服务。

```bash
mvn -pl ai-rec-odin-demo-main -am clean test
bash scripts/run-skills.sh --server.port=8080
```

也可以在 IDEA 运行 main 模块的 `com.jd.diviner.ai.rec.odin.demo.AiRecOdinDemoApplication`。重构后请重新导入 Maven、执行 Clean / Rebuild，再重启，避免 target 中残留旧 class 和页面。脚本可用 `MVN` 指定 Maven 完整路径，`JAVA_HOME` 指定 JDK；依赖已下载时可设 `SKILL_DEMO_OFFLINE=1`。

**商品 RAG 加载成功后才监听 HTTP**。看到控制台 `SERVICE_READY` 后访问 `http://127.0.0.1:8080/skills.html`；`#chat` 打开对话，`#skills` 打开实验室。`/` 和 `/index.html` 重定向到此页面。加载失败会终止启动，不会以未就绪状态提供推荐。

## 配置与数据

- 模型 API key、地址、Chat / Embedding 模型：`ai-rec-odin-demo-main/src/main/resources/profile/ai.properties`，对话和 Skill 共享当前模型，保留已有配置键。
- 超时：`skills/runtime.properties`；用户 UUID 与 AI Memory 接口：`skills/ai-memory.properties`。真实用户不自动回退成演示数据。
- 商品库：`catalog/jd-products.json`，保留原有 1000 个京东 SKU。是页面快照，不是实时报价；更新方法见 [商品数据说明](docs/jd-products.md)。
- 商品 RAG：启动预加载、向量检索、引用原文校验仍保留；独立 RAG 问答页面/API 已删除。见 [RAG 说明](docs/rag.md)。
- 对话系统提示词：`prompts/chat-system.txt`。浏览器保留聊天记录，服务端聊天窗口使用进程内存，不再连接旧 JDBC / JimDB 示例。

## 日志与测试

默认应用日志输出到进程控制台，诊断日志前缀 `agent_diag`。IDEA 在当前进程的 Run / Debug Console 搜索 `AGENT_LOGGING_READY`、`RAG_INDEX_PROGRESS`、`SERVICE_READY`；运行问题按 `requestId` / `runId` 排查。不再依赖平台日志目录，需落盘请使用 IDEA 保存控制台输出或 Shell 重定向。

```bash
mvn -pl ai-rec-odin-demo-main -am clean test
node --test ai-rec-odin-demo-main/src/test/js/*.test.mjs
node scripts/smoke-skills.mjs http://127.0.0.1:8080
```

Java 测试覆盖模型协议、工具执行、RAG、引用校验、启动门禁、SSE 会话与聊天记忆；Node 测试覆盖页面流式处理、导航、商品渲染和包依赖约束。测试使用隔离的模型/工具桩，不会以测试数据替换正式商品库。

现有 [Skill 说明](docs/skills.md)、[AI Memory 说明](docs/ai-memory-integration.md)、[排障记录](docs/skill-stream-troubleshooting.md) 保留。`docs/` 下架构调研和 Draw.io 图是历史设计资料，不作为当前代码布局的唯一依据。
