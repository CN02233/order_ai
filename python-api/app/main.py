"""Application entry point for the Python inquiry analysis service."""
import logging
import json
import time
import uuid

from dotenv import load_dotenv
from fastapi import FastAPI

from app.api.routes import router
from app.request_context import reset_request_id, set_request_id

# EN: Local runs use python-api/.env; deployments inject the same variables.
# 中文：本地运行读取 python-api/.env；部署环境直接注入同名环境变量。
load_dotenv()

# EN: Include file and line number so a log entry points to the failing code.
# 中文：带上文件名和行号，便于日志定位到出错代码。
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s [%(name)s] %(filename)s:%(lineno)d %(funcName)s - %(message)s",
    force=True,
)
logger = logging.getLogger("order_ai")

app = FastAPI(title="Order AI Inquiry Analysis Service", version="0.2.0")
app.include_router(router)


class HttpLoggingMiddleware:
    """EN: Log HTTP metadata and JSON field names only, never customer values.

    中文：只记录 HTTP 元数据和 JSON 字段名，绝不记录客户的字段值。
    """

    def __init__(self, application):
        self.application = application

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            await self.application(scope, receive, send)
            return

        headers = {key.lower(): value for key, value in scope.get("headers", [])}
        request_id = headers.get(b"x-request-id", str(uuid.uuid4()).encode()).decode(
            "utf-8", errors="replace"
        )
        # EN: Propagate the middleware request id into service log lines.
        # 中文：把中间件的请求 ID 传递到服务层日志中。
        request_id_token = set_request_id(request_id)
        method = scope.get("method", "")
        path = scope.get("path", "")
        content_type = headers.get(b"content-type", b"unknown").decode(
            "utf-8", errors="replace"
        )
        request_chunks = []
        response_chunks = []
        response_status = 500
        started_at = time.perf_counter()

        logger.info(
            "http_request_started request_id=%s method=%s path=%s content_type=%s declared_content_length=%s",
            request_id,
            method,
            path,
            content_type,
            headers.get(b"content-length", b"unknown").decode("ascii", errors="replace"),
        )

        async def logged_receive():
            message = await receive()
            if message["type"] == "http.request":
                request_chunks.append(message.get("body", b""))
            return message

        async def logged_send(message):
            nonlocal response_status
            if message["type"] == "http.response.start":
                response_status = message["status"]
                response_headers = list(message.get("headers", []))
                response_headers.append((b"x-request-id", request_id.encode("utf-8")))
                message["headers"] = response_headers
            elif message["type"] == "http.response.body":
                response_chunks.append(message.get("body", b""))
            await send(message)

        try:
            await self.application(scope, logged_receive, logged_send)
        except Exception:
            logger.exception(
                "http_request_failed request_id=%s method=%s path=%s",
                request_id,
                method,
                path,
            )
            raise
        finally:
            request_body = b"".join(request_chunks)
            response_body = b"".join(response_chunks)
            duration_ms = round((time.perf_counter() - started_at) * 1000, 2)
            request_fields = self._json_field_names(request_body)
            response_summary = self._response_summary(response_body)
            logger.info(
                "http_request_completed request_id=%s method=%s path=%s status=%s duration_ms=%s received_body_bytes=%s json_fields=%s response_body_bytes=%s response_summary=%s",
                request_id,
                method,
                path,
                response_status,
                duration_ms,
                len(request_body),
                request_fields,
                len(response_body),
                response_summary,
            )
            # EN: Always reset, otherwise the id leaks into the next request.
            # 中文：必须重置，否则请求 ID 会串到下一个请求。
            reset_request_id(request_id_token)

    @staticmethod
    def _json_field_names(body):
        try:
            value = json.loads(body.decode("utf-8"))
            if isinstance(value, dict):
                return ",".join(value.keys()) or "none"
            return "json_" + type(value).__name__
        except (UnicodeDecodeError, json.JSONDecodeError):
            return "unparseable_or_empty"

    @staticmethod
    def _response_summary(body):
        try:
            value = json.loads(body.decode("utf-8"))
            details = value.get("detail") if isinstance(value, dict) else None
            if isinstance(details, list):
                return ";".join(
                    "type={type},loc={loc},msg={msg}".format(
                        type=item.get("type", "unknown"),
                        loc=".".join(str(part) for part in item.get("loc", [])),
                        msg=item.get("msg", ""),
                    )
                    for item in details
                    if isinstance(item, dict)
                ) or "validation_error"
            if isinstance(value, dict):
                return "json_fields=" + ",".join(value.keys())
            return "json_" + type(value).__name__
        except (UnicodeDecodeError, json.JSONDecodeError):
            return "unparseable_or_empty"


app.add_middleware(HttpLoggingMiddleware)
