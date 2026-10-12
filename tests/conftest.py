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
