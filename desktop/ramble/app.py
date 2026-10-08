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
from .config import DATA_DIR, IS_LINUX, IS_MAC, Config
from .output import paste, press_enter, sound
from .transcribe import SAMPLE_RATE, Transcriber

TAP = 0.3  # seconds; a press shorter than this is a tap, not a hold


class Pipeline:
    """Everything after the audio is captured. Also used by `ramble file`."""

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

    def run(self, audio) -> tuple[str, str, str | None]:
        """(what Whisper heard, the tidy text, a spoken command: "enter", "send" or None)."""
        raw = self.transcribe(audio)
        spoken, action = cleanup.command(raw)
        return raw, tidy(self.config, spoken), action


def tidy(config: Config, text: str, vocabulary: list[str] | None = None, *,
         punctuation: bool | None = None, replacements: dict[str, str] | None = None) -> str:
    """`punctuation` and `replacements` come from the phone when it sends audio; else config.toml."""
    if not text or config.cleanup == "off":
        return text
    punctuation = config.spoken_punctuation if punctuation is None else punctuation
    replacements = {**config.replacements, **(replacements or {})}
    if config.cleanup == "rules":
        return cleanup.rules(text, punctuation=punctuation, replacements=replacements)
    vocabulary = config.vocabulary if vocabulary is None else vocabulary
    # What you said to type instead should come out spelled exactly that way too.
    vocabulary = [*vocabulary, *(v for v in replacements.values() if any(c.isalpha() for c in v))]
    return cleanup.polish(text, url=config.ollama_url, model=config.ollama_model, vocabulary=vocabulary,
                          timeout=config.ollama_timeout, punctuation=punctuation, replacements=replacements)


class Dictation:
    def __init__(self, config: Config):
        self.config = config
        self.pipeline = Pipeline(config)
        self.recorder = Recorder()
        self.jobs: queue.Queue = queue.Queue()
        self.pressed_at: float | None = None
        self.hands_free = False
        # Something on screen showing the state, e.g. the Mac bubble: it gets
        # .listening(), .writing() and .idle(), from any thread.
        self.ui = None

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

    # -- clicks on the bubble --------------------------------------------------
    def toggle(self) -> None:
        """A click: start hands-free dictation, or finish the one that's going."""
        if self.recorder.recording:
            self.hands_free = False
            self._finish()
        else:
            self._begin()
            self.hands_free = self.recorder.recording

    def cancel(self) -> None:
        """✕: stop listening and throw the recording away."""
        if self.recorder.recording:
            self.recorder.stop()
        self.hands_free = False
        self._show("idle")

    def _show(self, state: str) -> None:
        if self.ui is not None:
            getattr(self.ui, state)()

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
        self._show("listening")
        self.pipeline.warm()

    def _finish(self) -> None:
        audio = self.recorder.stop()
        if self.config.sounds:
            sound("stop")
        self._show("writing")
        self.jobs.put(audio)

    # -- worker -------------------------------------------------------------
    def work(self) -> None:
        while True:
            audio = self.jobs.get()
            try:
                self._handle(audio)
            finally:
                if not self.recorder.recording and self.jobs.empty():
                    self._show("idle")

    def _handle(self, audio) -> None:
        if not is_speech(audio):
            return
        started = time.monotonic()
        try:
            raw, text, action = self.pipeline.run(audio)
        except Exception as e:
            print(f"! transcription failed: {e}", flush=True)
            if self.config.sounds:
                sound("error")
            return
        if not text and not action:
            return
        took = time.monotonic() - started
        print(f"✓ {took:.1f}s  {text}" + (f"  [{action}]" if action else ""), flush=True)
        # Before Enter/Send, no trailing space: it would end up in the message.
        landed = paste(text + ("" if action else " "), restore=self.config.restore_clipboard) if text else True
        if action and landed:
            time.sleep(0.15)  # let the app take the paste before the Enter
            press_enter()
        if self.config.keep_history:
            self._remember(raw, text, len(audio) / SAMPLE_RATE, took)

    def _remember(self, raw: str, text: str, seconds: float, took: float) -> None:
        DATA_DIR.mkdir(parents=True, exist_ok=True)
        line = {"at": datetime.now().isoformat(timespec="seconds"), "seconds": round(seconds, 1),
                "took": round(took, 2), "raw": raw, "text": text}
        with open(DATA_DIR / "history.jsonl", "a", encoding="utf-8") as f:
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

        listener = keyboard.Listener(on_press=self.on_press, on_release=self.on_release)
        if IS_MAC and self.config.bubble:
            try:
                from . import bubble_mac
            except ImportError as e:
                print(f"! no bubble ({e}); the hotkey still works", flush=True)
            else:
                # AppKit has to own the main thread, so the hotkey listens on its own.
                listener.start()
                self._ready()
                bubble_mac.run(self)
                return
        self._ready()
        with listener:
            listener.join()

    def _ready(self) -> None:
        print(f"Ready. Hold [{self.config.hotkey}] to talk, or tap it for hands-free.", flush=True)
