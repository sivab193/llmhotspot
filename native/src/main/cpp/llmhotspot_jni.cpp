#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include "llama.h"

#define TAG "LLMHotspotNative"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {
std::mutex g_mutex;
llama_model * g_model = nullptr;
llama_context * g_context = nullptr;

void free_model_locked() {
    if (g_context) { llama_free(g_context); g_context = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
}

std::vector<llama_token> tokenize(const std::string & text, bool add_special) {
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    int32_t size = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, add_special, true);
    if (size == INT32_MIN) return {};
    if (size < 0) size = -size;
    std::vector<llama_token> tokens(size);
    const int32_t count = llama_tokenize(vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(), size, add_special, true);
    if (count < 0) return {};
    tokens.resize(count);
    return tokens;
}

std::string token_piece(llama_token token) {
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    std::vector<char> buffer(64);
    int32_t size = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, true);
    if (size < 0) {
        buffer.resize(-size);
        size = llama_token_to_piece(vocab, token, buffer.data(), static_cast<int32_t>(buffer.size()), 0, true);
    }
    return size > 0 ? std::string(buffer.data(), size) : std::string();
}

std::string jstring_utf8(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return result;
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_llmhotspot_nativebridge_NativeLlama_loadModel(JNIEnv * env, jobject, jstring path, jint context_size, jint gpu_layers) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_model_locked();
    llama_backend_init();
    const std::string model_path = jstring_utf8(env, path);
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = gpu_layers;
    g_model = llama_model_load_from_file(model_path.c_str(), model_params);
    if (!g_model) return env->NewStringUTF("Unable to load GGUF model");

    llama_context_params context_params = llama_context_default_params();
    context_params.n_ctx = static_cast<uint32_t>(context_size);
    context_params.n_batch = std::min<uint32_t>(512, context_params.n_ctx);
    context_params.n_threads = std::max(2u, std::thread::hardware_concurrency() / 2);
    context_params.n_threads_batch = std::max(2u, std::thread::hardware_concurrency());
    context_params.embeddings = true;
    context_params.pooling_type = LLAMA_POOLING_TYPE_NONE;
    context_params.type_k = GGML_TYPE_Q8_0;
    context_params.type_v = GGML_TYPE_Q8_0;
    g_context = llama_init_from_model(g_model, context_params);
    if (!g_context) {
        free_model_locked();
        return env->NewStringUTF("Unable to create llama.cpp context");
    }
    return nullptr;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_llmhotspot_nativebridge_NativeLlama_unloadModel(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(g_mutex);
    free_model_locked();
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_dev_llmhotspot_nativebridge_NativeLlama_generate(JNIEnv * env, jobject, jstring prompt_value, jstring suffix_value, jint max_tokens, jfloat temperature, jobject callback) {
    std::lock_guard<std::mutex> lock(g_mutex);
    jlong counts[2] = {0, 0};
    jlongArray result = env->NewLongArray(2);
    if (!g_model || !g_context) { env->SetLongArrayRegion(result, 0, 2, counts); return result; }

    std::string prompt = jstring_utf8(env, prompt_value);
    const std::string suffix = jstring_utf8(env, suffix_value);
    if (!suffix.empty()) {
        const llama_vocab * vocab = llama_model_get_vocab(g_model);
        prompt = token_piece(llama_vocab_fim_pre(vocab)) + prompt + token_piece(llama_vocab_fim_suf(vocab)) + suffix + token_piece(llama_vocab_fim_mid(vocab));
    }
    std::vector<llama_token> tokens = tokenize(prompt, true);
    counts[0] = static_cast<jlong>(tokens.size());
    if (tokens.empty() || tokens.size() >= llama_n_ctx(g_context)) { env->SetLongArrayRegion(result, 0, 2, counts); return result; }

    llama_memory_clear(llama_get_memory(g_context), true);
    llama_batch prompt_batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(g_context, prompt_batch) != 0) { env->SetLongArrayRegion(result, 0, 2, counts); return result; }

    llama_sampler * sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(std::max(0.0f, temperature)));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    jclass callback_class = env->GetObjectClass(callback);
    jmethodID on_token = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    for (int i = 0; i < max_tokens && tokens.size() + i < llama_n_ctx(g_context); ++i) {
        const llama_token token = llama_sampler_sample(sampler, g_context, -1);
        if (llama_vocab_is_eog(vocab, token)) break;
        const std::string piece = token_piece(token);
        jstring java_piece = env->NewStringUTF(piece.c_str());
        env->CallVoidMethod(callback, on_token, java_piece);
        env->DeleteLocalRef(java_piece);
        if (env->ExceptionCheck()) break;
        llama_batch next = llama_batch_get_one(const_cast<llama_token *>(&token), 1);
        if (llama_decode(g_context, next) != 0) break;
        counts[1]++;
    }
    llama_sampler_free(sampler);
    env->SetLongArrayRegion(result, 0, 2, counts);
    return result;
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_dev_llmhotspot_nativebridge_NativeLlama_embed(JNIEnv * env, jobject, jstring input_value) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_model || !g_context) return env->NewFloatArray(0);
    std::vector<llama_token> tokens = tokenize(jstring_utf8(env, input_value), true);
    if (tokens.empty()) return env->NewFloatArray(0);
    llama_memory_clear(llama_get_memory(g_context), true);
    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    if (llama_decode(g_context, batch) != 0) return env->NewFloatArray(0);
    const int32_t dimensions = llama_model_n_embd(g_model);
    float * source = llama_get_embeddings_ith(g_context, -1);
    if (!source) return env->NewFloatArray(0);
    std::vector<float> normalized(source, source + dimensions);
    double norm = 0.0;
    for (float value : normalized) norm += value * value;
    norm = std::sqrt(norm);
    if (norm > 0) for (float & value : normalized) value = static_cast<float>(value / norm);
    jfloatArray output = env->NewFloatArray(dimensions);
    env->SetFloatArrayRegion(output, 0, dimensions, normalized.data());
    return output;
}
