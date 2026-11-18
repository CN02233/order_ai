"""The single source of truth for human-review decisions.

EN: Both the AI path and the rule-fallback path call this function, so a category
always leads to the same business behaviour.
中文：AI 路径和规则降级路径共用此函数，保证同一分类在两个路径下产生一致的业务行为。
"""
from app.schemas.inquiry import InquiryCategory

# EN: Only these three categories need a human. 中文：只有这三类需要人工介入。
HUMAN_REVIEW_CATEGORIES = frozenset(
    category.value
    for category in (
        InquiryCategory.REFUND,
        InquiryCategory.COMPLAINT,
        InquiryCategory.OTHER,
    )
)


def needs_human_review(category: InquiryCategory | str) -> bool:
    """EN: True for REFUND, COMPLAINT and OTHER; False otherwise.

    中文：REFUND、COMPLAINT、OTHER 返回 True，其余分类返回 False。
    """
    return category in HUMAN_REVIEW_CATEGORIES
