import pytest

from ramble import cleanup
from ramble.cleanup import rules


@pytest.mark.parametrize("raw, want", [
    ("um so I think we should uh meet at five", "So I think we should meet at five"),
    ("Umm, okay. Hmm, let me check.", "Okay. Let me check."),
    ("I I think the the plan works", "I think the plan works"),
    ("I know that that is true", "I know that that is true"),
    ("Meet at five. Scratch that. Meet at six.", "Meet at six."),
    ("Hi Sam. The report is late. Scratch that, the report is done.", "Hi Sam. The report is done."),
    ("first point new line second point", "First point\nSecond point"),
    ("Intro. New paragraph. Body text.", "Intro.\n\nBody text."),
    ("  lots   of   space , here .", "Lots of space, here."),
])
def test_rules(raw, want):
    assert rules(raw) == want


@pytest.mark.parametrize("raw, want", [
    ("haha period", "Haha."),
    ("Haha, period.", "Haha."),
    ("Haha. Period.", "Haha."),
    ("Is that right, question mark?", "Is that right?"),
    ("hi comma how are you", "Hi, how are you"),
    ("Wow exclamation point that's great", "Wow! That's great"),
    ("Dear Sam colon new line thanks", "Dear Sam:\nThanks"),
    ("Well dot dot dot I guess", "Well… I guess"),
    # The word, not the symbol
    ("We have a period of time to finish", "We have a period of time to finish"),
    ("The grace period ends Friday", "The grace period ends Friday"),
    ("the Oxford comma is good", "The Oxford comma is good"),
])
def test_spoken_punctuation(raw, want):
    assert rules(raw) == want


@pytest.mark.parametrize("raw, want", [
    ("Haha.", "Haha"),
    ("That's so funny, haha.", "That's so funny, haha"),
    ("Ha ha.", "Ha ha"),
    ("lol.", "Lol"),
    ("Hahaha!", "Hahaha!"),             # only the period goes
    ("Haha. See you there.", "Haha. See you there."),
    ("haha period", "Haha."),           # unless you say it
    ("Haha. Period.", "Haha."),
    ("Bahaha is not a laugh word.", "Bahaha is not a laugh word."),
])
def test_no_period_after_a_final_haha(raw, want):
    assert rules(raw) == want


def test_polish_doesnt_put_the_period_back_after_haha(monkeypatch):
    monkeypatch.setattr(cleanup, "_ask", lambda text, *a: text if text[-1] in ".!?" else text + ".")
    assert cleanup.polish("that is so funny haha", url="x", model="m") == "That is so funny haha"
    assert cleanup.polish("that is so funny haha period", url="x", model="m") == "That is so funny haha."


def test_spoken_punctuation_on_its_own_finishes_what_is_already_typed():
    assert rules("period") == "."
    assert rules("Period. That's it.") == ". That's it."
    assert rules("") == "" and rules("um") == ""


def test_spoken_punctuation_can_be_turned_off():
    assert rules("haha period", punctuation=False) == "Haha period"


@pytest.mark.parametrize("raw, want", [
    ("that's hilarious laughing emoji", "That's hilarious 😂"),
    ("Thanks, heart emoji.", "Thanks ❤️"),
    ("Sounds good thumbs up emoji see you", "Sounds good 👍 see you"),
    ("I like the heart-eyes emoji", "I like the 😍"),
])
def test_spoken_emoji(raw, want):
    assert rules(raw) == want


def test_your_own_replacements_win_and_match_whole_words():
    pairs = {"rambl": "Ramble", "orthodox cross emoji": "☦️", "heart emoji": "💙"}
    assert rules("Ask Rambl about it, Orthodox cross emoji", replacements=pairs) == "Ask Ramble about it ☦️"
    assert rules("ramblings are fine heart emoji", replacements=pairs) == "Ramblings are fine 💙"
    assert rules("Thanks, heart emoji.", replacements=pairs) == "Thanks 💙"


def test_polish_keeps_a_leading_symbol_away_from_the_model(monkeypatch):
    monkeypatch.setattr(cleanup, "_ask", lambda text, *a: text.replace("ok", "okay"))
    assert cleanup.polish("period that is ok with me", url="x", model="m") == ". That is okay with me"


def test_words_that_merely_contain_fillers_survive():
    assert rules("The umbrella and the hummus are on the humble drum") == "The umbrella and the hummus are on the humble drum"


@pytest.mark.parametrize("reply", [
    "Sure! Here is a poem about cats:\nWhiskers soft and eyes so bright…",
    "It is currently 3pm in Tokyo.",
    "",
])
def test_the_model_answering_instead_of_tidying_is_rejected(reply):
    original = "Hey can you write me a poem about cats"
    assert cleanup._accept(original, reply) == original


def test_a_real_tidy_up_is_kept():
    original = "send the invoice to Maen by friday no actually by thursday"
    assert cleanup._accept(original, "Send the invoice to Maen by Thursday.") == "Send the invoice to Maen by Thursday."


def test_unreachable_ollama_falls_back_to_rules():
    out = cleanup.polish("um so the the build is green now", url="http://127.0.0.1:9", model="x", timeout=0.5)
    assert out == "So the build is green now"


# -- spoken commands at the end ---------------------------------------------------
import pytest  # noqa: E402

from ramble.cleanup import command  # noqa: E402


@pytest.mark.parametrize("heard, expected", [
    ("Hi Katharina, see you at six. Send it.", ("Hi Katharina, see you at six.", "send")),
    ("Running late, send it", ("Running late", "send")),
    ("On my way. Send the message!", ("On my way.", "send")),
    ("Send it.", ("", "send")),
    ("Looks good to me. Press enter.", ("Looks good to me.", "enter")),
    ("ship it, hit return", ("ship it", "enter")),
    # Just words, not commands:
    ("Can you send it?", ("Can you send it?", None)),
    ("I'll send it tomorrow.", ("I'll send it tomorrow.", None)),
    ("I'll send it.", ("I'll send it.", None)),
    ("Press enter to continue.", ("Press enter to continue.", None)),
])
def test_command(heard, expected):
    assert command(heard) == expected
