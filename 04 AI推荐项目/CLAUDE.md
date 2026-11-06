# AI推荐项目 · Odin Agent · 项目协作规范

> **代码仓库**：`~/Desktop/ai-rec-odin-demo/`（独立 git 仓库，IDEA 中开发，主分支 main）
> **本目录定位**：项目在 Zero World 工作区的大本营——背景、进度、约定、文档索引、代码镜像。
> `ai-rec-odin-demo/` 子目录是桌面仓库的**完整镜像**（2026-11-05 复制，317 文件/43M，仅排除 .git 和 .DS_Store）：排除 .git 是因为嵌套 git 会破坏 Zero World 的自动同步；开发真源仍是桌面仓库，改动后需重新 rsync 同步镜像。
> **工作区自动同步推送到公开 GitHub 仓库**（ZERO-WORLD），镜像中的两份文件已被 .gitignore 排除、只留在本地磁盘：`profile/ai.properties`（模型密钥）和 `skills/ai-memory.properties`（真实用户 UUID pin）。密钥一律不进公开仓库。
> 本索引 2026-11-05 经全量通读建立：桌面仓库 17 篇 docs + 4 篇 Joyspace 发布版 + 7 份 SKILL.md + 全部配置均已读入。

---

## 项目简介

基于 Spring AI 底座的个性化商品推荐 Agent 演示工程。**单进程 Java（JDK 19+ / Maven）**，唯一页面 `skills.html` 同时承载 **AI 对话** 和 **Skill 实验室** 两个视图。

核心内容是**三种个性化推荐 Skill 的对照实验**（同一 SkillEngine、同一份 1000 个京东 SKU 本地目录、同一模型配置）：

| Skill | ID | 执行模式 | 一句话区别 |
|-------|----|---------|-----------|
| 极速版 | `shopping-recommendation-fast` | PREPARED_RECOMMENDATION（固定 Workflow） | 代码先找商品（≤6 件），模型只做筛选，最多推荐 2 件，reasoningEffort=low |
| 普通版 | `shopping-recommendation` | AGENT（Agent Loop） | 模型自主理解需求、决定检索条件与工具调用，最多推荐 4 件、带引用对比 |
| 满血版 | `shopping-recommendation-deep` | DEEP_RECOMMENDATION_WORKFLOW | 固定关键阶段 + 逐兴趣向量召回（≤48 件）、8 件展开证据、最多推荐 6 件，reasoningEffort=high |

另有 4 个辅助 Skill（均注册在 `skills/recommendation.json` v2，handler 多为 commerce）：
- `shopping-recommendation-jev-cid` 类目意图版（handler `jev-cid`，WORKFLOW）：首屏模型决策一条「产品词｜品牌」调 select_cid1；后续屏有点击走 i2i 不请求模型，无点击再决策；商卡由 Diviner 直接供；模拟点击不写入真实 AI Memory
- `user-insight` 用户兴趣洞察（只读画像+行为，0~3 需求推断，不查商品）
- `shopping-behavior-prediction` 下一步购物行为预测（action 枚举 browse/compare/favorite/add_to_cart/purchase/repurchase，证据必须引用真实 JSON path）
- `beijing-weather-travel` 北京天气出游（真实调天气+携程景点榜，interests 为首要依据）

**四条设计原则**（详见开发指南）：
1. Skill 是能力定义 ≠ Agent Loop，可交给 Loop 自主规划或由 Workflow 固定阶段
2. 新增业务不改通用 Engine：指令写 `SKILL.md`、工具写 `tools`、业务规则写 `domain`、流程接 `application/<业务>`
3. 模型负责决策，Java 负责执行与校验；模型不能越过工具白名单，不能用文本伪装工具结果
4. 流式内容 ≠ 正式结果：最终结果必须通过 SKU、预算、结构、引用校验才能发布，绝不编造 SKU

---

## 代码地图

| 模块/目录 | 职责 |
|-----------|------|
| `ai-rec-odin-demo-main/src/main/resources/skills/<id>/` | 7 份 SKILL.md 指令、工具白名单、执行预算；`skills/recommendation.json` 技能清单 |
| `ai-rec-odin-demo-app/…/app/agent/{skill,tool,model,rag,runtime}` | Skill 加载、工具注册、模型配置、RAG、通用 Runtime 与执行器契约 |
| `ai-rec-odin-demo-app/…/app/application/{skill,commerce,chat}` | SkillEngine、commerce 业务扩展（handler）、普通对话 |
| `ai-rec-odin-demo-app/…/app/domain/{product,recommendation,travel,user}` | 商品检索、推荐约束、对比引用等业务规则 |
| `ai-rec-odin-demo-app/…/app/tools/{user,product,travel,category}` | Tool 参数协议与适配 |
| `ai-rec-odin-demo-app/…/app/infrastructure/{memory,travel}` | AI Memory / 外部 HTTP 客户端 |
| `ai-rec-odin-demo-app/…/app/bootstrap` | Bean 装配、RAG 启动预加载 |
| `ai-rec-odin-demo-web/…/web/controller` | HTTP / SSE / 会话恢复 |
| `main/…/resources/static/` | skills.html + workspace/skills/chat/product 四组 JS·CSS 模块（带版本参数防缓存） |
| `main/…/resources/catalog/jd-products.json` | 1000 个京东 SKU 页面快照（非实时报价）；另有 cid3_topn / cid2_name / brand_map 三个类目 CSV |
| `main/…/resources/profile/ai.properties` | 模型 API key、地址、Chat/Embedding 模型（**含密钥，永不外传**） |
| `scripts/` | 13 个 build-*-diagrams.mjs 图表生成器；jd-catalog / jd-discover-seeds / jd-collect-batch 采集脚本；run-skills.sh、smoke-skills.mjs、record-interest-demo.py |
| `docs/diagrams/` | 24 组 drawio+png（架构图、三版推荐流程图、案例图），由 build 脚本生成 |

**逻辑分层**：业务层（application）→ Agent 层（agent/runtime）→ 领域能力层（domain）→ AI 公共能力层（模型/RAG/工具治理）。

---

## 启动与测试

```bash
# 测试（Java + Node）
mvn -pl ai-rec-odin-demo-main -am clean test
node --test ai-rec-odin-demo-main/src/test/js/*.test.mjs

# 启动（商品 RAG 加载成功后才监听 HTTP）
bash scripts/run-skills.sh --server.port=8080
# 可用 MVN 指定 Maven 路径、JAVA_HOME 指定 JDK；依赖已下载可设 SKILL_DEMO_OFFLINE=1

# 冒烟
node scripts/smoke-skills.mjs http://127.0.0.1:8080
```

- 页面：`http://127.0.0.1:8080/skills.html`（`#chat` 对话 / `#skills` 实验室）
- 启动门禁日志：`SERVICE_READY`；诊断日志前缀 `agent_diag`（直接写 System.out，`AGENT_LOGGING_READY` 确认 DIRECT_CONSOLE 通道），按 `requestId` / `runId` 排查
- IDEA 入口：main 模块 `com.jd.diviner.ai.rec.odin.demo.AiRecOdinDemoApplication`
- 重构后需重新导入 Maven + Clean/Rebuild + 重启，避免 target 残留旧 class

---

## 文档索引（均在 `~/Desktop/ai-rec-odin-demo/docs/`）

### 入门与开发
| 文档 | 内容 |
|------|------|
| `recommendation-skills-developer-sharing.md` ⭐ | 入门首选：三版对照表、各版逐步执行、selectionStatus 归因、代码定位表、调试顺序、已验证/未验证边界 |
| `development.md` | 开发指南：四原则、代码地图、启动、API 清单、Tool/Skill/Workflow 最小示例、SSE 调试、交付清单、生产差距清单 |
| `skill-engine-execution.md` | Runtime 深读：runWithContext 主线、Workflow 四种节点（Tool/Local/Decision/Answer）、SkillExtension 扩展钩子、循环约束、ModelOutputGuard |
| `value-shopping-agent-walkthrough.md` | 「500 元耳机」高性价比选购案例，模块角色拆解 |
| `workflow-agent-case-flows.md` | 案例 3/4/5 流程简述（配合系统架构文档读） |

### 能力专项
| 文档 | 内容 |
|------|------|
| `skills.md` | Skill 专项：引用校验、极速版细节、mock 边界、SSE 协议、配置 v2 格式 |
| `rag.md` | RAG 链路、启动门禁、日志事件序列 |
| `ai-memory-integration.md` | /search 协议、REAL/DEMO 模式、响应归一、10s 超时/1MiB 限制 |
| `skill-progress-estimation.md` | SkillTimingHistory 进度估算（30 样本/配置，内存态重启丢失）、阶段预算、进度条规则 |

### 数据
| 文档 | 内容 |
|------|------|
| `jd-products.md` | 商品采集管线（浏览器快照，不绕过登录/验证码）、1000 SKU 现状、更新命令、事实边界（快照 7 天有效） |

### 架构与调研（历史/目标设计，非当前代码现状）
| 文档 | 内容 |
|------|------|
| `workflow-agent-system-architecture.md` | 四层设计（业务/Agent/领域能力/AI 公共）+ 百策横贯：两应用两 Runtime、Tool Adaptor vs Agent Adaptor、5 个跨系统案例、4 阶段交付顺序、8 条联合验收 |
| `shopping-agent-production-design.md` | C 端生产架构：交易状态机（PREPARING→…→SUCCEEDED/FAILED/UNKNOWN）、SLO 候选（P95 首进度≤1s/首答案≤5s）、容量测算、RACI、P0-P5 演进路线 |
| `agent-architecture-three-views.md` | 六层架构、模块/服务双视图、5 个场景走查 |
| `agent-module-call-relationships.md` | 目标设计调用矩阵 |
| `multi-agent-architecture-extension.md` | 子 Agent/外部 Agent 设计：委派管理器、任务契约、预算隔离 |
| `agent-industry-research-search-recommendation.md` | 行业调研（Claude Code/Rufus/Manus 等 17 个来源，F/I/P/U 证据分级）；LLM 辅助传统推荐 / Agentic / 生成式召回三条独立路线 |

### 排障（时间线记录）
| 文档 | 内容 |
|------|------|
| `skill-stream-troubleshooting.md` | 10/27–10/30 排障史：RAG ERROR_ENVELOPE 分类重试、启动门禁从"先 HTTP 后预加载"改为"预加载后才监听"、满血版 QUOTE_NOT_IN_SOURCE 触发 3 轮重构、ROUND_MISMATCH 前端协议修复、JSON_DECISION 协议纠正（最多 2 次）、JoyAI 工具协议兼容（stream-tool-calls=false）、MODEL_WAITING 心跳、会话恢复机制 |

### Joyspace 发布版
4 篇 `*.joyspace.md`（walkthrough / three-views / call-relationships / system-architecture）是对应本地文档的**发布扩写版**（长 60~130%），面向团队补了更多解释；内容以本地版为最新事实基准。

Joyspace 在线：[开发指南](https://joyspace.jd.com/pages/yPOTkgX9wDbVXPmBK2jE) · [三种推荐 Skill 介绍](https://joyspace.jd.com/pages/7betoo8mmmghMVRjIAxw)

---

## 配置与协议速查

- **Skill 预算**（maxModelCalls/maxToolCalls）：极速 2/4 · 普通 8/8 · 满血 12/20 · user-insight 6/5 · 行为预测 6/5 · 天气出游 8/7 · jev-cid 3/5
- **超时**（runtime.properties）：模型空闲 180s / 单轮 600s / Skill 总 1200s / 心跳 5s / 尝试 ≤3（退避 1s/2s）；页面超时 = 总时限+90s（默认 1290s）
- **AI Memory**（ai-memory.properties）：POST `/search`，10s 超时，limit 20，4 个真实用户 UUID pin；`username` 传 UUID 非 PIN，真实用户不回退演示数据
- **JEV/类目**（jev.properties）：openjev-laya.jd.com/v1/systemone，模型 laya-1.0，cid topn / cid2 名称 / 品牌映射 CSV；Diviner sniffer 45s
- **引用校验**：极速版无引用；普通版 evidenceId + 逐字 quote（6~500 字连续原文）；满血版 evidenceId + quoteId 由服务端从 quoteChoices 回填；修正有界（最多 2 轮），失败不发布
- **SSE 会话**：`POST /api/skills/{id}/session-stream?sessionId=<UUID>&resume=`，断线 30s 宽限重连回放（410 不存在 / 409 冲突），单进程 16 会话 × 32768 事件 × 8MiB
- **启动门禁**：两套 RAG 索引（1000 文档/2685 片段）构建完成后才监听 HTTP，最终日志 `SERVICE_READY`

---

## 重要约定

- **当前实现 vs 生产规划**要分清：这是单进程演示工程，"AI 搜索/AI 推荐独立 Runtime、百策平台发布、线上 AB、全站实时搜索"都是规划，不是现状
- 对话与 Skill 共享当前模型配置；真实用户不自动回退成演示数据
- 测试用隔离模型/工具桩，不会用测试数据替换正式商品库
- 进度记录写本目录 `今日进度.md`；代码改动在桌面仓库提交，commit 信息 `feat: <内容>`
- 改了桌面仓库代码后，同步镜像：`rsync -a --exclude='.git/' --exclude='.DS_Store' ~/Desktop/ai-rec-odin-demo/ "<本目录>/ai-rec-odin-demo/"`；`profile/ai.properties` 与 `skills/ai-memory.properties` 已被工作区 .gitignore 排除，同步后也不会进公开仓库，但 rsync 会把它们留在磁盘（这是预期行为）
- 镜像是快照不是工作副本：在镜像目录直接改代码不会回写桌面仓库，IDEA 里仍开发桌面仓库
