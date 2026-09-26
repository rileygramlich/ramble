"""Put text where the cursor is: via the clipboard and a paste keystroke.

Pasting is the only reliable way into every app (typing character by character
is slow and breaks on accents and emoji). The previous clipboard is put back
afterwards so dictating doesn't cost you what you'd copied.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import time

from .config import IS_MAC


def paste(text: str, restore: bool = True) -> None:
    import pyperclip

    previous = None
    if restore:
        try:
            previous = pyperclip.paste()
        except pyperclip.PyperclipException:
            previous = None
    pyperclip.copy(text)
    time.sleep(0.05)
    _press_paste()
    if previous is not None:
        time.sleep(0.4)  # let the target app read the clipboard first
        pyperclip.copy(previous)


def _press_paste() -> None:
    if not IS_MAC and os.environ.get("WAYLAND_DISPLAY") and shutil.which("wtype"):
        subprocess.run(["wtype", "-M", "ctrl", "v", "-m", "ctrl"], check=False)
        return
    from pynput.keyboard import Controller, Key

    kb = Controller()
    modifier = Key.cmd if IS_MAC else Key.ctrl
    with kb.pressed(modifier):
        kb.tap("v")


SOUNDS = {
    "start": ["/System/Library/Sounds/Tink.aiff", "/usr/share/sounds/freedesktop/stereo/message.oga"],
    "stop": ["/System/Library/Sounds/Pop.aiff", "/usr/share/sounds/freedesktop/stereo/bell.oga"],
    "error": ["/System/Library/Sounds/Basso.aiff", "/usr/share/sounds/freedesktop/stereo/dialog-warning.oga"],
}


def sound(name: str) -> None:
    player = "afplay" if IS_MAC else "paplay"
    for path in SOUNDS[name]:
        if os.path.exists(path) and shutil.which(player):
            subprocess.Popen([player, path], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            return
