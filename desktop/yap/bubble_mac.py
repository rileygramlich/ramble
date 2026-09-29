"""The Mac bubble: a light-blue pill floating at the bottom of the screen, like
Wispr Flow's bar. It sits above every app and every Space, full-screen ones
too, and never takes focus, so the paste still lands where you were typing.

Click the mic to talk hands-free. It opens into a pill with a live waveform:
✓ (or a click on the waveform) types what you said, ✕ throws it away. The
hotkey still works and the bubble follows along. Drag it anywhere.

`bubble = false` in the config turns it off.
"""
from __future__ import annotations

import math
import time

import objc
from AppKit import (
    NSApplication,
    NSApplicationActivationPolicyAccessory,
    NSBackingStoreBuffered,
    NSBezierPath,
    NSColor,
    NSEvent,
    NSPanel,
    NSScreen,
    NSStatusWindowLevel,
    NSTimer,
    NSView,
    NSWindowCollectionBehaviorCanJoinAllSpaces,
    NSWindowCollectionBehaviorFullScreenAuxiliary,
    NSWindowCollectionBehaviorStationary,
    NSWindowStyleMaskBorderless,
    NSWindowStyleMaskNonactivatingPanel,
)
from Foundation import NSMakeRect
from PyObjCTools import AppHelper

HEIGHT, PILL_WIDTH, BUTTON, BARS = 44, 184, 32, 18
# The Android bubble's colours: light blue, a deeper blue for buttons, navy ink.
BLUE, LIVE, INK = (0xA8, 0xDC, 0xFF), (0x6C, 0xC2, 0xFF), (0x0B, 0x2A, 0x45)


def run(dictation) -> None:
    """Show the bubble and run the Mac event loop. Blocks until Yap quits."""
    app = NSApplication.sharedApplication()
    app.setActivationPolicy_(NSApplicationActivationPolicyAccessory)  # no Dock icon
    dictation.ui = Bubble(dictation)
    AppHelper.runEventLoop(installInterrupt=True)


def color(rgb) -> NSColor:
    r, g, b = rgb
    return NSColor.colorWithSRGBRed_green_blue_alpha_(r / 255, g / 255, b / 255, 1.0)


class BubbleView(NSView):
    """Hands drawing and mouse events to the Bubble."""

    def initWithFrame_(self, frame):
        self = objc.super(BubbleView, self).initWithFrame_(frame)
        if self is None:
            return None
        self.bubble = None
        return self

    def acceptsFirstMouse_(self, event):
        return True  # one click works even though Yap is never the active app

    def drawRect_(self, rect):
        if self.bubble is not None:
            self.bubble.draw(self.bounds())

    def mouseDown_(self, event):
        self.bubble.mouse_down()

    def mouseDragged_(self, event):
        self.bubble.mouse_dragged()

    def mouseUp_(self, event):
        self.bubble.mouse_up(self.convertPoint_fromView_(event.locationInWindow(), None).x)


class Bubble:
    def __init__(self, dictation):
        self.dictation = dictation
        self.state = "idle"
        self.levels = [0.0] * BARS
        visible = NSScreen.mainScreen().visibleFrame()
        self.center_x = visible.origin.x + visible.size.width / 2
        self.bottom = visible.origin.y + 16  # just above the Dock

        self.panel = NSPanel.alloc().initWithContentRect_styleMask_backing_defer_(
            self._frame(HEIGHT), NSWindowStyleMaskBorderless | NSWindowStyleMaskNonactivatingPanel,
            NSBackingStoreBuffered, False)
        panel = self.panel
        panel.setLevel_(NSStatusWindowLevel)
        panel.setCollectionBehavior_(NSWindowCollectionBehaviorCanJoinAllSpaces
                                     | NSWindowCollectionBehaviorFullScreenAuxiliary
                                     | NSWindowCollectionBehaviorStationary)
        panel.setOpaque_(False)
        panel.setBackgroundColor_(NSColor.clearColor())
        panel.setHasShadow_(True)
        panel.setHidesOnDeactivate_(False)
        panel.setFloatingPanel_(True)
        panel.setBecomesKeyOnlyIfNeeded_(True)

        self.view = BubbleView.alloc().initWithFrame_(NSMakeRect(0, 0, HEIGHT, HEIGHT))
        self.view.bubble = self
        panel.setContentView_(self.view)
        panel.orderFrontRegardless()
        # Redraw about 30 times a second while it's moving; nothing to do at rest.
        self.timer = NSTimer.scheduledTimerWithTimeInterval_repeats_block_(1 / 30, True, lambda timer: self._tick())

    # -- state, from any thread --------------------------------------------------
    def listening(self):
        AppHelper.callAfter(self._set, "listening")

    def writing(self):
        AppHelper.callAfter(self._set, "writing")

    def idle(self):
        AppHelper.callAfter(self._set, "idle")

    def _set(self, state: str) -> None:
        if state == "listening" and self.state != "listening":
            self.levels = [0.0] * BARS
        self.state = state
        width = HEIGHT if state == "idle" else PILL_WIDTH
        self.panel.setFrame_display_(self._frame(width), True)
        self.view.setFrame_(NSMakeRect(0, 0, width, HEIGHT))
        self.view.setNeedsDisplay_(True)

    def _frame(self, width):
        return NSMakeRect(self.center_x - width / 2, self.bottom, width, HEIGHT)

    def _tick(self) -> None:
        if self.state == "listening":
            level = min(1.0, math.sqrt(self.dictation.recorder.level) * 2.4)
            self.levels = self.levels[1:] + [level]
        if self.state != "idle":
            self.view.setNeedsDisplay_(True)

    # -- mouse -------------------------------------------------------------------
    def mouse_down(self) -> None:
        self.pressed_at = NSEvent.mouseLocation()
        self.origin = self.panel.frame().origin
        self.dragged = False

    def mouse_dragged(self) -> None:
        now = NSEvent.mouseLocation()
        dx, dy = now.x - self.pressed_at.x, now.y - self.pressed_at.y
        if not self.dragged and math.hypot(dx, dy) < 4:
            return
        self.dragged = True
        self.panel.setFrameOrigin_((self.origin.x + dx, self.origin.y + dy))

    def mouse_up(self, x: float) -> None:
        if self.dragged:
            frame = self.panel.frame()
            self.center_x = frame.origin.x + frame.size.width / 2
            self.bottom = frame.origin.y
            return
        if self.state == "idle":
            self.dictation.toggle()
        elif self.state == "listening":
            if x < HEIGHT:
                self.dictation.cancel()  # ✕ on the left
            else:
                self.dictation.toggle()  # ✓ on the right, or the waveform

    # -- drawing -----------------------------------------------------------------
    def draw(self, bounds) -> None:
        w, h = bounds.size.width, bounds.size.height
        color(BLUE).setFill()
        NSBezierPath.bezierPathWithRoundedRect_xRadius_yRadius_(bounds, h / 2, h / 2).fill()
        if self.state == "idle":
            self._mic(w / 2, h / 2)
            return
        if self.state == "listening":
            for cx in (h / 2, w - h / 2):
                color(LIVE).setFill()
                NSBezierPath.bezierPathWithOvalInRect_(
                    NSMakeRect(cx - BUTTON / 2, h / 2 - BUTTON / 2, BUTTON, BUTTON)).fill()
            self._cross(h / 2, h / 2)
            self._check(w - h / 2, h / 2)
        self._bars(h + 4, w - h - 4, h)

    def _bars(self, x0: float, x1: float, h: float) -> None:
        step = (x1 - x0) / BARS
        bar = step * 0.5
        t = time.monotonic() / 0.18
        color(INK).setFill()
        for i in range(BARS):
            # While writing, a gentle ripple; while listening, your voice.
            level = 0.25 + 0.2 * math.sin(t - i * 0.55) if self.state == "writing" else self.levels[i]
            height = max(bar, level * (h * 0.6))
            x = x0 + step * i + (step - bar) / 2
            NSBezierPath.bezierPathWithRoundedRect_xRadius_yRadius_(
                NSMakeRect(x, h / 2 - height / 2, bar, height), bar / 2, bar / 2).fill()

    def _stroke(self, path) -> None:
        color(INK).setStroke()
        path.setLineWidth_(2.2)
        path.setLineCapStyle_(1)  # round
        path.setLineJoinStyle_(1)
        path.stroke()

    def _mic(self, cx: float, cy: float) -> None:
        color(INK).setFill()
        NSBezierPath.bezierPathWithRoundedRect_xRadius_yRadius_(NSMakeRect(cx - 4, cy - 2, 8, 13), 4, 4).fill()
        cup = NSBezierPath.bezierPath()
        cup.appendBezierPathWithArcWithCenter_radius_startAngle_endAngle_clockwise_((cx, cy + 3), 7.5, 180, 360, False)
        cup.moveToPoint_((cx, cy - 4.5))
        cup.lineToPoint_((cx, cy - 9))
        self._stroke(cup)

    def _cross(self, cx: float, cy: float) -> None:
        path = NSBezierPath.bezierPath()
        path.moveToPoint_((cx - 5.5, cy - 5.5))
        path.lineToPoint_((cx + 5.5, cy + 5.5))
        path.moveToPoint_((cx - 5.5, cy + 5.5))
        path.lineToPoint_((cx + 5.5, cy - 5.5))
        self._stroke(path)

    def _check(self, cx: float, cy: float) -> None:
        path = NSBezierPath.bezierPath()
        path.moveToPoint_((cx - 6, cy))
        path.lineToPoint_((cx - 2, cy - 4.5))
        path.lineToPoint_((cx + 6.5, cy + 5))
        self._stroke(path)
