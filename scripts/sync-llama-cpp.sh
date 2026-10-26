#!/usr/bin/env bash
# Sync a sparse llama.cpp tree for Aletheia Offline JNI (tag b5215).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DST="$ROOT/app/src/main/cpp/third_party/llama.cpp"
TAG="${LLAMA_CPP_TAG:-b5215}"
TMP="$(mktemp -d)"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

echo "Cloning ggml-org/llama.cpp@$TAG …"
git clone --depth 1 --branch "$TAG" https://github.com/ggml-org/llama.cpp.git "$TMP/llama.cpp"

rm -rf "$DST"
mkdir -p "$DST"
cd "$TMP/llama.cpp"
for item in cmake common ggml include src licenses CMakeLists.txt LICENSE README.md SECURITY.md CONTRIBUTING.md; do
  if [ -e "$item" ]; then
    cp -a "$item" "$DST/"
  fi
done
echo "Synced → $DST ($(du -sh "$DST" | awk '{print $1}'))"
