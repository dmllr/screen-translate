# Bergamot translator (the Firefox Translations engine) + Marian fork, from mozilla/translations.
# Expects libpcre2.cmake to be included first. CPU only; arm64-v8a and x86_64 Android.
#
#   arm64-v8a: int8 GEMM via ruy, float SGEMM via ruy, simd_utils for SSE emulation (Marian defaults).
#   x86_64:    int8 GEMM via intgemm (runtime-dispatched SSSE3/AVX2/AVX-512), float SGEMM via ruy.
#              Marian's x86 path expects MKL/OpenBLAS for SGEMM and aborts at runtime without one;
#              ruy has x86 AVX2/AVX-512 kernels (cpuinfo-dispatched) and is already in the tree.

if(ANDROID_ABI STREQUAL "arm64-v8a")
	set(BUILD_ARCH "armv8-a" CACHE STRING "" FORCE)
elseif(ANDROID_ABI STREQUAL "x86_64")
	# Marian replaces CMAKE_CXX_FLAGS with -march=${BUILD_ARCH}. The Android x86_64 ABI guarantees
	# SSE4.2 + POPCNT (x86-64-v2); plain "x86-64" would drop to SSE2.
	set(BUILD_ARCH "x86-64-v2" CACHE STRING "" FORCE)
	set(USE_RUY ON CACHE BOOL "" FORCE)
	set(USE_RUY_SGEMM ON CACHE BOOL "" FORCE)
	set(USE_INTGEMM ON CACHE BOOL "" FORCE)
else()
	message(FATAL_ERROR "Unsupported ABI ${ANDROID_ABI}: only arm64-v8a and x86_64 are supported")
endif()

set(COMPILE_CUDA OFF CACHE BOOL "" FORCE)
set(COMPILE_TESTS OFF CACHE BOOL "" FORCE)
set(COMPILE_UNIT_TESTS OFF CACHE BOOL "" FORCE)
set(COMPILE_LIBRARY_ONLY ON CACHE BOOL "" FORCE)
set(COMPILE_SERVER OFF CACHE BOOL "" FORCE)
set(COMPILE_EXAMPLES OFF CACHE BOOL "" FORCE)
set(USE_MKL OFF CACHE BOOL "" FORCE)
set(USE_DOXYGEN OFF CACHE BOOL "" FORCE)
set(USE_NCCL OFF CACHE BOOL "" FORCE)
set(USE_MPI OFF CACHE BOOL "" FORCE)
set(USE_FBGEMM OFF CACHE BOOL "" FORCE)
set(USE_SENTENCEPIECE ON CACHE BOOL "" FORCE)
set(USE_STATIC_LIBS ON CACHE BOOL "" FORCE)
set(SPM_ENABLE_SHARED OFF CACHE BOOL "" FORCE)
set(SPM_ENABLE_TCMALLOC OFF CACHE BOOL "" FORCE)
# Not SPM_BUILD_LIBRARY_ONLY: Marian's CMake references the spm_* tool targets. They are never
# built because the tree is added EXCLUDE_FROM_ALL.
set(SSPLIT_COMPILE_LIBRARY_ONLY ON CACHE BOOL "" FORCE)
set(INTGEMM_DONT_BUILD_TESTS ON CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(GIT_SUBMODULE OFF CACHE BOOL "" FORCE)   # import_remote handles submodules

# Android's bionic provides pthreads in libc. With setup.cmake's STATIC_LIBRARY try-compiles,
# FindThreads cannot link-test that and falls back to a nonexistent -lpthreads.
if(ANDROID)
	set(CMAKE_HAVE_LIBC_PTHREAD 1 CACHE INTERNAL "pthreads are part of bionic libc")
endif()

# The repo also hosts the training pipeline. Skip the submodules inference does not need
# (pre-setting `update = none` makes `git submodule update --init` leave them alone).
set(_bergamot_skip_submodules
	fast_align
	extract-lex
	3rd_party/kenlm
	3rd_party/marian-dev
	3rd_party/preprocess
	inference/3rd_party/emsdk
	inference/marian/src/3rd_party/nccl
	inference/marian/src/3rd_party/fbgemm
	inference/marian/src/3rd_party/simple-websocket-server
	inference/marian/src/3rd_party/onnxjs
)
set(_bergamot_git_config "")
foreach(sm ${_bergamot_skip_submodules})
	list(APPEND _bergamot_git_config "add submodule.${sm}.update none")
endforeach()

import_remote("https://github.com/mozilla/translations.git"
	# main @ 2026-10-29 (8eada038fd45bbf6fa58a412b22f80c79887fabc). Abbreviated: the tag becomes a
	# path component, and Windows builds are close to MAX_PATH.
	TAG "8eada038fd45"
	DST "${REPO_ROOT}/_imported"
	ALIAS translations
	CMAKE "inference"
	# NDK 29 fixes (sentencepiece constexpr enum cast, faiss missing <x86intrin.h> on bionic) and a
	# Bergamot use-after-free when several worker replicas load a model at once (translation_model).
	PATCH "${CMAKE_CURRENT_LIST_DIR}/patches/translations.patch"
	GIT_CONFIG ${_bergamot_git_config}
)

if(ANDROID_ABI STREQUAL "arm64-v8a" AND TARGET bergamot-translator-source)
	# Marian defines ARM/FMA/SSE (simd_utils' SSE-on-NEON emulation) with directory-scoped
	# add_compile_definitions, so code outside marian-fork that includes Marian headers
	# (Bergamot's translator sources and our JNI bridge) would take the x86 SSE path.
	# Make them usage requirements of the Bergamot target instead.
	target_compile_definitions(bergamot-translator-source PUBLIC ARM FMA SSE)
	target_compile_options(bergamot-translator-source PUBLIC -flax-vector-conversions -fno-strict-aliasing)
endif()
