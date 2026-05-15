# 功能规范 · 对话导购

> 版本：v0.1 · 更新时间：2026-05-06
> 作者：产品 Agent · 基于 Rufus 深度报告 + 竞品调研产出
> 上游依赖：`specs/overview.md`，`strategy/amazon.md`，`strategy/competitive.md`

---

## 一、功能概述

对话导购是本产品的核心功能，替代传统「关键词搜索框」，让用户用自然语言描述需求，系统通过**意图理解 + 三条链路协同**返回精准推荐。

**设计原则（来自 Rufus 经验）：**
- 不做独立 App，嵌入购物流程——对话是商品发现的起点，不是功能入口
- 流式输出，边生成边展示——不让用户等结果加载完
- 追问像聊天，不像填表——每次只问一个问题，用选项卡降低输入成本
- Help Me Decide——当搜索结果多时，主动帮用户归纳差异

---

## 二、用户故事

```
作为一个 [不知道怎么选礼物的用户]
我希望能 [用一句话描述我的需求，系统帮我追问清楚后推荐]
这样我就能 [不用自己研究，快速找到合适的商品]

验收标准：
Given 用户输入了模糊需求（如"帮我找个送女朋友的生日礼物"）
When 系统识别到槽位不完整（缺少预算/偏好）
Then 系统应主动追问一个明确问题（如"预算大概是多少？"）
 And 用户回答后，系统应返回 2-3 个带推荐理由的商品
 And 响应时间（流式首字）应在 1 秒以内
```

```
作为一个 [知道要什么的用户]
我希望能 [快速搜到商品，不被 AI 拦着]
这样我就能 [高效找到目标商品]

验收标准：
Given 用户输入了明确关键词（如"Nike Air Max 42 黑色"）
When 意图分类为 simple_search 且 clarity = clear
Then 系统应走传统搜索链路，200ms 内返回商品列表
 And 不应触发 LLM 追问流程
```

---

## 三、核心 Workflow：三链路设计

### 3.1 总体架构图

```
用户输入（文字）
      │
      ▼
┌─────────────────────────────────────────┐
│           意图分类 + 槽位提取            │
│   快速模型（目标 <100ms）                │
│                                         │
│   产出：                                │
│   - intent.type（意图类型）             │
│   - intent.clarity（清晰度）            │
│   - slots（已提取槽位）                 │
│   - system_check（系统状态）            │
└──────────────┬──────────────────────────┘
               │
      ┌────────▼─────────┐
      │    路由决策层      │
      │   Router Engine   │
      └────┬────────┬─────┘
           │        │        │
    ┌──────▼──┐ ┌──▼──────┐ ┌▼──────────┐
    │ 传统搜索 │ │ 大模型   │ │  兜底链路  │
    │  链路   │ │  链路   │ │  Fallback │
    └──────┬──┘ └──┬──────┘ └┬──────────┘
           │        │         │
      ┌────▼────────▼─────────▼────┐
      │       结果质量检查           │
      │  Result Quality Gate        │
      └────────────┬────────────────┘
                   │
      ┌────────────▼────────────────┐
      │       响应组装 + 渲染        │
      │  商品卡片 + AI 文案 + 反馈   │
      └─────────────────────────────┘
```

---

### 3.2 链路一：传统工程链路

**定位：快速、稳定的基础搜索，作为系统可靠性底座**

#### 触发条件

| 判断项 | 条件 | 说明 |
|--------|------|------|
| intent.type | `simple_search` | 用户有明确关键词 |
| intent.clarity | `clear` | 无需追问 |
| intent.complexity | `low` | 无复杂推荐需求 |
| system_state | 正常 | LLM 服务无异常 |

**触发示例：**
- "苹果 iPhone 15 128G 黑色"
- "Nike 跑鞋 42 码"
- "北鼻奶粉 900g"
- "小米电视 65 寸"

#### 处理流程

```
① 关键词标准化
   "苹果iPhone15" → ["苹果", "iPhone 15", "手机"]
   附加条件提取：尺码/颜色/规格

② 调用搜索算子（Java 组件）
   入参：keywords[], category?, price_range?, filters{}
   出参：product_list[]（含商品信息、价格、库存）

③ 结果排序
   综合得分 = 相关性分 × 0.5 + 销量分 × 0.3 + 评价分 × 0.2

④ 结果质量检查
   - 结果数 ≥ 3：直接展示，不触发 LLM
   - 结果数 1-2：触发 LLM 补充推荐（进入链路二 Supplement 模式）
   - 结果数 = 0：触发兜底链路 + 提示"换个关键词试试"
```

#### 输出格式

```json
{
  "pipeline": "traditional",
  "reply": null,
  "products": [
    {
      "id": "...",
      "title": "...",
      "price": 999,
      "image": "...",
      "rating": 4.8,
      "sales": 12000,
      "reason": null
    }
  ],
  "latency_ms": 120
}
```

#### SLA 要求

| 指标 | 目标值 |
|------|-------|
| P50 响应时延 | <100ms |
| P95 响应时延 | <200ms |
| 成功率 | >99.9% |

---

### 3.3 链路二：大模型链路

**定位：处理复杂意图、不清晰需求、需要推荐解释的场景**

#### 触发条件

| 判断项 | 条件 | 说明 |
|--------|------|------|
| intent.type | `guided_shopping` / `comparison` / `detail_inquiry` | 需要 AI 参与 |
| intent.clarity | `unclear` / `partial` | 槽位不完整，需追问 |
| intent.complexity | `medium` / `high` | 有多条件、跨品类需求 |
| 历史对话 | 轮次 > 1 | 多轮上下文对话 |

**触发示例：**
- "帮我找个送妈妈的母亲节礼物" → guided_shopping
- "这两个哪个更好用" → comparison
- "这款洗衣机噪音大不大" → detail_inquiry
- "我需要一个可以骑山地也可以上班用的自行车" → complex

#### 大模型链路内部分支

**分支 A：意图澄清（Clarification Branch）**

```
触发条件：intent.clarity == "unclear" 且对话轮次 < 3

流程：
① LLM 根据已有槽位，判断缺失的最关键槽位
② 生成一个自然语言追问（每次只问一个问题）
③ 提供快捷选项卡（减少打字成本）
④ 等待用户回答 → 槽位更新 → 重新路由

追问槽位优先级：
  category（品类）> scenario（场景）> price（价格）> recipient（使用对象）> preference（偏好）

示例：
  用户："帮我找个礼物"
  系统缺失：category、recipient、price
  系统追问：「是送给谁的？」+ 选项卡 [父母] [朋友] [伴侣] [同事] [自己]
```

**分支 B：推荐生成（Recommend Branch）**

```
触发条件：槽位足够清晰（clarity == "clear" 或 追问 ≤ 2 轮后补全）

流程：
① RAG 检索
   根据 slots 构造检索 Query
   检索来源优先级：
   ├── 商品库（匹配品类/关键词）
   ├── 用户评价摘要（提炼真实使用感受）
   ├── 商品详情 Q&A（处理 detail_inquiry）
   └── 热门榜单（兜底补充）

② LLM 推荐生成
   输入：slots + 检索结果 top-10
   输出：
   ├── 推荐文案（100字以内，人话，不用参数堆砌）
   ├── 推荐商品列表（2-4个）
   └── 每个商品的推荐理由（1-2句，匹配用户的具体需求）

③ 流式输出
   文案边生成边展示（Streaming）
   先展示文案，商品卡片在文案完成后一次性渲染

④ Help Me Decide（当返回 ≥ 3 个商品时）
   系统自动生成对比摘要：
   "A 更适合预算有限的你；B 如果追求品牌可以考虑；C 颜值最高但价格稍贵"
```

#### 输出格式

```json
{
  "pipeline": "llm",
  "branch": "recommend",
  "reply": "根据你的需求，给妈妈买护肤类礼物是个好选择。这几款口碑不错，适合日常使用...",
  "products": [
    {
      "id": "...",
      "title": "雅诗兰黛小棕瓶精华",
      "price": 280,
      "image": "...",
      "reason": "妈妈年龄段最常提到抗老需求，这款评价中 89% 提到坚持使用后皮肤变好"
    }
  ],
  "help_me_decide": "第一款抗老效果最被认可；第二款更适合敏感肌；第三款性价比最高",
  "latency_ms": 1800
}
```

#### SLA 要求

| 指标 | 目标值 |
|------|-------|
| 流式首字时延 | <1s |
| P95 完整响应时延 | <3s |
| 成功率 | >95% |
| LLM 调用超时阈值 | 5s（超时触发 Fallback） |

---

### 3.4 链路三：兜底链路

**定位：系统防降级保障，任何场景下都能给用户一个「有用的结果」**

#### 触发条件（任一满足即触发）

| 触发场景 | 原因 | 优先级 |
|---------|------|-------|
| LLM 服务异常/超时（>5s） | 服务故障 | 最高 |
| 传统搜索返回 0 结果 | 关键词无匹配 | 高 |
| 意图分类置信度 < 0.5 | 用户输入过于奇怪 | 中 |
| 对话追问超过 3 轮仍不清晰 | 防无限追问 | 中 |

#### 处理流程

```
① 确认可用品类（从已有槽位或历史行为中提取）
   有品类 → 该品类热销榜 Top-10
   无品类 → 全站今日热销 Top-10

② 生成兜底文案
   规则模板（非 LLM）：
   - LLM 超时："稍等，我给你推荐今天卖得最好的几款…"
   - 搜索无结果："没找到完全匹配的，这些相关商品供你参考…"
   - 意图不清："我先给你看看大家都在买什么，你再告诉我你的需求…"

③ 返回兜底结果 + 引导语
   "↑ 这是今日热销。你可以告诉我更多，比如预算、场景，我帮你精准找"
```

#### 输出格式

```json
{
  "pipeline": "fallback",
  "reason": "llm_timeout",
  "reply": "稍等，给你看看大家都在买的…",
  "products": [...],
  "guidance": "告诉我你的预算和用途，我帮你精准推荐",
  "latency_ms": 80
}
```

#### SLA 要求

| 指标 | 目标值 |
|------|-------|
| P95 响应时延 | <100ms（预缓存热销榜） |
| 成功率 | >99.99%（最后防线） |

---

## 四、路由决策逻辑（Router）

### 4.1 三层判断模型

```
第一层：系统状态检查（优先级最高，并行执行）
  LLM 服务健康检查 → 不健康 → 直接走 Fallback
  搜索服务健康检查 → 不健康 → 直接走 Fallback

第二层：意图分类路由
  intent.type == "simple_search"
    AND clarity == "clear"
    AND complexity == "low"
      → 传统搜索链路

  intent.type IN ["guided_shopping", "comparison", "detail_inquiry"]
    OR clarity IN ["unclear", "partial"]
    OR complexity IN ["medium", "high"]
    OR session.turns > 1
      → 大模型链路
        → clarity == "unclear" → 澄清分支
        → clarity == "clear"  → 推荐分支

第三层：结果质量检查（链路执行后）
  传统搜索结果数 < 3  → 触发 LLM 补充
  传统搜索结果数 = 0  → 触发 Fallback
  LLM 超时/错误      → 触发 Fallback
```

### 4.2 意图类型定义

| intent.type | 中文 | 典型输入 |
|-------------|------|---------|
| `simple_search` | 明确关键词搜索 | "Nike Air Max 42码黑色" |
| `guided_shopping` | 需引导推荐 | "帮我找个礼物" / "给孩子买玩具" |
| `comparison` | 对比决策 | "A和B哪个好" / "这两款有什么区别" |
| `detail_inquiry` | 商品详情问答 | "这款支持快充吗" / "会褪色吗" |
| `unknown` | 无法分类 | 随机文字、系统无法理解 |

### 4.3 槽位（用户标）定义

| 槽位 | 字段名 | 类型 | 示例值 |
|------|-------|------|-------|
| 品类 | `category` | string | "手机", "护肤品", "运动鞋" |
| 价格上限 | `price_max` | int | 300 |
| 价格下限 | `price_min` | int | 100 |
| 使用场景 | `scenario` | string | "户外", "送礼", "上班" |
| 使用对象 | `recipient` | string | "妈妈", "儿童", "自己" |
| 品牌偏好 | `brand` | string | "苹果", "无品牌偏好" |
| 用户偏好 | `preference` | string[] | ["性价比", "颜值", "品牌"] |
| 排除条件 | `exclude` | string[] | ["太重", "不要红色"] |

### 4.4 路由决策伪代码

```python
def route(intent, slots, system_state, session) -> Pipeline:
    # 第一层：系统状态
    if not system_state.llm_healthy:
        return Pipeline.FALLBACK

    # 第二层：意图路由
    if (intent.type == "simple_search"
        and intent.clarity == "clear"
        and intent.complexity == "low"):
        return Pipeline.TRADITIONAL

    if (intent.type in ["guided_shopping", "comparison", "detail_inquiry"]
        or intent.clarity in ["unclear", "partial"]
        or intent.complexity in ["medium", "high"]
        or session.turns > 1):
        if intent.clarity == "unclear" and session.turns < 3:
            return Pipeline.LLM_CLARIFY
        else:
            return Pipeline.LLM_RECOMMEND

    # 兜底：未知意图
    return Pipeline.FALLBACK


def post_quality_check(result, pipeline) -> Pipeline | None:
    """链路执行后的结果质量检查"""
    if pipeline == Pipeline.TRADITIONAL:
        if result.product_count == 0:
            return Pipeline.FALLBACK
        if result.product_count < 3:
            return Pipeline.LLM_SUPPLEMENT  # LLM 补充推荐
    if pipeline in [Pipeline.LLM_RECOMMEND, Pipeline.LLM_CLARIFY]:
        if result.error or result.timeout:
            return Pipeline.FALLBACK
    return None
```

---

## 五、交互设计规范

### 5.1 对话入口

```
首屏（用户首次进入）：
┌─────────────────────────────────┐
│  ✨ 你想买什么？                  │
│  ────────────────────────────   │
│  [帮我找礼物]  [我想买手机]       │
│  [最近什么好]  [我说，你来找]     │
└─────────────────────────────────┘

→ 点击快捷卡片或直接输入，都进入对话流
```

### 5.2 追问设计规范

**规则：**
- 每次只追问一个问题（不同时问预算+场景+对象）
- 追问附带快捷选项卡（3-5 个常见选项）
- 最多追问 3 轮，超过后进兜底

**追问文案示例：**

| 缺失槽位 | 追问文案 | 快捷选项 |
|---------|---------|---------|
| recipient | "是送给谁的？" | [妈妈] [爸爸] [朋友] [另一半] [自己] |
| price_max | "预算大概多少？" | [100以内] [100-300] [300-500] [500以上] |
| scenario | "主要用在哪里？" | [日常] [户外运动] [工作] [特殊场合] |
| preference | "你更看重什么？" | [性价比] [品牌] [颜值] [功能] |

### 5.3 商品卡片规范

```
┌────────────────────────────────┐
│ [商品图片]  商品名称（最多2行） │
│             ¥ 价格             │
│             ⭐ 4.8  1.2万人买过 │
│             推荐理由（1-2句）   │
└────────────────────────────────┘
```

**推荐理由写作规范：**
- 必须和用户的槽位挂钩（"根据你送礼的需求…"）
- 不用参数，用体验（"电池能用一整天"而非"5000mAh"）
- 最多 2 句话

### 5.4 Help Me Decide

当返回商品 ≥ 3 个时，在商品列表下方展示对比摘要：

```
┌────────────────────────────────┐
│ 🤔 怎么选？                    │
│ A 款：最适合预算敏感            │
│ B 款：品牌认可度最高            │
│ C 款：颜值最好，价格稍贵        │
└────────────────────────────────┘
```

### 5.5 反馈机制

每条 AI 回复下方展示：

```
[👍 有帮助]  [👎 不满意]  [继续追问...]
```

- 点击 👍 → 记录为正向信号，上报后台
- 点击 👎 → 弹出简短选项：「推荐不准」「理由不够」「商品太贵」→ 记录 + 触发新一轮推荐

---

## 六、边界条件与异常处理

| 场景 | 处理方式 |
|------|---------|
| 用户输入敏感词/无意义内容 | 返回引导文案，不进行搜索 |
| 用户询问非购物问题（"今天天气"）| "我专门帮你找商品，你想买什么？" |
| LLM 追问超 3 轮仍不清楚 | 进入兜底链路 + "你可以直接告诉我商品名" |
| 搜索返回 0 结果 | 兜底热销 + 建议换关键词 |
| LLM 超时（>5s）| 展示 loading 状态，超时后切换兜底，不让用户看到空白 |
| 网络断开 | 本地缓存最近一次结果 + 提示"网络不稳定，展示上次结果" |

---

## 七、需确认的问题

- [NEEDS CLARIFICATION] 搜索算子的入参格式：关键词是数组还是字符串？
- [NEEDS CLARIFICATION] 推荐算子是否支持按品类过滤？
- [NEEDS CLARIFICATION] LLM 使用 GLM-4-Flash 还是保留 Claude？成本预算是多少？
- [NEEDS CLARIFICATION] 用户历史购买数据是否可以接入个性化推荐？

---

> 版本历史：
> - v0.1（2026-05-06）：初版，基于 Rufus 竞品调研产出，含三链路 Workflow 完整设计
