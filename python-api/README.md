# Python API — Customer Inquiry Analysis Service

> EN: The FastAPI service that classifies customer inquiries. It does **not** own business data.
> 中文：基于 FastAPI 的客户咨询分类服务，**不**持有任何业务数据。业务数据由 `springboot-api` 负责。

This service is the AI layer of the three-part Order AI architecture:

- `python-api/` (this directory): inquiry analysis — LLM first, rule fallback, human-review flagging.
- `springboot-api/`: the business API for orders, inventory, refunds and logistics; calls our `/analyze-inquiry`.
- `vue-workbench/`: the customer support workbench.

Business rules stay deterministic: refund requests are only created for human review and **never** executed automatically.

For a class-by-class, method-by-method design reference, see [LLM_ANALYSIS.md](./LLM_ANALYSIS.md). 中文文档见 [README_CN.md](./README_CN.md).

---

## 1. Overview

- **LLM first**: classify inquiries with a Groq-backed model, returning a category, a self-assessed confidence and a suggested reply.
- **Rule fallback**: when the LLM is unconfigured, times out, errors out or returns an invalid result, fall back to the original keyword classifier.
- **Unified response**: both paths share one response model and record the analysis source plus a safe reason on failure.
- **Human review**: `REFUND`, `COMPLAINT` and `OTHER` are flagged for human review on both paths.
- **Privacy & log safety**: never log customer emails, inquiry text, the API key or raw model output; the middleware logs only field names and metadata.

Supported categories: `REFUND`, `COMPLAINT`, `ORDER_QUERY`, `PRODUCT_INQUIRY`, `OTHER`.

---

## 2. Requirements

- Python 3.12 (the Docker image uses `python:3.12-slim`)
- Optional: MySQL 8.4 (provided by `docker-compose.yml`, used by the `seed.py` seed script)
- Optional: a Groq API key — without one the service automatically uses rule fallback; no real key is needed for local development or tests.

---

## 3. Run locally

```sh
cd python-api

# Create and activate a virtual environment (if not already present)
python3.12 -m venv .venv
source .venv/bin/activate

# Install dependencies
pip install -r requirements.txt

# Copy the sample config and edit as needed; leaving GROQ_API_KEY empty uses rule classification
cp .env.example .env

# Start the service (default port 8000)
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

Health check:

```sh
curl http://localhost:8000/health
# {"status":"ok","service":"inquiry-analysis"}
```

> EN: `GROQ_API_KEY` is never required for local development or tests — an empty key falls back to rules.
> 中文：本地开发和测试**不需要**真实 `GROQ_API_KEY`；留空即自动使用规则分类。切勿把真实 Key 提交到 `.env.example`。

---

## 4. Configuration (environment variables)

Read from `python-api/.env` (loaded by `load_dotenv()`); in production the same variables are injected directly by the deployment platform.

| Variable | Default | Description |
| --- | --- | --- |
| `GROQ_API_KEY` | empty | Groq API key; **read from the environment only**. When empty the service falls back to rules. |
| `GROQ_MODEL` | `openai/gpt-oss-120b` | Groq model name; overridable via the environment variable. |
| `LLM_TIMEOUT_SECONDS` | `15` | Per-call LLM timeout in seconds; invalid or non-positive values fall back to 15. |
| `DATABASE_URL` | see `.env.example` | Database connection string used by the `seed.py` seed script. |

---

## 5. HTTP API

### `GET /health`

Returns `{"status": "ok", "service": "inquiry-analysis"}`.

### `POST /analyze-inquiry`

Request body (original fields, unchanged):

```json
{
  "customer_email": "customer@example.com",
  "subject": "Where is my order?",
  "body": "I placed order ORD-10001 three days ago and it has not shipped."
}
```

Response body:

```json
{
  "category": "ORDER_QUERY",
  "confidence": 0.95,
  "needs_human_review": false,
  "suggested_reply": "You can track your order using the tracking link.",
  "analysis_source": "AI",
  "error_message": null
}
```

Fields:

| Field | Type | Description |
| --- | --- | --- |
| `category` | string | One of the five categories. |
| `confidence` | float | 0–1; on the LLM path this is the **model's self-assessment**, not a calibrated probability; on the rule path it is a fixed marker (1.0 on a keyword hit / 0.5 for `OTHER`). |
| `needs_human_review` | bool | `true` for `REFUND` / `COMPLAINT` / `OTHER`, otherwise `false`. |
| `suggested_reply` | string \| null | Suggested reply; `null` on rule fallback. |
| `analysis_source` | string | `AI` or `RULE_BASED`. |
| `error_message` | string \| null | A fixed safe reason only when AI fails (e.g. `LLM request timed out`), otherwise `null`. |

Fallback response example (when `GROQ_API_KEY` is not configured):

```json
{
  "category": "ORDER_QUERY",
  "confidence": 1.0,
  "needs_human_review": false,
  "suggested_reply": null,
  "analysis_source": "RULE_BASED",
  "error_message": "LLM is not configured"
}
```

Request header: you may send `X-Request-ID` (at most 64 characters, only letters, digits, `.`, `_` and `-`); an invalid or empty value is replaced with a generated UUID. The same final ID is used in the response header and in logs.

---

## 6. Tests

All tests are mocked and require **no real Groq API key and no network access**.

```sh
cd python-api
.venv/bin/python -m pytest -q
```

Current result: **39 passed**.

Coverage: successful LLM classification, every fallback case (unconfigured / API error / timeout / invalid JSON / invalid category / invalid confidence), human-review rules, response compatibility, request-ID validation, log redaction, and default-model / environment override.

---

## 7. Docker

```sh
# Inject the key from the environment (leave empty to use rule fallback)
export GROQ_API_KEY=<your-key>   # optional

cd python-api
docker compose up --build
# API on http://localhost:8000, MySQL on 3306
```

Without Docker, run `python seed.py` to idempotently seed demo customers, products and orders (requires a working `DATABASE_URL` and database).

---

## 8. Project layout

```
python-api/
├── app/
│   ├── main.py                      # FastAPI entry point, logging middleware, request-ID validation
│   ├── request_context.py           # ContextVar for request_id
│   ├── api/routes.py                # /health, /analyze-inquiry
│   ├── domain/
│   │   ├── classifier.py            # original keyword classifier (fallback, unchanged)
│   │   └── review.py                # shared human-review rule
│   ├── infrastructure/llm/
│   │   ├── config.py                # environment-driven configuration
│   │   ├── errors.py                # fixed, safe failure reasons
│   │   ├── prompts.py               # strict JSON prompt
│   │   ├── client.py                # Groq SDK wrapper (timeout / retry boundary)
│   │   └── analyzer.py              # JSON parsing + Pydantic validation
│   ├── schemas/inquiry.py           # request/response models and enums
│   └── services/inquiry_analysis_service.py  # LLM-first + rule-fallback orchestration
├── tests/                           # pytest tests (fully mocked)
├── .env.example                     # environment sample (no real key)
├── requirements.txt
├── Dockerfile
├── docker-compose.yml
├── seed.py                          # demo-data seed script
├── README.md                        # this file (English)
├── README_CN.md                     # Chinese version
└── LLM_ANALYSIS.md                  # class- and method-level technical reference
```

---

## 9. Security notes

- The API key is read from environment variables only — never stored in source, logs or error messages.
- Customer emails, inquiry text, raw exception tracebacks and raw model output are never logged.
- LLM failure reasons are fixed strings; provider internals are never exposed to callers.
- `confidence` is a model self-assessment or a rule marker — not a calibrated probability.

> EN: Never commit a real API key. Keep `.env` local and leave `GROQ_API_KEY=` empty in `.env.example`.
> 中文：切勿提交真实 API Key。`.env` 仅保留在本地，`.env.example` 中的 `GROQ_API_KEY` 请保持为空。
