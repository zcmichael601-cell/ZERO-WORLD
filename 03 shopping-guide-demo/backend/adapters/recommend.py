"""
推荐算子适配器 v0.2 — Mock 返回热销手机，真实服务留接入点
"""

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


async def recommend_products(user_context: dict) -> list[dict]:
    if USE_MOCK_RECOMMEND:
        return _mock_recommend(user_context)
    return await _call_real_recommend(user_context)


def _mock_recommend(user_context: dict) -> list[dict]:
    phones = _load_phones()
    sensitivity = user_context.get("price_sensitivity", "mid")

    price_filtered = phones
    if sensitivity == "low":
        price_filtered = [p for p in phones if p.get("price", 0) < 2000]
    elif sensitivity == "mid":
        price_filtered = [p for p in phones if 1500 < p.get("price", 0) < 5000]
    elif sensitivity == "high":
        price_filtered = [p for p in phones if p.get("price", 0) >= 5000]

    # 按销量+评分排序
    ranked = sorted(
        price_filtered or phones,
        key=lambda p: p.get("sales", 0) * p.get("rating", 4.0),
        reverse=True,
    )
    top = ranked[:6]
    for p in top:
        p.setdefault("reason", "热销推荐")
    return top


async def _call_real_recommend(user_context: dict) -> list[dict]:
    """★ 接入点：替换为公司推荐算子接口"""
    headers = {"Authorization": f"Bearer {RECOMMEND_API_TOKEN}"}
    payload = {
        "sessionId":        user_context.get("session_id", ""),
        "keywords":         user_context.get("history_keywords", []),
        "priceSensitivity": user_context.get("price_sensitivity", "mid"),
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
