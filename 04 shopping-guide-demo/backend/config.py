import os

# ── AI 服务 ───────────────────────────────────────────────────────────
CLAUDE_API_KEY   = os.getenv("CLAUDE_API_KEY", "")
CLAUDE_MODEL     = "claude-sonnet-4-6"

# ── 公司内部搜索服务（替换为真实地址）────────────────────────────────
SEARCH_API_URL   = os.getenv("SEARCH_API_URL",    "http://internal-search-service/api/search")
SEARCH_API_TOKEN = os.getenv("SEARCH_API_TOKEN",  "")

# ── 公司内部推荐算子（替换为真实地址）────────────────────────────────
RECOMMEND_API_URL   = os.getenv("RECOMMEND_API_URL",   "http://internal-recommend-service/api/recommend")
RECOMMEND_API_TOKEN = os.getenv("RECOMMEND_API_TOKEN", "")

# ── 开关：True 用 mock 数据，False 调真实服务 ─────────────────────────
USE_MOCK_SEARCH    = os.getenv("USE_MOCK_SEARCH",    "true").lower() == "true"
USE_MOCK_RECOMMEND = os.getenv("USE_MOCK_RECOMMEND", "true").lower() == "true"
USE_MOCK_AI        = os.getenv("USE_MOCK_AI",        "true").lower() == "true"
