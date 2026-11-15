import os
import socket

import pytest

# Tests never use the network and never pick up a live mode from the developer's shell.
os.environ["EPIC_PROVIDER_MODE"] = "replay"


# The model settings a developer may have in the shell or in pc/.env (spec 003): a test that
# needs one sets it itself, so the suite gives the same result whichever profile or key is in
# use. Done once, when the session starts and before any fixture builds a server (the browser
# tests keep one for a whole module), and not for `pytest -m live`, which needs the keys.
MODEL_SETTINGS = (
    "EPIC_VISION_PROFILE", "EPIC_TEXT_PROFILE", "EPIC_VISION_API_BASE", "EPIC_VISION_MODEL",
    "EPIC_VISION_API_KEY", "EPIC_AGENT_API_BASE", "EPIC_AGENT_MODEL", "EPIC_AGENT_API_KEY",
    "EPIC_AGENT_PROVIDER", "DEEPSEEK_API_KEY",
)  # fmt: skip


def pytest_configure(config):
    expr = config.getoption("markexpr") or ""
    if "live" in expr and "not live" not in expr:  # `-m live` runs the paid tests, which need keys
        return
    from epic_replay import paths  # loads pc/.env first, so what it sets is removed too

    assert paths.ENV_FILE
    for name in MODEL_SETTINGS:
        os.environ.pop(name, None)


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
