#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#define LOG_TAG "NativeQemuBridge"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {
std::atomic_bool g_running(false);
void *g_qemu_handle = nullptr;

using qemu_init_t = void (*)(int, char **);
using qemu_main_loop_t = void (*)();
using qemu_cleanup_t = void (*)();
using legacy_main_t = int (*)(int, char **, char **);

std::string dl_error_or(const char *fallback) {
    const char *error = dlerror();
    return error ? std::string(error) : std::string(fallback);
}

void free_argv(std::vector<char *> &argv) {
    for (char *arg : argv) {
        std::free(arg);
    }
    argv.clear();
}
} // namespace

extern "C"
JNIEXPORT jstring JNICALL
Java_com_vectras_vm_NativeQemuBridge_start(JNIEnv *env, jclass,
                                            jstring library_path,
                                            jobjectArray java_argv) {
    if (g_running.exchange(true)) {
        return env->NewStringUTF("Native QEMU is already running");
    }

    if (library_path == nullptr || java_argv == nullptr) {
        g_running = false;
        return env->NewStringUTF("Invalid native QEMU arguments");
    }

    const char *library_path_chars = env->GetStringUTFChars(library_path, nullptr);
    if (library_path_chars == nullptr) {
        g_running = false;
        return env->NewStringUTF("Unable to read QEMU library path");
    }

    std::string result;
    std::vector<char *> argv;

    do {
        const jsize argc = env->GetArrayLength(java_argv);
        argv.reserve(static_cast<size_t>(argc) + 1);

        for (jsize i = 0; i < argc; ++i) {
            auto item = static_cast<jstring>(env->GetObjectArrayElement(java_argv, i));
            if (item == nullptr) {
                argv.push_back(strdup(""));
                continue;
            }

            const char *chars = env->GetStringUTFChars(item, nullptr);
            if (chars == nullptr) {
                env->DeleteLocalRef(item);
                result = "Unable to decode a QEMU argument";
                break;
            }

            argv.push_back(strdup(chars));
            env->ReleaseStringUTFChars(item, chars);
            env->DeleteLocalRef(item);
        }

        if (!result.empty()) break;
        argv.push_back(nullptr);

        dlerror();
        g_qemu_handle = dlopen(library_path_chars, RTLD_NOW | RTLD_LOCAL);
        if (g_qemu_handle == nullptr) {
            result = "dlopen failed: " + dl_error_or("unknown error");
            break;
        }

        LOGI("Loaded native QEMU library: %s", library_path_chars);

        dlerror();
        auto qemu_init = reinterpret_cast<qemu_init_t>(dlsym(g_qemu_handle, "qemu_init"));
        const char *init_error = dlerror();

        if (qemu_init != nullptr && init_error == nullptr) {
            dlerror();
            auto qemu_main_loop = reinterpret_cast<qemu_main_loop_t>(
                    dlsym(g_qemu_handle, "qemu_main_loop"));
            const char *loop_error = dlerror();

            dlerror();
            auto qemu_cleanup = reinterpret_cast<qemu_cleanup_t>(
                    dlsym(g_qemu_handle, "qemu_cleanup"));
            const char *cleanup_error = dlerror();

            if (qemu_main_loop == nullptr || loop_error != nullptr) {
                result = "qemu_main_loop symbol unavailable";
                break;
            }
            if (qemu_cleanup == nullptr || cleanup_error != nullptr) {
                result = "qemu_cleanup symbol unavailable";
                break;
            }

            LOGI("Starting QEMU through qemu_init/qemu_main_loop");
            qemu_init(static_cast<int>(argc), argv.data());
            qemu_main_loop();
            qemu_cleanup();
        } else {
            dlerror();
            auto legacy_main = reinterpret_cast<legacy_main_t>(dlsym(g_qemu_handle, "main"));
            const char *main_error = dlerror();
            if (legacy_main == nullptr || main_error != nullptr) {
                result = "Neither qemu_init nor main is exported by the QEMU library";
                break;
            }

            LOGI("Starting QEMU through legacy main()");
            legacy_main(static_cast<int>(argc), argv.data(), nullptr);
        }
    } while (false);

    if (g_qemu_handle != nullptr) {
        dlclose(g_qemu_handle);
        g_qemu_handle = nullptr;
    }

    free_argv(argv);
    env->ReleaseStringUTFChars(library_path, library_path_chars);
    g_running = false;

    if (!result.empty()) {
        LOGE("Native QEMU stopped with loader error: %s", result.c_str());
    } else {
        LOGI("Native QEMU stopped cleanly");
    }

    return env->NewStringUTF(result.c_str());
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_vectras_vm_NativeQemuBridge_isRunning(JNIEnv *, jclass) {
    return g_running.load() ? JNI_TRUE : JNI_FALSE;
}
