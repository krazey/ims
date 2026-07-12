cmake_minimum_required(VERSION 3.10.2)

add_library(
    rnnoise_jni
    SHARED
    rnnoise_jni.c
    rnnoise/src/denoise.c
    rnnoise/src/kiss_fft.c
    rnnoise/src/rnn.c
    rnnoise/src/rnn_data.c
    rnnoise/src/pitch.c
    rnnoise/src/celt_lpc.c)
target_link_libraries(rnnoise_jni PRIVATE m log)
target_include_directories(rnnoise_jni PUBLIC rnnoise/include)

set(WEBRTC_AEC3_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/webrtc-aec3")
if(NOT EXISTS "${WEBRTC_AEC3_ROOT}/api/echo_canceller3_factory.h")
    message(FATAL_ERROR
        "Missing app/jni/webrtc-aec3. Run git submodule update --init --recursive")
endif()

file(GLOB WEBRTC_AEC3_SOURCES
    "${WEBRTC_AEC3_ROOT}/api/*.cc"
    "${WEBRTC_AEC3_ROOT}/audio_processing/*.cc"
    "${WEBRTC_AEC3_ROOT}/audio_processing/*.c"
    "${WEBRTC_AEC3_ROOT}/audio_processing/aec3/*.cc"
    "${WEBRTC_AEC3_ROOT}/audio_processing/include/*.cc"
    "${WEBRTC_AEC3_ROOT}/audio_processing/logging/*.cc"
    "${WEBRTC_AEC3_ROOT}/audio_processing/resampler/*.cc"
    "${WEBRTC_AEC3_ROOT}/base/system_wrappers/source/*.c"
    "${WEBRTC_AEC3_ROOT}/base/system_wrappers/source/*.cc"
    "${WEBRTC_AEC3_ROOT}/base/rtc_base/memory/*.cc"
    "${WEBRTC_AEC3_ROOT}/base/rtc_base/*.cc"
    "${WEBRTC_AEC3_ROOT}/base/rtc_base/strings/*.cc"
    "${WEBRTC_AEC3_ROOT}/base/rtc_base/system/*.cc"
    "${WEBRTC_AEC3_ROOT}/base/jsoncpp/src/lib_json/*.cpp")

list(APPEND WEBRTC_AEC3_SOURCES
    "${WEBRTC_AEC3_ROOT}/audio_processing/utility/cascaded_biquad_filter.cc"
    "${WEBRTC_AEC3_ROOT}/audio_processing/utility/ooura_fft.cc")

set(WEBRTC_AEC3_FILTERED_SOURCES)
foreach(source IN LISTS WEBRTC_AEC3_SOURCES)
    if(NOT source MATCHES "(unit)?test\\.cc$" AND
       NOT source MATCHES "_(avx2|mips|neon|sse2)\\.cc$" AND
       NOT source MATCHES "/cpu_features_linux\\.c$")
        list(APPEND WEBRTC_AEC3_FILTERED_SOURCES "${source}")
    endif()
endforeach()
set(WEBRTC_AEC3_SOURCES ${WEBRTC_AEC3_FILTERED_SOURCES})

if(ANDROID_ABI MATCHES "^(x86|x86_64)$" OR
   CMAKE_SYSTEM_PROCESSOR MATCHES "^(i.86|x86|x86_64|amd64|AMD64)$")
    file(GLOB_RECURSE WEBRTC_AEC3_SSE2_SOURCES
        "${WEBRTC_AEC3_ROOT}/audio_processing/*_sse2.cc")
    list(APPEND WEBRTC_AEC3_SOURCES ${WEBRTC_AEC3_SSE2_SOURCES})
endif()

if(DEFINED CMAKE_ANDROID_NDK)
    set(PHHIMS_ANDROID_NDK "${CMAKE_ANDROID_NDK}")
elseif(DEFINED ANDROID_NDK)
    set(PHHIMS_ANDROID_NDK "${ANDROID_NDK}")
else()
    message(FATAL_ERROR "Android NDK path is unavailable")
endif()

set(
    ANDROID_CPUFEATURES_ROOT
    "${PHHIMS_ANDROID_NDK}/sources/android/cpufeatures")
if(NOT EXISTS "${ANDROID_CPUFEATURES_ROOT}/cpu-features.c")
    message(FATAL_ERROR
        "Android NDK cpufeatures source is unavailable: "
        "${ANDROID_CPUFEATURES_ROOT}")
endif()

add_library(
    webrtc_aec_jni
    SHARED
    webrtc_aec_jni.cpp
    "${ANDROID_CPUFEATURES_ROOT}/cpu-features.c"
    ${WEBRTC_AEC3_SOURCES})

set_target_properties(
    webrtc_aec_jni
    PROPERTIES
    CXX_STANDARD 20
    CXX_STANDARD_REQUIRED YES)

target_compile_definitions(
    webrtc_aec_jni
    PRIVATE
    WEBRTC_ANDROID
    WEBRTC_LINUX
    WEBRTC_POSIX
    NDEBUG)

target_compile_options(
    webrtc_aec_jni
    PRIVATE
    $<$<COMPILE_LANGUAGE:CXX>:-fexceptions>
    $<$<COMPILE_LANGUAGE:CXX>:-Wno-deprecated-volatile>
    $<$<COMPILE_LANGUAGE:CXX>:-Wno-implicit-fallthrough>
    -Wno-deprecated-declarations
    -Wno-unused-parameter
    -Wno-unused-variable)

target_include_directories(
    webrtc_aec_jni
    PRIVATE
    "${ANDROID_CPUFEATURES_ROOT}"
    "${WEBRTC_AEC3_ROOT}"
    "${WEBRTC_AEC3_ROOT}/api"
    "${WEBRTC_AEC3_ROOT}/base"
    "${WEBRTC_AEC3_ROOT}/base/rtc_base"
    "${WEBRTC_AEC3_ROOT}/base/system_wrappers/include"
    "${WEBRTC_AEC3_ROOT}/base/abseil"
    "${WEBRTC_AEC3_ROOT}/base/jsoncpp/include"
    "${WEBRTC_AEC3_ROOT}/audio_processing"
    "${WEBRTC_AEC3_ROOT}/audio_processing/aec3"
    "${WEBRTC_AEC3_ROOT}/audio_processing/include"
    "${WEBRTC_AEC3_ROOT}/audio_processing/logging"
    "${WEBRTC_AEC3_ROOT}/audio_processing/resampler"
    "${WEBRTC_AEC3_ROOT}/audio_processing/utility")

target_link_libraries(webrtc_aec_jni PRIVATE log m)
