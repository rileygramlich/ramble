"""`ramble serve` end to end, with a stand-in for Whisper."""
import json
import threading
import urllib.request
import numpy as np
import pytest

from ramble import serve as serve_module
from ramble.config import Config


class FakeWhisper:
    def __init__(self, *args):
        self.model = "fake"
        self.heard = None

    def load(self):
        pass

    def __call__(self, audio, vocabulary=()):
        self.heard = (len(audio), list(vocabulary))
        return "um so send it to Katharina"


@pytest.fixture
def server(monkeypatch):
    fake = FakeWhisper()
    monkeypatch.setattr(serve_module, "Transcriber", lambda *a: fake)
    server = serve_module.make_server(Config(cleanup="rules"), "127.0.0.1", 0)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{server.server_address[1]}", fake
    server.shutdown()


def test_dictate_returns_tidy_text(server):
    url, fake = server
    pcm = (np.sin(np.arange(16000) / 5) * 8000).astype("<i2").tobytes()
    req = urllib.request.Request(url + "/dictate", pcm, {"X-Yap-Vocabulary": "Katharina,%20Yap"})
    body = json.loads(urllib.request.urlopen(req).read())
    assert fake.heard == (16000, ["Katharina", "Yap"])
    assert body["raw"] == "um so send it to Katharina"
    assert body["text"] == "So send it to Katharina"
    assert body["action"] is None


def test_health(server):
    url, _ = server
    assert json.loads(urllib.request.urlopen(url + "/health").read()) == {"ok": True, "model": "fake"}


def test_refuses_to_listen_everywhere():
    with pytest.raises(SystemExit):
        serve_module.make_server(Config(), "", 8723)
