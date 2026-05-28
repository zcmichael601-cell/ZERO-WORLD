"""推荐适配器 v0.4 — 场景感知 + 关键词关联 + 个性化推荐理由，真实服务留接入点。"""

import json
from pathlib import Path

import httpx
from config import RECOMMEND_API_URL, RECOMMEND_API_TOKEN, USE_MOCK_RECOMMEND

_DATA_PATH = Path(__file__).parent.parent / "data" / "phones.json"
_PHONES: list[dict] = []


def _load_phones() -> list[dict]:
    global _PHONES
    if not _PHONES:
        try:
            _PHONES = json.loads(_DATA_PATH.read_text(encoding="utf-8"))
        except Exception:
            _PHONES = []
    return _PHONES


async def get_trending(
    scenario: str = "",
    price_max: int = 0,
    limit: int = 12,
) -> list[dict]:
    """发现页热门商品，按销量×评分排序，支持场景和价格过滤。"""
    phones = _load_phones()
    pool = phones
    if price_max:
        pool = [p for p in pool if p.get("price", 0) <= price_max]
    if scenario:
        def rank_score(p):
            s = p.get("sales", 0) * 0.00001 + p.get("rating", 4.0) * 2.0
            if any(scenario in t for t in p.get("tags", [])):
                s += 3.0
            return s
    else:
        def rank_score(p):
            return p.get("sales", 0) * p.get("rating", 4.0)

    ranked = sorted(pool, key=rank_score, reverse=True)[:limit]
    # 不修改原数据，返回副本
    return [dict(p) for p in ranked]


async def recommend_products(user_context: dict) -> list[dict]:
    if USE_MOCK_RECOMMEND:
        return _mock_recommend(user_context)
    return await _call_real_recommend(user_context)


# ── 推荐理由生成 ─────────────────────────────────────────────────────

_SCENARIO_REASONS = {
    "游戏":  "游戏性能强劲，高帧率流畅体验",
    "拍照":  "拍照实力出众，夜拍细节丰富",
    "商务":  "商务续航优秀，轻薄便携",
    "学生":  "性价比超高，学生首选",
    "直播":  "前摄出色，直播颜值加分",
}


def _build_reason(p: dict, sensitivity: str, scenario: str, keywords: list) -> str:
    tags = p.get("tags", [])
    # 场景匹配
    if scenario and scenario in _SCENARIO_REASONS:
        scenario_kw = scenario
        if any(scenario_kw in t for t in tags):
            return _SCENARIO_REASONS[scenario_kw]
    # 关键词匹配
    for kw in keywords:
        if any(kw in t for t in tags) or kw in p.get("title", ""):
            return f"与你的需求「{kw}」高度吻合"
    # 价格段通用理由
    price = p.get("price", 0)
    if sensitivity == "low" or price < 2000:
        return f"高性价比入门之选，仅 ¥{int(price)}"
    if sensitivity == "high" or price >= 6000:
        return "旗舰配置，顶级体验"
    sales = p.get("sales", 0)
    if sales > 50000:
        return f"热销超{sales // 10000}万台，口碑验证"
    return "综合评分出色，放心购"


def _mock_recommend(user_context: dict) -> list[dict]:
    phones = _load_phones()
    sensitivity = user_context.get("price_sensitivity", "mid")
    scenario    = user_context.get("scenario", "")
    keywords    = user_context.get("history_keywords", [])

    # 价格段筛选
    if sensitivity == "low":
        pool = [p for p in phones if p.get("price", 0) < 2000]
    elif sensitivity == "high":
        pool = [p for p in phones if p.get("price", 0) >= 5000]
    else:
        pool = [p for p in phones if 1500 < p.get("price", 0) < 5500]

    if not pool:
        pool = phones

    # 场景标签加权
    scenario_kws = {
        "游戏": "游戏", "拍照": "拍照", "商务": "商务", "学生": "学生",
    }
    tag_kw = scenario_kws.get(scenario, "")

    def score(p: dict) -> float:
        s = p.get("sales", 0) * 0.00001 + p.get("rating", 4.0) * 2.0
        if tag_kw and any(tag_kw in t for t in p.get("tags", [])):
            s += 3.0
        for kw in keywords:
            if kw in p.get("title", "") or any(kw in t for t in p.get("tags", [])):
                s += 1.5
        return s

    ranked = sorted(pool, key=score, reverse=True)[:6]

    result = []
    for p in ranked:
        p = dict(p)
        p["reason"] = _build_reason(p, sensitivity, scenario, keywords)
        result.append(p)
    return result


async def _call_real_recommend(user_context: dict) -> list[dict]:
    """★ 接入点：替换为公司推荐算子接口。"""
    headers = {"Authorization": f"Bearer {RECOMMEND_API_TOKEN}"}
    payload = {
        "sessionId":        user_context.get("session_id", ""),
        "keywords":         user_context.get("history_keywords", []),
        "priceSensitivity": user_context.get("price_sensitivity", "mid"),
        "scenario":         user_context.get("scenario", ""),
        "topN": 6,
    }
    async with httpx.AsyncClient(timeout=5.0) as client:
        resp = await client.post(RECOMMEND_API_URL, json=payload, headers=headers)
        resp.raise_for_status()
        data = resp.json()

    items = data.get("recommendations", data.get("items", []))
    return [
        {
            "id":         item.get("skuId", item.get("id", "")),
            "title":      item.get("itemName", item.get("title", "")),
            "price":      float(item.get("price", 0)),
            "image":      item.get("imageUrl", item.get("image", "")),
            "rating":     float(item.get("score", item.get("rating", 4.0))),
            "sales":      int(item.get("salesCount", item.get("sales", 0))),
            "reason":     item.get("reason", "推荐"),
            "highlights": item.get("highlights", []),
        }
        for item in items
    ]
