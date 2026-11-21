"""Request and response models for inquiry analysis.

EN: The response contract is additive so existing Spring Boot callers keep working.
中文：响应契约只做增量扩展，保证现有 Spring Boot 调用方不受影响。
"""
from enum import Enum
from typing import Optional

from pydantic import BaseModel, EmailStr, Field


class InquiryCategory(str, Enum):
    """EN: The only categories allowed on both paths. 中文：两条路径唯一允许的五种分类。"""

    REFUND = "REFUND"
    COMPLAINT = "COMPLAINT"
    ORDER_QUERY = "ORDER_QUERY"
    PRODUCT_INQUIRY = "PRODUCT_INQUIRY"
    OTHER = "OTHER"


class AnalysisSource(str, Enum):
    """EN: Where the classification came from. 中文：分类结果的来源。"""

    AI = "AI"
    RULE_BASED = "RULE_BASED"


class InquiryPriority(str, Enum):
    """EN: The only priorities an AI analysis may return.

    中文：AI 分析唯一允许返回的优先级集合。共享给 LLM 输出模型与 API 响应，保证两端校验一致。
    """

    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    URGENT = "URGENT"


class AnalyzeInquiryRequest(BaseModel):
    # EN: Original request fields; unchanged on purpose. 中文：原有请求字段，刻意保持不变。
    customer_email: EmailStr
    subject: str = ""
    body: str


class AnalyzeInquiryResponse(BaseModel):
    """EN: Stable API contract.

    The first four fields are the original contract and keep their names and
    meaning; the last two are additive with defaults, so older callers that only
    map the original fields keep working.
    中文：稳定的响应契约。前四个字段是原有契约，名称和语义不变；后两个字段为新增且带默认值，
    只映射旧字段的调用方无需修改即可继续工作。
    """

    category: InquiryCategory
    # EN: Model self-assessment or a rule constant, not a calibrated probability.
    # 中文：模型自评或规则固定值，不是经过校准的真实概率。
    confidence: float = Field(ge=0.0, le=1.0)
    needs_human_review: bool
    suggested_reply: Optional[str] = None
    # EN: Defaults to RULE_BASED for compatibility with older constructors.
    # 中文：默认 RULE_BASED，兼容仍按旧结构构造响应的代码。
    analysis_source: AnalysisSource = AnalysisSource.RULE_BASED
    # EN: Non-null only on AI failure. 中文：仅在 AI 失败时非空。
    error_message: Optional[str] = None
    # EN: AI-judged priority. Optional and defaulted to None on purpose: a RULE_BASED fallback has
    #     no evidence for a priority and MUST NOT fabricate one. Adding a field keeps older callers
    #     working because they simply ignore it.
    # 中文：AI 判断的优先级。刻意设为可选且默认 None：RULE_BASED 降级没有优先级证据，绝不能伪造一个。
    #     新增为增量字段，旧调用方忽略它即可继续工作。
    priority: Optional[InquiryPriority] = None
