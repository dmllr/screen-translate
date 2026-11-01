# fastText, used only for language identification (lid.176). Its own CMake builds the CLI and a
# shared library; we only need the core sources as a static library.
import_remote("https://github.com/facebookresearch/fastText.git"
	TAG "v0.9.2"
	DST "${REPO_ROOT}/_imported"
	ALIAS fasttext
	NO_CMAKE
)

if(NOT TARGET fasttext_static)
	file(GLOB FASTTEXT_SOURCES "${fasttext_path}/src/*.cc")
	list(FILTER FASTTEXT_SOURCES EXCLUDE REGEX ".*/main\\.cc$")
	add_library(fasttext_static STATIC ${FASTTEXT_SOURCES})
	target_include_directories(fasttext_static PUBLIC "${fasttext_path}/src")
	target_compile_features(fasttext_static PUBLIC cxx_std_17)
	target_compile_options(fasttext_static PRIVATE -O3 -fPIC -Wno-deprecated-declarations)
endif()
