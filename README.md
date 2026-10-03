# Ramble

**Talk instead of typing, on every device you own.** Ramble is free, private, open-source dictation in the spirit of Wispr Flow: tap the bubble (or hold a key), speak, and tidy text appears wherever your cursor is.

- **Runs on your own devices.** Speech-to-text uses OpenAI's Whisper locally. Nothing is uploaded, and there's no account, subscription or weekly limit.
- **Tidies as it types.** A rules pass removes "um"/"uh" and stutters, and handles "scratch that", "new line" and "new paragraph". If you have [Ollama](https://ollama.com), a small local model also fixes punctuation, capitalisation and self-corrections ("five, no actually six" becomes "six"). Ollama is optional: without it, the rules do the tidy-up.
- **Knows your words.** List names and jargon once, and both Whisper and the tidy-up spell them right.
- **Voice commands.** End with **"send it"** and Ramble types your words, then sends (it taps the app's Send button on a phone, presses Enter on a computer). **"press enter"** presses Enter. A command only counts as its own sentence at the very end: "See you at six. Send it." sends; "Can you send it?" is just text.
- **Your phone can borrow your computer's brain.** Point the phone at `ramble serve` on your computer (over [Tailscale](https://tailscale.com)) and it uses a much bigger speech model there. Away from it, the phone does everything itself.

## Platforms

Ramble is growing one system at a time.

| | How you use it | Status |
|---|---|---|
| **Android** | A small mic sits just above your normal keyboard whenever it's open: tap, talk, tap ✓ and it types. Or use Ramble as a voice keyboard. | ✅ Ready |
| **macOS** | A floating bubble at the bottom of the screen, or hold **Right Option ⌥**. Apple Silicon uses MLX for speed. | ✅ Ready |
| **Linux** | Hold **Right Ctrl**, talk, let go and it pastes. Works on Wayland and X11. | ✅ Ready |
| **Windows** | Hold **Right Ctrl**, talk, let go and it pastes. | 🧪 Experimental: tested automatically on Windows, but [tell us](../../issues) how it goes on yours |
| **iOS** | | ❌ Not possible as a bubble: Apple doesn't let keyboards or overlays use the microphone |

A floating bubble for Windows and Linux, and more languages, are next. [Contributions](CONTRIBUTING.md) welcome.

### How it compares

There are good open-source dictation apps for the desktop, like [Handy](https://github.com/cjpais/Handy), [OpenWhispr](https://github.com/OpenWhispr/openwhispr) and [VoiceInk](https://github.com/Beingpax/VoiceInk). Ramble's difference is that it covers **your phone too**: on Android, a Wispr-style bubble that works over *any* keyboard (not only as a key inside one), and the option to send your phone's speech to your own computer's bigger model, with the same tidy-up and voice commands on every device.

## Android

Download `ramble.apk` from [Releases](../../releases), install it, open **Ramble**, and do the two steps: allow the microphone and turn on the **Ramble bubble** (Settings → Accessibility). Because the APK isn't from the Play Store, Android 13+ greys that switch out at first: open Ramble's **App info**, tap ⋮ → **Allow restricted settings**, then turn it on.

Like Wispr Flow's, the bubble only appears while a keyboard is open: a small, see-through mic resting at the screen edge just above the keyboard. Tap it and it opens into a small pill with a waveform: tap ✓ (or the waveform) to type what you said, ✕ to throw it away. Or hold the mic and let go. Drag it to switch sides or change how high it sits. It stays out of the way of the Ramble keyboard, which has its own mic.

- **Typing:** the words go straight into the text box. Ramble re-reads the box to check they landed, falls back to pasting (marked sensitive, then cleared from the clipboard) for boxes that only accept a paste, and tells you if neither worked. Tapped out of the text box while talking? The words are copied so you can paste them anywhere.
- **History:** the app keeps your last 100 dictations, only on the phone. Tap one to copy it, hold one to delete it.
- **Keyboard instead:** turn on the Ramble keyboard and switch to it. 🌐 goes back to your normal keyboard.
- **Diagnostics:** Settings → Diagnostics shows what the bubble did with recent dictations (never the words), with **Copy report** for bug reports.
- **Speech model:** `ggml-base.en-q5_1` on the phone (57 MB, bundled), or your computer's `large-v3-turbo` through `ramble serve` (below).

Phones need arm64 with the armv8.2 `fp16` and `dotprod` extensions, which covers roughly everything from 2018 on (Snapdragon 845 and later, Pixel 3 and later).

## macOS

```bash
git clone https://github.com/rileygramlich/ramble && cd ramble/desktop && ./install-mac.sh
```

The installer sets up `uv`, Ramble, Ollama and the tidy-up model, downloads the speech model (about 1.6 GB, once), and adds a login item. Then grant the two permissions it walks you through, **Accessibility** and **Input Monitoring**, for the Python program it prints. Ramble needs those to hear the hotkey and press ⌘V.

- **The bubble:** a mic floats at the bottom-centre of every screen and Space and never takes focus from what you're typing in. Click it, talk, click ✓. `bubble = false` in the settings hides it.
- Settings: `~/.config/ramble/config.toml`. Log: `~/Library/Logs/ramble.log`. History: `~/.local/share/ramble/history.jsonl`.
- `ramble check` tests the mic, the model and Ollama. `ramble file clip.m4a` transcribes a recording.
- Restart: `launchctl kickstart -k gui/$(id -u)/dev.rileygramlich.ramble`
- If no text box has the cursor when you finish, the words stay on the clipboard with a notification.

## Linux

Hold **Right Ctrl**, talk, let go and it pastes; tap for hands-free. Speech runs on the CPU with faster-whisper (`small.en`).

1. System packages: the audio library, clipboard tools, the paste helper, and ffmpeg for `ramble file`. On Ubuntu/Debian:

   ```bash
   sudo apt install libportaudio2 wl-clipboard xclip ydotool ffmpeg
   systemctl --user enable --now ydotool   # the daemon ydotool pastes through
   ```

2. Let Ramble hear the hotkey under Wayland. GNOME and KDE hide global key presses from apps, so Ramble reads the keyboard from `/dev/input`. Then **log out and back in**:

   ```bash
   sudo usermod -aG input $USER
   ```

3. Ramble itself, with [uv](https://docs.astral.sh/uv/), which fetches the right Python:

   ```bash
   curl -LsSf https://astral.sh/uv/install.sh | sh
   git clone https://github.com/rileygramlich/ramble ~/ramble && cd ~/ramble/desktop
   uv sync
   ```

4. Optional, for the smarter tidy-up: [Ollama](https://ollama.com) and the model.

   ```bash
   curl -fsSL https://ollama.com/install.sh | sh
   ollama pull qwen2.5:1.5b
   ```

5. Check it, then start it at every login:

   ```bash
   uv run ramble check    # mic, speech model, Ollama
   ln -s "$PWD/linux/ramble.service" ~/.config/systemd/user/
   systemctl --user enable --now ramble
   ```

   The service expects the clone at `~/ramble`. Edit `WorkingDirectory` in `linux/ramble.service` if yours is somewhere else.

- Settings: `~/.config/ramble/config.toml` (`uv run ramble init` writes an example). After editing: `systemctl --user restart ramble`
- Log: `journalctl --user -u ramble -f`. History: `~/.local/share/ramble/history.jsonl`
- **"can't read any keyboard in /dev/input"** in the log: you haven't logged in again since step 2. Until then the hotkey only works while an X11 app has focus.
- **Nothing pastes:** check `systemctl --user status ydotool`. Terminals paste with Ctrl+Shift+V, so in a terminal press that yourself; the text is on the clipboard.

## Windows (experimental)

Install [Git](https://git-scm.com/download/win), then in PowerShell:

```powershell
git clone https://github.com/rileygramlich/ramble; cd ramble\desktop
powershell -ExecutionPolicy Bypass -File install-windows.ps1
```

The installer sets up `uv` and Ramble, offers Ollama for the smarter tidy-up, downloads the speech model, checks the microphone, and adds a Startup shortcut so Ramble runs quietly at login. Hold **Right Ctrl**, talk, let go and it pastes; tap it for hands-free.

- Settings: `%APPDATA%\Ramble\config.toml`. Log and history: `%LOCALAPPDATA%\Ramble\`
- To stop it starting at login, delete the Ramble shortcut in your Startup folder (Win+R → `shell:startup`).
- Windows can't tell whether a text box has the cursor, so Ramble always pastes and then puts your old clipboard back.

Windows support is new and runs on the same code as Linux and macOS. Its tests run on Windows for every change, but real-world reports are very welcome.

## Your computer as your phone's speech server (optional)

A phone can only run a small Whisper model. With `ramble serve` running on a computer, the Android app sends your audio there instead and gets tidy text back in one round trip: Whisper `large-v3-turbo` plus the Ollama tidy-up. Away from it, the phone does everything itself and tries the computer again a minute later. Audio only travels inside your own [Tailscale](https://tailscale.com) network.

On a Linux computer, after the setup above:

```bash
cd ~/ramble/desktop
ln -s "$PWD/linux/ramble-serve.service" ~/.config/systemd/user/
systemctl --user enable --now ramble-serve
curl http://$(tailscale ip -4):8723/health   # {"ok": true, "model": "large-v3-turbo"}
```

(On a Mac or Windows, run `ramble serve 100.x.y.z` with your Tailscale address.) Then, in the Ramble app on your phone, put `http://100.x.y.z:8723` under Settings → **Speech on your computer**.

It listens on the Tailscale address only, on port 8723. If `ufw` is on, allow it from the tailnet: `sudo ufw allow in on tailscale0 to any port 8723`. The log shows each dictation with how long Whisper and the tidy-up took; on a laptop CPU that's about 3.5 s for 10 s of speech, and an NVIDIA GPU is several times faster. Change the model with `serve_model` in the settings.

## Building Ramble

**Desktop:** `cd desktop && uv sync && uv run pytest`. The code is in `desktop/ramble/`.

**Android** (JDK 21 and the Android SDK/NDK):

```bash
cd android
./scripts/fetch-deps.sh          # whisper.cpp source + the speech model (not in git)
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
```

Official releases are signed with the maintainer's release key, which isn't in the repo, so your own builds install as a separate debug app. Debug builds also accept a test broadcast that runs the typing step without a microphone, which is handy on the emulator:

```bash
adb shell "am broadcast -a dev.rileygramlich.yap.TEST_INSERT --es text 'Hello from the emulator'"
```

### Layout

```
desktop/ramble/cleanup.py     rules + Ollama tidy-up (the prompt, examples and safety checks)
desktop/ramble/transcribe.py  Whisper via mlx or faster-whisper
desktop/ramble/app.py         hotkey → record → transcribe → tidy → paste
desktop/ramble/serve.py       ramble serve: speech for your phone
desktop/tests/                tests (uv run pytest), run on Linux, macOS and Windows in CI
android/app/src/main/java     YapBubble (the floating mic) and YapKeyboard share Dictation;
                              Cleanup.java and Polish.java port cleanup.py, so keep them in step
android/app/src/main/cpp      the JNI bridge to whisper.cpp
```

(Ramble was called Yap until October 2026. Some internal names keep the old name, so existing installs update in place.)

## License

[MIT](LICENSE). Use it, change it, build on it. Ramble builds on [whisper.cpp](https://github.com/ggml-org/whisper.cpp) and [OpenAI Whisper](https://github.com/openai/whisper) (both MIT), [faster-whisper](https://github.com/SYSTRAN/faster-whisper) and [MLX Whisper](https://github.com/ml-explore/mlx-examples).
