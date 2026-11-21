"""LLM-first inquiry analysis tests.

EN: Every LLM interaction is mocked: no real Groq endpoint and no real key needed.
中文：所有 LLM 交互都被 Mock：不调用真实 Groq 接口，也不需要真实 API Key。
"""
import json
import logging

import pytest
from fastapi.testclient import TestClient

from app.api.routes import get_analysis_service
from app.domain.review import needs_human_review
from app.infrastructure.llm.analyzer import GroqLlmAnalyzer
from app.infrastructure.llm.client import GroqChatClient
from app.infrastructure.llm.errors import (
    REASON_API_ERROR,
    REASON_INVALID_JSON,
    REASON_INVALID_RESULT,
    REASON_NOT_CONFIGURED,
    REASON_TIMEOUT,
    LlmAnalysisError,
)
from app.main import app
from app.schemas.inquiry import (
    AnalysisSource,
    AnalyzeInquiryRequest,
    InquiryCategory,
    InquiryPriority,
)
from app.services.inquiry_analysis_service import InquiryAnalysisService

ORDER_REQUEST = {
    "customer_email": "john@example.com",
    "subject": "Order",
    "body": "Where is my order?",
}
REFUND_REQUEST = {
    "customer_email": "mary@example.com",
    "subject": "Return",
    "body": "I want a refund for my last purchase.",
}


def llm_json(
    category: str,
    confidence=0.9,
    reply="Thanks for contacting us.",
    priority="MEDIUM",
) -> str:
    """EN: Build a valid model answer, including the now-required priority.

    中文：构造合法的模型返回内容，包含现已必填的 priority。
    """
    return json.dumps(
        {
            "category": category,
            "priority": priority,
            "confidence": confidence,
            "suggested_reply": reply,
        }
    )


class FakeChatClient:
    """EN: Queue one raw answer (or exception) and record call metadata.

    中文：排队返回一个原始模型答案（或异常），并记录调用元数据。
    """

    def __init__(self, outcome):
        self.outcome = outcome
        self.calls = 0
        self.system_prompt = ""
        self.user_prompt = ""

    def complete(self, system_prompt: str, user_prompt: str) -> str:
        self.calls += 1
        self.system_prompt = system_prompt
        self.user_prompt = user_prompt
        if isinstance(self.outcome, BaseException):
            raise self.outcome
        return self.outcome


def service_with(outcome) -> InquiryAnalysisService:
    """EN: Build a service backed by a mocked LLM. 中文：构造使用 Mock LLM 的服务。"""
    return InquiryAnalysisService(GroqLlmAnalyzer(FakeChatClient(outcome)))


@pytest.fixture(autouse=True)
def _clear_dependency_overrides():
    # EN: Isolate DI overrides between tests. 中文：在测试之间隔离依赖注入覆盖。
    yield
    app.dependency_overrides.clear()


def test_llm_success_order_query():
    response = service_with(
        llm_json(
            "ORDER_QUERY",
            0.95,
            "You can track your order using the tracking link.",
        )
    ).analyze(AnalyzeInquiryRequest(**ORDER_REQUEST))

    assert response.category == InquiryCategory.ORDER_QUERY
    assert response.confidence == 0.95
    assert response.needs_human_review is False
    assert response.suggested_reply == "You can track your order using the tracking link."
    assert response.analysis_source == AnalysisSource.AI
    assert response.error_message is None


def test_llm_success_refund_needs_human_review():
    response = service_with(llm_json("REFUND", 0.88)).analyze(
        AnalyzeInquiryRequest(**REFUND_REQUEST)
    )

    assert response.category == InquiryCategory.REFUND
    assert response.analysis_source == AnalysisSource.AI
    assert response.needs_human_review is True
    assert response.error_message is None


def test_llm_api_exception_triggers_rule_fallback_without_leaking_error_text():
    response = service_with(LlmAnalysisError(REASON_API_ERROR)).analyze(
        AnalyzeInquiryRequest(**ORDER_REQUEST)
    )

    assert response.analysis_source == AnalysisSource.RULE_BASED
    assert response.category == InquiryCategory.ORDER_QUERY
    assert response.suggested_reply is None
    assert response.error_message == REASON_API_ERROR


def test_unexpected_llm_exception_also_triggers_rule_fallback():
    # EN: Even an SDK error containing a key-looking string must stay hidden.
    # 中文：即使 SDK 异常里带着疑似密钥的文本，也绝不能泄漏到响应中。
    response = service_with(
        RuntimeError("gsk_supersecret_key_value appeared in the SDK error")
    ).analyze(AnalyzeInquiryRequest(**ORDER_REQUEST))

    assert response.analysis_source == AnalysisSource.RULE_BASED
    assert response.category == InquiryCategory.ORDER_QUERY
    assert response.error_message == REASON_API_ERROR
    assert "gsk_" not in (response.error_message or "")


def test_llm_timeout_triggers_rule_fallback():
    response = service_with(LlmAnalysisError(REASON_TIMEOUT)).analyze(
        AnalyzeInquiryRequest(**ORDER_REQUEST)
    )

    assert response.analysis_source == AnalysisSource.RULE_BASED
    assert response.error_message == REASON_TIMEOUT
    assert response.category == InquiryCategory.ORDER_QUERY


def test_groq_client_maps_timeout_exception_to_safe_reason():
    assert GroqChatClient._failure_reason(TimeoutError("timed out")) == REASON_TIMEOUT
    assert GroqChatClient._failure_reason(RuntimeError("any failure")) == REASON_API_ERROR


def test_llm_invalid_json_triggers_rule_fallback():
    response = service_with("this is not json").analyze(
        AnalyzeInquiryRequest(**ORDER_REQUEST)
    )

    assert response.analysis_source == AnalysisSource.RULE_BASED
    assert response.error_message == REASON_INVALID_JSON
    assert response.category == InquiryCategory.ORDER_QUERY
    assert response.suggested_reply is None


@pytest.mark.parametrize("category", ["UNKNOWN_CATEGORY", "REFUNDS", "", 123])
def test_llm_invalid_category_triggers_rule_fallback(category):
    response = service_with(llm_json(category)).analyze(
        AnalyzeInquiryRequest(**ORDER_REQUEST)
    )

    assert response.analysis_source == AnalysisSource.RULE_BASED
    assert response.error_message == REASON_INVALID_RESULT
    assert response.category == InquiryCategory.ORDER_QUERY


@pytest.mark.parametrize("confidence", [1.5, -0.1, "very high", None])
def test_llm_invalid_confidence_triggers_rule_fallback(confidence):
    response = service_with(llm_json("ORDER_QUERY", confidence)).analyze(
        AnalyzeInquiryRequest(**ORDER_REQUEST)
    )

    assert response.analysis_source == AnalysisSource.RULE_BASED
    assert response.error_message == REASON_INVALID_RESULT
    assert response.category == InquiryCategory.ORDER_QUERY


def test_llm_missing_field_triggers_rule_fallback():
    # EN: suggested_reply is required on the model side; a missing key is a failure.
    # 中文：模型输出中 suggested_reply 必填，缺字段视为 AI 失败。
    invalid = json.dumps({"category": "ORDER_QUERY", "confidence": 0.8})
    response = service_with(invalid).analyze(AnalyzeInquiryRequest(**ORDER_REQUEST))

    assert response.analysis_source == AnalysisSource.RULE_BASED
    assert response.error_message == REASON_INVALID_RESULT


def test_other_category_requires_human_review():
    response = service_with(llm_json("OTHER", 0.42)).analyze(
        AnalyzeInquiryRequest(**ORDER_REQUEST)
    )

    assert response.category == InquiryCategory.OTHER
    assert response.analysis_source == AnalysisSource.AI
    assert response.needs_human_review is True


def test_review_rule_is_identical_for_ai_and_rule_paths():
    for category in ("REFUND", "COMPLAINT", "OTHER", "ORDER_QUERY", "PRODUCT_INQUIRY"):
        assert needs_human_review(category) == (
            category in {"REFUND", "COMPLAINT", "OTHER"}
        )

    ai = service_with(llm_json("OTHER")).analyze(AnalyzeInquiryRequest(**ORDER_REQUEST))
    fallback = service_with("invalid json").analyze(
        AnalyzeInquiryRequest(
            customer_email="test@example.com", subject="", body="Nothing matched here"
        )
    )
    assert fallback.category == InquiryCategory.OTHER
    assert ai.needs_human_review == fallback.needs_human_review is True


def test_fallback_sets_analysis_source_and_clears_suggested_reply():
    response = service_with("not json").analyze(AnalyzeInquiryRequest(**ORDER_REQUEST))

    assert response.analysis_source == AnalysisSource.RULE_BASED
    assert response.analysis_source.value == "RULE_BASED"
    assert response.suggested_reply is None
    assert response.error_message == REASON_INVALID_JSON


def test_existing_endpoint_still_works_with_mocked_llm():
    app.dependency_overrides[get_analysis_service] = lambda: service_with(
        llm_json("ORDER_QUERY", 0.95, "Use the tracking link.")
    )
    client = TestClient(app)

    assert client.get("/health").json() == {"status": "ok", "service": "inquiry-analysis"}

    response = client.post("/analyze-inquiry", json=ORDER_REQUEST)
    assert response.status_code == 200
    body = response.json()
    # EN: Original fields are unchanged; new fields are additive only.
    # 中文：原有字段不变，新字段只是增量扩展。
    assert body["category"] == "ORDER_QUERY"
    assert body["confidence"] == 0.95
    assert body["needs_human_review"] is False
    assert body["suggested_reply"] == "Use the tracking link."
    assert body["analysis_source"] == "AI"
    assert body["error_message"] is None
    # EN: priority is additive; original fields keep their names and meaning.
    # 中文：priority 为增量字段；原有字段名与语义保持不变。
    assert body["priority"] == "MEDIUM"
    assert set(body) == {
        "category",
        "priority",
        "confidence",
        "needs_human_review",
        "suggested_reply",
        "analysis_source",
        "error_message",
    }


def test_no_real_api_key_needed(monkeypatch):
    # EN: With no key configured the endpoint must still answer via rules.
    # 中文：未配置 Key 时接口仍须通过规则分类正常返回。
    monkeypatch.delenv("GROQ_API_KEY", raising=False)
    response = TestClient(app).post("/analyze-inquiry", json=ORDER_REQUEST)

    assert response.status_code == 200
    assert response.json()["analysis_source"] == "RULE_BASED"
    assert response.json()["error_message"] == REASON_NOT_CONFIGURED
    assert response.json()["category"] == "ORDER_QUERY"


def test_groq_client_without_key_never_imports_sdk_or_calls_network(monkeypatch):
    monkeypatch.delenv("GROQ_API_KEY", raising=False)
    monkeypatch.delitem(__import__("sys").modules, "groq", raising=False)

    with pytest.raises(LlmAnalysisError) as exc_info:
        GroqChatClient().complete("system", "user")

    assert exc_info.value.reason == REASON_NOT_CONFIGURED


def test_logs_never_contain_customer_data_or_key(caplog, monkeypatch):
    caplog.set_level(logging.INFO, logger="order_ai")
    marker = "PRIVATE_CUSTOMER_CONTENT_MARKER"
    fake_key_marker = "gsk_PRIVATE_KEY_MARKER"
    monkeypatch.setenv("GROQ_API_KEY", fake_key_marker)
    client = FakeChatClient(llm_json("ORDER_QUERY", 0.9, "ok"))
    service = InquiryAnalysisService(GroqLlmAnalyzer(client))
    app.dependency_overrides[get_analysis_service] = lambda: service

    TestClient(app).post(
        "/analyze-inquiry",
        headers={"X-Request-ID": "test-llm-logging"},
        json={
            "customer_email": "private@example.com",
            "subject": marker,
            "body": marker,
        },
    )

    messages = [record.getMessage() for record in caplog.records]
    analysis_log = next(
        message for message in messages if "analysis_completed" in message
    )
    assert "request_id=test-llm-logging" in analysis_log
    assert "source=AI" in analysis_log
    assert "category=ORDER_QUERY" in analysis_log
    assert "duration_ms=" in analysis_log
    for secret in (marker, fake_key_marker, "private@example.com", "gsk_"):
        assert all(secret not in message for message in messages)

    # EN: Prompt content stays in memory only; it is never written to logs.
    # 中文：Prompt 内容只存在于内存中，绝不写入日志。
    assert marker in client.user_prompt
    assert "category" in client.system_prompt


def test_rule_classifier_failure_is_not_swallowed(monkeypatch):
    # EN: A broken rule must raise instead of being silently degraded.
    # 中文：规则分类器出错必须抛出异常，不能被静默降级掩盖。
    def broken_classify(_text):
        raise RuntimeError("rule engine is broken")

    monkeypatch.setattr(
        "app.services.inquiry_analysis_service.classify", broken_classify
    )
    service = service_with(LlmAnalysisError(REASON_API_ERROR))

    with pytest.raises(RuntimeError, match="rule engine is broken"):
        service.analyze(AnalyzeInquiryRequest(**ORDER_REQUEST))


# ---------------------------------------------------------------------------
# T5: AI priority contract
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("priority", ["LOW", "MEDIUM", "HIGH", "URGENT"])
def test_llm_returns_valid_priority(priority):
    """EN: Every allowed priority is accepted on the AI path.

    中文：AI 路径接受全部合法优先级。
    """
    response = service_with(
        llm_json("ORDER_QUERY", priority=priority)
    ).analyze(AnalyzeInquiryRequest(**ORDER_REQUEST))

    assert response.analysis_source == AnalysisSource.AI
    assert response.priority == InquiryPriority(priority)
    assert response.priority.value == priority


@pytest.mark.parametrize("priority", ["URGENTLY", "medium", "", 123, None])
def test_llm_invalid_priority_triggers_rule_fallback(priority):
    """EN: Illegal or missing priority is an AI failure, never silently MEDIUM.

    中文：非法或缺失优先级一律视为 AI 失败，绝不静默替换为 MEDIUM。
    """
    response = service_with(llm_json("ORDER_QUERY", priority=priority)).analyze(
        AnalyzeInquiryRequest(**ORDER_REQUEST)
    )

    assert response.analysis_source == AnalysisSource.RULE_BASED
    assert response.error_message == REASON_INVALID_RESULT
    assert response.priority is None


def test_llm_missing_priority_triggers_rule_fallback():
    """EN: A model answer with no priority key must fail, not default.

    中文：模型返回缺少 priority 键必须失败，不得使用默认值。
    """
    missing = json.dumps(
        {"category": "ORDER_QUERY", "confidence": 0.8, "suggested_reply": "ok"}
    )
    response = service_with(missing).analyze(AnalyzeInquiryRequest(**ORDER_REQUEST))

    assert response.analysis_source == AnalysisSource.RULE_BASED
    assert response.error_message == REASON_INVALID_RESULT
    assert response.priority is None


def test_llm_invalid_json_triggers_rule_fallback_with_null_priority():
    response = service_with("not json").analyze(AnalyzeInquiryRequest(**ORDER_REQUEST))

    assert response.analysis_source == AnalysisSource.RULE_BASED
    assert response.error_message == REASON_INVALID_JSON
    assert response.priority is None


def test_rule_fallback_never_fabricates_priority():
    """EN: Regardless of the safe failure reason, a RULE_BASED result carries no priority.

    中文：无论安全失败原因为何，RULE_BASED 结果都不携带优先级。
    """
    for outcome in (
        LlmAnalysisError(REASON_API_ERROR),
        LlmAnalysisError(REASON_TIMEOUT),
        "broken json",
        RuntimeError("any sdk failure"),
    ):
        response = service_with(outcome).analyze(AnalyzeInquiryRequest(**ORDER_REQUEST))
        assert response.analysis_source == AnalysisSource.RULE_BASED
        assert response.priority is None


def test_prompt_requires_priority():
    """EN: The system prompt must demand priority and list the allowed values.

    中文：系统提示词必须要求返回 priority 并列出合法取值。
    """
    client = FakeChatClient(llm_json("ORDER_QUERY"))
    InquiryAnalysisService(GroqLlmAnalyzer(client)).analyze(
        AnalyzeInquiryRequest(**ORDER_REQUEST)
    )
    assert "priority" in client.system_prompt
    for value in ("LOW", "MEDIUM", "HIGH", "URGENT"):
        assert value in client.system_prompt
