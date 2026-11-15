# llama.cpp for Aletheia Offline

Pinned to [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp) tag **`b5215`**.

Used by `app/src/main/cpp` JNI (`aletheia_llama`) for on-device GGUF inference.

## Default: CMake FetchContent

The vendored tree is **not** committed (keeps the PR/diff under review-bot size limits).  
On first native configure, CMake fetches tag `b5215` via `FetchContent` (needs network).

## Optional: local sync (offline / reproducible builds)

```bash
./scripts/sync-llama-cpp.sh
```

That writes `app/src/main/cpp/third_party/llama.cpp/` (gitignored). CMake prefers the local tree when present.
