"""
购物导购助手 v1.0 后端
架构：SSE 流式输出 + 三链路路由（传统搜索 / LLM / 兜底）+ 熔断器 + 指标监控
"""

import json
import logging
import time
import asyncio
from contextlib import asynccontextmanager
from pathlib import Path
from typing import AsyncGenerator

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import StreamingResponse
from pydantic import ValidationError

import config
from models.request import ChatRequest
from models.response import (
    IntentPayload, ThinkingPayload, ClarifyPayload,
    RankPayload, ProductItem, DonePayload, ErrorPayload,
)
from adapters.ai import (
    intent_router, clarification_generator, product_ranker,
    TRADITIONAL_INTENTS, LLM_INTENTS, FALLBACK_INTENTS,
    _rank_cache,
)
from adapters.search import search_products
from adapters.recommend import recommend_products, get_trending
from utils.circuit_breaker import CircuitBreaker
import utils.metrics as _metrics


# ── 日志配置 ──────────────────────────────────────────────────────────

logging.basicConfig(
    level=getattr(logging, config.LOG_LEVEL, logging.INFO),
    format='{"time":"%(asctime)s","level":"%(levelname)s","msg":%(message)s}',
    datefmt="%Y-%m-%dT%H:%M:%S",
)
log = logging.getLogger("guide")


# ── 启动校验 ──────────────────────────────────────────────────────────

_DATA_PATH = Path(__file__).parent / "data" / "phones.json"

def _startup_checks() -> dict:
    issues = []
    warnings = []

    if not _DATA_PATH.exists():
        issues.append("phones.json not found")
    else:
        try:
            phones = json.loads(_DATA_PATH.read_text(encoding="utf-8"))
            if not phones:
                issues.append("phones.json is empty")
            else:
                log.info(f'"Loaded phones.json: {len(phones)} entries"')
        except Exception as e:
            issues.append(f"phones.json parse error: {e}")

    if not config.USE_MOCK_AI and not config.GLM_API_KEY:
        issues.append("GLM_API_KEY not set (required when USE_MOCK_AI=false)")

    if issues:
        for msg in issues:
            log.error(f'"Startup check FAILED: {msg}"')
    else:
        log.info('"All startup checks passed"')

    return {"ok": len(issues) == 0, "issues": issues, "warnings": warnings}


@asynccontextmanager
async def lifespan(app: FastAPI):
    result = _startup_checks()
    if not result["ok"]:
        log.warning(f'"Starting with issues: {result["issues"]}"')
    yield


app = FastAPI(title="购物导购助手 v1.0", lifespan=lifespan)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"], allow_methods=["*"], allow_headers=["*"],
)

_glm_cb = CircuitBreaker(
    failure_threshold=config.CB_FAILURE_THRESHOLD,
    recovery_timeout=config.CB_RECOVERY_TIMEOUT,
)


# ── SSE 工具函数 ──────────────────────────────────────────────────────

def _sse(event: str, data: dict) -> str:
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"


def _done_payload(pipeline: str, intent_type: str,
                  start_ms: float, clarify_turns: int = 0) -> dict:
    return DonePayload(
        pipeline      = pipeline,
        intent_type   = intent_type,
        latency_ms    = int((time.monotonic() - start_ms) * 1000),
        clarify_turns = clarify_turns,
    ).model_dump()


def _sse_done(pipeline: str, intent_type: str,
              start_ms: float, clarify_turns: int = 0,
              is_error: bool = False) -> str:
    payload = _done_payload(pipeline, intent_type, start_ms, clarify_turns)
    _metrics.record(pipeline, intent_type, payload["latency_ms"], is_error)
    log.info(
        f'"pipeline":"{pipeline}","intent":"{intent_type}",'
        f'"latency_ms":{payload["latency_ms"]},"error":{str(is_error).lower()}'
    )
    return _sse("done", payload)


# ── 兜底链路 ──────────────────────────────────────────────────────────

async def _fallback_pipeline(
    req: ChatRequest,
    trigger_reason: str,
    start_ms: float,
    intent_type: str = "unknown",
    clarify_turns: int = 0,
) -> AsyncGenerator[str, None]:
    fallback_texts = {
        "llm_open":       "AI 服务暂时繁忙，给你看看今天大家都在买的手机 👇",
        "llm_timeout":    "响应有点慢，先给你推荐热门手机，稍后可以重试 👇",
        "llm_error":      "出了点小问题，这些手机口碑都很不错 👇",
        "search_empty":   "没找到完全匹配的，这些热销手机供你参考 👇",
        "out_of_scope":   "我专门帮你找手机，你可以告诉我想要什么类型的手机？",
        "cross_platform": "我是京东导购助手，帮你在京东找到最合适的手机～",
        "clarify_limit":  "问了几轮还没确认需求，先看看热门推荐，你可以继续告诉我要求 👇",
    }
    reply_text = fallback_texts.get(trigger_reason,
                                    "遇到了一点问题，这些热门手机供你参考 👇")

    if trigger_reason in ("out_of_scope", "cross_platform"):
        yield _sse("thinking", ThinkingPayload(message=reply_text).model_dump())
        yield _sse_done("fallback", intent_type, start_ms, clarify_turns)
        return

    yield _sse("thinking", ThinkingPayload(message=reply_text).model_dump())

    try:
        user_context = {"history_keywords": [], "price_sensitivity": "mid"}
        products = await recommend_products(user_context)
    except Exception:
        products = []

    for p in products[:4]:
        yield _sse("product", ProductItem(**{
            "id":       p.get("id", ""),
            "title":    p.get("title", ""),
            "price":    p.get("price", 0),
            "image":    p.get("image", ""),
            "rating":   p.get("rating", 4.0),
            "sales":    p.get("sales", 0),
            "reason":   p.get("reason", "热销推荐"),
            "highlights": p.get("highlights", []),
        }).model_dump())
        await asyncio.sleep(0)

    yield _sse_done("fallback", intent_type, start_ms, clarify_turns)


# ── 传统搜索链路 ──────────────────────────────────────────────────────

async def _traditional_pipeline(
    req: ChatRequest,
    intent_result,
    start_ms: float,
) -> AsyncGenerator[str, None]:
    slots = intent_result.slots

    yield _sse("intent", IntentPayload(
        intent_type = intent_result.intent_type,
        confidence  = intent_result.confidence,
        pipeline    = "traditional",
        slots       = slots,
    ).model_dump())

    yield _sse("thinking", ThinkingPayload(message="正在搜索商品…").model_dump())

    try:
        products = await search_products(
            query     = req.message,
            category  = "手机",
            price_max = slots.get("price_max", 0),
            brand     = slots.get("brand", ""),
            price_min = slots.get("price_min", 0),
            scenario  = slots.get("scenario", ""),
        )
    except Exception:
        async for chunk in _fallback_pipeline(
            req, "llm_error", start_ms,
            intent_type=intent_result.intent_type,
        ):
            yield chunk
        return

    if not products:
        async for chunk in _fallback_pipeline(
            req, "search_empty", start_ms,
            intent_type=intent_result.intent_type,
        ):
            yield chunk
        return

    for p in products[:6]:
        yield _sse("product", ProductItem(**{
            "id":       p.get("id", ""),
            "title":    p.get("title", ""),
            "price":    p.get("price", 0),
            "image":    p.get("image", ""),
            "rating":   p.get("rating", 4.0),
            "sales":    p.get("sales", 0),
            "reason":   None,
            "highlights": p.get("highlights", []),
        }).model_dump())
        await asyncio.sleep(0)

    yield _sse_done("traditional", intent_result.intent_type, start_ms)


# ── LLM 链路 ──────────────────────────────────────────────────────────

async def _llm_pipeline(
    req: ChatRequest,
    intent_result,
    start_ms: float,
) -> AsyncGenerator[str, None]:
    slots        = intent_result.slots
    history      = [m.model_dump() for m in req.history]
    clarify_turns = sum(1 for m in history if m["role"] == "assistant"
                        and ("吗" in m["content"] or "？" in m["content"]
                             or "?" in m["content"]))

    yield _sse("intent", IntentPayload(
        intent_type = intent_result.intent_type,
        confidence  = intent_result.confidence,
        pipeline    = "llm",
        slots       = slots,
    ).model_dump())

    # 熔断检查
    if _glm_cb.is_open:
        async for chunk in _fallback_pipeline(
            req, "llm_open", start_ms,
            intent_type=intent_result.intent_type,
            clarify_turns=clarify_turns,
        ):
            yield chunk
        return

    # 槽位不足且追问未超限 → 追问
    # spec_comparison：用户已经在消息里指明要对比的对象，不需要再追问
    needs_clarify = (
        "price_max" not in slots
        and "scenario" not in slots
        and "brand" not in slots
        and clarify_turns < 3
        and intent_result.intent_type != "spec_comparison"
    )
    if needs_clarify:
        try:
            clarify = await clarification_generator(req.message, history, slots)
            _glm_cb.record_success()
            yield _sse("clarify", ClarifyPayload(
                question = clarify.question,
                options  = clarify.options,
            ).model_dump())
            yield _sse_done("llm", intent_result.intent_type,
                            start_ms, clarify_turns + 1)
            return
        except Exception:
            _glm_cb.record_failure()
            if _glm_cb.is_open:
                async for chunk in _fallback_pipeline(
                    req, "llm_open", start_ms,
                    intent_type=intent_result.intent_type,
                    clarify_turns=clarify_turns,
                ):
                    yield chunk
                return

    # 追问超限 → 兜底
    if clarify_turns >= 3 and "price_max" not in slots and "brand" not in slots:
        async for chunk in _fallback_pipeline(
            req, "clarify_limit", start_ms,
            intent_type=intent_result.intent_type,
            clarify_turns=clarify_turns,
        ):
            yield chunk
        return

    # 搜索商品（spec_comparison 时双品牌各搜一批）
    thinking_msg = (
        "正在对比两款手机…"
        if intent_result.intent_type == "spec_comparison"
        else "正在为你筛选合适的手机…"
    )
    yield _sse("thinking", ThinkingPayload(message=thinking_msg).model_dump())

    try:
        if intent_result.intent_type == "spec_comparison" and "brand2" in slots:
            results_a = await search_products(
                query="", category="手机", price_max=0, brand=slots["brand"]
            )
            results_b = await search_products(
                query="", category="手机", price_max=0, brand=slots["brand2"]
            )
            # 交错合并，各取 top-3
            products = []
            for a, b in zip(results_a[:3], results_b[:3]):
                products.append(a)
                products.append(b)
        else:
            products = await search_products(
                query     = req.message,
                category  = "手机",
                price_max = slots.get("price_max", 0),
                brand     = slots.get("brand", ""),
                price_min = slots.get("price_min", 0),
                scenario  = slots.get("scenario", ""),
            )
        if not products:
            user_context = {
                "history_keywords": [req.message],
                "price_sensitivity": (
                    "low" if slots.get("price_max", 0) > 0
                             and slots.get("price_max", 0) < 2000
                    else "mid"
                ),
                "scenario": slots.get("scenario", ""),
            }
            products = await recommend_products(user_context)
    except Exception:
        _glm_cb.record_failure()
        async for chunk in _fallback_pipeline(
            req, "llm_error", start_ms,
            intent_type=intent_result.intent_type,
            clarify_turns=clarify_turns,
        ):
            yield chunk
        return

    if not products:
        async for chunk in _fallback_pipeline(
            req, "search_empty", start_ms,
            intent_type=intent_result.intent_type,
            clarify_turns=clarify_turns,
        ):
            yield chunk
        return

    # LLM 排序
    yield _sse("thinking", ThinkingPayload(message="AI 正在分析最适合你的方案…").model_dump())

    try:
        rank_result = await product_ranker(products, slots, req.message)
        _glm_cb.record_success()
    except Exception:
        _glm_cb.record_failure()
        rank_result = None

    if rank_result:
        ranked_products = rank_result.products
        summary         = rank_result.summary
    else:
        ranked_products = products[:4]
        summary         = ""

    for p in ranked_products:
        yield _sse("product", ProductItem(**{
            "id":       p.get("id", ""),
            "title":    p.get("title", ""),
            "price":    p.get("price", 0),
            "image":    p.get("image", ""),
            "rating":   p.get("rating", 4.0),
            "sales":    p.get("sales", 0),
            "reason":   p.get("reason"),
            "highlights": p.get("highlights", []),
        }).model_dump())
        await asyncio.sleep(0)

    if summary and len(ranked_products) >= 3:
        yield _sse("rank", RankPayload(
            summary  = summary,
            products = [],
        ).model_dump())

    yield _sse_done("llm", intent_result.intent_type, start_ms, clarify_turns)


# ── 主路由入口 ────────────────────────────────────────────────────────

@app.post("/chat")
async def chat(request: Request):
    start_ms = time.monotonic()

    try:
        body = await request.json()
        req  = ChatRequest(**body)
    except (ValidationError, Exception) as e:
        _err_msg = str(e)
        async def _err_gen():
            yield _sse("error", ErrorPayload(
                message=_err_msg, code="invalid_request"
            ).model_dump())
        _metrics.record("error", "invalid_request",
                        int((time.monotonic() - start_ms) * 1000), is_error=True)
        return StreamingResponse(_err_gen(), media_type="text/event-stream")

    async def _generate():
        try:
            history = [m.model_dump() for m in req.history]
            intent_result = await intent_router(req.message, history)

            if intent_result.pipeline == "traditional":
                async for chunk in _traditional_pipeline(req, intent_result, start_ms):
                    yield chunk

            elif intent_result.pipeline == "llm":
                async for chunk in _llm_pipeline(req, intent_result, start_ms):
                    yield chunk

            else:
                yield _sse("intent", IntentPayload(
                    intent_type = intent_result.intent_type,
                    confidence  = intent_result.confidence,
                    pipeline    = "fallback",
                    slots       = intent_result.slots,
                ).model_dump())
                async for chunk in _fallback_pipeline(
                    req, intent_result.intent_type, start_ms,
                    intent_type=intent_result.intent_type,
                ):
                    yield chunk

        except Exception as e:
            log.error(f'"Unhandled error: {e}"')
            yield _sse("error", ErrorPayload(
                message="服务异常，请稍后重试", code="internal_error"
            ).model_dump())
            yield _sse_done("fallback", "unknown", start_ms, is_error=True)

    return StreamingResponse(_generate(), media_type="text/event-stream")


# ── 健康检查 & 监控端点 ───────────────────────────────────────────────

@app.get("/health")
def health():
    phones_ok = _DATA_PATH.exists()
    api_key_set = bool(config.GLM_API_KEY) or config.USE_MOCK_AI
    return {
        "status":      "ok" if (phones_ok and api_key_set) else "degraded",
        "version":     "1.0",
        "cb_state":    _glm_cb.state,
        "phones_ok":   phones_ok,
        "api_key_set": api_key_set,
        "mock_mode":   {
            "ai":        config.USE_MOCK_AI,
            "search":    config.USE_MOCK_SEARCH,
            "recommend": config.USE_MOCK_RECOMMEND,
        },
    }


@app.get("/metrics")
def metrics():
    return {
        **_metrics.snapshot(),
        "rank_cache": _rank_cache.stats(),
        "circuit_breaker": {"state": _glm_cb.state},
    }


@app.get("/trending")
async def trending_products(
    scenario: str = "",
    price_max: int = 0,
    limit: int = 12,
):
    """发现页热门推荐，按场景/价格段过滤，按销量×评分排序。"""
    products = await get_trending(scenario=scenario, price_max=price_max, limit=limit)
    return {"products": products, "scenario": scenario, "total": len(products)}


@app.get("/products/{product_id}")
def product_detail(product_id: str):
    """商品详情端点（含完整规格参数）。"""
    phones = json.loads(_DATA_PATH.read_text(encoding="utf-8"))
    for p in phones:
        if p["id"] == product_id:
            return {"product": p}
    from fastapi import HTTPException
    raise HTTPException(status_code=404, detail="Product not found")
