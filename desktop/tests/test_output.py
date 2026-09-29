"""Pasting vs. leaving the dictation on the clipboard when no text box has the cursor."""
import sys
import types

import pytest

from yap import output


@pytest.fixture
def clipboard(monkeypatch):
    board = {"text": "what you copied earlier", "pastes": 0}
    fake = types.SimpleNamespace(
        copy=lambda t: board.__setitem__("text", t),
        paste=lambda: board["text"],
        PyperclipException=Exception,
    )
    monkeypatch.setitem(sys.modules, "pyperclip", fake)
    monkeypatch.setattr(output, "_press_paste", lambda: board.__setitem__("pastes", board["pastes"] + 1))
    monkeypatch.setattr(output, "notify", lambda message: None)
    monkeypatch.setattr(output.time, "sleep", lambda s: None)
    return board


def test_text_box_gets_it_and_clipboard_comes_back(clipboard, monkeypatch):
    monkeypatch.setattr(output, "focused_text_field", lambda: True)
    assert output.paste("hello there ") is True
    assert clipboard["pastes"] == 1
    assert clipboard["text"] == "what you copied earlier"


def test_no_text_box_leaves_dictation_on_clipboard(clipboard, monkeypatch):
    monkeypatch.setattr(output, "focused_text_field", lambda: False)
    assert output.paste("hello there ") is False
    assert clipboard["text"] == "hello there "


def test_unknown_behaves_like_before(clipboard, monkeypatch):
    monkeypatch.setattr(output, "focused_text_field", lambda: None)
    assert output.paste("hello there ") is True
    assert clipboard["text"] == "what you copied earlier"
