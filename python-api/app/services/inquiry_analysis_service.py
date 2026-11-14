"""Use-case orchestration for classifying a customer inquiry."""
from app.domain.classifier import classify
from app.schemas.inquiry import AnalyzeInquiryRequest, AnalyzeInquiryResponse


class InquiryAnalysisService:
    """Analyze inquiry text and identify cases that need human review."""

    def analyze(self, request: AnalyzeInquiryRequest) -> AnalyzeInquiryResponse:
        text = f"{request.subject} {request.body}"
        category = classify(text)
        return AnalyzeInquiryResponse(
            category=category,
            confidence=1.0 if category != "OTHER" else 0.5,
            needs_human_review=category in {"REFUND", "COMPLAINT", "OTHER"},
            suggested_reply=None,
        )
