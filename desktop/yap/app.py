"""The dictation loop: hotkey → record → transcribe → tidy → paste.

Hold the hotkey to talk and let go to paste. A quick tap instead starts
hands-free dictation, and the next press ends it.
"""
from __future__ import annotations

import json
import queue
import threading
import time
import urllib.request
from datetime import datetime

from . import cleanup
from .audio import Recorder, is_speech
from .config import DATA_DIR, IS_LINUX, Config
from .output import paste, sound
from .transcribe import SAMPLE_RATE, Transcriber

TAP = 0.3  # seconds; a press shorter than this is a tap, not a hold


class Pipeline:
    """Everything after the audio is captured. Also used by `yap file`."""

    def __init__(self, config: Config):
        self.config = config
        self.transcribe = Transcriber(config.engine, config.model, config.language, config.vocabulary)

    def warm(self) -> None:
        """Load the LLM in the background while the person is still speaking."""
        if self.config.cleanup != "ollama":
            return
        def ping():
            body = json.dumps({"model": self.config.ollama_model, "keep_alive": "30m"}).encode()
            req = urllib.request.Request(self.config.ollama_url.rstrip("/") + "/api/generate", body,
                                         {"Content-Type": "application/json"})
            try:
                urllib.request.urlopen(req, timeout=30).read()
            except OSError:
                pass
        threading.Thread(target=ping, daemon=True).start()

    def run(self, audio) -> tuple[str, str]:
        raw = self.transcribe(audio)
        c = self.config
        if not raw or c.cleanup == "off":
            return raw, raw
        if c.cleanup == "rules":
            return raw, cleanup.rules(raw)
        return raw, cleanup.polish(raw, url=c.ollama_url, model=c.ollama_model,
                                   vocabulary=c.vocabulary, timeout=c.ollama_timeout)


class Dictation:
    def __init__(self, config: Config):
        self.config = config
        self.pipeline = Pipeline(config)
        self.recorder = Recorder()
        self.jobs: queue.Queue = queue.Queue()
        self.pressed_at: float | None = None
        self.hands_free = False

    # -- hotkey -------------------------------------------------------------
    def on_press(self, key) -> None:
        if self._is_hotkey(key):
            self.down()

    def on_release(self, key) -> None:
        if self._is_hotkey(key):
            self.up()

    def down(self) -> None:
        if self.pressed_at is not None:
            return  # key-repeat while held
        self.pressed_at = time.monotonic()
        if self.hands_free:  # this press ends a hands-free dictation
            self.hands_free = False
            self._finish()
            return
        self._begin()

    def up(self) -> None:
        if self.pressed_at is None:
            return
        held = time.monotonic() - self.pressed_at
        self.pressed_at = None
        if not self.recorder.recording:
            return
        if held < TAP:
            self.hands_free = True  # a tap: keep listening until the next press
            print("… hands-free — press again to finish", flush=True)
        else:
            self._finish()

    def _is_hotkey(self, key) -> bool:
        return getattr(key, "name", None) == self.config.hotkey or str(key) == f"Key.{self.config.hotkey}"

    def _begin(self) -> None:
        try:
            self.recorder.start()
        except Exception as e:  # no mic, or permission denied
            print(f"! can't open the microphone: {e}", flush=True)
            if self.config.sounds:
                sound("error")
            return
        if self.config.sounds:
            sound("start")
        self.pipeline.warm()

    def _finish(self) -> None:
        audio = self.recorder.stop()
        if self.config.sounds:
            sound("stop")
        self.jobs.put(audio)

    # -- worker -------------------------------------------------------------
    def work(self) -> None:
        while True:
            audio = self.jobs.get()
            if not is_speech(audio):
                continue
            started = time.monotonic()
            try:
                raw, text = self.pipeline.run(audio)
            except Exception as e:
                print(f"! transcription failed: {e}", flush=True)
                if self.config.sounds:
                    sound("error")
                continue
            if not text:
                continue
            took = time.monotonic() - started
            print(f"✓ {took:.1f}s  {text}", flush=True)
            paste(text + " ", restore=self.config.restore_clipboard)
            if self.config.keep_history:
                self._remember(raw, text, len(audio) / SAMPLE_RATE, took)

    def _remember(self, raw: str, text: str, seconds: float, took: float) -> None:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        line = {"at": datetime.now().isoformat(timespec="seconds"), "seconds": round(seconds, 1),
                "took": round(took, 2), "raw": raw, "text": text}
        with open(DATA_DIR / "history.jsonl", "a") as f:
            f.write(json.dumps(line, ensure_ascii=False) + "\n")

    # -- main ---------------------------------------------------------------
    def run(self) -> None:
        print(f"Loading {self.pipeline.transcribe.model} …", flush=True)
        self.pipeline.transcribe.load()
        self.pipeline.warm()
        threading.Thread(target=self.work, daemon=True).start()
        if IS_LINUX:
            from . import evdev_keys
            code, devices = evdev_keys.keyboards(self.config.hotkey)
            if devices:
                self._ready()
                evdev_keys.listen(devices, code, self.down, self.up)
                return
            print("! can't read any keyboard in /dev/input, so the hotkey only works in X11 apps.\n"
                  "  Fix: sudo usermod -aG input $USER, then log out and back in.", flush=True)
        from pynput import keyboard

        self._ready()
        with keyboard.Listener(on_press=self.on_press, on_release=self.on_release) as listener:
            listener.join()

    def _ready(self) -> None:
        print(f"Ready. Hold [{self.config.hotkey}] to talk, or tap it for hands-free.", flush=True)
