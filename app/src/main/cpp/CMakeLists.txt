cmake_minimum_required(VERSION 3.22.1)
project(paperlight_kindle C)
add_library(paperlight_kindle SHARED kindle.c
    libmobi/buffer.c libmobi/compression.c libmobi/debug.c libmobi/index.c
    libmobi/memory.c libmobi/meta.c libmobi/parse_rawml.c libmobi/read.c
    libmobi/structure.c libmobi/util.c libmobi/write.c)
target_compile_definitions(paperlight_kindle PRIVATE PACKAGE_VERSION="0.12")
target_include_directories(paperlight_kindle PRIVATE libmobi)
target_link_libraries(paperlight_kindle PRIVATE z)
