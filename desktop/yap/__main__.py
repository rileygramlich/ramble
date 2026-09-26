"""yap: private, free, on-device dictation.

  yap              run it (hold the hotkey to talk)
  yap file AUDIO   transcribe and tidy an audio file, and print the result
  yap check        test the microphone, the model, and Ollama
  yap init         write an example config to ~/.config/yap/config.toml
"""
from __future__ import annotations

import shutil
import subprocess
import sys
import time
import urllib.request

import numpy as np

from . import config as cfg
from .transcribe import SAMPLE_RATE


def main(argv: list[str] = sys.argv[1:]) -> None:
    command = argv[0] if argv else "run"
    config = cfg.load()
    if command == "run":
        from .app import Dictation
        Dictation(config).run()
    elif command == "file" and len(argv) == 2:
        from .app import Pipeline
        started = time.monotonic()
        raw, text = Pipeline(config).run(read_audio(argv[1]))
        print(f"raw:   {raw}\ntext:  {text}\ntook:  {time.monotonic() - started:.1f}s")
    elif command == "check":
        check(config)
    elif command == "init":
        if cfg.CONFIG_FILE.exists():
            sys.exit(f"{cfg.CONFIG_FILE} already exists.")
        cfg.CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        cfg.CONFIG_FILE.write_text(cfg.EXAMPLE)
        print(f"Wrote {cfg.CONFIG_FILE}")
    else:
        sys.exit(__doc__)


def read_audio(path: str) -> np.ndarray:
    if not shutil.which("ffmpeg"):
        sys.exit("Reading audio files needs ffmpeg (brew install ffmpeg).")
    raw = subprocess.run(
        ["ffmpeg", "-nostdin", "-loglevel", "error", "-i", path, "-f", "f32le", "-ac", "1", "-ar", str(SAMPLE_RATE), "-"],
        capture_output=True, check=True,
    ).stdout
    return np.frombuffer(raw, dtype=np.float32)


def check(config: cfg.Config) -> None:
    print(f"config    {cfg.CONFIG_FILE if cfg.CONFIG_FILE.exists() else '(defaults)'}")
    print(f"hotkey    {config.hotkey}")
    print(f"engine    {config.engine}")

    from .audio import Recorder
    rec = Recorder()
    try:
        rec.start(); time.sleep(1.0); audio = rec.stop()
        level = float(np.sqrt(np.mean(audio**2))) if len(audio) else 0.0
        print(f"mic       ok, {len(audio) / SAMPLE_RATE:.1f}s captured, level {level:.4f}")
    except Exception as e:
        print(f"mic       FAILED: {e}")

    from .transcribe import Transcriber
    t = Transcriber(config.engine, config.model, config.language, config.vocabulary)
    started = time.monotonic()
    try:
        t.load()
        print(f"whisper   ok, {t.model} loaded in {time.monotonic() - started:.1f}s")
    except Exception as e:
        print(f"whisper   FAILED: {e}")

    if config.cleanup == "ollama":
        try:
            urllib.request.urlopen(config.ollama_url.rstrip("/") + "/api/tags", timeout=3).read()
            print(f"ollama    ok at {config.ollama_url} ({config.ollama_model})")
        except OSError as e:
            print(f"ollama    unreachable ({e}); tidy-up will use the built-in rules")
    else:
        print(f"cleanup   {config.cleanup}")


if __name__ == "__main__":
    main()
