"""Settings, read from ~/.config/yap/config.toml with sensible defaults.

Every key is optional; anything left out falls back to DEFAULTS below.
"""
from __future__ import annotations

import os
import platform
import tomllib
from dataclasses import dataclass, field, fields
from pathlib import Path

IS_MAC = platform.system() == "Darwin"
IS_LINUX = platform.system() == "Linux"
IS_APPLE_SILICON = IS_MAC and platform.machine() == "arm64"

CONFIG_DIR = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "yap"
CONFIG_FILE = CONFIG_DIR / "config.toml"
DATA_DIR = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local/share")) / "yap"


@dataclass
class Config:
    # Hold this key to talk; double-tap it to keep listening hands-free until the next tap.
    # Any pynput key name: alt_r (right Option on a Mac), ctrl_r, cmd_r, f13…
    hotkey: str = "alt_r" if IS_MAC else "ctrl_r"

    # "mlx" is Apple Silicon only and the fastest there; "faster-whisper" runs anywhere.
    engine: str = "mlx" if IS_APPLE_SILICON else "faster-whisper"
    model: str = ""  # blank = the engine's default (see transcribe.py)
    language: str = "en"

    # Tidy-up pass. "ollama" uses a small local model and falls back to "rules"
    # whenever Ollama is slow or unreachable; "rules" alone is instant; "off" pastes raw text.
    cleanup: str = "ollama"
    ollama_url: str = "http://127.0.0.1:11434"
    ollama_model: str = "qwen2.5:1.5b"
    ollama_timeout: float = 6.0

    # Names and jargon Whisper should expect and the tidy-up must not "correct".
    vocabulary: list[str] = field(default_factory=list)

    # `yap serve`: the model used for other devices' audio, and where to listen.
    # Bigger than the default because this machine has the time the phone doesn't.
    serve_model: str = "large-v3-turbo"
    serve_host: str = "127.0.0.1"  # set to this machine's Tailscale address
    serve_port: int = 8723

    # Mac: a Wispr-style bubble at the bottom of the screen. Click to talk.
    bubble: bool = True

    sounds: bool = True
    keep_history: bool = True  # every dictation is appended to DATA_DIR/history.jsonl
    restore_clipboard: bool = True


def load(path: Path = CONFIG_FILE) -> Config:
    config = Config()
    if path.exists():
        data = tomllib.loads(path.read_text())
        known = {f.name for f in fields(Config)}
        for key, value in data.items():
            if key not in known:
                raise SystemExit(f"{path}: unknown setting '{key}'")
            setattr(config, key, value)
    return config


EXAMPLE = """\
# Yap settings. Delete any line to use the default.

# hotkey = "alt_r"          # hold to talk, double-tap for hands-free
# engine = "mlx"            # mlx (Apple Silicon) or faster-whisper
# model = ""                # blank = engine default
# cleanup = "ollama"        # ollama, rules, or off
# ollama_model = "qwen2.5:1.5b"
# bubble = true             # Mac: the floating mic at the bottom of the screen

# Words Whisper tends to get wrong: names, products, jargon.
vocabulary = ["Tommy Games", "Quiddler", "Tailscale", "Gramlich"]
"""
