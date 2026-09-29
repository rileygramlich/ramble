"""Hear the hotkey on Linux by reading the keyboards directly.

Wayland doesn't let apps see global key presses, so pynput only notices the
hotkey while an X11 window has focus. Reading /dev/input works everywhere, but
needs the `input` group: `sudo usermod -aG input $USER`, then log in again.
"""
from __future__ import annotations

import selectors
from typing import Callable

# pynput key names (what config.hotkey uses) → evdev key names.
NAMES = {
    "ctrl_r": "KEY_RIGHTCTRL", "ctrl_l": "KEY_LEFTCTRL",
    "alt_r": "KEY_RIGHTALT", "alt_l": "KEY_LEFTALT", "alt_gr": "KEY_RIGHTALT",
    "shift_r": "KEY_RIGHTSHIFT", "shift_l": "KEY_LEFTSHIFT",
    "cmd_r": "KEY_RIGHTMETA", "cmd_l": "KEY_LEFTMETA", "cmd": "KEY_LEFTMETA",
    "caps_lock": "KEY_CAPSLOCK", "scroll_lock": "KEY_SCROLLLOCK", "pause": "KEY_PAUSE",
    "menu": "KEY_COMPOSE", "insert": "KEY_INSERT",
}


def keyboards(hotkey: str):
    """The input devices that have the hotkey. Empty if we can't read any."""
    import evdev

    code = evdev.ecodes.ecodes.get(NAMES.get(hotkey, "KEY_" + hotkey.upper()))
    if code is None:
        raise SystemExit(f"Don't know the key '{hotkey}' (try ctrl_r, alt_r, f13 …)")
    found = []
    for path in evdev.list_devices():
        try:
            device = evdev.InputDevice(path)
        except OSError:
            continue  # not readable: not in the input group yet
        if code in device.capabilities().get(evdev.ecodes.EV_KEY, []):
            found.append(device)
    return code, found


def listen(devices, code: int, on_press: Callable[[], None], on_release: Callable[[], None]) -> None:
    sel = selectors.DefaultSelector()
    for device in devices:
        sel.register(device, selectors.EVENT_READ)
    while True:
        for key, _ in sel.select():
            device = key.fileobj
            try:
                events = list(device.read())
            except OSError:  # unplugged
                sel.unregister(device)
                continue
            for event in events:
                if event.type == 1 and event.code == code:  # EV_KEY
                    if event.value == 1:
                        on_press()
                    elif event.value == 0:
                        on_release()  # 2 is key-repeat: ignored
