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
from utils.lru_cache import TTLCache

# V4-02: cache for GLM ranking results (key = hash of product ids + slots + message)
_rank_cache = TTLCache(maxsize=config.RANK_CACHE_SIZE, ttl=config.RANK_CACHE_TTL)


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
    "detail_inquiry":  "规格追问（这款支持快充吗）",
}

TRADITIONAL_INTENTS = {"direct_search", "time_sensitive"}
LLM_INTENTS         = {"model_selection", "spec_comparison", "guided_shopping"}
FALLBACK_INTENTS    = {"cross_platform", "out_of_scope"}
DETAIL_INTENTS      = {"detail_inquiry"}


# ── 本地规则快速路径 ─────────────────────────────────────────────────────

_BRAND_WORDS  = ["苹果", "iphone", "华为", "小米", "oppo", "vivo", "三星",
                 "荣耀", "一加", "魅族", "realme", "红米", "redmi", "iqoo"]
_COMPARE_WORDS = ["哪个好", "区别", "差别", "对比", "比较", "vs",
                  "好还是", "还是.*好", "和.*哪", "哪.*更好"]
_GUIDE_WORDS  = ["推荐", "帮我选", "选一款", "适合", "什么手机好",
                 "送礼", "预算", "需要一个", "想买"]
_TIME_WORDS   = ["最新", "刚发布", "今年", "新款", "2026", "最近发布", "新出"]

# detail_inquiry 检测
_SPEC_Q_WORDS = ["快充", "充电", "电池", "续航", "芯片", "处理器",
                 "像素", "相机", "摄像", "屏幕", "防水", "nfc",
                 "存储", "参数", "规格", "5g", "mah"]
_SPEC_Q_PATTERNS = [
    r"支持.{0,8}吗", r"有没有.{0,8}", r"是否支持",
    r".{0,6}多大", r".{0,6}多少.{0,4}[mMgG]",
    r".{0,6}几[Ww寸]", r".{0,6}是什么", r".{0,6}怎么样",
    r"\S{1,6}吗$",     # 兜底：直接 X吗 结尾（防水吗/NFC吗）
]
_PRONOUN_REFS = ["这款", "这个", "它", "该机", "这部", "这手机"]
# 型号级关键词（配合数字检测用）
_MODEL_WORDS  = ["mate", "nova", "pura", "galaxy", "ace", "find",
                 "magic", "civi", "note", "ultra", "pro", "plus"]


def _is_detail_inquiry(msg: str) -> bool:
    """快速判断是否为规格追问：需同时满足（规格词 + 疑问句式 + 商品指代）。"""
    msg_lower = msg.lower()
    has_spec = any(w in msg_lower for w in _SPEC_Q_WORDS)
    has_q    = any(re.search(p, msg) for p in _SPEC_Q_PATTERNS)
    has_pronoun = any(w in msg for w in _PRONOUN_REFS)
    brand_hit   = (any(b in msg_lower for b in _BRAND_WORDS)
                   or any(m in msg_lower for m in _MODEL_WORDS))
    has_ref = has_pronoun or (brand_hit and bool(re.search(r"\d{2,}", msg)))
    return has_spec and has_q and has_ref

def _rule_based_intent(message: str) -> IntentResult:
    msg = message.lower()

    # out_of_scope: 明显非手机购物（V4-01 扩展词表）
    if re.search(
        r"天气|新闻|音乐|导航|翻译|股票|笑话"
        r"|外卖|点餐|订餐|美食|餐厅|饭店"
        r"|打车|叫车|出租车|网约车"
        r"|旅游|机票|火车票|酒店|民宿"
        r"|租房|买房|装修|搬家"
        r"|贷款|理财|基金|保险"
        r"|挂号|医院|看病|药"
        r"|快递|物流|寄件"
        r"|充话费|话费|流量",
        msg,
    ):
        return IntentResult("out_of_scope", 0.95, pipeline="fallback")

    # cross_platform: 比价/哪里买
    if re.search(r"哪里买|哪买|更便宜|京东|淘宝|拼多多|哪个平台", msg):
        return IntentResult("cross_platform", 0.85, pipeline="fallback")

    # detail_inquiry: 规格追问（这款支持快充吗 / iPhone16支持快充吗）
    if _is_detail_inquiry(message):
        return IntentResult("detail_inquiry", 0.88,
                            slots=_extract_basic_slots(msg), pipeline="llm")

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
        # spec_comparison：双品牌已确定，不让 GLM 覆盖 brand2
        if quick.intent_type == "spec_comparison" and "brand2" in quick.slots and quick.confidence >= 0.85:
            return quick
        # detail_inquiry：规则已能精准识别，直接返回，不需要 GLM 意图路由
        if quick.intent_type in DETAIL_INTENTS and quick.confidence >= 0.80:
            return quick

    result = await _glm_intent_router(message, history)

    # 本地规则兜底：GLM 漏掉的槽位（如 "3000以内" 这类短回复）用本地规则补上
    local_slots = _extract_basic_slots(message)
    for k, v in local_slots.items():
        if k not in result.slots:
            result.slots[k] = v

    return result


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

    # V4-02: check cache first
    cache_key = _rank_cache.make_key(
        sorted(p["id"] for p in candidates),
        json.dumps(slots, sort_keys=True, ensure_ascii=False),
        message,
    )
    cached = _rank_cache.get(cache_key)
    if cached is not None:
        return cached

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
        rank_result = RankResult(result, summary)
        _rank_cache.set(cache_key, rank_result)
        return rank_result
    except Exception:
        return _mock_rank(products, slots)


# ── 规格追问答题 ──────────────────────────────────────────────────────────

async def answer_spec_question(product: dict, question: str) -> str:
    """先走规则答题，无规则命中时 fallback 到 GLM。"""
    rule_answer = _spec_rule_answer(product, question)
    if rule_answer:
        return rule_answer
    if config.USE_MOCK_AI:
        return f"关于{product.get('title', '该产品')}的规格，建议查看商品详情页确认。"
    return await _glm_spec_answer(product, question)


def _spec_rule_answer(product: dict, question: str) -> Optional[str]:
    msg  = question.lower()
    name = product.get("title", "该产品")
    highlights = product.get("highlights", [])

    # 快充 / 充电速度
    if re.search(r"快充|充电速度|充多少|几[wW]|充电[wW]|充电功率", msg):
        for h in highlights:
            m = re.search(r"(\d+)[wW](超充|快充|充电)", h)
            if m:
                return f"{name} 支持 {m.group(1)}W {'超' if '超' in m.group(2) else ''}快充。"
        return f"关于 {name} 的快充规格，建议查看商品详情页确认。"

    # 电池 / 续航
    if re.search(r"电池|续航|mah|电量|耐用", msg):
        mah = product.get("battery_mah")
        if mah:
            level = "大容量" if mah >= 5500 else ("较大" if mah >= 4500 else "标准")
            return f"{name} 搭载 {mah}mAh {level}电池。"

    # 芯片 / 处理器
    if re.search(r"芯片|处理器|cpu|soc", msg):
        chip = product.get("chip")
        if chip:
            return f"{name} 搭载 {chip} 处理器。"

    # 存储
    if re.search(r"存储|rom|多少[gG]|几个[gG]", msg):
        storage = product.get("storage")
        if storage:
            return f"这款是 {storage}GB 存储版本。"

    # 相机 / 像素
    if re.search(r"相机|摄像|像素|拍照|摄影|镜头", msg):
        mp = product.get("camera_mp")
        cam_hl = [h for h in highlights if any(w in h for w in ["摄", "拍", "像素", "镜头", "变焦"])]
        if mp and cam_hl:
            return f"{name} 主摄 {mp}MP，亮点：{cam_hl[0]}。"
        if mp:
            return f"{name} 主摄为 {mp}MP。"
        if cam_hl:
            return "拍照亮点：" + "；".join(cam_hl[:2]) + "。"

    # 屏幕 / 尺寸
    if re.search(r"屏幕|尺寸|几寸|屏大", msg):
        size = product.get("screen_size")
        if size:
            return f"{name} 屏幕尺寸为 {size} 英寸。"

    # 防水
    if re.search(r"防水|ip\d|泼水|溅水", msg):
        for h in highlights:
            if re.search(r"ip\d+|防水", h.lower()):
                return f"{name} {h}。"
        return f"关于 {name} 的防水等级，建议查看商品详情页确认。"

    # NFC
    if re.search(r"nfc|近场支付|碰一碰", msg):
        for h in highlights:
            if "nfc" in h.lower():
                return f"{name} 支持 NFC。"
        return f"关于 {name} 的 NFC 支持，建议查看商品详情页确认。"

    # 5G
    if re.search(r"5g|5[gG]网络", msg):
        return f"{name} 支持 5G 网络。"

    # 颜色
    if re.search(r"颜色|配色|什么色", msg):
        color = product.get("color")
        if color:
            return f"该款为{color}配色。"

    # 价格
    if re.search(r"价格|多少钱|售价|价位|多贵", msg):
        price = product.get("price")
        if price:
            return f"{name} 售价 ¥{int(price):,}。"

    return None


async def _glm_spec_answer(product: dict, question: str) -> str:
    prod_summary = {k: product.get(k) for k in
                    ["title", "brand", "price", "chip", "storage",
                     "battery_mah", "camera_mp", "screen_size", "color",
                     "highlights", "tags"]}
    system = (
        "根据商品数据回答用户的规格问题，简洁（1-2句话）。"
        "如数据中没有该信息，诚实说建议查看商品详情页，不要编造。\n\n"
        f"商品：{json.dumps(prod_summary, ensure_ascii=False)}"
    )
    msgs = [{"role": "system", "content": system},
            {"role": "user",   "content": question}]
    try:
        return await _call_glm(config.GLM_FAST_MODEL, msgs, max_tokens=100)
    except Exception:
        return "关于该问题，建议查看商品详情页获取准确信息。"
