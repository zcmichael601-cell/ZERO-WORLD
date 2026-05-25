import os
from pathlib import Path

_env_path = Path(__file__).parent / ".env"
if _env_path.exists():
    from dotenv import load_dotenv
    load_dotenv(_env_path)

# ── GLM AI ────────────────────────────────────────────────────────────
GLM_API_KEY    = os.getenv("GLM_API_KEY", "")
GLM_FAST_MODEL = "glm-4-flash"   # intent routing, slot extraction, clarification
GLM_SMART_MODEL = "glm-4"        # ranking, explanation generation

# ── 搜索/推荐算子（v0.3 阶段用 Mock）────────────────────────────────
SEARCH_API_URL      = os.getenv("SEARCH_API_URL",      "http://internal-search/api/search")
SEARCH_API_TOKEN    = os.getenv("SEARCH_API_TOKEN",    "")
RECOMMEND_API_URL   = os.getenv("RECOMMEND_API_URL",   "http://internal-recommend/api/recommend")
RECOMMEND_API_TOKEN = os.getenv("RECOMMEND_API_TOKEN", "")

# ── 功能开关 ──────────────────────────────────────────────────────────
USE_MOCK_AI        = os.getenv("USE_MOCK_AI",        "false").lower() == "true"
USE_MOCK_SEARCH    = os.getenv("USE_MOCK_SEARCH",    "true").lower()  == "true"
USE_MOCK_RECOMMEND = os.getenv("USE_MOCK_RECOMMEND", "true").lower()  == "true"

# ── 熔断器参数 ────────────────────────────────────────────────────────
CB_FAILURE_THRESHOLD = int(os.getenv("CB_FAILURE_THRESHOLD", "3"))
CB_RECOVERY_TIMEOUT  = int(os.getenv("CB_RECOVERY_TIMEOUT",  "30"))

# ── 超时参数（秒）────────────────────────────────────────────────────
GLM_TIMEOUT    = float(os.getenv("GLM_TIMEOUT",    "5.0"))
SEARCH_TIMEOUT = float(os.getenv("SEARCH_TIMEOUT", "0.2"))
