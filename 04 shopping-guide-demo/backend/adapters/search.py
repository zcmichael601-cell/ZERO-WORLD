"""
搜索适配器
─────────────────────────────────────────────────────────────
接入公司内部搜索算子。

【接入步骤】
1. 找技术同学确认搜索接口的：地址 / 鉴权方式 / 入参格式 / 出参格式
2. 在 config.py 里填写 SEARCH_API_URL 和 SEARCH_API_TOKEN
3. 把 config.py 里的 USE_MOCK_SEARCH 改为 false
4. 修改 _call_real_search() 里的请求参数和响应解析，匹配真实接口格式
"""

import httpx
from config import SEARCH_API_URL, SEARCH_API_TOKEN, USE_MOCK_SEARCH

# ── Mock 商品库（接入真实搜索后可删除）───────────────────────────────
_MOCK_PRODUCTS = {
    "手机": [
        {"name": "iPhone 15",     "desc": "A16芯片，48MP摄像头",   "price": "5999", "emoji": "📱"},
        {"name": "小米14",         "desc": "骁龙8 Gen3，徕卡影像",  "price": "3999", "emoji": "📱"},
        {"name": "华为Pura 70",    "desc": "麒麟芯片，卫星通信",    "price": "4999", "emoji": "📱"},
    ],
    "耳机": [
        {"name": "AirPods Pro 2",  "desc": "主动降噪，空间音频",    "price": "1899", "emoji": "🎧"},
        {"name": "索尼WH-1000XM5", "desc": "业界最强降噪",          "price": "2299", "emoji": "🎧"},
        {"name": "小米耳机4 Pro",  "desc": "性价比之选",            "price": "499",  "emoji": "🎧"},
    ],
    "电脑": [
        {"name": "MacBook Air M3", "desc": "轻薄长续航，18小时电池", "price": "8499", "emoji": "💻"},
        {"name": "联想小新Pro 16", "desc": "高刷屏，创作首选",       "price": "5999", "emoji": "💻"},
        {"name": "戴尔XPS 13",     "desc": "超薄商务本",            "price": "7299", "emoji": "💻"},
    ],
}


async def search_products(query: str, category: str = "", price_max: int = 0) -> list[dict]:
    """
    统一搜索入口，自动选择 mock 或真实服务。

    返回格式（每个商品）:
    {
        "name":  str,   # 商品名
        "desc":  str,   # 简介
        "price": str,   # 价格（字符串）
        "emoji": str,   # 图标（可选）
    }
    """
    if USE_MOCK_SEARCH:
        return _mock_search(category)
    return await _call_real_search(query, category, price_max)


def _mock_search(category: str) -> list[dict]:
    return _MOCK_PRODUCTS.get(category, [])


async def _call_real_search(query: str, category: str, price_max: int) -> list[dict]:
    """
    ★ 接入点：在这里替换为公司真实搜索接口

    示例（根据实际接口调整）:
    - 入参：query（搜索词）、category（品类）、priceMax（价格上限）
    - 出参：items 列表，每个包含商品信息
    """
    headers = {"Authorization": f"Bearer {SEARCH_API_TOKEN}"}
    payload = {
        "query":    query,
        "category": category,
        "priceMax": price_max,
        "pageSize": 5,
    }

    async with httpx.AsyncClient(timeout=5.0) as client:
        resp = await client.post(SEARCH_API_URL, json=payload, headers=headers)
        resp.raise_for_status()
        data = resp.json()

    # ★ 根据真实接口的返回结构调整这里的字段名
    items = data.get("items", data.get("result", data.get("data", [])))
    return [
        {
            "name":  item.get("itemName", item.get("name", "")),
            "desc":  item.get("itemDesc", item.get("desc", "")),
            "price": str(item.get("price", item.get("salePrice", ""))),
            "emoji": "📦",
        }
        for item in items
    ]
