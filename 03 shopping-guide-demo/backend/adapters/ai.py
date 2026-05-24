"""
AI 适配器（意图理解 + 回复生成）
─────────────────────────────────────────────────────────────
接入 Claude API 做槽位提取和自然语言生成。
USE_MOCK_AI=true 时用规则代替，不消耗 API 额度。
"""

import json
import httpx
from config import CLAUDE_API_KEY, CLAUDE_MODEL, USE_MOCK_AI

_INTENT_KEYWORDS = ["手机", "耳机", "电脑", "平板", "相机", "手表"]


async def extract_intent(history: list[dict]) -> dict:
    """
    从对话历史里提取用户意图。

    返回:
    {
        "category":   str,   # 品类，如 "手机"，未识别为 ""
        "price_max":  int,   # 价格上限，0 表示未指定
        "keywords":   list,  # 关键词列表
        "is_clear":   bool,  # 意图是否足够清晰，可以去搜索了
        "follow_up":  str,   # 如果不清晰，要追问的问题
    }
    """
    if USE_MOCK_AI:
        return _mock_extract_intent(history)
    return await _claude_extract_intent(history)


async def generate_reply(intent: dict, products: list[dict]) -> str:
    """根据意图和商品列表生成自然语言回复。"""
    if USE_MOCK_AI:
        return _mock_generate_reply(intent, products)
    return await _claude_generate_reply(intent, products)


# ── Mock 实现（规则逻辑）────────────────────────────────────────────
def _mock_extract_intent(history: list[dict]) -> dict:
    last = next((m["content"] for m in reversed(history) if m["role"] == "user"), "")

    category = next((k for k in _INTENT_KEYWORDS if k in last), "")
    price_max = 0
    for token in last.replace("以内", "").replace("左右", "").split():
        if token.isdigit():
            price_max = int(token)
            break

    if not category:
        return {"category": "", "price_max": 0, "keywords": [],
                "is_clear": False, "follow_up": "你想买什么类型的商品呢？比如手机、耳机或电脑？"}

    if not price_max and len(history) <= 2:
        return {"category": category, "price_max": 0, "keywords": [category],
                "is_clear": False, "follow_up": f"好的，你想买{category}！预算大概是多少呢？"}

    return {"category": category, "price_max": price_max,
            "keywords": [category], "is_clear": True, "follow_up": ""}


def _mock_generate_reply(intent: dict, products: list[dict]) -> str:
    cat   = intent.get("category", "商品")
    price = intent.get("price_max", 0)
    budget_str = f"{price}以内" if price else ""
    if products:
        return f"根据你的需求，为你找到以下{budget_str}{cat}，都是目前口碑最好的型号 👇"
    return f"暂时没有找到符合条件的{cat}，可以换个关键词试试？"


# ── Claude API 实现 ──────────────────────────────────────────────────
async def _claude_extract_intent(history: list[dict]) -> dict:
    """★ 接入点：用 Claude 做意图提取"""
    system = """你是一个购物导购助手，从对话历史中提取用户购物意图。
以 JSON 格式返回（不要加代码块），字段：
- category: 商品品类（手机/耳机/电脑等，识别不到为空字符串）
- price_max: 价格上限整数（没提到为0）
- keywords: 关键词列表
- is_clear: 意图是否清晰到可以去搜索（布尔）
- follow_up: 如果不清晰，需要追问的问题（字符串）"""

    messages = [{"role": m["role"], "content": m["content"]} for m in history]

    resp = await _call_claude(system, messages)
    try:
        return json.loads(resp)
    except Exception:
        return {"category": "", "price_max": 0, "keywords": [],
                "is_clear": False, "follow_up": "请问你想买什么呢？"}


async def _claude_generate_reply(intent: dict, products: list[dict]) -> str:
    """★ 接入点：用 Claude 生成自然语言推荐语"""
    system = "你是一个亲切的购物导购助手，根据搜索结果生成简洁的推荐语（50字以内）。"
    content = f"用户意图：{intent}\n搜索到的商品：{products}"
    return await _call_claude(system, [{"role": "user", "content": content}])


async def _call_claude(system: str, messages: list) -> str:
    headers = {
        "x-api-key":         CLAUDE_API_KEY,
        "anthropic-version": "2023-06-01",
        "content-type":      "application/json",
    }
    payload = {
        "model":      CLAUDE_MODEL,
        "max_tokens": 512,
        "system":     system,
        "messages":   messages,
    }
    async with httpx.AsyncClient(timeout=15.0) as client:
        resp = await client.post("https://api.anthropic.com/v1/messages",
                                 json=payload, headers=headers)
        resp.raise_for_status()
        return resp.json()["content"][0]["text"]
