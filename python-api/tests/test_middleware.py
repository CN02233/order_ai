"""Tests for request-id validation and safe exception logging.

EN: Invalid X-Request-ID values are replaced; failures log type only.
中文：非法的 X-Request-ID 会被替换；异常日志只记录异常类型。
"""
import logging
import re

import pytest
from fastapi.testclient import TestClient

from app.api.routes import get_analysis_service
from app.main import app

UUID_PATTERN = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
)
ORDER_REQUEST = {
    "customer_email": "john@example.com",
    "subject": "Order",
    "body": "Where is my order?",
}


@pytest.fixture(autouse=True)
def _clear_dependency_overrides():
    # EN: Keep dependency overrides isolated between tests.
    # 中文：保证各测试之间的依赖注入覆盖互不干扰。
    yield
    app.dependency_overrides.clear()


def _started_logs(caplog):
    return [
        record.getMessage()
        for record in caplog.records
        if "http_request_started" in record.getMessage()
    ]


@pytest.mark.parametrize("raw_id", ["bad id", "bad/id", "bad@id", "x" * 65, ""])
def test_invalid_request_id_is_replaced(raw_id, caplog):
    """EN: Invalid or empty ids become UUIDs in headers and logs alike.

    中文：非法或空 ID 会被 UUID 替换，响应头与日志使用同一个值。
    """
    caplog.set_level(logging.INFO, logger="order_ai")
    response = TestClient(app).get("/health", headers={"X-Request-ID": raw_id})
    final_id = response.headers["x-request-id"]
    logs = _started_logs(caplog)

    assert response.status_code == 200
    assert final_id != raw_id
    assert UUID_PATTERN.fullmatch(final_id)
    assert logs
    assert all(f"request_id={final_id}" in message for message in logs)


@pytest.mark.parametrize("raw_id", ["abc123", "test-request-422", "A.b_c-9", "a" * 64])
def test_valid_request_id_is_kept(raw_id, caplog):
    """EN: A valid id is preserved in the response header and logs.

    中文：合法 ID 在响应头和日志中保持原样。
    """
    caplog.set_level(logging.INFO, logger="order_ai")
    response = TestClient(app).get("/health", headers={"X-Request-ID": raw_id})
    logs = _started_logs(caplog)

    assert response.headers["x-request-id"] == raw_id
    assert logs
    assert all(f"request_id={raw_id}" in message for message in logs)


def test_exception_log_has_no_message_or_traceback(caplog):
    """EN: Failure logs keep type only; the exception still reaches FastAPI.

    中文：失败日志只记录异常类型，异常仍正常向 FastAPI 上层传播。
    """
    caplog.set_level(logging.INFO, logger="order_ai")
    secret = "SUPER_SECRET_EXCEPTION_MESSAGE_9f3a"

    class ExplodingService:
        def analyze(self, _request):
            raise RuntimeError(secret)

    app.dependency_overrides[get_analysis_service] = lambda: ExplodingService()
    client = TestClient(app)

    with pytest.raises(RuntimeError):
        client.post("/analyze-inquiry", json=ORDER_REQUEST)

    records = [
        record
        for record in caplog.records
        if "http_request_failed" in record.getMessage()
    ]
    assert len(records) == 1
    record = records[0]
    message = record.getMessage()

    assert "request_id=" in message
    assert "method=POST" in message
    assert "path=/analyze-inquiry" in message
    assert "error_type=RuntimeError" in message
    assert secret not in message
    assert "Traceback" not in message
    assert record.exc_info is None
    assert record.exc_text is None
