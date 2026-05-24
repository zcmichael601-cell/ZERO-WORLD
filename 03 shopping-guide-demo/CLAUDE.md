# 购物导购助手 · 项目协作规范

## 项目简介
AI + 传统搜索混合的购物导购 demo，采用 **SDD（规范驱动开发）** 和 **多 Agent 协作**模式开发。

## Agent 团队

| Agent | 角色文件 | 职责 | 可见范围 |
|-------|---------|------|---------|
| Strategy | `.claude/agents/strategy.md` | 行业/竞品/SWOT 分析 | 🔒 仅 PM |
| Orchestrator | `.claude/agents/orchestrator.md` | 任务分发、进度追踪 | 全团队 |
| 产品专家 | `.claude/agents/product.md` | 写 Spec 规范文档 | 全团队 |
| 研发专家 | `.claude/agents/dev.md` | 技术架构 + 代码实现 | 全团队 |
| QA 专家 | `.claude/agents/qa.md` | 测试计划 + 验收 + 安全 | 全团队 |

⚠️ **`strategy/` 目录仅 PM 可读，其他所有 Agent 禁止访问。**

**启动任何 Agent 前，先读对应的角色文件。**

---

## 目录结构与职责

```
strategy/           ← 🔒 仅 PM（Strategy Agent 产出）
├── industry.md     ← 行业分析
├── customer.md     ← 客户研究
├── competitive.md  ← 竞品分析
└── swot.md         ← SWOT 分析

specs/              ← 产品 Agent（规范文档）
├── overview.md     ← 产品定位、用户场景、版本规划（原 PRD）
├── features/       ← 各功能详细规范
│   ├── chat.md
│   ├── discover.md
│   └── profile.md
└── api/
    └── contracts.md← 前后端接口约定

plans/              ← 研发 Agent（实现计划）
├── architecture.md ← 技术架构文档 ⚠️ 需 PM 审批
├── frontend.md     ← 前端实现计划
└── backend.md      ← 后端实现计划

tasks/              ← Orchestrator（任务看板）
└── current.md

tests/              ← QA Agent
└── test-plan.md    ← 测试计划文档 ⚠️ 需 PM 审批

frontend/           ← 研发 Agent（前端代码）
backend/            ← 研发 Agent（后端代码）
```

---

## 六个关键确认节点（必须等 PM 审批才能继续）

```
① 战略分析确认（仅 PM 可见）
   Strategy Agent 完成行业/竞品/SWOT → PM 审阅确认

② Spec 审批
   产品 Agent 完成 specs/ 规范文档 → PM 审批 → 研发启动

③ 技术架构审批  ← 新增
   研发 Agent 完成 plans/architecture.md → PM 审批 → 继续写实现计划

④ 实现计划审批
   研发 Agent 完成 plans/frontend.md + backend.md → PM 审批 → 开始写代码

⑤ 测试文档审批  ← 新增
   QA Agent 完成 tests/test-plan.md → PM 审批 → 开始执行测试

⑥ 上线审批
   QA Agent 验收通过 → PM 审批 → 部署
```

---

## 完整开发工作流

```
PM 提供战略信息
  → Strategy Agent 分析（私密，仅PM看）→ ① PM 确认

PM 下达产品需求
  → Orchestrator 拆解任务
  → 产品 Agent 写 specs/              → ② PM 审批

研发 Agent 写 plans/architecture.md   → ③ PM 审批

研发 Agent 写 plans/frontend+backend  → ④ PM 审批

研发 Agent 写代码（frontend/ backend/）

QA Agent 写 tests/test-plan.md        → ⑤ PM 审批

QA Agent 执行验收 + 安全检查

验收报告 → Orchestrator 汇总           → ⑥ PM 审批 → 部署
```

---

## 重要约定
- `strategy/` 已加入 `.gitignore`，不进入版本控制
- 规范变了 → 改 `specs/`，代码跟着重新生成
- 接口变了 → 必须同步更新 `specs/api/contracts.md`
- 任何 Agent 遇到阻塞 → 立即上报 Orchestrator，不擅自推进
- 技术栈：前端原生 HTML/CSS/JS，后端 Python 3.9 + FastAPI，AI 计划接入 GLM
