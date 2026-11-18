cmake_minimum_required(VERSION 3.22.1)
project(clipmi_native C CXX)

# Library statis hasil native/build-native.sh (libass, HarfBuzz, FriBidi, FreeType).
if(NOT CLIPMI_NATIVE_PREFIX OR NOT EXISTS "${CLIPMI_NATIVE_PREFIX}/lib/libass.a")
    message(FATAL_ERROR "libass belum dibangun di ${CLIPMI_NATIVE_PREFIX}; jalankan native/build-native.sh (lihat native/README.md)")
endif()

foreach(lib ass harfbuzz fribidi freetype)
    add_library(${lib} STATIC IMPORTED)
    set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION "${CLIPMI_NATIVE_PREFIX}/lib/lib${lib}.a")
endforeach()

add_library(clipmi_native SHARED ass_jni.c text_measure.c)
target_include_directories(clipmi_native PRIVATE "${CLIPMI_NATIVE_PREFIX}/include" "${CLIPMI_NATIVE_PREFIX}/include/freetype2")
target_compile_options(clipmi_native PRIVATE -O2 -Wall)
# Urutan penting untuk library statis: libass butuh harfbuzz/fribidi/freetype, harfbuzz butuh freetype.
target_link_libraries(clipmi_native ass harfbuzz fribidi freetype jnigraphics log m)
target_link_options(clipmi_native PRIVATE "-Wl,-z,max-page-size=16384" "-Wl,--gc-sections")
