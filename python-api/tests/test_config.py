"""Tests for LLM configuration defaults and overrides.

EN: Verify the Groq model default and the GROQ_MODEL environment override.
中文：验证 Groq 模型默认值以及 GROQ_MODEL 环境变量的覆盖能力。
"""
from app.infrastructure.llm.config import DEFAULT_MODEL, LlmConfig


def test_default_model_is_gpt_oss_120b(monkeypatch):
    """EN: The built-in default is openai/gpt-oss-120b.

    中文：内置默认模型为 openai/gpt-oss-120b。
    """
    monkeypatch.delenv("GROQ_MODEL", raising=False)
    assert DEFAULT_MODEL == "openai/gpt-oss-120b"
    assert LlmConfig.from_env().model == "openai/gpt-oss-120b"


def test_groq_model_env_overrides_default(monkeypatch):
    """EN: GROQ_MODEL takes precedence over the default model.

    中文：GROQ_MODEL 优先于默认模型。
    """
    monkeypatch.setenv("GROQ_MODEL", "llama-3.3-70b-versatile")
    assert LlmConfig.from_env().model == "llama-3.3-70b-versatile"
