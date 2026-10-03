"""The Mac bubble and the dictation states it shows.

AppKit only exists on a Mac, so the bubble runs here against a stand-in that
accepts any call. That checks Yap's own logic (states, clicks, drags, every
drawing path), not how macOS draws it.
"""
import sys
import types

import numpy as np
import pytest

from ramble import app as app_module
from ramble.config import Config


# -- dictation states, as any UI sees them -------------------------------------------
class FakeRecorder:
    def __init__(self):
        self.recording = False
        self.level = 0.0

    def start(self):
        self.recording = True

    def stop(self):
        self.recording = False
        return np.zeros(16000, dtype=np.float32)


class FakeUI:
    def __init__(self):
        self.states = []

    def listening(self):
        self.states.append("listening")

    def writing(self):
        self.states.append("writing")

    def idle(self):
        self.states.append("idle")


@pytest.fixture
def dictation(monkeypatch):
    monkeypatch.setattr(app_module, "Transcriber", lambda *a, **k: types.SimpleNamespace(model="fake"))
    d = app_module.Dictation(Config(sounds=False, cleanup="off"))
    d.recorder = FakeRecorder()
    d.pipeline.warm = lambda: None
    d.ui = FakeUI()
    return d


def test_click_starts_hands_free_and_click_again_finishes(dictation):
    dictation.toggle()
    assert dictation.recorder.recording and dictation.hands_free
    dictation.toggle()
    assert not dictation.recorder.recording and not dictation.hands_free
    assert dictation.ui.states == ["listening", "writing"]
    assert dictation.jobs.qsize() == 1


def test_cross_throws_it_away(dictation):
    dictation.toggle()
    dictation.cancel()
    assert dictation.ui.states == ["listening", "idle"]
    assert dictation.jobs.empty()


def test_back_to_idle_once_the_work_is_done(dictation):
    dictation.toggle()
    dictation.toggle()
    audio = dictation.jobs.get_nowait()
    handed = [audio]

    def get():  # hand the worker this one job, then stop it
        if handed:
            return handed.pop()
        raise StopIteration

    dictation.jobs.get = get
    dictation._handle = lambda audio: None
    with pytest.raises(StopIteration):
        dictation.work()
    assert dictation.ui.states == ["listening", "writing", "idle"]


# -- the bubble, against a stand-in AppKit -------------------------------------------
class Anything:
    """Stands in for any AppKit class, object or constant."""

    def __init__(self, *args, **kwargs):
        pass

    def __getattr__(self, name):
        return Anything()

    def __call__(self, *args, **kwargs):
        return Anything()

    def __or__(self, other):
        return self

    __ror__ = __or__


class Rect:
    def __init__(self, x, y, w, h):
        self.origin = types.SimpleNamespace(x=x, y=y)
        self.size = types.SimpleNamespace(width=w, height=h)


class FakePanel(Anything):
    def __init__(self, frame):
        self._frame = frame

    def setFrame_display_(self, frame, display):
        self._frame = frame

    def setFrameOrigin_(self, point):
        self._frame = Rect(point[0], point[1], self._frame.size.width, self._frame.size.height)

    def frame(self):
        return self._frame


class Mouse:
    at = types.SimpleNamespace(x=0, y=0)


@pytest.fixture
def bubble_module(monkeypatch):
    class NSView:
        def initWithFrame_(self, frame):
            self._bounds = frame
            return self

        @classmethod
        def alloc(cls):
            return cls()

        def bounds(self):
            return self._bounds

        def setFrame_(self, frame):
            self._bounds = frame

        def setNeedsDisplay_(self, flag):
            pass

    appkit = types.ModuleType("AppKit")
    for name in ["NSApplication", "NSApplicationActivationPolicyAccessory", "NSBackingStoreBuffered",
                 "NSBezierPath", "NSColor", "NSScreen", "NSStatusWindowLevel", "NSTimer",
                 "NSWindowCollectionBehaviorCanJoinAllSpaces", "NSWindowCollectionBehaviorFullScreenAuxiliary",
                 "NSWindowCollectionBehaviorStationary", "NSWindowStyleMaskBorderless",
                 "NSWindowStyleMaskNonactivatingPanel"]:
        setattr(appkit, name, Anything())
    appkit.NSView = NSView
    appkit.NSPanel = types.SimpleNamespace(alloc=lambda: types.SimpleNamespace(
        initWithContentRect_styleMask_backing_defer_=lambda frame, *rest: FakePanel(frame)))
    appkit.NSEvent = types.SimpleNamespace(mouseLocation=lambda: Mouse.at)
    screen = types.SimpleNamespace(visibleFrame=lambda: Rect(0, 0, 1440, 900))
    appkit.NSScreen = types.SimpleNamespace(mainScreen=lambda: screen)
    foundation = types.ModuleType("Foundation")
    foundation.NSMakeRect = Rect
    objc = types.ModuleType("objc")
    objc.super = lambda cls, obj: super(cls, obj)
    helper = types.ModuleType("PyObjCTools.AppHelper")
    helper.callAfter = lambda fn, *args: fn(*args)  # run at once instead of on the main thread
    tools = types.ModuleType("PyObjCTools")
    tools.AppHelper = helper
    for name, module in {"AppKit": appkit, "Foundation": foundation, "objc": objc,
                         "PyObjCTools": tools, "PyObjCTools.AppHelper": helper}.items():
        monkeypatch.setitem(sys.modules, name, module)
    monkeypatch.delitem(sys.modules, "ramble.bubble_mac", raising=False)
    import ramble.bubble_mac as bubble_mac
    return bubble_mac


def test_bubble_opens_into_a_pill_and_back(bubble_module, dictation):
    bubble = dictation.ui = bubble_module.Bubble(dictation)  # as bubble_mac.run() does
    assert bubble.panel.frame().size.width == bubble_module.HEIGHT
    assert bubble.panel.frame().origin.x == 720 - bubble_module.HEIGHT / 2  # bottom centre

    bubble.mouse_down()
    bubble.mouse_up(20)  # a click on the mic
    assert dictation.recorder.recording
    assert bubble.state == "listening"
    assert bubble.panel.frame().size.width == bubble_module.PILL_WIDTH
    assert bubble.panel.frame().origin.x == 720 - bubble_module.PILL_WIDTH / 2  # grows from the centre

    dictation.recorder.level = 0.05
    bubble._tick()
    assert bubble.levels[-1] > 0
    bubble.draw(bubble.view.bounds())

    bubble.mouse_down()
    bubble.mouse_up(bubble_module.PILL_WIDTH - 10)  # ✓
    assert not dictation.recorder.recording
    assert bubble.state == "writing"
    bubble.draw(bubble.view.bounds())

    bubble.idle()
    assert bubble.state == "idle"
    bubble.draw(bubble.view.bounds())


def test_cross_cancels(bubble_module, dictation):
    bubble = dictation.ui = bubble_module.Bubble(dictation)  # as bubble_mac.run() does
    bubble.mouse_down()
    bubble.mouse_up(20)
    bubble.mouse_down()
    bubble.mouse_up(10)  # ✕ on the left
    assert bubble.state == "idle"
    assert dictation.jobs.empty()


def test_drag_moves_it_without_clicking(bubble_module, dictation):
    bubble = dictation.ui = bubble_module.Bubble(dictation)  # as bubble_mac.run() does
    Mouse.at = types.SimpleNamespace(x=0, y=0)
    bubble.mouse_down()
    Mouse.at = types.SimpleNamespace(x=100, y=300)
    bubble.mouse_dragged()
    bubble.mouse_up(20)
    assert not dictation.recorder.recording
    assert bubble.bottom == 16 + 300
    assert bubble.center_x == 720 + 100
