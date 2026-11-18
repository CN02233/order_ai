"""Groq-backed LLM analysis with strict validation and safe failure modes.

EN: Every failure is converted to a fixed safe reason before falling back to rules.
中文：所有失败都会先转换成固定的安全原因，再降级到规则分类。
"""
