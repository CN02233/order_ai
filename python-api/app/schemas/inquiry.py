"""Request and response models for inquiry analysis."""
from typing import Optional
from pydantic import BaseModel, EmailStr


class AnalyzeInquiryRequest(BaseModel):
    customer_email: EmailStr
    subject: str = ""
    body: str


class AnalyzeInquiryResponse(BaseModel):
    category: str
    confidence: float
    needs_human_review: bool
    suggested_reply: Optional[str] = None
