"""Request-scoped context shared by the HTTP middleware and domain services.

EN: The middleware already assigns a request id; this variable makes the same id
available to service log lines without changing the public API.
中文：中间件已生成请求 ID；此变量让服务日志复用同一个 ID，无需改动对外 API。
"""
from contextvars import ContextVar

_request_id: ContextVar[str] = ContextVar("request_id", default="-")


def set_request_id(value: str):
    # EN: Returns a token that must be reset after the request.
    # 中文：返回 token，请求结束后必须调用 reset_request_id 归还。
    return _request_id.set(value)


def reset_request_id(token) -> None:
    # EN: Reset prevents one request's id leaking into the next one.
    # 中文：重置可避免上一个请求的 ID 串到下一个请求。
    _request_id.reset(token)


def get_request_id() -> str:
    return _request_id.get()
