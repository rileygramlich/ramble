# Contributing to Ramble

Thanks for helping. Ramble aims to be dictation for every device, so reports from systems and phones the maintainer doesn't have are some of the most useful contributions.

## Reporting a problem

Open an issue with:

- Your system (Android phone model and version, or macOS / Linux distro / Windows version)
- What you did, what you expected, and what happened
- **Android:** the report from Ramble → ⚙ → Diagnostics → **Copy report** (it lists what the bubble did, never your words)
- **Desktop:** the end of the log (`ramble.log`, or `journalctl --user -u ramble` on Linux) and the output of `ramble check`

## Making a change

- **Desktop** (Python, in `desktop/`): `uv sync`, then `uv run pytest`. Tests run on Linux, macOS and Windows for every pull request.
- **Android** (Java, in `android/`): `./scripts/fetch-deps.sh` once, then `./gradlew assembleDebug`. Test what you can on a real phone; the emulator has no working microphone, but debug builds accept a test broadcast that exercises the typing step (see the README).
- The tidy-up exists twice: `desktop/ramble/cleanup.py` and Android's `Cleanup.java` / `Polish.java`. Change them together.
- Keep pull requests focused, and describe how you tested.

## Good places to start

- A floating bubble on Windows and Linux (like the macOS one)
- Telling whether a text box has the cursor on Windows (UI Automation) and Linux (AT-SPI)
- Languages other than English
- Packaging: an installer for Windows, a Homebrew formula, F-Droid
