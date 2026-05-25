"""
搜索适配器 v0.2 — Mock 直接从 phones.json 检索，真实服务留接入点
"""

import json
import re
import os
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
) -> list[dict]:
    if USE_MOCK_SEARCH:
        return _mock_search(query, price_max, brand)
    return await _call_real_search(query, category, price_max, brand)


def _mock_search(query: str, price_max: int, brand: str) -> list[dict]:
    phones = _load_phones()
    query_lower = query.lower()

    results = []
    for p in phones:
        # 品牌过滤
        if brand and p.get("brand", "") != brand:
            continue
        # 价格过滤
        if price_max and p.get("price", 0) > price_max:
            continue

        # 关键词匹配分
        score = 0.0
        title_lower = p.get("title", "").lower()
        for token in re.split(r"[\s,，。、]+", query_lower):
            if not token:
                continue
            if token in title_lower:
                score += 2.0
            elif any(token in h.lower() for h in p.get("highlights", [])):
                score += 1.0
            elif any(token in t for t in p.get("tags", [])):
                score += 0.5

        # 销量 + 评分基础分
        score += p.get("rating", 4.0) * 0.3
        score += min(p.get("sales", 0) / 50000, 1.0) * 0.2

        if score > 0.3 or (not brand and not price_max):
            results.append((score, p))

    results.sort(key=lambda x: -x[0])
    return [item for _, item in results[:10]]


async def _call_real_search(
    query: str, category: str, price_max: int, brand: str
) -> list[dict]:
    """★ 接入点：替换为公司真实搜索接口"""
    headers = {"Authorization": f"Bearer {SEARCH_API_TOKEN}"}
    payload = {
        "query":    query,
        "category": category,
        "brand":    brand,
        "priceMax": price_max,
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
