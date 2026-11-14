"""HTTP endpoints for health checks and inquiry analysis."""
from fastapi import APIRouter
from app.schemas.inquiry import AnalyzeInquiryRequest, AnalyzeInquiryResponse
from app.services.inquiry_analysis_service import InquiryAnalysisService

router = APIRouter()
analysis_service = InquiryAnalysisService()


@router.get("/health")
def health():
    """Report that the Python analysis service is available."""
    return {"status": "ok", "service": "inquiry-analysis"}


@router.post("/analyze-inquiry", response_model=AnalyzeInquiryResponse)
def analyze_inquiry(payload: AnalyzeInquiryRequest) -> AnalyzeInquiryResponse:
    """Classify an inquiry and indicate whether a human should review it."""
    return analysis_service.analyze(payload)
