#!/usr/bin/env bash
# Clones the pinned llama.cpp release into third_party/llama.cpp.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TAG="$(tr -d '[:space:]' < "$ROOT/llama-version.txt")"
DEST="$ROOT/third_party/llama.cpp"
if [ -f "$DEST/CMakeLists.txt" ]; then
  echo "llama.cpp already present at $DEST"
  exit 0
fi
mkdir -p "$ROOT/third_party"
git clone --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp "$DEST"
