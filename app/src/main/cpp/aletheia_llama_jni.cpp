/**
 * Thin JNI bridge for Aletheia Offline GGUF inference (llama.cpp).
 * Pattern adapted from ggml-org/llama.cpp examples/llama.android (tag b5215).
 */
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <string>
#include <unistd.h>

#include "common.h"
#include "llama.h"

#define TAG "AletheiaLlamaJNI"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

jclass g_int_var_class = nullptr;
jmethodID g_int_var_value = nullptr;
jmethodID g_int_var_inc = nullptr;
std::string g_cached_token_chars;

bool is_valid_utf8(const char *string) {
    if (!string) return true;
    const unsigned char *bytes = (const unsigned char *)string;
    while (*bytes != 0x00) {
        int num;
        if ((*bytes & 0x80) == 0x00) num = 1;
        else if ((*bytes & 0xE0) == 0xC0) num = 2;
        else if ((*bytes & 0xF0) == 0xE0) num = 3;
        else if ((*bytes & 0xF8) == 0xF0) num = 4;
        else return false;
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) return false;
            bytes += 1;
        }
    }
    return true;
}

void log_callback(ggml_log_level level, const char *fmt, void * /*data*/) {
    if (level == GGML_LOG_LEVEL_ERROR) __android_log_print(ANDROID_LOG_ERROR, TAG, "%s", fmt);
    else if (level == GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, "%s", fmt);
    else if (level == GGML_LOG_LEVEL_INFO) __android_log_print(ANDROID_LOG_INFO, TAG, "%s", fmt);
    else __android_log_print(ANDROID_LOG_DEBUG, TAG, "%s", fmt);
}

} // namespace

extern "C" {

JNIEXPORT void JNICALL
Java_it_grg_flighttimeapp_aletheia_AletheiaLlamaNative_nativeBackendInit(JNIEnv *, jclass) {
    llama_log_set(log_callback, nullptr);
    llama_backend_init();
}

JNIEXPORT void JNICALL
Java_it_grg_flighttimeapp_aletheia_AletheiaLlamaNative_nativeBackendFree(JNIEnv *, jclass) {
    llama_backend_free();
}

JNIEXPORT jstring JNICALL
Java_it_grg_flighttimeapp_aletheia_AletheiaLlamaNative_nativeSystemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jlong JNICALL
Java_it_grg_flighttimeapp_aletheia_AletheiaLlamaNative_nativeLoadModel(JNIEnv *env, jclass, jstring filename) {
    llama_model_params model_params = llama_model_default_params();
    const char *path = env->GetStringUTFChars(filename, nullptr);
    LOGi("Loading GGUF from %s", path);
    llama_model *model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(filename, path);
    if (!model) {
        LOGe("nativeLoadModel failed");
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Failed to load GGUF model");
        return 0;
    }
    return reinterpret_cast<jlong>(model);
}

JNIEXPORT void JNICALL
Java_it_grg_flighttimeapp_aletheia_AletheiaLlamaNative_nativeFreeModel(JNIEnv *, jclass, jlong model) {
    if (model) llama_model_free(reinterpret_cast<llama_model *>(model));
}

JNIEXPORT jlong JNICALL
Java_it_grg_flighttimeapp_aletheia_AletheiaLlamaNative_nativeNewContext(
        JNIEnv *env, jclass, jlong jmodel, jint n_ctx) {
    auto *model = reinterpret_cast<llama_model *>(jmodel);
    if (!model) {
        env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Model is null");
        return 0;
    }
    int n_threads = std::max(1, std::min(6, (int)sysconf(_SC_NPROCESSORS_ONLN) - 1));
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = n_ctx > 0 ? n_ctx : 2048;
    ctx_params.n_threads = n_threads;
    ctx_params.n_threads_batch = n_threads;
    llama_context *context = llama_init_from_model(model, ctx_params);
    if (!context) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Failed to create llama context");
        return 0;
    }
    LOGi("Context ready threads=%d n_ctx=%d", n_threads, (int)ctx_params.n_ctx);
    return reinterpret_cast<jlong>(context);
}

JNIEXPORT void JNICALL
Java_it_grg_flighttimeapp_aletheia_AletheiaLlamaNative_nativeFreeContext(JNIEnv *, jclass, jlong context) {
    if (context) llama_free(reinterpret_cast<llama_context *>(context));
}

JNIEXPORT jlong JNICALL
Java_it_grg_flighttimeapp_aletheia_AletheiaLlamaNative_nativeNewBatch(
        JNIEnv *, jclass, jint n_tokens, jint embd, jint n_seq_max) {
    llama_batch stack = llama_batch_init(n_tokens, embd, n_seq_max);
    auto *batch = new llama_batch(stack);
    return reinterpret_cast<jlong>(batch);
}

JNIEXPORT void JNICALL
Java_it_grg_flighttimeapp_aletheia_AletheiaLlamaNative_nativeFreeBatch(JNIEnv *, jclass, jlong batch_pointer) {
    auto *batch = reinterpret_cast<llama_batch *>(batch_pointer);
    if (!batch) return;
    llama_batch_free(*batch);
    delete batch;
}

JNIEXPORT jlong JNICALL
Java_it_grg_flighttimeapp_aletheia_AletheiaLlamaNative_nativeNewSampler(JNIEnv *, jclass) {
    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler *smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    return reinterpret_cast<jlong>(smpl);
}

JNIEXPORT void JNICALL
Java_it_grg_flighttimeapp_aletheia_AletheiaLlamaNative_nativeFreeSampler(JNIEnv *, jclass, jlong sampler) {
    if (sampler) llama_sampler_free(reinterpret_cast<llama_sampler *>(sampler));
}

JNIEXPORT jint JNICALL
Java_it_grg_flighttimeapp_aletheia_AletheiaLlamaNative_nativeCompletionInit(
        JNIEnv *env,
        jclass,
        jlong context_pointer,
        jlong batch_pointer,
        jstring jtext,
        jboolean format_chat,
        jint n_len) {
    g_cached_token_chars.clear();
    const char *text = env->GetStringUTFChars(jtext, nullptr);
    auto *context = reinterpret_cast<llama_context *>(context_pointer);
    auto *batch = reinterpret_cast<llama_batch *>(batch_pointer);

    const bool parse_special = (format_chat == JNI_TRUE);
    const auto tokens_list = common_tokenize(context, text, true, parse_special);

    auto n_ctx = llama_n_ctx(context);
    auto n_kv_req = tokens_list.size() + (size_t)n_len;
    if (n_kv_req > n_ctx) {
        LOGe("n_kv_req (%zu) > n_ctx (%u)", n_kv_req, n_ctx);
    }

    common_batch_clear(*batch);
    for (size_t i = 0; i < tokens_list.size(); i++) {
        common_batch_add(*batch, tokens_list[i], (llama_pos)i, {0}, false);
    }
    if (batch->n_tokens > 0) {
        batch->logits[batch->n_tokens - 1] = true;
    }
    if (llama_decode(context, *batch) != 0) {
        LOGe("llama_decode() failed during prompt");
    }
    env->ReleaseStringUTFChars(jtext, text);
    return batch->n_tokens;
}

JNIEXPORT jstring JNICALL
Java_it_grg_flighttimeapp_aletheia_AletheiaLlamaNative_nativeCompletionLoop(
        JNIEnv *env,
        jclass,
        jlong context_pointer,
        jlong batch_pointer,
        jlong sampler_pointer,
        jint n_len,
        jobject intvar_ncur) {
    auto *context = reinterpret_cast<llama_context *>(context_pointer);
    auto *batch = reinterpret_cast<llama_batch *>(batch_pointer);
    auto *sampler = reinterpret_cast<llama_sampler *>(sampler_pointer);
    const auto *model = llama_get_model(context);
    const auto *vocab = llama_model_get_vocab(model);

    if (!g_int_var_class) g_int_var_class = (jclass)env->NewGlobalRef(env->GetObjectClass(intvar_ncur));
    if (!g_int_var_value) g_int_var_value = env->GetMethodID(g_int_var_class, "getValue", "()I");
    if (!g_int_var_inc) g_int_var_inc = env->GetMethodID(g_int_var_class, "inc", "()V");

    const auto new_token_id = llama_sampler_sample(sampler, context, -1);
    const auto n_cur = env->CallIntMethod(intvar_ncur, g_int_var_value);
    if (llama_vocab_is_eog(vocab, new_token_id) || n_cur >= n_len) {
        return nullptr;
    }

    auto new_token_chars = common_token_to_piece(context, new_token_id);
    g_cached_token_chars += new_token_chars;

    jstring new_token;
    if (is_valid_utf8(g_cached_token_chars.c_str())) {
        new_token = env->NewStringUTF(g_cached_token_chars.c_str());
        g_cached_token_chars.clear();
    } else {
        new_token = env->NewStringUTF("");
    }

    common_batch_clear(*batch);
    common_batch_add(*batch, new_token_id, n_cur, {0}, true);
    env->CallVoidMethod(intvar_ncur, g_int_var_inc);

    if (llama_decode(context, *batch) != 0) {
        LOGe("llama_decode() failed during generation");
    }
    return new_token;
}

JNIEXPORT void JNICALL
Java_it_grg_flighttimeapp_aletheia_AletheiaLlamaNative_nativeKvClear(JNIEnv *, jclass, jlong context) {
    if (context) llama_kv_self_clear(reinterpret_cast<llama_context *>(context));
}

} // extern "C"
