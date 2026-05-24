# 技术架构文档

> 状态：待填写 · 负责人：研发 Agent · 需 PM 审批后生效

## 一、整体架构图

```
（待研发 Agent 填写）
```

## 二、技术选型

| 层级 | 技术选择 | 选型理由 |
|------|---------|---------|
| 前端 | 原生 HTML/CSS/JS | 无需构建工具，demo 快速迭代 |
| 后端 | Python 3.9 + FastAPI | 轻量，适配器模式易于扩展 |
| AI 层 | GLM-4-Flash（计划） | 成本低，兼容 OpenAI 接口 |
| 搜索 | 公司内部 Java 算子 | 待接入 |
| 推荐 | 公司内部 Java 算子 | 待接入 |
| 部署 | （待定） | |

## 三、模块划分

### 前端模块
```
frontend/
├── index.html      ← App 壳，三 Tab 路由
├── style.css       ← 全局样式
└── app.js          ← Tab 切换 + 聊天逻辑
```

### 后端模块
```
backend/
├── main.py         ← FastAPI 路由，主流程编排
├── config.py       ← 环境变量和开关配置
└── adapters/
    ├── ai.py       ← AI 意图理解 + 回复生成
    ← search.py    ← 搜索算子适配
    └── recommend.py← 推荐算子适配
```

## 四、数据流

```
用户输入
  → POST /chat（携带对话历史）
  → adapters/ai.py：意图提取
  → 意图清晰？
      否 → 返回追问话术
      是 → adapters/search.py：搜索商品
          → 有结果？
              否 → adapters/recommend.py：推荐兜底
              是 → adapters/ai.py：生成推荐语
  → 返回 {reply, products}
```

## 五、关键技术决策

| 决策 | 选择 | 理由 | 风险 |
|------|------|------|------|
| AI 接入方式 | HTTP 调用外部 API | 解耦，可随时换模型 | 网络延迟 |
| 算子接入 | 适配器模式 | mock/真实可切换 | 字段映射需手动维护 |
| （待补充） | | | |

## 六、非功能性指标

| 指标 | 目标值 | 说明 |
|------|-------|------|
| 响应时间 | P99 < 3s | 含 AI 调用 |
| 并发 | （待定） | |
| 可用性 | （待定） | |

---
> 此文档需 PM 审批后，研发 Agent 方可开始编写实现计划（plans/frontend.md 和 plans/backend.md）
