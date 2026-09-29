"""`yap serve`: speech-to-text and tidy-up for other devices, e.g. the phone.

The phone records, sends the audio here over Tailscale, and gets tidy text back
in one round trip. This machine runs a bigger Whisper model than a phone can,
and the Ollama tidy-up next to it. Nothing leaves your tailnet.

    POST /dictate   body: 16 kHz mono 16-bit little-endian PCM
                    header X-Yap-Vocabulary: comma-separated names (URL-encoded)
                    → {"raw": ..., "text": ..., "took": seconds}
    GET  /health    → {"ok": true, "model": ...}

Listen on the Tailscale address only (--host 100.x.y.z), not on every network.
"""
from __future__ import annotations

import json
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import numpy as np

from . import cleanup
from .config import Config
from .transcribe import SAMPLE_RATE, Transcriber

MAX_SECONDS = 300  # refuse anything longer than five minutes of audio


def serve(config: Config, host: str, port: int) -> None:
    server = make_server(config, host, port)
    print(f"Serving speech on http://{host}:{port}", flush=True)
    server.serve_forever()


def make_server(config: Config, host: str, port: int) -> ThreadingHTTPServer:
    if not host:
        # An empty host would mean every network; tailscale ip prints nothing when it's down.
        raise SystemExit("No address to listen on (is Tailscale up?). Give one: yap serve 100.x.y.z")
    transcribe = Transcriber(config.engine, config.serve_model, config.language, config.vocabulary)
    print(f"Loading {transcribe.model} …", flush=True)
    transcribe.load()
    lock = threading.Lock()  # one transcription at a time; the model isn't shared safely

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            if self.path != "/health":
                return self._send(404, {"error": "not found"})
            self._send(200, {"ok": True, "model": transcribe.model})

        def do_POST(self):
            if self.path != "/dictate":
                return self._send(404, {"error": "not found"})
            size = int(self.headers.get("Content-Length") or 0)
            if not 0 < size <= MAX_SECONDS * SAMPLE_RATE * 2:
                return self._send(413, {"error": "send 16 kHz 16-bit mono PCM, up to five minutes"})
            audio = np.frombuffer(self.rfile.read(size), dtype="<i2").astype(np.float32) / 32768
            words = urllib.parse.unquote_plus(self.headers.get("X-Yap-Vocabulary") or "")
            vocabulary = [w.strip() for w in words.split(",") if w.strip()]
            started = time.monotonic()
            with lock:
                raw = transcribe(audio, vocabulary)
            heard = time.monotonic()
            text = tidy(config, raw, [*config.vocabulary, *vocabulary])
            took = round(time.monotonic() - started, 2)
            print(f"✓ {took}s (whisper {heard - started:.1f}s, tidy {time.monotonic() - heard:.1f}s)  "
                  f"{len(audio) / SAMPLE_RATE:.1f}s from {self.client_address[0]}  {text}", flush=True)
            self._send(200, {"raw": raw, "text": text, "took": took})

        def _send(self, status: int, body: dict):
            data = json.dumps(body, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def log_message(self, *args):
            pass  # the ✓ line above is the useful log

    return ThreadingHTTPServer((host, port), Handler)


def tidy(config: Config, raw: str, vocabulary: list[str]) -> str:
    if not raw or config.cleanup == "off":
        return raw
    if config.cleanup == "rules":
        return cleanup.rules(raw)
    return cleanup.polish(raw, url=config.ollama_url, model=config.ollama_model,
                          vocabulary=vocabulary, timeout=config.ollama_timeout)
