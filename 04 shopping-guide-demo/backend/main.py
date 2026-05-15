from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import List

from adapters.ai import extract_intent, generate_reply
from adapters.search import search_products
from adapters.recommend import recommend_products

app = FastAPI()
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])


class Message(BaseModel):
    role: str
    content: str

class ChatRequest(BaseModel):
    history: List[Message]


@app.post("/chat")
async def chat(req: ChatRequest):
    history = [m.model_dump() for m in req.history]

    # ① AI 提取用户意图
    intent = await extract_intent(history)

    # ② 意图不清晰 → 追问，不调搜索
    if not intent["is_clear"]:
        return {"reply": intent["follow_up"], "products": []}

    # ③ 调搜索算子
    products = await search_products(
        query     = " ".join(intent.get("keywords", [])),
        category  = intent.get("category", ""),
        price_max = intent.get("price_max", 0),
    )

    # ④ 搜索无结果 → 降级到推荐算子
    if not products:
        user_context = {
            "history_keywords": intent.get("keywords", []),
            "price_sensitivity": "low" if intent.get("price_max", 0) < 2000 else "mid",
        }
        products = await recommend_products(user_context)

    # ⑤ AI 生成自然语言回复
    reply = await generate_reply(intent, products)

    return {"reply": reply, "products": products}


@app.get("/health")
def health():
    return {"status": "ok"}
