# 测试计划 · 购物导购助手 Demo v0.3

> 版本：v1.0 · 日期：2026-05-26
> 作者：QA Agent
> 上游依赖：`specs/features/chat.md` v0.1，`plans/pre-dev-research-plan.md` Sprint 3

---

## 一、测试范围

| 层次 | 覆盖内容 |
|------|---------|
| 接口层 | `/chat` SSE 端点（7 种事件类型）、`/health` 健康检查 |
| 意图路由 | 7 种 intent_type 路由正确性 |
| 三链路 | 传统搜索链路 / LLM 链路 / 兜底链路 |
| 多轮对话 | 追问 → 回答 → 商品推荐 完整链路 |
| 输入校验 | 空消息、纯空格、超长、特殊字符 |
| 熔断器 | CLOSED → OPEN → HALF_OPEN 状态转移 |
| 性能 | 传统链路 P95 < 200ms，LLM 首字 < 1s |

**不在范围内（v0.4 再测）：**
- 真实搜索/推荐算子对接
- 前端 E2E 自动化（Playwright）
- 负载压测

---

## 二、测试环境

| 项目 | 配置 |
|------|------|
| 后端 | FastAPI + uvicorn，localhost:8000 |
| AI 模型 | GLM-4-Flash（意图/追问）+ GLM-4（排序） |
| Mock 数据 | `data/phones.json`（48 款手机） |
| Mock 开关 | `USE_MOCK_SEARCH=true`，`USE_MOCK_RECOMMEND=true`，`USE_MOCK_AI=false` |
| 前端 | file:// 打开 `frontend/index.html` |

---

## 三、功能测试用例

### 3.1 输入校验（TC-001 ~ TC-005）

| ID | 输入 | 预期 | SLA |
|----|------|------|-----|
| TC-001 | `""` 空字符串 | `event: error`，code=invalid_request | < 50ms |
| TC-002 | `"   "` 纯空格 | `event: error`，message 含 "blank" | < 50ms |
| TC-003 | 510 字符超长 | `event: error`，code=invalid_request | < 50ms |
| TC-004 | `null` message 字段缺失 | HTTP 422 或 SSE error | < 50ms |
| TC-005 | 含 `<script>` 特殊字符 | 正常处理，不执行脚本，返回商品或追问 | — |

### 3.2 意图路由（TC-010 ~ TC-070）

| ID | 输入 | 预期 intent_type | 预期 pipeline | 置信度要求 |
|----|------|----------------|--------------|---------|
| TC-010 | `"小米15 256G黑色"` | direct_search | traditional | ≥ 0.80 |
| TC-011 | `"iPhone 16 128G"` | direct_search | traditional | ≥ 0.80 |
| TC-020 | `"最新款手机有哪些"` | time_sensitive | traditional | ≥ 0.75 |
| TC-021 | `"2026年新发布的手机"` | time_sensitive | traditional | ≥ 0.75 |
| TC-030 | `"帮我推荐一款手机"` | model_selection / guided_shopping | llm | ≥ 0.60 |
| TC-031 | `"3000以内拍照好的手机推荐"` | guided_shopping | llm | ≥ 0.75 |
| TC-040 | `"iPhone和小米哪个好"` | spec_comparison | llm | ≥ 0.80 |
| TC-041 | `"华为Mate70和小米15区别"` | spec_comparison | llm | ≥ 0.85 |
| TC-050 | `"今天天气怎么样"` | out_of_scope | fallback | ≥ 0.90 |
| TC-051 | `"帮我点外卖"` | out_of_scope | fallback | ≥ 0.80 |
| TC-060 | `"京东和淘宝哪里买便宜"` | cross_platform | fallback | ≥ 0.80 |
| TC-070 | `"送女朋友生日礼物"` | guided_shopping | llm | ≥ 0.70 |

### 3.3 三链路（TC-100 ~ TC-130）

| ID | 场景 | 验证点 |
|----|------|-------|
| TC-100 | 传统链路正常返回 | `event: product` ≥ 1 条，latency_ms < 200 |
| TC-101 | 传统链路品牌过滤 | 结果全部是指定品牌 |
| TC-102 | 传统链路价格过滤 | 结果价格全部 ≤ price_max |
| TC-110 | LLM 链路带 reason | 每个 product 有 reason 字段 |
| TC-111 | LLM 链路有 rank summary | ≥ 3 个商品时出现 `event: rank` |
| TC-120 | 兜底链路 out_of_scope | 无商品，只有 thinking + done |
| TC-121 | 兜底链路搜索无结果 | 有商品（热销推荐），有 done |

### 3.4 追问 & 多轮对话（TC-200 ~ TC-230）

| ID | 场景 | 验证点 |
|----|------|-------|
| TC-200 | 首轮无槽位 → 追问 | `event: clarify`，有 options ≥ 2 个 |
| TC-201 | 追问后回答预算 → 结果 | 第二轮返回 `event: product` |
| TC-202 | 追问后回答场景 → 结果 | 第二轮结果与场景相关 |
| TC-210 | spec_comparison 双品牌 → 不追问 | 直接 `event: product`，两个品牌均有结果 |
| TC-220 | 追问超 3 轮 → 兜底 | 第 4 轮返回兜底商品 |
| TC-230 | 新对话重置 session | history 清空，新 sessionId |

### 3.5 性能（TC-300 ~ TC-310）

| ID | 场景 | SLA 目标 |
|----|------|---------|
| TC-300 | direct_search 本地路径 | latency_ms < 50ms |
| TC-301 | traditional 链路完整 | latency_ms < 200ms |
| TC-310 | LLM 链路（GLM 调用）| latency_ms < 6000ms（P95） |

### 3.6 熔断器（TC-400 ~ TC-410）

| ID | 场景 | 验证点 |
|----|------|-------|
| TC-400 | 正常状态 | `/health` cb_state = closed |
| TC-401 | 3 次 GLM 失败后 | cb_state = open，链路走 fallback |
| TC-410 | 30s 恢复后 | cb_state = half_open → closed |

---

## 四、测试通过标准（准入 v0.3 上线）

**必须全部通过（P0）：**
- TC-001 ~ TC-003（输入校验）
- TC-010、TC-011（direct_search 核心路径）
- TC-040（spec_comparison 双品牌）
- TC-100、TC-110（传统/LLM 链路返回商品）
- TC-200、TC-201（追问 + 多轮对话）
- TC-300（direct_search 延迟 < 50ms）

**允许失败（P1，v0.4 修复）：**
- TC-220（追问超 3 轮，当前不做强依赖）
- TC-310（LLM 延迟，受 GLM 响应波动影响）
- TC-401/TC-410（熔断器恢复，需手动构造失败场景）

---

## 五、测试执行记录

执行结果见 `tests/report.md`。
