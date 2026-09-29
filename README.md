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

| | Mac (`desktop/`) | Android (`android/`) |
|---|---|---|
| How you use it | Hold **Right Option ⌥**, talk, let go and it pastes. Tap for hands-free. | A voice keyboard. Hold the mic, talk, let go and it types. Tap for hands-free. |
| Speech model | `whisper-large-v3-turbo` on Apple's MLX | `ggml-base.en-q5_1` in whisper.cpp, bundled (57 MB) |
| Tidy-up model | Ollama on the Mac | Ollama on Hermes or Artemius over Tailscale, if set; otherwise rules only |

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

It also runs on Linux (`uv run yap` in `desktop/`, using faster-whisper on the CPU).
Install `libportaudio2`. On Wayland, Yap reads the hotkey straight from
`/dev/input`, so add yourself to the `input` group (`sudo usermod -aG input $USER`,
then log in again), and it pastes with `ydotool`, so keep `ydotoold` running. To start
it at login: `ln -s "$PWD/linux/yap.service" ~/.config/systemd/user/ && systemctl --user enable --now yap`.
Log: `journalctl --user -u yap -f`.

## Android

Install `yap.apk`, open **Yap**, and do the three steps: allow the microphone,
turn on the keyboard, and switch to it. 🌐 goes back to your normal keyboard;
long-press it to pick one.

For smarter tidy-up, enter your Ollama address in the Yap app, for example
`http://100.112.5.58:11434` for Artemius, with Tailscale on the phone. Ollama only
listens on localhost by default, so on that machine run:

```bash
sudo systemctl edit ollama   # add:  [Service]  Environment="OLLAMA_HOST=0.0.0.0"
sudo systemctl restart ollama
```

Then block port 11434 from anything but the tailnet (e.g. `sudo ufw allow in on tailscale0 to any port 11434`).

Build it yourself (JDK 21 and the Android SDK/NDK; on Hermes they're already in
`~/.local/share/android-toolchain` and `~/Android/Sdk`):

```bash
cd android
./scripts/fetch-deps.sh          # whisper.cpp source + the speech model (not in git)
export JAVA_HOME=$(ls -d ~/.local/share/android-toolchain/jdk-21*) ANDROID_HOME=~/Android/Sdk
./gradlew assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
```

The native build targets arm64 phones with the armv8.2 `fp16` and `dotprod`
extensions, which covers roughly everything from 2018 on (Snapdragon 845 and
later, Pixel 3 and later).

## Layout

```
desktop/yap/cleanup.py     rules + Ollama tidy-up (the prompt, examples and safety checks)
desktop/yap/transcribe.py  Whisper via mlx or faster-whisper
desktop/yap/app.py         hotkey → record → transcribe → tidy → paste
desktop/tests/             cleanup tests (uv run pytest)
android/app/src/main/java  the keyboard; Cleanup.java and Polish.java port cleanup.py, so keep them in step
android/app/src/main/cpp   the JNI bridge to whisper.cpp
```
