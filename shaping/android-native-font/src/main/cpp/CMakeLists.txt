cmake_minimum_required(VERSION 3.22...4.3)
project(tiqian_native_font LANGUAGES C CXX)

# HarfBuzz 10.4 still declares compatibility with pre-3.5 CMake. CMake 4
# requires callers to opt into a supported policy floor before adding it.
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)

# Pinned upstream release archives. These sources are compiled into the AAR;
# no Android private Minikin/HarfBuzz symbol is linked or called.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(FT_DISABLE_ZLIB ON CACHE BOOL "" FORCE)
set(FT_DISABLE_BZIP2 ON CACHE BOOL "" FORCE)
set(FT_DISABLE_PNG ON CACHE BOOL "" FORCE)
set(FT_DISABLE_HARFBUZZ ON CACHE BOOL "" FORCE)
set(FT_DISABLE_BROTLI ON CACHE BOOL "" FORCE)
FetchContent_Declare(
    freetype
    URL https://codeload.github.com/freetype/freetype/tar.gz/refs/tags/VER-2-13-3
    URL_HASH SHA256=bc5c898e4756d373e0d991bab053036c5eb2aa7c0d5c67e8662ddc6da40c4103
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(freetype)

set(HB_HAVE_FREETYPE ON CACHE BOOL "" FORCE)
set(HB_BUILD_UTILS OFF CACHE BOOL "" FORCE)
set(HB_BUILD_SUBSET OFF CACHE BOOL "" FORCE)
set(HB_HAVE_GLIB OFF CACHE BOOL "" FORCE)
set(HB_HAVE_GOBJECT OFF CACHE BOOL "" FORCE)
set(HB_HAVE_ICU OFF CACHE BOOL "" FORCE)
set(HB_HAVE_GRAPHITE2 OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    harfbuzz
    URL https://codeload.github.com/harfbuzz/harfbuzz/tar.gz/refs/tags/10.4.0
    URL_HASH SHA256=0d25a3f74af4e8744700ac19050af5a80ae330378a5802a5cd71e523bb6fda1f
    DOWNLOAD_EXTRACT_TIMESTAMP TRUE
)
FetchContent_MakeAvailable(harfbuzz)

add_library(tiqian_native_font SHARED tiqian_native_font.cpp)
target_link_libraries(tiqian_native_font PRIVATE harfbuzz freetype android log)
