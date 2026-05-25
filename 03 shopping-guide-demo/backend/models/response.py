from pydantic import BaseModel
from typing import List, Optional


class ProductItem(BaseModel):
    id: str
    title: str
    price: float
    image: str
    rating: float
    sales: int
    reason: Optional[str] = None
    highlights: List[str] = []


class IntentPayload(BaseModel):
    intent_type: str
    confidence: float
    pipeline: str        # "traditional" | "llm" | "fallback"
    slots: dict


class ThinkingPayload(BaseModel):
    message: str


class ClarifyPayload(BaseModel):
    question: str
    options: List[str] = []


class RankPayload(BaseModel):
    summary: str         # Help Me Decide 对比摘要
    products: List[ProductItem]


class DonePayload(BaseModel):
    pipeline: str
    intent_type: str
    latency_ms: int
    clarify_turns: int = 0


class ErrorPayload(BaseModel):
    message: str
    code: str = "unknown"
