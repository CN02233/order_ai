"""Deterministic inquiry classification rules."""

RULES = {
    "REFUND": ("refund", "return", "money back"),
    "COMPLAINT": ("complaint", "wrong item", "damaged", "unhappy"),
    "ORDER_QUERY": ("where is", "tracking", "order status", "my order"),
    "PRODUCT_INQUIRY": ("in stock", "available", "product", "sku"),
}


def classify(text: str) -> str:
    """Return the first matching inquiry category, or OTHER when no rule matches."""
    normalized_text = text.lower()
    for category, phrases in RULES.items():
        if any(phrase in normalized_text for phrase in phrases):
            return category
    return "OTHER"
