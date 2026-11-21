"""Use-case orchestration: ask the LLM first, fall back to deterministic rules.

EN: Both paths share one response model and one human-review rule.
中文：两条路径共用同一响应模型和同一套人工审核规则。
"""
import logging
import time

from app.domain.classifier import classify
from app.domain.review import needs_human_review
from app.infrastructure.llm.analyzer import GroqLlmAnalyzer, LlmAnalyzer
from app.infrastructure.llm.errors import REASON_API_ERROR, LlmAnalysisError
from app.request_context import get_request_id
from app.schemas.inquiry import (
    AnalysisSource,
    AnalyzeInquiryRequest,
    AnalyzeInquiryResponse,
    InquiryCategory,
)

logger = logging.getLogger("order_ai")


class InquiryAnalysisService:
    """EN: Analyze an inquiry with one consistent response shape on both paths.

    中文：无论走哪条路径，都返回结构一致的咨询分析结果。
    """

    def __init__(self, llm_analyzer: LlmAnalyzer | None = None):
        self._llm_analyzer = llm_analyzer or GroqLlmAnalyzer()

    def analyze(self, request: AnalyzeInquiryRequest) -> AnalyzeInquiryResponse:
        started_at = time.perf_counter()

        try:
            result = self._llm_analyzer.analyze(request.subject, request.body)
        except LlmAnalysisError as error:
            # EN: Pre-classified safe reason; no exception text involved.
            # 中文：原因已预先归类且安全，不涉及异常文本。
            return self._fallback(request, error.reason, started_at)
        except Exception as error:
            # EN: Defensive boundary: an unexpected AI failure must not break the API.
            # Log only the exception type, never its message or traceback.
            # 中文：防御边界：AI 侧的意外异常不能让接口失败；只记录异常类型，
            # 绝不记录异常消息或堆栈。
            logger.error(
                "llm_unexpected_error request_id=%s error_type=%s",
                get_request_id(),
                type(error).__name__,
            )
            return self._fallback(request, REASON_API_ERROR, started_at)

        # EN: The model never decides whether a human should review.
        # 中文：是否需要人工审核绝不采信模型返回值，统一按分类计算。
        response = AnalyzeInquiryResponse(
            category=result.category,
            confidence=result.confidence,
            needs_human_review=needs_human_review(result.category),
            suggested_reply=result.suggested_reply,
            analysis_source=AnalysisSource.AI,
            error_message=None,
            # EN: Only an AI result may carry a priority; it was enum-validated in the analyzer.
            # 中文：只有 AI 结果才携带优先级；其值已在 analyzer 中按枚举校验。
            priority=result.priority,
        )
        self._log_completion(response, "succeeded", "none", started_at)
        return response

    def _fallback(
        self, request: AnalyzeInquiryRequest, reason: str, started_at: float
    ) -> AnalyzeInquiryResponse:
        # EN: No broad catch here: a broken rule classifier must surface.
        # 中文：此处不做兜底捕获：规则分类器自身出错必须显式暴露，不能静默吞掉。
        text = f"{request.subject} {request.body}"
        category = classify(text)
        response = AnalyzeInquiryResponse(
            category=InquiryCategory(category),
            # EN: Rule confidence is a fixed marker, not a probability.
            # 中文：规则路径的 confidence 只是固定标记，不代表概率。
            confidence=1.0 if category != "OTHER" else 0.5,
            needs_human_review=needs_human_review(category),
            suggested_reply=None,  # EN: no generated reply on fallback. 中文：降级时不生成建议回复。
            analysis_source=AnalysisSource.RULE_BASED,
            error_message=reason,
            # EN: Explicitly null: a rule result has no priority evidence and must never look like
            #     an AI judgement. 中文：显式为 null：规则结果没有优先级证据，绝不伪装成 AI 判断。
            priority=None,
        )
        self._log_completion(response, "failed", reason, started_at)
        return response

    @staticmethod
    def _log_completion(
        response: AnalyzeInquiryResponse,
        ai_status: str,
        fallback_reason: str,
        started_at: float,
    ) -> None:
        """EN: Log metadata only: no email, inquiry text, key or raw model output.

        中文：只记录元数据：不记录邮箱、咨询正文、API Key 或模型原始返回。
        """
        duration_ms = round((time.perf_counter() - started_at) * 1000, 2)
        log = logger.warning if ai_status == "failed" else logger.info
        log(
            "analysis_completed request_id=%s ai_status=%s source=%s category=%s "
            "needs_human_review=%s duration_ms=%s fallback_reason=%s",
            get_request_id(),
            ai_status,
            response.analysis_source.value,
            response.category.value,
            response.needs_human_review,
            duration_ms,
            fallback_reason,
        )
