# PCRE2, required by ssplit-cpp (Bergamot's sentence splitter).
#
# Built as a regular subproject: ssplit's own "internal PCRE2" mode downloads a tarball and runs a
# nested CMake without passing the generator, which breaks under Gradle on Windows. ssplit gets
# this build through its "system PCRE2" path (find_library/find_path keep pre-set cache values).
set(PCRE2_BUILD_PCRE2_8 ON CACHE BOOL "" FORCE)
set(PCRE2_BUILD_PCRE2_16 OFF CACHE BOOL "" FORCE)
set(PCRE2_BUILD_PCRE2_32 OFF CACHE BOOL "" FORCE)
set(PCRE2_BUILD_PCRE2GREP OFF CACHE BOOL "" FORCE)
set(PCRE2_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(PCRE2_SUPPORT_JIT OFF CACHE BOOL "" FORCE)   # sentence splitting is cheap; avoids W^X issues
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_STATIC_LIBS ON CACHE BOOL "" FORCE)

import_remote("https://github.com/PCRE2Project/pcre2.git"
	TAG "pcre2-10.39"
	DST "${REPO_ROOT}/_imported"
	ALIAS pcre2
)

# pcre2.h is generated into the binary dir.
set(SSPLIT_USE_INTERNAL_PCRE2 OFF CACHE BOOL "" FORCE)
set(PCRE2_LIBRARIES pcre2-8-static CACHE STRING "" FORCE)
set(PCRE2_INCLUDE_DIRS "${pcre2_binary_path}" CACHE PATH "" FORCE)
set(PCRE2_FOUND TRUE CACHE BOOL "" FORCE)
