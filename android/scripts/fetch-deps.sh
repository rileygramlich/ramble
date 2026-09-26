#!/usr/bin/env bash
# Fetch what the keyboard builds from but git doesn't store: whisper.cpp's source
# and the speech model it bundles. Run once before the first build.
set -euo pipefail
cd "$(dirname "$0")/.."

WHISPER_TAG=v1.9.4
MODEL=ggml-base.en-q5_1.bin   # ~57 MB, English, a good balance of speed and accuracy on a phone

if [[ ! -d third_party/whisper.cpp ]]; then
  git clone --depth 1 --branch "$WHISPER_TAG" https://github.com/ggml-org/whisper.cpp third_party/whisper.cpp
fi

mkdir -p app/src/main/assets
if [[ ! -f "app/src/main/assets/$MODEL" ]]; then
  curl -L --fail -o "app/src/main/assets/$MODEL" "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$MODEL"
fi
echo "ready"
