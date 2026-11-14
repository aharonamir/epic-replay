import os
import socket

import pytest

# Tests never use the network and never pick up a live mode from the developer's shell.
os.environ["EPIC_PROVIDER_MODE"] = "replay"


@pytest.fixture
def no_network(monkeypatch):
    def refuse(*a, **k):
        raise AssertionError("network access attempted in a replay-mode test")

    monkeypatch.setattr(socket.socket, "connect", refuse)
    monkeypatch.setattr(socket, "create_connection", refuse)


class Catch:
    """Stands in for `Client.send` of httpx and httpx2 (the openai package is built on httpx2):
    notes each request and answers a chat completion holding `content`. No socket can open."""

    def __init__(self, content: str = "{}", usage=None, message: dict | None = None):
        self.content = content
        self.usage = {"prompt_tokens": 1000, "completion_tokens": 500} if usage is None else usage
        self.message = message or {}
        self.requests: list[dict] = []

    def __call__(self, client, request, **kw):
        import json

        import httpx
        import httpx2

        body = json.loads(request.content)
        self.requests.append(
            {
                "method": request.method,
                "url": str(request.url),
                "headers": {k.lower(): v for k, v in request.headers.items()},
                "timeout": request.extensions.get("timeout"),
                "body": body,
            }
        )
        reply = {
            "id": "c1", "object": "chat.completion", "created": 1, "model": body["model"],
            "choices": [{"index": 0, "finish_reason": "stop",
                         "message": {"role": "assistant", "content": self.content, **self.message}}],
            "usage": {**self.usage, "total_tokens": sum(self.usage.values())},
        }  # fmt: skip
        lib = httpx2 if isinstance(request, httpx2.Request) else httpx
        return lib.Response(200, json=reply, request=request)


@pytest.fixture
def http_catch(monkeypatch):
    """`http_catch(content, ...)` -> the Catch that now answers every model request."""

    def install(*args, **kw) -> Catch:
        import httpx
        import httpx2

        catch = Catch(*args, **kw)

        def refuse(*a, **k):
            raise AssertionError("a request left the test")

        for lib in (httpx, httpx2):
            monkeypatch.setattr(lib.Client, "send", lambda self, req, **k: catch(self, req, **k))
        monkeypatch.setattr(socket.socket, "connect", refuse)
        monkeypatch.setattr(socket, "create_connection", refuse)
        return catch

    return install
