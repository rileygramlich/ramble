# Yap

Talk instead of typing. It's a free, private stand-in for Wispr Flow: hold a
key, speak, let go, and tidy text appears wherever your cursor is.

- **Speech-to-text runs on your own device** with OpenAI's Whisper. Nothing is
  uploaded, and there's no account, subscription or weekly limit.
- **Tidy-up**: a rules pass removes "um"/"uh" and stutters, and handles
  "scratch that", "new line" and "new paragraph". Then a small local model
  (Ollama, `qwen2.5:1.5b`) fixes punctuation, capitalisation and
  self-corrections ("five, no actually six" becomes "six"). If the model is slow,
  unreachable, or starts *answering* you instead of tidying, Yap falls back to
  the rules output.
- **Your vocabulary**: list names and jargon once, and both Whisper and the tidy-up
  spell them right.
- **Voice commands**: end with **"send it"** (or "send that", "send the message")
  and Yap types your words, then sends: it taps the app's Send button on the
  phone, and presses Enter on a computer. **"press enter"** (or "hit enter")
  presses Enter. It only counts as a command when it's its own sentence at the
  very end: "See you at six. Send it." sends; "Can you send it?" is just text.

| | Mac (`desktop/`) | Android (`android/`) |
|---|---|---|
| How you use it | Hold **Right Option ⌥**, talk, let go and it pastes. Tap for hands-free. | A floating mic bubble over your normal keyboard: tap, talk, tap again and it types. Or use Yap as a voice keyboard. |
| Speech model | `whisper-large-v3-turbo` on Apple's MLX | `large-v3-turbo` on Art over Tailscale; away from home `ggml-base.en-q5_1` on the phone (57 MB, bundled) |
| Tidy-up model | Ollama on the Mac | On Art with the speech, over Tailscale; otherwise rules only |

## Mac

```bash
git clone <this repo> && cd yap/desktop && ./install-mac.sh
```

The installer sets up `uv`, Yap, Ollama and the tidy-up model, downloads the
speech model (about 1.6 GB, once), and adds a login item. Then grant two
permissions it walks you through: **Accessibility** and **Input Monitoring**,
for the Python program it prints. Yap needs those to hear the hotkey and press ⌘V.

- Settings: `~/.config/yap/config.toml` (hotkey, engine, model, vocabulary, cleanup mode)
- Log: `~/Library/Logs/yap.log`. History: `~/.local/share/yap/history.jsonl`, handy if a paste lands in the wrong place.
- `yap check` tests the mic, the model and Ollama. `yap file clip.m4a` transcribes a recording.
- Restart: `launchctl kickstart -k gui/$(id -u)/dev.rileygramlich.yap`
- Clicked away while talking? If no text box has the cursor when you let go, the
  words stay on the clipboard (with a "Copied" notification) so you can paste them.

## Linux

Same idea as the Mac: hold **Right Ctrl**, talk, let go and it pastes; tap for
hands-free. Speech runs on the CPU with faster-whisper (`small.en`).

1. System packages: the audio library, clipboard tools, the paste helper, and
   ffmpeg for `yap file`. On Ubuntu/Debian:

   ```bash
   sudo apt install libportaudio2 wl-clipboard xclip ydotool ffmpeg
   systemctl --user enable --now ydotool   # the daemon ydotool pastes through
   ```

2. Let Yap hear the hotkey under Wayland. GNOME and KDE hide global key presses
   from apps, so Yap reads the keyboard from `/dev/input`. Then **log out and back in**:

   ```bash
   sudo usermod -aG input $USER
   ```

3. Yap itself, with [uv](https://docs.astral.sh/uv/), which fetches the right Python:

   ```bash
   curl -LsSf https://astral.sh/uv/install.sh | sh
   git clone https://github.com/rileygramlich/yap && cd yap/desktop
   uv sync
   ```

4. Optional, for the smarter tidy-up: [Ollama](https://ollama.com) and the model.

   ```bash
   curl -fsSL https://ollama.com/install.sh | sh
   ollama pull qwen2.5:1.5b
   ```

5. Check it, then start it at every login:

   ```bash
   uv run yap check    # mic, speech model, Ollama
   ln -s "$PWD/linux/yap.service" ~/.config/systemd/user/
   systemctl --user enable --now yap
   ```

   The service expects the clone at `~/dev/yap`. Edit `WorkingDirectory` in
   `linux/yap.service` if yours is somewhere else.

- Settings: `~/.config/yap/config.toml` (`uv run yap init` writes an example). After editing: `systemctl --user restart yap`
- Log: `journalctl --user -u yap -f`. History: `~/.local/share/yap/history.jsonl`
- **"can't read any keyboard in /dev/input"** in the log: you haven't logged in again since step 2.
  Until then the hotkey only works while an X11 app has focus.
- **Nothing pastes:** check `systemctl --user status ydotool`. Terminals paste with
  Ctrl+Shift+V, so in a terminal press that yourself; the text is on the clipboard.
- Linux can't tell whether a text box has the cursor, so Yap always pastes and then puts your old clipboard back.

## Speech on Art (for the phone)

A phone can only run a small Whisper. So when it can reach Art over Tailscale,
the Android app sends your audio there, and Art sends back tidy text in one round
trip: Whisper `large-v3-turbo` plus the Ollama tidy-up, both on Art. Away from
home, or when Art is off, the phone does it all itself and tries Art again a
minute later. Audio only ever travels inside your tailnet.

On Art, after the Linux setup above:

```bash
cd ~/dev/yap/desktop
ln -s "$PWD/linux/yap-serve.service" ~/.config/systemd/user/
systemctl --user enable --now yap-serve
curl http://$(tailscale ip -4):8723/health   # {"ok": true, "model": "large-v3-turbo"}
```

It listens on Art's Tailscale address only, on port 8723. If `ufw` is on, allow
it from the tailnet: `sudo ufw allow in on tailscale0 to any port 8723`. The log
(`journalctl --user -u yap-serve -f`) shows each dictation with how long Whisper
and the tidy-up took. On Art's CPU that's about 3.5 s for 10 s of speech; an NVIDIA
GPU with working drivers is several times faster. Change the model with
`serve_model` in `~/.config/yap/config.toml`.

## Android

Install `yap.apk`, open **Yap**, and do the two steps: allow the microphone and
turn on the **Yap bubble** (Settings → Accessibility). A mic bubble then floats
beside any text box you're typing in, and your normal keyboard stays. Tap it,
talk, and tap it again, or hold it and let go. Drag it to move it. Because the
APK isn't from the Play Store, Android 13+ greys the switch out at first: open
Yap's App info, tap ⋮ → **Allow restricted settings**, then turn it on.

Tapped out of the text box while talking? The bubble keeps listening, and when
you finish, the words are copied to the clipboard ("Copied: no text box had the
cursor") so you can paste them wherever you like.

Prefer a keyboard? Turn on the Yap keyboard and switch to it instead. 🌐 goes
back to your normal keyboard; long-press it to pick one.

With Tailscale on the phone, speech goes to Art first (see
[Speech on Art](#speech-on-art-for-the-phone)); the address is
`http://100.112.5.58:8723` by default. Clear it in the Yap app to stay on the
phone only. Ollama on Art can stay on localhost: the tidy-up runs next to it.

Build it yourself (JDK 21 and the Android SDK/NDK; on Hermes and Art they're in
`~/.local/share/android-toolchain` and `~/Android/Sdk`):

```bash
cd android
./scripts/fetch-deps.sh          # whisper.cpp source + the speech model (not in git)
export JAVA_HOME=$(ls -d ~/.local/share/android-toolchain/jdk-21*) ANDROID_HOME=~/Android/Sdk
./gradlew assembleRelease        # → app/build/outputs/apk/release/app-release.apk
```

Releases are signed with the Yap release key, which is kept out of git in
`~/.config/yap-signing/` (`yap-release.jks` plus `keystore.properties`, or set
`YAP_SIGNING` to another properties file). Copy that folder to any machine that
builds releases, and keep a backup: every APK signed with it installs over the
last one, and without it the next release needs an uninstall. Without the key,
`assembleRelease` gives an unsigned APK; `assembleDebug` still works for testing.

The native build targets arm64 phones with the armv8.2 `fp16` and `dotprod`
extensions, which covers roughly everything from 2018 on (Snapdragon 845 and
later, Pixel 3 and later).

## Layout

```
desktop/yap/cleanup.py     rules + Ollama tidy-up (the prompt, examples and safety checks)
desktop/yap/transcribe.py  Whisper via mlx or faster-whisper
desktop/yap/app.py         hotkey → record → transcribe → tidy → paste
desktop/tests/             cleanup tests (uv run pytest)
android/app/src/main/java  YapBubble (floating mic) and YapKeyboard share Dictation; Cleanup.java and Polish.java port cleanup.py, so keep them in step
android/app/src/main/cpp   the JNI bridge to whisper.cpp
```
