"""HTTP endpoints for health checks and inquiry analysis.

EN: The route path and request fields are part of the public contract.
中文：路由路径和请求字段属于对外契约，保持不变。
"""
from fastapi import APIRouter, Depends

from app.schemas.inquiry import AnalyzeInquiryRequest, AnalyzeInquiryResponse
from app.services.inquiry_analysis_service import InquiryAnalysisService

router = APIRouter()
_analysis_service = InquiryAnalysisService()


def get_analysis_service() -> InquiryAnalysisService:
    """EN: Dependency seam for injecting a mocked analyzer in tests.

    中文：依赖注入点，便于测试时注入 Mock 分析器，无需外部网络。
    """
    return _analysis_service


@router.get("/health")
def health():
    """EN: Report that the Python analysis service is available. 中文：报告 Python 分析服务可用。"""
    return {"status": "ok", "service": "inquiry-analysis"}


@router.post("/analyze-inquiry", response_model=AnalyzeInquiryResponse)
def analyze_inquiry(
    payload: AnalyzeInquiryRequest,
    service: InquiryAnalysisService = Depends(get_analysis_service),
) -> AnalyzeInquiryResponse:
    """EN: Classify an inquiry and flag cases needing human review.

    中文：对咨询进行分类，并标记是否需要人工审核。
    """
    return service.analyze(payload)
