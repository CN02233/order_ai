"""Parse and validate LLM output; turn every failure into a safe LlmAnalysisError.

EN: Unparseable JSON, missing fields, illegal categories and out-of-range confidence
are all AI failures, not data to pass downstream.
中文：无法解析的 JSON、字段缺失、非法分类和越界 confidence 都视为 AI 失败，不能传给下游。
"""
import json
from typing import Protocol

from pydantic import BaseModel, Field, ValidationError

from app.infrastructure.llm.client import ChatClient, GroqChatClient
from app.infrastructure.llm.errors import (
    REASON_EMPTY_RESPONSE,
    REASON_INVALID_JSON,
    REASON_INVALID_RESULT,
    LlmAnalysisError,
)
from app.infrastructure.llm.prompts import SYSTEM_PROMPT, build_user_prompt
from app.schemas.inquiry import InquiryCategory


class LlmAnalysis(BaseModel):
    """EN: Validated model output; review is never trusted from the model.

    中文：校验后的模型输出；人工审核决定绝不采信模型返回值。
    """

    category: InquiryCategory
    confidence: float = Field(ge=0.0, le=1.0)  # EN: 0..1 only. 中文：只允许 0 到 1。
    # EN: Required on purpose: an incomplete answer must trigger fallback,
    # not silently become None. 中文：故意设为必填：不完整的模型回答必须触发降级，而非静默变成 None。
    suggested_reply: str | None


class LlmAnalyzer(Protocol):
    def analyze(self, subject: str, body: str) -> LlmAnalysis:
        ...


class GroqLlmAnalyzer:
    def __init__(self, client: ChatClient | None = None):
        self._client: ChatClient = client or GroqChatClient()

    def analyze(self, subject: str, body: str) -> LlmAnalysis:
        raw = self._client.complete(SYSTEM_PROMPT, build_user_prompt(subject, body))
        if not raw or not raw.strip():
            raise LlmAnalysisError(REASON_EMPTY_RESPONSE)

        try:
            payload = json.loads(self._strip_code_fence(raw))
        except (json.JSONDecodeError, TypeError, ValueError):
            # EN: Never log the raw string here: it may contain customer text.
            # 中文：此处绝不记录原始字符串，因为它可能包含客户咨询正文。
            raise LlmAnalysisError(REASON_INVALID_JSON) from None

        try:
            return LlmAnalysis.model_validate(payload)
        except ValidationError:
            # EN: Covers missing fields, unknown categories and bad confidence.
            # 中文：覆盖字段缺失、非法分类和 confidence 越界等情况。
            raise LlmAnalysisError(REASON_INVALID_RESULT) from None

    @staticmethod
    def _strip_code_fence(text: str) -> str:
        """EN: Accept an optional ```json ... ``` wrapper, without trusting it.

        中文：容忍可选的 ```json ... ``` 包裹，但不信任其中的内容。
        """
        stripped = text.strip()
        if stripped.startswith("```"):
            stripped = stripped[3:]
            if stripped.lower().startswith("json"):
                stripped = stripped[4:]
            stripped = stripped.rsplit("```", 1)[0]
        return stripped.strip()
