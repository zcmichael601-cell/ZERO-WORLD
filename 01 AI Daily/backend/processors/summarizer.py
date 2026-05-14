"""
用 Claude API 对原始内容生成摘要、核心要点和标签
"""

import os
import anthropic

_client = None

def _get_client():
    global _client
    if _client is None:
        _client = anthropic.Anthropic(api_key=os.environ["ANTHROPIC_API_KEY"])
    return _client


SYSTEM_PROMPT = """你是一个专注于 AI 领域的资讯编辑，擅长提炼技术内容的核心价值。
你的任务是将原始内容（播客文字稿、YouTube 描述或推文）整理成结构化摘要。

输出必须是合法 JSON，格式如下：
{
  "summary": "2-3句话的摘要，用中文，说清楚这条内容讲了什么",
  "core_content": [
    "核心要点1，具体、有信息量，避免废话",
    "核心要点2",
    "核心要点3",
    "核心要点4（可选）",
    "核心要点5（可选）"
  ],
  "tags": ["标签1", "标签2", "标签3", "标签4", "标签5"]
}

要求：
- summary 用中文，客观陈述内容，不要用「本文」「本视频」等开头
- core_content 每条15-40字，提炼真正有价值的信息点，3-5条
- tags 选 3-5 个，优先用专有名词（模型名、公司名、技术术语），其次是主题词
- 如果原文是英文，输出依然用中文
- 只输出 JSON，不要加任何额外说明"""


def summarize(title: str, raw_text: str, platform: str) -> dict:
    """
    返回 {"summary": ..., "core_content": [...], "tags": [...]}
    失败时返回 fallback 结构
    """
    # 截断过长文本（节省 token）
    max_chars = 4000
    if len(raw_text) > max_chars:
        raw_text = raw_text[:max_chars] + "\n...[内容已截断]"

    prompt = f"""平台：{platform}
标题：{title}

原文内容：
{raw_text}

请按要求输出 JSON 摘要。"""

    try:
        client = _get_client()
        msg = client.messages.create(
            model="claude-haiku-4-5-20251001",
            max_tokens=800,
            system=SYSTEM_PROMPT,
            messages=[{"role": "user", "content": prompt}],
        )
        import json
        text = msg.content[0].text.strip()
        # 提取 JSON（防止模型多输出了注释或 markdown 代码块）
        if "```" in text:
            text = text.split("```")[1]
            if text.startswith("json"):
                text = text[4:]
        return json.loads(text)
    except Exception as e:
        print(f"  [summarizer] 失败: {e}")
        return _fallback(title)


def _fallback(title: str) -> dict:
    return {
        "summary": title,
        "core_content": ["（摘要生成失败，请查看原文）"],
        "tags": ["AI"],
    }
