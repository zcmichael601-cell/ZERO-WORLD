from pydantic import BaseModel, Field, field_validator
from typing import List, Optional


class Message(BaseModel):
    role: str
    content: str


class ChatRequest(BaseModel):
    message: str = Field(..., min_length=1, max_length=500)
    history: List[Message] = Field(default_factory=list)
    session_id: Optional[str] = None

    @field_validator("message")
    @classmethod
    def strip_and_check(cls, v: str) -> str:
        v = v.strip()
        if not v:
            raise ValueError("message must not be blank")
        return v
