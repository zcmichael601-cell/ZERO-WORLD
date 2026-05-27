"""搜索适配器 v0.4 — 增强 Mock（scenario / price_min / 场景评分），真实服务留接入点。"""

import json
import re
from pathlib import Path

import httpx
from config import SEARCH_API_URL, SEARCH_API_TOKEN, USE_MOCK_SEARCH

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


async def search_products(
    query: str,
    category: str = "",
    price_max: int = 0,
    brand: str = "",
    price_min: int = 0,
    scenario: str = "",
) -> list[dict]:
    if USE_MOCK_SEARCH:
        return _mock_search(query, price_max, brand, price_min, scenario)
    return await _call_real_search(query, category, price_max, brand, price_min, scenario)


# ── 场景 → 标签映射 ─────────────────────────────────────────────────

_SCENARIO_TAGS = {
    "游戏":  ["游戏", "高刷新率", "强散热", "大电池"],
    "拍照":  ["拍照", "超广角", "夜拍", "专业相机"],
    "商务":  ["商务", "长续航", "轻薄", "快充"],
    "学生":  ["学生", "性价比", "大内存", "续航"],
    "直播":  ["直播", "前置", "颜值", "自拍"],
    "老人":  ["大屏", "大字体", "简洁", "长辈"],
}


def _mock_search(
    query: str,
    price_max: int,
    brand: str,
    price_min: int = 0,
    scenario: str = "",
) -> list[dict]:
    phones = _load_phones()
    query_lower = query.lower()
    scenario_tags = _SCENARIO_TAGS.get(scenario, [])

    results = []
    for p in phones:
        # 品牌过滤（精确匹配）
        if brand and p.get("brand", "") != brand:
            continue
        # 价格上限过滤
        if price_max and p.get("price", 0) > price_max:
            continue
        # 价格下限过滤
        if price_min and p.get("price", 0) < price_min:
            continue

        score = 0.0
        title_lower = p.get("title", "").lower()
        tags = p.get("tags", [])
        highlights = p.get("highlights", [])

        # 关键词匹配
        for token in re.split(r"[\s,，。、]+", query_lower):
            if not token or len(token) < 2:
                continue
            if token in title_lower:
                score += 2.0
            elif any(token in h.lower() for h in highlights):
                score += 1.0
            elif any(token in t for t in tags):
                score += 0.5

        # 场景标签加权
        if scenario_tags:
            matched = sum(1 for st in scenario_tags if any(st in t for t in tags + highlights))
            score += matched * 0.8

        # 销量 + 评分基础分
        score += p.get("rating", 4.0) * 0.3
        score += min(p.get("sales", 0) / 50000, 1.0) * 0.2

        # 有品牌/价格约束时，0 分商品也纳入；否则需基础相关性
        threshold = 0.0 if (brand or price_max or price_min) else 0.3
        if score > threshold or not query_lower.strip():
            results.append((score, p))

    results.sort(key=lambda x: -x[0])
    return [item for _, item in results[:10]]


async def _call_real_search(
    query: str, category: str,
    price_max: int, brand: str,
    price_min: int = 0, scenario: str = "",
) -> list[dict]:
    """★ 接入点：替换为公司真实搜索接口。"""
    headers = {"Authorization": f"Bearer {SEARCH_API_TOKEN}"}
    payload = {
        "query":    query,
        "category": category,
        "brand":    brand,
        "priceMax": price_max,
        "priceMin": price_min,
        "scenario": scenario,
        "pageSize": 10,
    }
    async with httpx.AsyncClient(timeout=5.0) as client:
        resp = await client.post(SEARCH_API_URL, json=payload, headers=headers)
        resp.raise_for_status()
        data = resp.json()

    items = data.get("items", data.get("result", data.get("data", [])))
    return [
        {
            "id":         item.get("skuId", item.get("id", "")),
            "title":      item.get("itemName", item.get("title", "")),
            "price":      float(item.get("price", item.get("salePrice", 0))),
            "image":      item.get("imageUrl", item.get("image", "")),
            "rating":     float(item.get("score", item.get("rating", 4.0))),
            "sales":      int(item.get("salesCount", item.get("sales", 0))),
            "highlights": item.get("highlights", []),
        }
        for item in items
    ]
