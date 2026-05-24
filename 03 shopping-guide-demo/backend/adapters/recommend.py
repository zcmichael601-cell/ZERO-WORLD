"""
推荐算子适配器
─────────────────────────────────────────────────────────────
接入公司内部推荐算子（召回 + 排序）。

【接入步骤】
1. 找算法同学确认推荐接口：地址 / 入参（用户画像/上下文） / 出参格式
2. 在 config.py 填写 RECOMMEND_API_URL 和 RECOMMEND_API_TOKEN
3. 把 USE_MOCK_RECOMMEND 改为 false
4. 修改 _call_real_recommend() 里的字段映射
"""

import httpx
from config import RECOMMEND_API_URL, RECOMMEND_API_TOKEN, USE_MOCK_RECOMMEND

_MOCK_HOT = [
    {"name": "iPhone 15 Pro",   "desc": "本周热销 #1",  "price": "7999", "emoji": "🔥"},
    {"name": "索尼降噪耳机",    "desc": "用户好评 96%", "price": "1899", "emoji": "⭐"},
    {"name": "MacBook Air M3",  "desc": "生产力首选",   "price": "8499", "emoji": "💻"},
]


async def recommend_products(user_context: dict) -> list[dict]:
    """
    推荐入口。

    user_context 示例:
    {
        "session_id": "abc123",
        "history_keywords": ["手机", "拍照"],
        "price_sensitivity": "mid",   # low / mid / high
    }
    """
    if USE_MOCK_RECOMMEND:
        return _MOCK_HOT
    return await _call_real_recommend(user_context)


async def _call_real_recommend(user_context: dict) -> list[dict]:
    """
    ★ 接入点：替换为公司推荐算子接口

    推荐算子通常需要：
    - 用户 ID 或 session ID
    - 当前对话的意图/关键词
    - 可选：价格区间、品牌偏好
    """
    headers = {"Authorization": f"Bearer {RECOMMEND_API_TOKEN}"}
    payload = {
        "sessionId":       user_context.get("session_id", ""),
        "keywords":        user_context.get("history_keywords", []),
        "priceSensitivity": user_context.get("price_sensitivity", "mid"),
        "topN": 5,
    }

    async with httpx.AsyncClient(timeout=5.0) as client:
        resp = await client.post(RECOMMEND_API_URL, json=payload, headers=headers)
        resp.raise_for_status()
        data = resp.json()

    items = data.get("recommendations", data.get("items", []))
    return [
        {
            "name":  item.get("itemName", ""),
            "desc":  item.get("reason", item.get("desc", "")),
            "price": str(item.get("price", "")),
            "emoji": "⭐",
        }
        for item in items
    ]
