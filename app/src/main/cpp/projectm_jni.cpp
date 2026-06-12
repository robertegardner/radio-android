// Thin JNI bridge over the projectM 4.x C API. All functions that touch the
// handle must run on the GL thread (GLSurfaceView.Renderer callbacks or
// queueEvent) — projectM owns GL objects and has no internal locking.
#include <jni.h>
#include <android/log.h>

#include <projectM-4/projectM.h>

#define LOG_TAG "projectm-jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
projectm_handle pm(jlong h) { return reinterpret_cast<projectm_handle>(h); }
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_io_rg2_radio_viz_ProjectMNative_create(JNIEnv*, jclass, jint width, jint height) {
    projectm_handle handle = projectm_create();
    if (handle == nullptr) {
        LOGE("projectm_create failed");
        return 0;
    }
    projectm_set_window_size(handle, static_cast<size_t>(width), static_cast<size_t>(height));
    projectm_set_fps(handle, 60);
    // Modest mesh keeps per-pixel warp cost sane on phone GPUs.
    projectm_set_mesh_size(handle, 48, 32);
    projectm_set_aspect_correction(handle, true);
    // Preset rotation is driven from Kotlin (30 s timer / tap), not by the
    // built-in duration clock — park it effectively at infinity.
    projectm_set_preset_duration(handle, 86400.0);
    projectm_set_soft_cut_duration(handle, 2.7);
    projectm_set_hard_cut_enabled(handle, false);
    LOGI("projectM created (%dx%d)", width, height);
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL
Java_io_rg2_radio_viz_ProjectMNative_destroy(JNIEnv*, jclass, jlong handle) {
    if (handle != 0) projectm_destroy(pm(handle));
}

JNIEXPORT void JNICALL
Java_io_rg2_radio_viz_ProjectMNative_resize(JNIEnv*, jclass, jlong handle, jint width, jint height) {
    if (handle != 0) {
        projectm_set_window_size(pm(handle), static_cast<size_t>(width), static_cast<size_t>(height));
    }
}

JNIEXPORT void JNICALL
Java_io_rg2_radio_viz_ProjectMNative_renderFrame(JNIEnv*, jclass, jlong handle) {
    if (handle != 0) projectm_opengl_render_frame(pm(handle));
}

JNIEXPORT void JNICALL
Java_io_rg2_radio_viz_ProjectMNative_addPcm(JNIEnv* env, jclass, jlong handle, jfloatArray samples) {
    if (handle == 0 || samples == nullptr) return;
    const jsize count = env->GetArrayLength(samples);
    if (count <= 0) return;
    jfloat* data = env->GetFloatArrayElements(samples, nullptr);
    if (data == nullptr) return;
    projectm_pcm_add_float(pm(handle), data, static_cast<unsigned int>(count), PROJECTM_MONO);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_io_rg2_radio_viz_ProjectMNative_loadPreset(JNIEnv* env, jclass, jlong handle,
                                                jstring path, jboolean smooth) {
    if (handle == 0 || path == nullptr) return;
    const char* cpath = env->GetStringUTFChars(path, nullptr);
    if (cpath == nullptr) return;
    // A bad preset logs and falls back to the idle preset; it must not crash.
    projectm_load_preset_file(pm(handle), cpath, smooth == JNI_TRUE);
    env->ReleaseStringUTFChars(path, cpath);
}

} // extern "C"
