#!/usr/bin/env bash
# Clones the pinned llama.cpp release into third_party/llama.cpp, and the Khronos OpenCL headers
# (header-only, for the Adreno GPU backend) into third_party/OpenCL-Headers.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TAG="$(tr -d '[:space:]' < "$ROOT/llama-version.txt")"
CL_TAG="v2025.07.22"
DEST="$ROOT/third_party/llama.cpp"
CL_DEST="$ROOT/third_party/OpenCL-Headers"
mkdir -p "$ROOT/third_party"
if [ -f "$DEST/CMakeLists.txt" ]; then
  echo "llama.cpp already present at $DEST"
else
  git clone --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp "$DEST"
fi
if [ -f "$CL_DEST/CL/cl.h" ]; then
  echo "OpenCL headers already present at $CL_DEST"
else
  git clone --depth 1 --branch "$CL_TAG" https://github.com/KhronosGroup/OpenCL-Headers "$CL_DEST"
fi
