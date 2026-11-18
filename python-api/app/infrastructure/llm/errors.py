"""Fixed, safe failure reasons for the LLM boundary.

EN: These strings become ``error_message`` in the API response and appear in logs.
Keep them generic: never include customer text, the API key, a raw exception or a
raw model response.
中文：这些字符串会作为响应的 error_message 返回并写入日志。必须保持通用，绝不夹带客户正文、
API Key、原始异常或模型原始返回内容。
"""

LlmFailureReason = str

# EN: Fixed reasons only, so no caller can receive provider internals.
# 中文：只使用固定原因，避免把服务商内部信息传递给调用方。
REASON_NOT_CONFIGURED: LlmFailureReason = "LLM is not configured"
REASON_TIMEOUT: LlmFailureReason = "LLM request timed out"
REASON_API_ERROR: LlmFailureReason = "LLM request failed"
REASON_EMPTY_RESPONSE: LlmFailureReason = "LLM returned an empty response"
REASON_INVALID_JSON: LlmFailureReason = "LLM returned invalid JSON"
REASON_INVALID_RESULT: LlmFailureReason = "LLM returned an invalid analysis result"


class LlmAnalysisError(Exception):
    """EN: AI analysis failed; the caller can safely fall back to rules.

    中文：AI 分析失败；调用方可以安全地降级到规则分类。
    """

    def __init__(self, reason: LlmFailureReason):
        self.reason = reason
        super().__init__(reason)
