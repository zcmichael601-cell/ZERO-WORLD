"""
采集目标配置 — 按需增减频道、账号、播客
"""

# ── Podcast RSS 订阅源 ──
PODCAST_FEEDS = [
    {
        "name": "Lex Fridman Podcast",
        "rss":  "https://lexfridman.com/feed/podcast/",
        "lang": "en",
    },
    {
        "name": "a16z AI Podcast",
        "rss":  "https://feeds.simplecast.com/JGE3yC0V",
        "lang": "en",
    },
    {
        "name": "The TWIML AI Podcast",
        "rss":  "https://feeds.simplecast.com/A84DSRnV",
        "lang": "en",
    },
    {
        "name": "NVIDIA AI Podcast",
        "rss":  "https://blogs.nvidia.com/ai-podcast/feed/",
        "lang": "en",
    },
    {
        "name": "Hard Fork (NYT)",
        "rss":  "https://feeds.simplecast.com/l2i9YnTd",
        "lang": "en",
    },
]

# ── YouTube 频道 ID ──
# 在频道页 URL 中找：youtube.com/channel/UC...  或  youtube.com/@handle
YOUTUBE_CHANNELS = [
    {"id": "UCH-_hzb2ILSCo9ftVSnrCIQ", "name": "Yannic Kilcher"},
    {"id": "UCZHmQk67mSJgfCCTn7xBfew", "name": "Two Minute Papers"},
    {"id": "UCbmNph6atAoGfqLoCL_duAg", "name": "Andrej Karpathy"},
    {"id": "UCSHZKyawb77ixDdsGog4iWA", "name": "Lex Fridman"},
    {"id": "UCWX3yGbOHM3pPTXMFJHFnFg", "name": "AI Explained"},
    {"id": "UCVHgDC0lVfZKeoXUZm1sq8Q", "name": "Matt Wolfe"},
]

# YouTube 关键词搜索（补充频道订阅）
YOUTUBE_SEARCH_QUERIES = [
    "AI LLM 2026",
    "large language model latest",
    "OpenAI GPT release",
    "Anthropic Claude update",
    "AI agent tutorial",
]

# ── X 账号（通过 Nitter RSS 免费抓取）──
X_ACCOUNTS = [
    "sama",           # Sam Altman (OpenAI)
    "karpathy",       # Andrej Karpathy
    "ylecun",         # Yann LeCun (Meta)
    "demishassabis",  # Demis Hassabis (DeepMind)
    "GaryMarcus",     # Gary Marcus
    "goodfellow_ian", # Ian Goodfellow
    "tegmark",        # Max Tegmark
    "DrJimFan",       # Jim Fan (NVIDIA)
    "alexalbert__",   # Alex Albert (Anthropic)
    "bindureddy",     # Bindu Reddy
]

# Nitter 镜像实例（任一可用即可）
NITTER_INSTANCES = [
    "https://nitter.net",
    "https://nitter.cz",
    "https://nitter.privacydev.net",
]

# AI 相关关键词过滤（X 帖子需包含至少一个关键词才保留）
AI_KEYWORDS = [
    "AI", "LLM", "GPT", "Claude", "Gemini", "model", "agent", "inference",
    "fine-tun", "RLHF", "transformer", "neural", "OpenAI", "Anthropic",
    "DeepMind", "Meta AI", "Mistral", "人工智能", "大模型",
]
