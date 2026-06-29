#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TARGET_DIR="$ROOT_DIR/kokoro-assets/kokoro"
BASE_URL="https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/resolve/main"
CMUDICT_URL="https://raw.githubusercontent.com/cmusphinx/cmudict/master"

mkdir -p "$TARGET_DIR/voices" "$TARGET_DIR/phonemizer"

download() {
  local remote_path="$1"
  local local_path="$2"
  if [[ -s "$local_path" ]]; then
    echo "exists $local_path"
    return
  fi
  echo "download $remote_path"
  curl -L --fail --retry 3 --retry-delay 2 \
    "$BASE_URL/$remote_path" \
    -o "$local_path"
}

download "onnx/model_quantized.onnx" "$TARGET_DIR/model_quantized.onnx"
download "tokenizer.json" "$TARGET_DIR/tokenizer.json"
download "tokenizer_config.json" "$TARGET_DIR/tokenizer_config.json"
if [[ ! -s "$TARGET_DIR/phonemizer/cmudict.dict" ]]; then
  curl -L --fail --retry 3 --retry-delay 2 \
    "$CMUDICT_URL/cmudict.dict" \
    -o "$TARGET_DIR/phonemizer/cmudict.dict"
fi
if [[ ! -s "$TARGET_DIR/phonemizer/LICENSE.cmudict" ]]; then
  curl -L --fail --retry 3 --retry-delay 2 \
    "$CMUDICT_URL/LICENSE" \
    -o "$TARGET_DIR/phonemizer/LICENSE.cmudict"
fi

for voice in \
  af_heart \
  af_sarah \
  af_bella \
  af_sky \
  am_adam \
  am_michael \
  am_fenrir \
  bf_emma \
  bm_george
do
  download "voices/${voice}.bin" "$TARGET_DIR/voices/${voice}.bin"
done

cat > "$TARGET_DIR/README.md" <<'EOF'
# Kokoro Runtime Assets

These files are downloaded from `onnx-community/Kokoro-82M-v1.0-ONNX`.

- Model: Apache-2.0 licensed Kokoro-82M ONNX quantized model.
- Voices: curated voice `.bin` style vectors used by NovelApp narration.
- Tokenizer files: official Kokoro token vocabulary.
- Phonemizer dictionary: CMUdict pronunciation data used to map English words to Kokoro-compatible phoneme tokens.

Run `scripts/download-kokoro-assets.sh` again to restore missing files.
EOF

echo "Kokoro assets ready in $TARGET_DIR"
