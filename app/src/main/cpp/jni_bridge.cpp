// JNI glue for com.hy.assistant.llm.LlamaNative. Strings cross the boundary as
// UTF-8 byte arrays because JNI's "modified UTF-8" mangles emoji.
// Every entry point catches C++ exceptions: letting one reach the JVM aborts the process.
#include <android/log.h>
#include <jni.h>

#include <cstdlib>
#include <exception>
#include <string>

#include "llama.h"
#include "llm_core.h"

#define TAG "HyLlm"

namespace {

std::string to_string(JNIEnv *env, jbyteArray arr) {
    if (!arr) return {};
    jsize len = env->GetArrayLength(arr);
    std::string out(len, '\0');
    env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(out.data()));
    return out;
}

void log_callback(ggml_log_level level, const char *text, void *) {
    int prio = level == GGML_LOG_LEVEL_ERROR  ? ANDROID_LOG_ERROR
               : level == GGML_LOG_LEVEL_WARN ? ANDROID_LOG_WARN
                                              : ANDROID_LOG_DEBUG;
    __android_log_print(prio, TAG, "%s", text);
}

}  // namespace

extern "C" {

JNIEXPORT void JNICALL Java_com_hy_assistant_llm_LlamaNative_backendInit(JNIEnv *env, jobject, jstring cache_dir) {
    try {
        // Compiled GPU kernels are cached here: the first GPU run compiles them (slow), later ones load them.
        const char *dir = env->GetStringUTFChars(cache_dir, nullptr);
        if (dir) {
            setenv("GGML_OPENCL_KERNEL_CACHE_DIR", dir, 1);
            env->ReleaseStringUTFChars(cache_dir, dir);
        }
        llama_log_set(log_callback, nullptr);
        hy::backend_init();
    } catch (...) {
        hy::set_last_error("Backend init failed");
    }
}

JNIEXPORT jstring JNICALL Java_com_hy_assistant_llm_LlamaNative_gpuName(JNIEnv *env, jobject) {
    return env->NewStringUTF(hy::gpu_name().c_str());
}

JNIEXPORT jlong JNICALL Java_com_hy_assistant_llm_LlamaNative_loadModel(JNIEnv *env, jobject, jstring path, jboolean gpu) {
    const char *c_path = env->GetStringUTFChars(path, nullptr);
    if (!c_path) return 0;
    hy::Engine *engine = nullptr;
    try {
        engine = hy::open(c_path, gpu == JNI_TRUE);  // never throws itself, but the release below must always run
    } catch (...) {
        hy::set_last_error("Native error while loading model");
    }
    env->ReleaseStringUTFChars(path, c_path);
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jboolean JNICALL Java_com_hy_assistant_llm_LlamaNative_usesGpu(JNIEnv *, jobject, jlong handle) {
    auto *engine = reinterpret_cast<hy::Engine *>(handle);
    return engine && engine->gpu ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_hy_assistant_llm_LlamaNative_freeModel(JNIEnv *, jobject, jlong handle) {
    hy::close(reinterpret_cast<hy::Engine *>(handle));
}

JNIEXPORT jint JNICALL Java_com_hy_assistant_llm_LlamaNative_generate(
    JNIEnv *env, jobject, jlong handle, jint slot, jbyteArray system, jbyteArray user, jint n_ctx,
    jint n_threads, jint max_tokens, jfloat temperature, jbyteArray grammar, jobject callback) {
    try {
        jclass cb_class = env->GetObjectClass(callback);
        jmethodID on_bytes = env->GetMethodID(cb_class, "onBytes", "([B)Z");
        if (!on_bytes) {
            hy::set_last_error("Callback method not found");
            return -10;
        }

        hy::GenParams params;
        params.n_ctx = n_ctx;
        params.n_threads = n_threads;
        params.max_tokens = max_tokens;
        params.temperature = temperature;
        params.grammar = to_string(env, grammar);

        auto on_text = [&](const std::string &text) -> bool {
            jbyteArray arr = env->NewByteArray((jsize)text.size());
            if (!arr) return false;
            env->SetByteArrayRegion(arr, 0, (jsize)text.size(), reinterpret_cast<const jbyte *>(text.data()));
            jboolean keep_going = env->CallBooleanMethod(callback, on_bytes, arr);
            env->DeleteLocalRef(arr);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                return false;
            }
            return keep_going == JNI_TRUE;
        };

        return hy::generate(reinterpret_cast<hy::Engine *>(handle), slot, to_string(env, system),
                            to_string(env, user), params, on_text);
    } catch (const std::exception &e) {
        hy::set_last_error(std::string("Native error: ") + e.what());
    } catch (...) {
        hy::set_last_error("Native error: unknown exception");
    }
    return -7;
}

JNIEXPORT jint JNICALL Java_com_hy_assistant_llm_LlamaNative_lastReused(JNIEnv *, jobject, jlong handle, jint slot) {
    return hy::last_reused(reinterpret_cast<hy::Engine *>(handle), slot);
}

// [prompt tokens, prompt ms, generated tokens, generation ms] of the slot's last generate().
JNIEXPORT jdoubleArray JNICALL Java_com_hy_assistant_llm_LlamaNative_lastTimings(JNIEnv *env, jobject, jlong handle, jint slot) {
    hy::Timings t = hy::last_timings(reinterpret_cast<hy::Engine *>(handle), slot);
    jdouble values[4] = {(double)t.prompt_tokens, t.prompt_ms, (double)t.gen_tokens, t.gen_ms};
    jdoubleArray arr = env->NewDoubleArray(4);
    if (arr) env->SetDoubleArrayRegion(arr, 0, 4, values);
    return arr;
}

JNIEXPORT jstring JNICALL Java_com_hy_assistant_llm_LlamaNative_lastError(JNIEnv *env, jobject) {
    return env->NewStringUTF(hy::last_error().c_str());
}

}  // extern "C"
