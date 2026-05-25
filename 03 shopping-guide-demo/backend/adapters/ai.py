"""
AI 适配器 v0.2 — GLM-4-Flash 意图路由 + 槽位提取 + 追问生成；GLM-4 排序解释
USE_MOCK_AI=true 时用规则代替，不消耗 API 额度。
"""

import json
import re
import time
import httpx
from dataclasses import dataclass, field
from typing import Optional

import config


# ── 数据类 ──────────────────────────────────────────────────────────────

@dataclass
class IntentResult:
    intent_type: str          # 见 INTENT_TYPES
    confidence: float         # 0.0 - 1.0
    slots: dict = field(default_factory=dict)
    pipeline: str = "llm"     # "traditional" | "llm" | "fallback"


@dataclass
class ClarifyResult:
    question: str
    options: list[str] = field(default_factory=list)


@dataclass
class RankResult:
    products: list[dict]      # 排序后的商品列表（带 reason 字段）
    summary: str = ""         # Help Me Decide 对比摘要


# ── 意图类型 ─────────────────────────────────────────────────────────────

INTENT_TYPES = {
    "direct_search":   "明确关键词直接搜索",
    "time_sensitive":  "时间敏感（最新款/今天热销）",
    "model_selection": "选型推荐（帮我选一款）",
    "spec_comparison": "规格对比（A和B哪个好）",
    "guided_shopping": "引导式购物（有需求但不确定要什么）",
    "cross_platform":  "跨平台比价",
    "out_of_scope":    "超出范围（非购物问题）",
}

TRADITIONAL_INTENTS = {"direct_search", "time_sensitive"}
LLM_INTENTS         = {"model_selection", "spec_comparison", "guided_shopping"}
FALLBACK_INTENTS    = {"cross_platform", "out_of_scope"}


# ── 本地规则快速路径 ─────────────────────────────────────────────────────

_BRAND_WORDS  = ["苹果", "iphone", "华为", "小米", "oppo", "vivo", "三星",
                 "荣耀", "一加", "魅族", "realme", "红米", "redmi", "iqoo"]
_COMPARE_WORDS = ["哪个好", "区别", "差别", "对比", "比较", "vs",
                  "好还是", "还是.*好", "和.*哪", "哪.*更好"]
_GUIDE_WORDS  = ["推荐", "帮我选", "选一款", "适合", "什么手机好",
                 "送礼", "预算", "需要一个", "想买"]
_TIME_WORDS   = ["最新", "刚发布", "今年", "新款", "2026", "最近发布", "新出"]

def _rule_based_intent(message: str) -> IntentResult:
    msg = message.lower()

    # out_of_scope: 明显非手机购物
    if re.search(r"天气|新闻|音乐|导航|翻译|股票|笑话", msg):
        return IntentResult("out_of_scope", 0.95, pipeline="fallback")

    # cross_platform: 比价/哪里买
    if re.search(r"哪里买|哪买|更便宜|京东|淘宝|拼多多|哪个平台", msg):
        return IntentResult("cross_platform", 0.85, pipeline="fallback")

    # spec_comparison
    for pat in _COMPARE_WORDS:
        if re.search(pat, msg):
            brand_map_local = {
                "苹果": "苹果", "iphone": "苹果", "华为": "华为", "小米": "小米",
                "oppo": "OPPO", "vivo": "vivo", "三星": "三星", "荣耀": "荣耀",
                "一加": "一加", "魅族": "魅族", "realme": "Realme",
                "红米": "小米", "redmi": "小米", "iqoo": "vivo",
            }
            found_brands = list(dict.fromkeys(
                v for k, v in brand_map_local.items() if k in msg
            ))
            slots: dict = {}
            if found_brands:
                slots["brand"] = found_brands[0]
            if len(found_brands) >= 2:
                slots["brand2"] = found_brands[1]
            conf = 0.90 if len(found_brands) >= 2 else 0.78
            return IntentResult("spec_comparison", conf, slots=slots, pipeline="llm")

    # time_sensitive
    if any(w in msg for w in _TIME_WORDS):
        return IntentResult("time_sensitive", 0.82,
                            slots=_extract_basic_slots(msg), pipeline="traditional")

    # direct_search: 品牌 + 型号/数字特征
    brand_hit = any(b in msg for b in _BRAND_WORDS)
    has_digits = bool(re.search(r"\d{2,}", msg))     # 型号/价格/存储
    if brand_hit and has_digits:
        return IntentResult("direct_search", 0.88,
                            slots=_extract_basic_slots(msg), pipeline="traditional")

    # guided_shopping
    if any(w in msg for w in _GUIDE_WORDS):
        return IntentResult("guided_shopping", 0.80,
                            slots=_extract_basic_slots(msg), pipeline="llm")

    # model_selection: 只提品牌或泛问
    if brand_hit or re.search(r"手机|机子|机器", msg):
        return IntentResult("model_selection", 0.70,
                            slots=_extract_basic_slots(msg), pipeline="llm")

    return IntentResult("model_selection", 0.50, pipeline="llm")


def _extract_basic_slots(msg: str) -> dict:
    slots: dict = {}

    # 品牌
    brand_map = {"苹果": "苹果", "iphone": "苹果", "华为": "华为", "小米": "小米",
                 "oppo": "OPPO", "vivo": "vivo", "三星": "三星", "荣耀": "荣耀",
                 "一加": "一加", "魅族": "魅族", "realme": "Realme",
                 "红米": "小米", "redmi": "小米", "iqoo": "vivo"}
    for k, v in brand_map.items():
        if k in msg.lower():
            slots["brand"] = v
            break

    # 预算
    m = re.search(r"(\d+)\s*(元|块|以内|左右|以下|预算|budget)", msg)
    if m:
        slots["price_max"] = int(m.group(1))

    # 存储
    m = re.search(r"(\d+)\s*g\b", msg.lower())
    if m and int(m.group(1)) in {64, 128, 256, 512, 1024}:
        slots["storage"] = int(m.group(1))

    # 场景
    if re.search(r"游戏|吃鸡|原神|王者", msg):
        slots["scenario"] = "游戏"
    elif re.search(r"拍照|摄影|拍摄|相机", msg):
        slots["scenario"] = "拍照"
    elif re.search(r"商务|办公|工作", msg):
        slots["scenario"] = "商务"
    elif re.search(r"学生|上学", msg):
        slots["scenario"] = "学生"

    return slots


# ── 公开 API ─────────────────────────────────────────────────────────────

async def intent_router(message: str, history: list[dict]) -> IntentResult:
    """第一步：意图路由 + 槽位提取。无对话历史时先走本地规则快速路径。"""
    if config.USE_MOCK_AI:
        return _mock_intent_router(message)

    # 本地快速路径：首轮对话 + 高置信度规则匹配
    if not history:
        quick = _rule_based_intent(message)
        if quick.intent_type in TRADITIONAL_INTENTS and quick.confidence >= 0.75:
            return quick
        if quick.intent_type in FALLBACK_INTENTS and quick.confidence >= 0.80:
            return quick

    return await _glm_intent_router(message, history)


async def clarification_generator(
    message: str,
    history: list[dict],
    slots: dict,
) -> ClarifyResult:
    """生成一个追问问题（每次只问一个最关键的缺失槽位）。"""
    if config.USE_MOCK_AI:
        return _mock_clarification(slots)
    return await _glm_clarification(message, history, slots)


async def product_ranker(
    products: list[dict],
    slots: dict,
    message: str,
) -> RankResult:
    """对候选商品排序并生成推荐理由 + Help Me Decide 对比摘要。"""
    if config.USE_MOCK_AI:
        return _mock_rank(products, slots)
    return await _glm_rank(products, slots, message)


# ── Mock 实现 ─────────────────────────────────────────────────────────────

def _mock_intent_router(message: str) -> IntentResult:
    result = _rule_based_intent(message)
    result.confidence = max(result.confidence, 0.75)
    return result


def _mock_clarification(slots: dict) -> ClarifyResult:
    if "price_max" not in slots:
        return ClarifyResult("你的预算大概是多少？",
                             ["1500以内", "1500-3000", "3000-5000", "5000以上"])
    if "scenario" not in slots:
        return ClarifyResult("你主要用手机做什么？",
                             ["日常拍照", "手游", "商务办公", "刷视频"])
    if "brand" not in slots:
        return ClarifyResult("有品牌偏好吗？",
                             ["苹果", "华为", "小米", "OPPO/vivo", "都行"])
    return ClarifyResult("还有什么特别在意的？",
                         ["续航长", "轻薄", "拍照好", "颜值高"])


def _mock_rank(products: list[dict], slots: dict) -> RankResult:
    scored = []
    scenario = slots.get("scenario", "")
    for p in products:
        score = p.get("rating", 4.0) * 0.5 + min(p.get("sales", 0) / 100000, 1.0) * 0.5
        if scenario == "游戏" and "游戏" in p.get("tags", []):
            score += 0.3
        elif scenario == "拍照" and "拍照" in p.get("tags", []):
            score += 0.3
        scored.append((score, p))

    scored.sort(key=lambda x: -x[0])
    ranked = []
    for i, (_, p) in enumerate(scored[:4]):
        p = dict(p)
        if i == 0:
            p["reason"] = f"综合评分最高，销量{p.get('sales',0)//10000}万+"
        elif scenario and scenario in p.get("tags", []):
            p["reason"] = f"{scenario}场景最佳选择"
        else:
            p["reason"] = "口碑出色，性价比高"
        ranked.append(p)

    summary = ""
    if len(ranked) >= 3:
        summary = (f"「{ranked[0]['title'][:10]}」综合最强；"
                   f"「{ranked[1]['title'][:10]}」性价比高；"
                   f"「{ranked[2]['title'][:10]}」适合预算有限")
    return RankResult(ranked, summary)


# ── GLM API 实现 ──────────────────────────────────────────────────────────

_GLM_URL = "https://open.bigmodel.cn/api/paas/v4/chat/completions"


async def _call_glm(model: str, messages: list, max_tokens: int = 512) -> str:
    headers = {
        "Authorization": f"Bearer {config.GLM_API_KEY}",
        "Content-Type":  "application/json",
    }
    payload = {
        "model":       model,
        "messages":    messages,
        "temperature": 0.1,
        "max_tokens":  max_tokens,
    }
    async with httpx.AsyncClient(timeout=config.GLM_TIMEOUT) as client:
        resp = await client.post(_GLM_URL, json=payload, headers=headers)
        resp.raise_for_status()
        return resp.json()["choices"][0]["message"]["content"]


async def _glm_intent_router(message: str, history: list[dict]) -> IntentResult:
    system = f"""你是京东购物导购助手，专注手机品类。分析用户意图并输出 JSON。

意图类型（intent_type）：
- direct_search   明确型号/品牌直接搜索
- time_sensitive  询问最新款/热销
- model_selection 让你帮忙推荐选型
- spec_comparison 两款对比/哪个好
- guided_shopping 有需求但需要引导（场景/礼物/预算）
- cross_platform  问在哪平台买更便宜
- out_of_scope    非购物问题

槽位（slots，识别到的填入）：brand / price_max / price_min / storage / scenario / color

pipeline 规则：
- direct_search/time_sensitive → "traditional"
- model_selection/spec_comparison/guided_shopping → "llm"
- cross_platform/out_of_scope → "fallback"

输出纯 JSON（不加代码块），示例：
{{"intent_type":"direct_search","confidence":0.92,"pipeline":"traditional","slots":{{"brand":"苹果","storage":256}}}}"""

    msgs = [{"role": "system", "content": system}]
    for m in history[-4:]:
        msgs.append({"role": m["role"], "content": m["content"]})
    msgs.append({"role": "user", "content": message})

    try:
        raw = await _call_glm(config.GLM_FAST_MODEL, msgs, max_tokens=200)
        data = json.loads(raw.strip())
        return IntentResult(
            intent_type = data.get("intent_type", "model_selection"),
            confidence  = float(data.get("confidence", 0.70)),
            slots       = data.get("slots", {}),
            pipeline    = data.get("pipeline", "llm"),
        )
    except Exception:
        return _rule_based_intent(message)


async def _glm_clarification(
    message: str,
    history: list[dict],
    slots: dict,
) -> ClarifyResult:
    filled = list(slots.keys())
    missing_priority = [k for k in
                        ["price_max", "scenario", "brand", "storage", "color"]
                        if k not in filled]

    system = f"""你是购物导购助手，帮用户追问缺失信息。
已知槽位：{filled}
缺失槽位（按优先级）：{missing_priority}

只问最关键的一个问题，输出 JSON：
{{"question":"问题文本","options":["选项1","选项2","选项3","选项4"]}}
选项 3-5 个，简洁口语化。不加代码块。"""

    msgs = [{"role": "system", "content": system}]
    for m in history[-4:]:
        msgs.append({"role": m["role"], "content": m["content"]})
    msgs.append({"role": "user", "content": message})

    try:
        raw = await _call_glm(config.GLM_FAST_MODEL, msgs, max_tokens=150)
        data = json.loads(raw.strip())
        return ClarifyResult(
            question = data.get("question", "你有什么特别要求吗？"),
            options  = data.get("options", []),
        )
    except Exception:
        return _mock_clarification(slots)


async def _glm_rank(
    products: list[dict],
    slots: dict,
    message: str,
) -> RankResult:
    candidates = products[:8]  # 最多传8条给LLM排序
    prod_summary = json.dumps(
        [{"id": p["id"], "title": p["title"], "price": p["price"],
          "rating": p["rating"], "sales": p["sales"],
          "highlights": p.get("highlights", [])}
         for p in candidates],
        ensure_ascii=False
    )

    system = f"""你是购物导购助手，根据用户需求对手机进行排序并生成推荐理由。

用户需求：{message}
已知槽位：{slots}

候选商品（JSON）：
{prod_summary}

要求：
1. 选出最合适的 2-4 款，按推荐度排序
2. 每款写一句推荐理由（15字以内，针对用户需求，不堆参数）
3. 写一句 Help Me Decide 对比总结（30字以内）

输出纯 JSON（不加代码块）：
{{"ranked":["id1","id2","id3"],"reasons":{{"id1":"理由1","id2":"理由2"}},"summary":"对比摘要"}}"""

    msgs = [{"role": "user", "content": system}]

    try:
        raw = await _call_glm(config.GLM_SMART_MODEL, msgs, max_tokens=400)
        data = json.loads(raw.strip())
        ranked_ids = data.get("ranked", [])
        reasons    = data.get("reasons", {})
        summary    = data.get("summary", "")

        prod_map = {p["id"]: p for p in candidates}
        result = []
        for pid in ranked_ids:
            if pid in prod_map:
                p = dict(prod_map[pid])
                p["reason"] = reasons.get(pid, "")
                result.append(p)

        if not result:
            return _mock_rank(products, slots)
        return RankResult(result, summary)
    except Exception:
        return _mock_rank(products, slots)
