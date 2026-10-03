"""ramble: free, private, on-device dictation.

  ramble              run it (hold the hotkey to talk)
  ramble file AUDIO   transcribe and tidy an audio file, and print the result
  ramble check        test the microphone, the model, and Ollama
  ramble serve [HOST] transcribe and tidy audio for your phone (see serve.py)
  ramble init         write an example config file
  ramble version      print the version
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


def _quiet_launch_log() -> None:
    """Started without a console (Windows pythonw, a login item): write output to a log file."""
    if sys.stdout is None or sys.stderr is None:
        cfg.DATA_DIR.mkdir(parents=True, exist_ok=True)
        log = open(cfg.DATA_DIR / "ramble.log", "a", buffering=1, encoding="utf-8")
        sys.stdout = sys.stdout or log
        sys.stderr = sys.stderr or log


def main(argv: list[str] = sys.argv[1:]) -> None:
    _quiet_launch_log()
    command = argv[0] if argv else "run"
    if command in ("version", "--version"):
        from importlib.metadata import PackageNotFoundError, version
        try:
            print(f"ramble {version('ramble')}")
        except PackageNotFoundError:
            print("ramble (not installed as a package)")
        return
    if command in ("help", "--help", "-h"):
        print(__doc__)
        return
    config = cfg.load()
    if command == "run":
        from .app import Dictation
        Dictation(config).run()
    elif command == "file" and len(argv) == 2:
        from .app import Pipeline
        started = time.monotonic()
        raw, text, action = Pipeline(config).run(read_audio(argv[1]))
        print(f"raw:   {raw}\ntext:  {text}\npress: {action or '-'}\ntook:  {time.monotonic() - started:.1f}s")
    elif command == "serve" and len(argv) <= 2:
        from .serve import serve
        serve(config, argv[1] if len(argv) == 2 else config.serve_host, config.serve_port)
    elif command == "check":
        check(config)
    elif command == "init":
        if cfg.CONFIG_FILE.exists():
            sys.exit(f"{cfg.CONFIG_FILE} already exists.")
        cfg.CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        cfg.CONFIG_FILE.write_text(cfg.EXAMPLE, encoding="utf-8")
        print(f"Wrote {cfg.CONFIG_FILE}")
    else:
        sys.exit(__doc__)


def read_audio(path: str) -> np.ndarray:
    if not shutil.which("ffmpeg"):
        sys.exit("Reading audio files needs ffmpeg (Mac: brew install ffmpeg, Windows: winget install ffmpeg, Linux: your package manager).")
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
