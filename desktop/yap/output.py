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
    if not IS_MAC and os.environ.get("WAYLAND_DISPLAY"):
        # ydotool works on every compositor (it needs ydotoold running); wtype
        # works on wlroots ones like Sway and Hyprland but not GNOME or KDE.
        # 29 and 47 are the evdev codes for left Ctrl and V.
        if shutil.which("ydotool") and subprocess.run(
                ["ydotool", "key", "29:1", "47:1", "47:0", "29:0"], capture_output=True).returncode == 0:
            return
        if shutil.which("wtype"):
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
