# Python API — 客户咨询分析服务

> EN: The FastAPI service that classifies customer inquiries. It does **not** own business data.
> 中文：基于 FastAPI 的客户咨询分类服务，**不**持有任何业务数据。业务数据由 `springboot-api` 负责。

本服务为 Order AI 三端架构中的 AI 能力层：

- `python-api/`（本目录）：咨询分析 —— LLM 优先、规则降级、人工审核标记。
- `springboot-api/`：订单/库存/退款/物流等业务 API，调用本服务的 `/analyze-inquiry`。
- `vue-workbench/`：客服工作台。

业务规则保持确定性：退款请求只创建供人工审核，**绝不自动执行退款**。

如需了解每个类与方法的完整设计说明，见 [LLM_ANALYSIS.md](./LLM_ANALYSIS.md)。

---

## 1. 功能概览

- **LLM 优先**：使用 Groq SDK 调用大模型分析咨询，返回分类、自评置信度与建议回复。
- **规则降级**：LLM 未配置、超时、调用异常或返回无效结果时，自动使用原有关键词规则分类器。
- **统一结构**：两条路径返回相同的响应模型，并记录分析来源与（失败时的）安全错误原因。
- **人工审核**：`REFUND` / `COMPLAINT` / `OTHER` 两类路径统一标记为需要人工审核。
- **隐私与日志安全**：不记录客户邮箱、咨询正文、API Key 或模型原始返回；中间件只记录字段名与元数据。

支持的五种分类：`REFUND`、`COMPLAINT`、`ORDER_QUERY`、`PRODUCT_INQUIRY`、`OTHER`。

---

## 2. 环境要求

- Python 3.12（Docker 镜像使用 `python:3.12-slim`）
- 可选：MySQL 8.4（`docker-compose.yml` 提供，种子脚本 `seed.py` 使用）
- 可选：Groq API Key（不配置则自动走规则降级，本地开发/测试无需真实 Key）

---

## 3. 本地运行

```sh
cd python-api

# 创建并激活虚拟环境（若尚未创建）
python3.12 -m venv .venv
source .venv/bin/activate

# 安装依赖
pip install -r requirements.txt

# 复制配置样例并按需要填写；不填写 GROQ_API_KEY 即走规则分类
cp .env.example .env

# 启动服务（默认 8000 端口）
uvicorn app.main:app --host 0.0.0.0 --port 8000
```

健康检查：

```sh
curl http://localhost:8000/health
# {"status":"ok","service":"inquiry-analysis"}
```

> EN: `GROQ_API_KEY` is never required for local development or tests — an empty key falls back to rules.
> 中文：本地开发和测试**不需要**真实 `GROQ_API_KEY`；留空即自动使用规则分类。切勿把真实 Key 提交到 `.env.example`。

---

## 4. 配置（环境变量）

从 `python-api/.env` 读取（由 `load_dotenv()` 载入），生产环境由部署平台直接注入同名变量。

| 变量 | 默认值 | 说明 |
| --- | --- | --- |
| `GROQ_API_KEY` | 空 | Groq API Key；**只能来自环境变量**。留空时服务自动走规则降级。 |
| `GROQ_MODEL` | `openai/gpt-oss-120b` | Groq 模型名，可通过环境变量覆盖。 |
| `LLM_TIMEOUT_SECONDS` | `15` | 单次 LLM 请求超时（秒）；非法或非正数回退到 15。 |
| `DATABASE_URL` | 见 `.env.example` | 数据库连接串，供 `seed.py` 种子脚本使用。 |

---

## 5. HTTP 接口

### `GET /health`

返回 `{"status": "ok", "service": "inquiry-analysis"}`。

### `POST /analyze-inquiry`

请求体（原有字段，保持不变）：

```json
{
  "customer_email": "customer@example.com",
  "subject": "Where is my order?",
  "body": "I placed order ORD-10001 three days ago and it has not shipped."
}
```

响应体：

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

字段说明：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `category` | string | 五种分类之一。 |
| `confidence` | float | 0~1；LLM 路径为**模型自评**，非校准概率；规则路径为固定标记（命中 1.0 / OTHER 0.5）。 |
| `needs_human_review` | bool | `REFUND` / `COMPLAINT` / `OTHER` 为 `true`，其余 `false`。 |
| `suggested_reply` | string \| null | 建议回复；规则降级时为 `null`。 |
| `analysis_source` | string | `AI` 或 `RULE_BASED`。 |
| `error_message` | string \| null | 仅 AI 失败时返回固定安全原因（如 `LLM request timed out`），正常为 `null`。 |

降级响应示例（`GROQ_API_KEY` 未配置时）：

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

请求头：可传入 `X-Request-ID`（最长 64 字符，仅字母/数字/`.`/`_`/`-`）；非法或为空时服务端生成 UUID。同一个最终 ID 用于响应头与日志。

---

## 6. 测试

全部测试均为 Mock，**不依赖真实 Groq API Key 或网络连接**。

```sh
cd python-api
.venv/bin/python -m pytest -q
```

当前结果：**39 passed**。

覆盖范围：LLM 成功分类、各类降级（未配置/异常/超时/非法 JSON/非法分类/非法置信度）、人工审核规则、响应结构兼容、请求 ID 校验、日志脱敏、默认模型与环境变量覆盖。

---

## 7. Docker 运行

```sh
# 从环境变量注入 Key（留空则走规则降级）
export GROQ_API_KEY=<your-key>   # 可选

cd python-api
docker compose up --build
# 服务监听 http://localhost:8000，MySQL 监听 3306
```

未使用 Docker 时，可直接运行 `python seed.py` 幂等写入演示客户/商品/订单（需先配置 `DATABASE_URL` 并确保数据库可用）。

---

## 8. 目录结构

```
python-api/
├── app/
│   ├── main.py                      # FastAPI 入口、日志中间件、请求 ID 校验
│   ├── request_context.py           # request_id 的 ContextVar
│   ├── api/routes.py                # /health、/analyze-inquiry
│   ├── domain/
│   │   ├── classifier.py            # 原有关键词规则分类器（降级用，未改动）
│   │   └── review.py                # 共享人工审核规则
│   ├── infrastructure/llm/
│   │   ├── config.py                # 环境变量配置
│   │   ├── errors.py                # 固定安全失败原因
│   │   ├── prompts.py               # 严格 JSON Prompt
│   │   ├── client.py                # Groq SDK 封装（超时/重试边界）
│   │   └── analyzer.py              # JSON 解析 + Pydantic 校验
│   ├── schemas/inquiry.py           # 请求/响应模型与枚举
│   └── services/inquiry_analysis_service.py  # LLM 优先 + 规则降级编排
├── tests/                           # pytest 测试（全 Mock）
├── .env.example                     # 环境变量样例（不含真实 Key）
├── requirements.txt
├── Dockerfile
├── docker-compose.yml
├── seed.py                          # 演示数据种子脚本
└── LLM_ANALYSIS.md                  # 类与方法级技术文档
```

---

## 9. 安全约定

- API Key 只从环境变量读取，不写入源码、日志或错误信息。
- 不记录客户邮箱、咨询正文、原始异常堆栈或模型原始返回。
- LLM 失败原因使用固定枚举字符串，不暴露服务商内部信息。
- `confidence` 是模型自评/规则标记，不应作为经过校准的概率使用。

> EN: Never commit a real API key. Keep `.env` local and leave `GROQ_API_KEY=` empty in `.env.example`.
> 中文：切勿提交真实 API Key。`.env` 仅保留在本地，`.env.example` 中的 `GROQ_API_KEY` 请保持为空。
