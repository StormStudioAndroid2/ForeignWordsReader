cmake_minimum_required(VERSION 3.22.1)

project(foreignwords_udpipe LANGUAGES CXX)

set(UDPIPE_ROOT "${CMAKE_CURRENT_LIST_DIR}/upstream/src")

file(GLOB_RECURSE UDPIPE_SOURCES CONFIGURE_DEPENDS
    "${UDPIPE_ROOT}/*.cpp")

list(FILTER UDPIPE_SOURCES EXCLUDE REGEX ".*/rest_server/.*")
list(FILTER UDPIPE_SOURCES EXCLUDE REGEX ".*/udpipe\\.cpp$")
list(FILTER UDPIPE_SOURCES EXCLUDE REGEX ".*/morphodita/derivator/derivator_dictionary_encoder\\.cpp$")

add_library(foreignwords_udpipe SHARED
    ${UDPIPE_SOURCES}
    "${CMAKE_CURRENT_LIST_DIR}/adapter/udpipe_adapter.cpp"
    "${CMAKE_CURRENT_LIST_DIR}/adapter/udpipe_jni.cpp")

target_include_directories(foreignwords_udpipe PRIVATE
    "${UDPIPE_ROOT}"
    "${CMAKE_CURRENT_LIST_DIR}/adapter")

target_compile_features(foreignwords_udpipe PRIVATE cxx_std_11)
target_compile_options(foreignwords_udpipe PRIVATE
    -fexceptions
    -frtti
    -Wno-unused-parameter
    -Wno-sign-compare)
