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
