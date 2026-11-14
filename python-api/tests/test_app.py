from fastapi.testclient import TestClient
from app.main import app
client = TestClient(app)

def test_health():
    assert client.get("/health").json() == {"status": "ok", "service": "inquiry-analysis"}

def test_inquiry_analysis():
    response = client.post("/analyze-inquiry", json={"customer_email":"john@example.com", "subject":"Order", "body":"Where is my order?"})
    assert response.status_code == 200
    assert response.json()["category"] == "ORDER_QUERY"
    assert response.json()["needs_human_review"] is False

def test_missing_request_body_is_logged_without_customer_content(caplog):
    response = client.post("/analyze-inquiry", headers={"X-Request-ID": "test-request-422"})

    assert response.status_code == 422
    completion_logs = [record.getMessage() for record in caplog.records]
    matching_log = next(
        message for message in completion_logs
        if "http_request_completed" in message and "test-request-422" in message
    )
    assert "received_body_bytes=0" in matching_log
    assert "json_fields=unparseable_or_empty" in matching_log
    assert "loc=body" in matching_log
