cmake_minimum_required(VERSION 3.22)
# VERSION must match the embedded projectM submodule: its version.h.in is
# configured from CMAKE_PROJECT_VERSION_* — the TOP-LEVEL project's version,
# i.e. ours. Without it the PROJECTM_VERSION_* macros generate empty and
# ProjectMCWrapper.cpp fails to compile.
project(projectm-jni VERSION 4.1.6 LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# libprojectM (git submodule at third_party/projectm), linked statically into
# our single JNI .so. ENABLE_GLES is not set here: projectM's own
# cmake_dependent_option force-enables it when CMAKE_SYSTEM_NAME is Android.
set(ENABLE_PLAYLIST OFF CACHE BOOL "" FORCE)   # preset rotation is Kotlin-side
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

add_subdirectory(
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/projectm
    ${CMAKE_CURRENT_BINARY_DIR}/projectm
)

add_library(projectm-jni SHARED projectm_jni.cpp)

# projectM's root CMakeLists caches CMAKE_DEBUG_POSTFIX "d", which would
# rename debug builds to libprojectm-jnid.so and break System.loadLibrary.
set_target_properties(projectm-jni PROPERTIES DEBUG_POSTFIX "")

target_link_libraries(projectm-jni
    PRIVATE
        libprojectM::projectM
        android
        log
        GLESv3
        EGL
)
