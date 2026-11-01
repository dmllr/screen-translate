cmake_minimum_required(VERSION 4.0)
project(screentranslate_native CXX C)

# Native code is always built optimized: Marian at -O0 is ~20x slower and useless even for
# debugging the Kotlin side. Java/Kotlin debug builds are unaffected.
set(CMAKE_BUILD_TYPE Release)

# Shared CMake conventions + import_remote(); third-party repos are cloned (pinned) into
# ${REPO_ROOT}/_imported on first configure and shared by all ABIs.
set(PROJECT_CMAKE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../../../cmake")
include("${PROJECT_CMAKE_DIR}/setup.cmake")
include("${PROJECT_CMAKE_DIR}/libpcre2.cmake")
include("${PROJECT_CMAKE_DIR}/libbergamot.cmake")
include("${PROJECT_CMAKE_DIR}/libfasttext.cmake")

# ---------------------------------------------------------------------------
# JNI library
# ---------------------------------------------------------------------------
add_library(screentranslate SHARED
  jni/bergamot_jni.cpp
  jni/fasttext_jni.cpp
  jni/log_redirect.cpp
)
# C++17, not setup.cmake's C++20: Bergamot's headers pull in CLI11, which uses std::result_of.
set_target_properties(screentranslate PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
target_link_libraries(screentranslate PRIVATE bergamot-translator-source fasttext_static android log)
target_link_options(screentranslate PRIVATE
  -Wl,--exclude-libs,ALL         # keep the static libraries' ~10k symbols private; only JNI is exported
  -Wl,--gc-sections
  -Wl,-z,max-page-size=16384     # Android 15+ 16 KB page size devices
)
