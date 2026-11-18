"""Environment-driven configuration for the Groq client.

EN: The API key is read from the process environment only. It is never stored in
source control, logged, or included in an error message.
中文：API Key 只从进程环境变量读取；不写入源码、不记录到日志、不出现在错误信息中。
"""
import os
from dataclasses import dataclass

DEFAULT_MODEL = "openai/gpt-oss-120b"
DEFAULT_TIMEOUT_SECONDS = 15.0


@dataclass(frozen=True)
class LlmConfig:
    """EN: Immutable config for one LLM call. 中文：单次 LLM 调用的不可变配置。"""

    api_key: str | None
    model: str
    timeout_seconds: float

    @classmethod
    def from_env(cls) -> "LlmConfig":
        """EN: Build from env; an invalid timeout falls back to the safe default.

        中文：从环境变量构建配置；非法超时值回退到安全默认值。
        """
        raw_timeout = os.getenv("LLM_TIMEOUT_SECONDS", "")
        try:
            timeout = float(raw_timeout) if raw_timeout else DEFAULT_TIMEOUT_SECONDS
        except ValueError:
            # EN: Unparseable value -> default. 中文：无法解析的值按默认值处理。
            timeout = DEFAULT_TIMEOUT_SECONDS
        if timeout <= 0:
            # EN: A non-positive timeout means "unbounded", so never accept it.
            # 中文：非正数超时等于没有上限，必须回退到默认值。
            timeout = DEFAULT_TIMEOUT_SECONDS

        return cls(
            # EN: An empty string means "not configured". 中文：空字符串视为未配置。
            api_key=os.getenv("GROQ_API_KEY") or None,
            model=os.getenv("GROQ_MODEL") or DEFAULT_MODEL,
            timeout_seconds=timeout,
        )
