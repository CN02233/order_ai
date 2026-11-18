"""Thin, lazy wrapper around the Groq Python SDK.

EN: The SDK is imported only on the first real call, so importing the application
never requires the dependency or an API key. Config is read at call time, and every
SDK failure becomes a generic LlmAnalysisError before it reaches a caller or a log.
中文：SDK 只在真正调用时才导入，因此导入应用时不要求依赖存在或配置了 Key。配置在调用时读取；
所有 SDK 失败都会先转成通用的 LlmAnalysisError，绝不带到调用方或日志里。
"""
from typing import Any, Protocol

from app.infrastructure.llm.config import LlmConfig
from app.infrastructure.llm.errors import (
    REASON_API_ERROR,
    REASON_EMPTY_RESPONSE,
    REASON_NOT_CONFIGURED,
    REASON_TIMEOUT,
    LlmAnalysisError,
)


class ChatClient(Protocol):
    """EN: Minimal seam for tests and the Groq adapter. 中文：测试与 Groq 适配器共用的最小接口。"""

    def complete(self, system_prompt: str, user_prompt: str) -> str:
        ...


class GroqChatClient:
    """EN: Send one completion and return the raw message content.

    中文：发送一次补全请求并返回原始消息内容。
    """

    def __init__(self, config: LlmConfig | None = None):
        self._config_override = config

    def complete(self, system_prompt: str, user_prompt: str) -> str:
        # EN: Re-read env per call so a rotated key takes effect immediately.
        # 中文：每次调用都重新读取环境变量，使轮换后的 Key 立即生效。
        config = self._config_override or LlmConfig.from_env()
        if not config.api_key:
            raise LlmAnalysisError(REASON_NOT_CONFIGURED)
        try:
            from groq import Groq
        except ImportError:
            # EN: Missing optional dependency is treated as an AI failure.
            # 中文：缺少可选依赖按 AI 失败处理，交由规则降级。
            raise LlmAnalysisError(REASON_NOT_CONFIGURED) from None

        try:
            # EN: A fresh client closed after use bounds the timeout and avoids
            # caching a key that may change. 中文：每次新建并关闭客户端，既限制超时范围，
            # 也避免缓存可能变化的 Key。
            with Groq(
                api_key=config.api_key,
                timeout=config.timeout_seconds,
                max_retries=0,  # EN: no retry, so total time stays bounded. 中文：不重试，保证总耗时有上限。
            ) as client:
                response = client.chat.completions.create(
                    model=config.model,
                    messages=[
                        {"role": "system", "content": system_prompt},
                        {"role": "user", "content": user_prompt},
                    ],
                    response_format={"type": "json_object"},
                    temperature=0,
                    timeout=config.timeout_seconds,
                )
        except Exception as exc:  # EN: all SDK/transport errors are AI failures. 中文：所有 SDK/传输错误都算 AI 失败。
            raise LlmAnalysisError(self._failure_reason(exc)) from None

        try:
            return response.choices[0].message.content or ""
        except (AttributeError, IndexError, TypeError):
            raise LlmAnalysisError(REASON_EMPTY_RESPONSE) from None

    @staticmethod
    def _failure_reason(exc: BaseException) -> str:
        """EN: Classify an exception without exposing its message to callers.

        中文：只做异常分类，绝不把异常消息暴露给调用方。
        """
        name = type(exc).__name__.lower()
        text = str(exc).lower()
        # EN: Message text is only scanned for a timeout keyword, never returned.
        # 中文：异常文本只用于判断超时关键词，绝不会作为结果返回。
        if "timeout" in name or "timeout" in text or "timed out" in text:
            return REASON_TIMEOUT
        return REASON_API_ERROR
