"""Settings, read from config.toml with sensible defaults.

Every key is optional; anything left out falls back to the defaults below.
Where things live:
    Linux    ~/.config/ramble/config.toml   (data: ~/.local/share/ramble)
    macOS    ~/.config/ramble/config.toml   (data: ~/.local/share/ramble)
    Windows  %APPDATA%\\Ramble\\config.toml   (data: %LOCALAPPDATA%\\Ramble)
Installs from when this was called Yap keep using their ~/.config/yap folders.
"""
from __future__ import annotations

import os
import platform
import tomllib
from dataclasses import dataclass, field, fields
from pathlib import Path

IS_MAC = platform.system() == "Darwin"
IS_LINUX = platform.system() == "Linux"
IS_WINDOWS = platform.system() == "Windows"
IS_APPLE_SILICON = IS_MAC and platform.machine() == "arm64"


def _dirs() -> tuple[Path, Path]:
    if IS_WINDOWS:
        roaming = Path(os.environ.get("APPDATA", Path.home() / "AppData/Roaming"))
        local = Path(os.environ.get("LOCALAPPDATA", Path.home() / "AppData/Local"))
        return roaming / "Ramble", local / "Ramble"
    config = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    data = Path(os.environ.get("XDG_DATA_HOME", Path.home() / ".local/share"))
    # Keep using the old folders if this machine set Ramble up as Yap.
    if (config / "yap").exists() and not (config / "ramble").exists():
        return config / "yap", data / "yap"
    return config / "ramble", data / "ramble"


CONFIG_DIR, DATA_DIR = _dirs()
CONFIG_FILE = CONFIG_DIR / "config.toml"


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
    # Say this, type that: a word Whisper keeps mishearing, a shortcut, or your own emoji.
    replacements: dict[str, str] = field(default_factory=dict)
    # "haha period" → "haha.", "is that right question mark" → "is that right?"
    spoken_punctuation: bool = True

    # `ramble serve`: the model used for other devices' audio, and where to listen.
    # Bigger than the default because this machine has the time the phone doesn't.
    serve_model: str = "large-v3-turbo"
    serve_host: str = "127.0.0.1"  # set to this machine's Tailscale address
    serve_port: int = 8723

    # Mac: a Wispr-style bubble at the bottom of the screen. Click to talk. (Not on Windows or Linux yet.)
    bubble: bool = True

    sounds: bool = True
    keep_history: bool = True  # every dictation is appended to DATA_DIR/history.jsonl
    restore_clipboard: bool = True


def load(path: Path = CONFIG_FILE) -> Config:
    config = Config()
    if path.exists():
        data = tomllib.loads(path.read_text(encoding="utf-8"))
        known = {f.name for f in fields(Config)}
        for key, value in data.items():
            if key not in known:
                raise SystemExit(f"{path}: unknown setting '{key}'")
            setattr(config, key, value)
    return config


EXAMPLE = """\
# Ramble settings. Delete any line to use the default.

# hotkey = "alt_r"          # hold to talk, tap for hands-free (default: alt_r on Mac, ctrl_r elsewhere)
# engine = "faster-whisper" # mlx (Apple Silicon only) or faster-whisper
# model = ""                # blank = engine default
# cleanup = "ollama"        # ollama, rules, or off
# ollama_model = "qwen2.5:1.5b"
# bubble = true             # Mac: the floating mic at the bottom of the screen

# Words Whisper tends to get wrong: names, products, jargon.
vocabulary = ["Tailscale", "Kubernetes", "Ada Lovelace"]

# spoken_punctuation = true # "haha period" → "haha.", "comma", "question mark"…

# Say this, type that. Built in: "laughing emoji" → 😂, "heart emoji" → ❤️ and more.
# [replacements]
# "rambl" = "Ramble"
# "orthodox cross emoji" = "☦️"
"""
