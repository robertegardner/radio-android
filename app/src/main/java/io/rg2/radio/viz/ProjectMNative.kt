package io.rg2.radio.viz

/**
 * JNI surface for libprojectM (see `app/src/main/cpp/projectm_jni.cpp`).
 * Every call taking a handle must run on the GL thread — projectM owns GL
 * objects and is not internally synchronized. [MilkdropRenderer] is the only
 * intended caller.
 */
object ProjectMNative {
    /**
     * Whether the native library loaded. False on ABIs we don't build for —
     * callers must check this before any other call, or the externals throw.
     */
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("projectm-jni") }.isSuccess
    }

    external fun create(width: Int, height: Int): Long
    external fun destroy(handle: Long)
    external fun resize(handle: Long, width: Int, height: Int)
    external fun renderFrame(handle: Long)
    external fun addPcm(handle: Long, samples: FloatArray)
    external fun loadPreset(handle: Long, path: String, smooth: Boolean)
}
