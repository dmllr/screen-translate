# Setup CMake for the project (same conventions as eillance/src/cmake/setup.cmake)

execute_process(
  COMMAND git rev-parse --show-toplevel
  WORKING_DIRECTORY "${CMAKE_CURRENT_LIST_DIR}"
  OUTPUT_VARIABLE REPO_ROOT
  OUTPUT_STRIP_TRAILING_WHITESPACE
  ERROR_QUIET
)
# Not (yet) a git checkout, e.g. a source archive: the repo root is two levels above src/cmake.
if("${REPO_ROOT}" STREQUAL "")
  get_filename_component(REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../.." ABSOLUTE)
endif()

# Export to all directories
set(REPO_ROOT "${REPO_ROOT}" CACHE INTERNAL "Git repository root")

# Windows hosts: sources imported outside the CMake source tree get object file names that embed
# their full path, which exceeds MAX_PATH. That needs Windows long-path support plus a long-path
# aware Ninja (1.12+, shipped with the SDK's CMake 4.x).
if(CMAKE_HOST_WIN32)
  cmake_host_system_information(RESULT _long_paths
    QUERY WINDOWS_REGISTRY "HKLM/SYSTEM/CurrentControlSet/Control/FileSystem" VALUE "LongPathsEnabled")
  if(NOT "${_long_paths}" STREQUAL "1")
    message(WARNING
      "Windows long paths are disabled; the build will likely fail with 'Filename longer than 260 "
      "characters'. Enable them (admin PowerShell):\n"
      "  Set-ItemProperty HKLM:\\SYSTEM\\CurrentControlSet\\Control\\FileSystem LongPathsEnabled 1")
  endif()
endif()

# Some imported projects (e.g. PCRE2 10.39) declare cmake_minimum_required < 3.5, which CMake 4
# rejects unless a policy floor is given.
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)

enable_language(C)
enable_language(CXX)

set(CMAKE_TRY_COMPILE_TARGET_TYPE "STATIC_LIBRARY")
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 17)
set(CMAKE_C_STANDARD_REQUIRED ON)

# Policies
set(CMAKE_POLICY_DEFAULT_CMP0077 NEW)
cmake_policy(SET CMP0063 NEW)
cmake_policy(SET CMP0077 NEW)
if (CMAKE_VERSION VERSION_GREATER_EQUAL "3.24.0")
  cmake_policy(SET CMP0135 NEW)
endif()

include("${CMAKE_CURRENT_LIST_DIR}/import_remote.cmake")
