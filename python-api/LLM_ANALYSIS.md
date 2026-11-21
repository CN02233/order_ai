# 咨询分析技术文档：LLM 优先 + 规则降级

本文档整理 `python-api` 中咨询分析相关的全部类与方法。本次改造只涉及 `python-api`，未新建服务，
未修改 `springboot-api` 与 `vue-workbench`。

---

## 1. 目标与边界

| 项目 | 说明 |
| --- | --- |
| 分析方式 | 优先调用 Groq LLM；AI 失败或结果无效时自动降级到现有关键词规则分类器 |
| 返回结构 | AI 与规则两条路径共用同一个 `AnalyzeInquiryResponse` |
| 可观测性 | 记录 request id、AI 成败、分析来源、分类、耗时、降级原因 |
| 安全 | 不记录客户邮箱、咨询正文、API Key、异常堆栈或模型原始返回 |
| 不在范围内 | RAG、Agent、n8n、自动退款/取消订单、整体重构 |

---

## 2. 调用链

```
POST /analyze-inquiry
  │
  ├─ HttpLoggingMiddleware.__call__        设置 request_id（ContextVar）
  │
  ├─ routes.analyze_inquiry(payload, service)
  │
  └─ InquiryAnalysisService.analyze(request)
        │
        ├─ LLM 成功 ──────────────────────────► AnalyzeInquiryResponse(analysis_source=AI)
        │
        └─ LlmAnalysisError / 意外异常
              └─ _fallback(...)
                    └─ domain.classifier.classify(text)  ─► AnalyzeInquiryResponse(analysis_source=RULE_BASED)
                                                            suggested_reply=None, error_message=<安全原因>
```

人工审核判定在两条路径上都调用 `domain.review.needs_human_review()`，保证业务行为一致。

---

## 3. 文件职责总览

| 文件 | 职责 |
| --- | --- |
| `app/schemas/inquiry.py` | 请求/响应模型，分类与来源枚举 |
| `app/infrastructure/llm/config.py` | 从环境变量读取模型、Key、超时 |
| `app/infrastructure/llm/errors.py` | 固定的安全失败原因与 `LlmAnalysisError` |
| `app/infrastructure/llm/prompts.py` | 系统提示词与用户提示词构造 |
| `app/infrastructure/llm/client.py` | Groq SDK 懒加载封装 |
| `app/infrastructure/llm/analyzer.py` | JSON 解析 + Pydantic 校验 |
| `app/domain/classifier.py` | 原有关键词规则，未改动 |
| `app/domain/review.py` | 共享人工审核规则 |
| `app/request_context.py` | request id 的 ContextVar |
| `app/services/inquiry_analysis_service.py` | 编排 LLM 优先与规则降级 |
| `app/api/routes.py` | HTTP 端点与依赖注入点 |
| `app/main.py` | 应用入口、日志中间件、`load_dotenv()` |
| `tests/test_llm_analysis.py` | 全 Mock 的 LLM 相关测试 |

---

## 4. 类与方法详解

### 4.1 `app/schemas/inquiry.py`

**`InquiryCategory(str, Enum)`** — 允许的五种分类，同时约束 LLM 输出与响应结构。

| 成员 | 含义 |
| --- | --- |
| `REFUND` | 退款或退货 |
| `COMPLAINT` | 投诉 |
| `ORDER_QUERY` | 订单查询 |
| `PRODUCT_INQUIRY` | 商品咨询 |
| `OTHER` | 其他 |

**`AnalysisSource(str, Enum)`** — 结果来源：`AI` / `RULE_BASED`。

**`AnalyzeInquiryRequest(BaseModel)`** — 原请求字段，保持不变：

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `customer_email` | `EmailStr` | 必填 | 客户邮箱（只用于校验与持久化，不进分析日志） |
| `subject` | `str` | `""` | 主题 |
| `body` | `str` | 必填 | 咨询正文 |

**`AnalyzeInquiryResponse(BaseModel)`** — 统一响应：

| 字段 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `category` | `InquiryCategory` | 必填 | 咨询分类 |
| `confidence` | `float` | 必填 | 限制 0–1。AI 路径是模型自评，规则路径是固定标记（1.0/0.5），**不是校准概率** |
| `needs_human_review` | `bool` | 必填 | 是否需人工审核 |
| `suggested_reply` | `Optional[str]` | `None` | 建议回复；降级时一定为 `None` |
| `analysis_source` | `AnalysisSource` | `RULE_BASED` | 新增字段，默认值保证旧构造方式兼容 |
| `error_message` | `Optional[str]` | `None` | 新增字段；仅 AI 失败时非空 |
| `priority` | `Optional[InquiryPriority]` | `None` | T5 新增；仅 AI 路径返回合法优先级，RULE_BASED 降级恒为 `None`，绝不伪造 |

前四个字段是原有契约，名称与语义未变；其余为增量扩展。`InquiryPriority` 允许值：`LOW` / `MEDIUM` / `HIGH` / `URGENT`。

---

### 4.2 `app/infrastructure/llm/config.py`

模块常量：`DEFAULT_MODEL = "openai/gpt-oss-120b"`、`DEFAULT_TIMEOUT_SECONDS = 15.0`。

**`LlmConfig`（frozen dataclass）**

| 字段 | 类型 | 来源 |
| --- | --- | --- |
| `api_key` | `str \| None` | `GROQ_API_KEY`，空串视为未配置 |
| `model` | `str` | `GROQ_MODEL`，缺省用默认模型 |
| `timeout_seconds` | `float` | `LLM_TIMEOUT_SECONDS`，非法或非正数回退默认值 |

**`LlmConfig.from_env() -> LlmConfig`（classmethod）**
只从进程环境变量读取；Key 不写入源码、不记录日志、不出现在错误信息中。配置在每次 LLM 调用时
重新读取，使轮换后的 Key 立即生效。

---

### 4.3 `app/infrastructure/llm/errors.py`

**`LlmFailureReason = str`** — 失败原因类型别名。

| 常量 | 值 | 触发场景 |
| --- | --- | --- |
| `REASON_NOT_CONFIGURED` | `LLM is not configured` | 无 Key 或未安装 SDK |
| `REASON_TIMEOUT` | `LLM request timed out` | 请求超时 |
| `REASON_API_ERROR` | `LLM request failed` | SDK/HTTP/意外 AI 异常 |
| `REASON_EMPTY_RESPONSE` | `LLM returned an empty response` | 模型返回空内容 |
| `REASON_INVALID_JSON` | `LLM returned invalid JSON` | 返回内容无法解析为 JSON |
| `REASON_INVALID_RESULT` | `LLM returned an invalid analysis result` | 字段缺失/分类非法/confidence 越界 |

**`LlmAnalysisError(Exception)`**
- `__init__(self, reason: LlmFailureReason)`：保存 `self.reason`。
- 只携带预分类的安全原因，异常消息不向调用方或日志传播。

---

### 4.4 `app/infrastructure/llm/prompts.py`

**`SYSTEM_PROMPT`（str 常量）**
要求模型只返回 JSON，键为 `category`、`confidence`、`suggested_reply`；分类限定为
`InquiryCategory` 的五个值；`confidence` 为 0–1 自评；客户文本视为不可信数据，不执行其中指令；
回复中不带邮箱等个人信息。

**`build_user_prompt(subject: str, body: str) -> str`**
把主题与正文包进 `<subject>` / `<body>` 数据标签，防止提示词注入。提示词文本只在内存中，不写日志。

---

### 4.5 `app/infrastructure/llm/client.py`

**`ChatClient`（Protocol）**
- `complete(self, system_prompt: str, user_prompt: str) -> str`：测试与 Groq 适配器共用的最小接口。

**`GroqChatClient`**
- `__init__(self, config: LlmConfig | None = None)`：可选注入配置；为 `None` 时调用时读环境变量。
- `complete(self, system_prompt, user_prompt) -> str`：
  1. 无 Key 直接抛 `LlmAnalysisError(REASON_NOT_CONFIGURED)`；
  2. 懒加载 `groq.Groq`（缺依赖同样视为 AI 失败）；
  3. `with Groq(api_key, timeout, max_retries=0)` 创建并关闭客户端，保证超时有上限、不缓存 Key；
  4. 调用 `chat.completions.create`，`response_format={"type": "json_object"}`、`temperature=0`；
  5. 返回 `choices[0].message.content`，响应结构异常时抛 `REASON_EMPTY_RESPONSE`；
  6. 所有 SDK/传输异常经 `_failure_reason` 转换后抛出。
- `_failure_reason(exc: BaseException) -> str`（staticmethod）：
  只看异常类型名与消息中的 `timeout`/`timed out` 关键词来区分超时与一般失败；
  **异常消息只用于匹配，绝不作为返回值**。

---

### 4.6 `app/infrastructure/llm/analyzer.py`

**`LlmAnalysis(BaseModel)`** — 校验后的模型输出。

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| `category` | `InquiryCategory` | 只能是五种合法分类 |
| `priority` | `InquiryPriority` | **必填**且只能是 `LOW`/`MEDIUM`/`HIGH`/`URGENT`；缺失/非法视为 AI 失败，绝不静默降级为 MEDIUM |
| `confidence` | `float` | `Field(ge=0.0, le=1.0)` |
| `suggested_reply` | `str \| None` | **必填**（无默认值），缺字段视为 AI 失败 |

人工审核字段刻意不包含在模型输出中，不采信模型决定。

**`LlmAnalyzer`（Protocol）**
- `analyze(self, subject: str, body: str) -> LlmAnalysis`

**`GroqLlmAnalyzer`**
- `__init__(self, client: ChatClient | None = None)`：默认使用 `GroqChatClient()`。
- `analyze(self, subject, body) -> LlmAnalysis`：
  1. 调用 `client.complete(SYSTEM_PROMPT, build_user_prompt(...))`；
  2. 空内容 → `REASON_EMPTY_RESPONSE`；
  3. `json.loads` 失败 → `REASON_INVALID_JSON`，不记录原始字符串（可能含客户正文）；
  4. `LlmAnalysis.model_validate` 失败 → `REASON_INVALID_RESULT`。
- `_strip_code_fence(text: str) -> str`（staticmethod）：容忍可选的
  ` ```json ... ``` ` 包裹，但不信任其中内容。

---

### 4.7 `app/domain/classifier.py`（原有，未改动）

**`classify(text: str) -> str`**
按 `RULES` 字典顺序做小写子串匹配，返回首个命中分类，无命中返回 `OTHER`。
这是规则降级的唯一分类来源，异常不允许被静默吞掉。

---

### 4.8 `app/domain/review.py`

**`HUMAN_REVIEW_CATEGORIES`**：`{"REFUND", "COMPLAINT", "OTHER"}` 的 `frozenset`。

**`needs_human_review(category: InquiryCategory | str) -> bool`**
单一事实来源；AI 路径与规则路径都调用它，保证相同分类得到相同审核决定。

| 分类 | 人工审核 |
| --- | --- |
| `REFUND` / `COMPLAINT` / `OTHER` | 是 |
| `ORDER_QUERY` / `PRODUCT_INQUIRY` | 否 |

---

### 4.9 `app/request_context.py`

基于 `contextvars.ContextVar`，默认值为 `"-"`。

| 函数 | 说明 |
| --- | --- |
| `set_request_id(value: str)` | 写入请求 ID，返回 token |
| `reset_request_id(token)` | 请求结束时归还，防止 ID 串到下一请求 |
| `get_request_id()` | 供服务日志读取当前请求 ID |

---

### 4.10 `app/services/inquiry_analysis_service.py`

**`InquiryAnalysisService`**
- `__init__(self, llm_analyzer: LlmAnalyzer | None = None)`：默认 `GroqLlmAnalyzer()`，
  可注入 Mock 分析器。
- `analyze(self, request: AnalyzeInquiryRequest) -> AnalyzeInquiryResponse`：
  - 计时开始；
  - 调用 `llm_analyzer.analyze(request.subject, request.body)`；
  - `LlmAnalysisError` → `_fallback(request, error.reason, started_at)`；
  - 其他 `Exception` → 只记录异常**类型名**，再以 `REASON_API_ERROR` 降级；
  - 成功时按 `result` 构造 `analysis_source=AI` 的响应，`needs_human_review` 由共享规则计算。
- `_fallback(self, request, reason, started_at) -> AnalyzeInquiryResponse`：
  - 拼接 `subject + body` 调用 `classify()`；
  - **不做兜底捕获**：规则分类器异常直接抛出；
  - `analysis_source=RULE_BASED`、`suggested_reply=None`、`error_message=reason`；
  - confidence：命中关键词 1.0，`OTHER` 为 0.5。
- `_log_completion(response, ai_status, fallback_reason, started_at)`（staticmethod）：
  输出 `analysis_completed` 日志，含 request id、AI 成败、来源、分类、审核标记、耗时、降级原因；
  不记录邮箱、正文、Key 或模型原始返回。AI 失败用 `WARNING`，成功用 `INFO`。

---

### 4.11 `app/api/routes.py`

| 函数 | 说明 |
| --- | --- |
| `get_analysis_service() -> InquiryAnalysisService` | 依赖注入点，测试可覆盖以注入 Mock，无需网络 |
| `health()` | `GET /health`，返回 `{"status": "ok", "service": "inquiry-analysis"}` |
| `analyze_inquiry(payload, service)` | `POST /analyze-inquiry`，保持原路径与原请求字段，委托 `service.analyze(payload)` |

---

### 4.12 `app/main.py`

- `load_dotenv()`：本地从 `python-api/.env` 载入环境变量；部署环境直接注入同名变量。
- `REQUEST_ID_PATTERN`：合法请求 ID 的正则 `[A-Za-z0-9._-]{1,64}`。
- **`resolve_request_id(raw: bytes | None) -> str`**：校验客户端 `X-Request-ID`。超过 64 字符、
  含非字母数字/点/下划线/连字符、或为空时，生成新的 UUID。同一个最终 ID 用于日志、
  `ContextVar` 和响应头。
- **`HttpLoggingMiddleware`**
  - `__call__(scope, receive, send)`：调用 `resolve_request_id` 得到最终 ID，通过 `set_request_id`
    传入服务层；在 `finally` 中记录请求完成日志并 `reset_request_id`；响应头回写 `x-request-id`。
  - 异常处理：使用 `logger.error` 仅记录 `request_id`、`method`、`path`、`error_type`，
    **不记录异常消息和堆栈**，随后 `raise` 原样传播给 FastAPI。
  - `_json_field_names(body)`（staticmethod）：只记录 JSON **字段名**，不记录值。
  - `_response_summary(body)`（staticmethod）：只记录响应字段名/校验错误结构。

---

## 5. 降级矩阵

| 场景 | 失败原因 | 结果 |
| --- | --- | --- |
| 未配置 `GROQ_API_KEY` / 缺 SDK | `REASON_NOT_CONFIGURED` | RULE_BASED |
| 请求超时 | `REASON_TIMEOUT` | RULE_BASED |
| SDK/HTTP/意外 AI 异常 | `REASON_API_ERROR` | RULE_BASED |
| 模型返回空内容 | `REASON_EMPTY_RESPONSE` | RULE_BASED |
| 非法 JSON | `REASON_INVALID_JSON` | RULE_BASED |
| 字段缺失/分类非法/confidence 越界 | `REASON_INVALID_RESULT` | RULE_BASED |
| 规则分类器自身异常 | 不捕获，直接抛出 | HTTP 500，日志留痕 |

---

## 6. 配置

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `GROQ_API_KEY` | 空 | 为空则全程走规则；只从环境变量读取，禁止入库 |
| `GROQ_MODEL` | `openai/gpt-oss-120b` | Groq 模型名，可用环境变量覆盖；请检查本地 `.env` 是否仍配置旧模型 |
| `LLM_TIMEOUT_SECONDS` | `15` | 单次请求超时（秒），非法值回退默认 |

样例见 `.env.example`；`docker-compose.yml` 从宿主环境透传以上变量（空值即规则模式）。
`requirements.txt` 新增 `groq>=1,<2`。

---

## 7. API 契约与兼容性

请求字段保持 `customer_email`、`subject`、`body`；响应新增两个字段：

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

Spring Boot 的 `InquiryAnalysisResponse` 是四字段 record，使用默认 `ObjectMapper`
（`FAIL_ON_UNKNOWN_PROPERTIES` 关闭），会忽略新增的 `analysis_source` 与 `error_message`；
因此 Java 与 Vue 均无需修改。

---

## 8. 测试

`tests/test_llm_analysis.py` 中的辅助结构：

| 名称 | 说明 |
| --- | --- |
| `FakeChatClient` | 返回一个预设的原始模型答案或异常，并记录 system/user prompt |
| `llm_json(category, confidence, reply)` | 构造合法模型返回 |
| `service_with(outcome)` | 构造注入 Mock LLM 的服务 |
| `_clear_dependency_overrides()` | autouse fixture，隔离各测试的 DI 覆盖 |

`tests/test_config.py`：默认模型为 `openai/gpt-oss-120b`、`GROQ_MODEL` 覆盖默认值。

`tests/test_middleware.py`：非法/为空/超长的 `X-Request-ID` 被替换为 UUID、合法 ID 保持不变、
响应头与日志使用同一个 ID、异常日志只含类型不含消息与堆栈且异常继续传播。

覆盖内容：LLM 成功（ORDER_QUERY / REFUND）、API 异常、超时、非法 JSON、非法分类、
非法 confidence、字段缺失、OTHER/REFUND 人工审核、两条路径审核规则一致、降级来源、
旧接口兼容、无真实 Key 可运行、日志脱敏、规则异常不外吞、模型配置、请求 ID 校验、
异常日志安全。

```sh
cd python-api
.venv/bin/python -m pytest -q
```

结果：**39 passed**。测试全部 Mock，不访问真实 Groq API。

---

## 9. 修改文件清单

| 文件 | 类型 | 说明 |
| --- | --- | --- |
| `README.md` | 修改 | 增加 LLM 配置与行为说明 |
| `LLM_ANALYSIS.md` | 新增 | 本文档 |
| `python-api/requirements.txt` | 修改 | 新增 `groq>=1,<2` |
| `python-api/.env.example` | 修改 | 新增三个 LLM 环境变量样例 |
| `python-api/docker-compose.yml` | 修改 | 透传 LLM 环境变量 |
| `python-api/app/infrastructure/__init__.py` | 新增 | 适配器包 |
| `python-api/app/infrastructure/llm/__init__.py` | 新增 | LLM 包 |
| `python-api/app/infrastructure/llm/config.py` | 新增 | 环境配置 |
| `python-api/app/infrastructure/llm/errors.py` | 新增 | 安全失败原因 |
| `python-api/app/infrastructure/llm/prompts.py` | 新增 | 提示词 |
| `python-api/app/infrastructure/llm/client.py` | 新增 | Groq SDK 封装 |
| `python-api/app/infrastructure/llm/analyzer.py` | 新增 | 解析与 Pydantic 校验 |
| `python-api/app/domain/review.py` | 新增 | 共享审核规则 |
| `python-api/app/request_context.py` | 新增 | request id 上下文 |
| `python-api/app/schemas/inquiry.py` | 修改 | 枚举与新增响应字段 |
| `python-api/app/services/inquiry_analysis_service.py` | 修改 | LLM 优先与降级编排 |
| `python-api/app/api/routes.py` | 修改 | 依赖注入点 |
| `python-api/app/main.py` | 修改 | `load_dotenv`、请求 ID 传播 |
| `python-api/tests/test_llm_analysis.py` | 新增 | 全 Mock 测试 |

`python-api/app/domain/classifier.py` 规则未改动。所有新增/修改的注释均为中英双语。

### Task 01.1 增量变更

| 文件 | 说明 |
| --- | --- |
| `app/infrastructure/llm/config.py` | 默认模型改为 `openai/gpt-oss-120b`（保留 `GROQ_MODEL` 覆盖） |
| `app/main.py` | `resolve_request_id` 校验 `X-Request-ID`；异常日志只记类型不记消息/堆栈 |
| `.env.example` / `docker-compose.yml` / `README.md` | 模型默认值同步更新 |
| `tests/test_config.py` | 新增：默认模型与环境变量覆盖 |
| `tests/test_middleware.py` | 新增：请求 ID 校验与异常日志安全 |

注意：本地 `python-api/.env` 若仍固定 `llama-3.3-70b-versatile`，需手动改为
`openai/gpt-oss-120b`（或在环境中设置 `GROQ_MODEL`）；真实 API Key 只保留在本地，未写入代码或提交。
