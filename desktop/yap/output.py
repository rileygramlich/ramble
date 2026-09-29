"""Put text where the cursor is: via the clipboard and a paste keystroke.

Pasting is the only reliable way into every app (typing character by character
is slow and breaks on accents and emoji). The previous clipboard is put back
afterwards so dictating doesn't cost you what you'd copied.

If the cursor wasn't in a text box (you clicked away while talking), the
dictation stays on the clipboard instead, like Wispr Flow, so it isn't lost.
Only macOS can tell us that; elsewhere Yap assumes there was a text box.
"""
from __future__ import annotations

import os
import shutil
import subprocess
import time

from .config import IS_MAC


TEXT_ROLES = {"AXTextField", "AXTextArea", "AXComboBox", "AXSearchField"}


def paste(text: str, restore: bool = True) -> bool:
    """Paste text at the cursor. False if it was left on the clipboard instead."""
    import pyperclip

    in_field = focused_text_field()
    previous = None
    if restore and in_field is not False:
        try:
            previous = pyperclip.paste()
        except pyperclip.PyperclipException:
            previous = None
    pyperclip.copy(text)
    time.sleep(0.05)
    # Paste even when unsure: some apps (Electron, web pages) don't say they're a
    # text box. Pressing paste where there's no text box does nothing.
    _press_paste()
    if in_field is False:
        notify("Copied: no text box had the cursor")
        return False
    if previous is not None:
        time.sleep(0.4)  # let the target app read the clipboard first
        pyperclip.copy(previous)
    return True


def focused_text_field() -> bool | None:
    """Is the cursor in something you can type into? None if we can't tell.

    On macOS this asks the Accessibility API (the permission Yap already has
    for pressing ⌘V). Anything short of a clear yes counts as no there, because
    a wrong no only costs the old clipboard, while a wrong yes loses the dictation.
    """
    if not IS_MAC:
        return None
    try:
        import ApplicationServices as ax

        err, focused = ax.AXUIElementCopyAttributeValue(
            ax.AXUIElementCreateSystemWide(), ax.kAXFocusedUIElementAttribute, None)
        if err != ax.kAXErrorSuccess or focused is None:
            return False
        err, role = ax.AXUIElementCopyAttributeValue(focused, ax.kAXRoleAttribute, None)
        if err == ax.kAXErrorSuccess and role in TEXT_ROLES:
            return True
        err, names = ax.AXUIElementCopyAttributeNames(focused, None)
        # Editable web content and custom editors have a text selection even when their role is generic.
        return err == ax.kAXErrorSuccess and "AXSelectedTextRange" in (names or ())
    except Exception:
        return False


def notify(message: str) -> None:
    print(f"  {message}", flush=True)
    if IS_MAC and shutil.which("osascript"):
        subprocess.Popen(["osascript", "-e", f'display notification "{message}" with title "Yap"'],
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


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
