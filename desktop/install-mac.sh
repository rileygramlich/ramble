#!/usr/bin/env bash
# Install Ramble on a Mac: the `ramble` command, a small local model for tidy-up,
# and a login item so it's always running. Safe to run again to update.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
LABEL="dev.rileygramlich.ramble"
OLD_LABEL="dev.rileygramlich.yap"  # from when it was called Yap
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
LOG="$HOME/Library/Logs/ramble.log"
MODEL="qwen2.5:1.5b"

[[ "$(uname)" == "Darwin" ]] || { echo "This installer is for macOS."; exit 1; }
[[ "$(uname -m)" == "arm64" ]] || echo "Note: Intel Mac, so Ramble will use faster-whisper on the CPU (slower than Apple Silicon)."

step() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }

step "uv (Python tooling)"
if ! command -v uv >/dev/null; then
  curl -LsSf https://astral.sh/uv/install.sh | sh
  export PATH="$HOME/.local/bin:$PATH"
fi
uv --version

step "Ramble"
uv tool uninstall yap >/dev/null 2>&1 || true
uv tool install --force --python 3.12 "$HERE"
RAMBLE="$HOME/.local/bin/ramble"
[[ -f "$HOME/.config/ramble/config.toml" || -f "$HOME/.config/yap/config.toml" ]] || "$RAMBLE" init

step "Ollama + $MODEL for tidy-up"
if ! command -v ollama >/dev/null; then
  if command -v brew >/dev/null; then
    brew install ollama
    brew services start ollama
  else
    echo "Install Ollama from https://ollama.com/download, open it once, then run this script again."
    echo "(Ramble still works without it: the built-in rules do the tidy-up.)"
  fi
fi
if command -v ollama >/dev/null; then
  ollama pull "$MODEL"
fi

step "Downloading the speech model and checking the microphone"
echo "macOS will ask for microphone access. Say yes."
"$RAMBLE" check || true

step "Start at login"
mkdir -p "$(dirname "$PLIST")"
cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key><array><string>$RAMBLE</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ProcessType</key><string>Interactive</string>
  <key>EnvironmentVariables</key>
  <dict><key>PATH</key><string>$HOME/.local/bin:/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string></dict>
  <key>StandardOutPath</key><string>$LOG</string>
  <key>StandardErrorPath</key><string>$LOG</string>
</dict>
</plist>
EOF
launchctl bootout "gui/$(id -u)/$OLD_LABEL" 2>/dev/null || true
rm -f "$HOME/Library/LaunchAgents/$OLD_LABEL.plist"
launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
launchctl bootstrap "gui/$(id -u)" "$PLIST"

# macOS grants keyboard access per program. Ramble runs as this Python binary.
PY="$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$HOME/.local/share/uv/tools/ramble/bin/python")"
step "Two permissions to grant (once)"
cat <<EOF
Ramble needs to hear the hotkey and press ⌘V for you. In System Settings, add this
program under both Privacy & Security → Accessibility and → Input Monitoring:

    $PY

(Click +, press ⌘⇧G, and paste that path.) Opening both panes now…
EOF
open "x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility"
open "x-apple.systempreferences:com.apple.preference.security?Privacy_ListenEvent"
echo "$PY" | pbcopy && echo "(The path is on your clipboard.)"

cat <<EOF

Then restart Ramble:   launchctl kickstart -k gui/$(id -u)/$LABEL

Hold Right Option (⌥) to talk and let go to paste. Tap it for hands-free.
Log: $LOG   Settings: ~/.config/ramble/config.toml
EOF
