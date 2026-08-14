cmake_minimum_required(VERSION 3.22)
project(whisper_jni)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)
FetchContent_Declare(
    whispercpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG        master
    GIT_SHALLOW    TRUE
)

# Cross-compiling to Android: never let ggml probe/target the *host* CPU's
# instruction set (-march=native), and skip pieces of whisper.cpp/ggml we
# don't need in an embedded JNI build.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)

# With GGML_NATIVE off and no explicit ARM arch, ggml's CMake (ggml-cpu/CMakeLists.txt)
# appends NO -march flags at all for the ARM build — it silently falls back to the compiler's
# baseline armv8-a, without dotprod/fp16, which whisper.cpp's quantized matmul kernels (the
# dominant inference cost) rely on for anything close to reasonable speed. This is a very
# plausible root cause of severely slow transcription on real devices (confirmed independently
# in CI: even a 4-second clip on the tiny model exceeded a 120s budget). armv8.2-a+dotprod+fp16
# is supported by essentially every Android device from ~2018 onward; it's not enabled via
# GGML_NATIVE (which would probe the *build host's* CPU, wrong for cross-compilation) but
# directly via GGML_CPU_ARM_ARCH, which only affects the arm64-v8a build (ggml's CMake only
# reads it inside its ARM-specific branch, so the x86_64 CI build is unaffected).
# Trade-off: a device older than ~2017 (ARMv8.0, no dotprod) would crash with SIGILL running
# this build. Proper per-device runtime dispatch (GGML_CPU_ALL_VARIANTS + GGML_BACKEND_DL,
# building multiple .so variants and loading the best match at runtime) is what upstream
# actually designs for this, but requires restructuring to shared native libs and wiring a
# runtime backend-loading call from Kotlin — not attempted here without real-device testing to
# verify it end to end.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_CURL OFF CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)

FetchContent_MakeAvailable(whispercpp)

add_library(whisper_jni SHARED whisper_jni.cpp)

target_include_directories(whisper_jni PRIVATE
    ${whispercpp_SOURCE_DIR}/include
    ${whispercpp_SOURCE_DIR}/ggml/include
)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(whisper_jni whisper ggml ${log-lib} ${android-lib})
