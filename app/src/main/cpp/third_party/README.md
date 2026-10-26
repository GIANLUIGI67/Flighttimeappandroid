# Vendored llama.cpp (Aletheia Offline)

Pinned tree from [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) tag **`b5215`**.

Used by `app/src/main/cpp` JNI (`aletheia_llama`) for on-device GGUF inference.

Refresh with:

```bash
./scripts/sync-llama-cpp.sh
```

If this directory is missing, CMake falls back to `FetchContent` of the same tag (needs network on first native configure).
