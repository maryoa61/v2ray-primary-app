# app/src/main/cpp/CMakeLists.txt
#
# FIX: Includes race condition patch for hev-socks5-tunnel v2.15.0
# See: https://github.com/heiher/hev-socks5-tunnel/issues/XXX
#
# The bug: hev_socks5_tunnel_run() sets `run = 1` AFTER starting tasks.
# On 2nd+ session, tasks see run==0 from previous session and exit immediately.
# Fix: move `run = 1` before task starts.

cmake_minimum_required(VERSION 3.22.1)
project(hev_socks5_tunnel_jni C)

include(ExternalProject)

set(HEV_TUNNEL_SRC_DIR ${CMAKE_CURRENT_BINARY_DIR}/hev-socks5-tunnel-src)
set(HEV_TUNNEL_GIT_TAG "2.15.0")

find_program(MAKE_EXECUTABLE
    NAMES make
    PATHS ${ANDROID_NDK}/prebuilt/${ANDROID_HOST_TAG}/bin
          ${CMAKE_ANDROID_NDK}/prebuilt/${ANDROID_HOST_TAG}/bin
    NO_DEFAULT_PATH
)
if(NOT MAKE_EXECUTABLE)
    find_program(MAKE_EXECUTABLE make)
endif()
if(NOT MAKE_EXECUTABLE)
    message(FATAL_ERROR "hev-socks5-tunnel build requires `make` but none was found.")
endif()

# Write the fix script to a temp file during configure
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/fix_hev_tunnel.py [=[
#!/usr/bin/env python3
import sys
filepath = sys.argv[1]
with open(filepath, 'r') as f:
    c = f.read()
# The fix: move `run = 1;` from after task starts to before
# Match both tab and space indentation
for sep in ['\t', '    ']:
    old_block = (
        f'{sep}hev_task_run (task_lwip_timer, lwip_timer_task_entry, NULL);\n'
        f'\n'
        f'{sep}run = 1;\n'
        f'{sep}hev_task_system_run ();'
    )
    new_block = (
        f'{sep}run = 1;\n'
        f'\n'
        f'{sep}hev_task_run (task_lwip_timer, lwip_timer_task_entry, NULL);\n'
        f'\n'
        f'{sep}hev_task_system_run ();'
    )
    if old_block in c:
        c = c.replace(old_block, new_block)
        with open(filepath, 'w') as f:
            f.write(c)
        print(f"✅ Race condition fixed in {filepath}")
        sys.exit(0)
print(f"❌ Pattern not found — file may already be patched or version mismatch")
sys.exit(1)
]=])

# Run the patch steps from a separate shell script instead of an inline
# multi-line quoted string. A multi-line PATCH_COMMAND with embedded quotes
# makes CMake generate an invalid build.ninja on some CMake versions
# (ninja: error: build.ninja:N: expected '=', got lexing error).
file(WRITE ${CMAKE_CURRENT_BINARY_DIR}/patch_hev_tunnel.sh "
#!/bin/sh
set -e
printf '' > '${HEV_TUNNEL_SRC_DIR}/src/hev-jni.c'
python3 '${CMAKE_CURRENT_BINARY_DIR}/fix_hev_tunnel.py' '${HEV_TUNNEL_SRC_DIR}/src/hev-socks5-tunnel.c'
")

ExternalProject_Add(
    hev_socks5_tunnel_upstream
    GIT_REPOSITORY https://github.com/heiher/hev-socks5-tunnel.git
    GIT_TAG        ${HEV_TUNNEL_GIT_TAG}
    GIT_SUBMODULES_RECURSE ON
    SOURCE_DIR     ${HEV_TUNNEL_SRC_DIR}
    CONFIGURE_COMMAND ""
    # 1. Blank out upstream's JNI wrapper (symbol name mismatch)
    # 2. Fix race condition: move `run = 1` before task starts
    PATCH_COMMAND bash ${CMAKE_CURRENT_BINARY_DIR}/patch_hev_tunnel.sh
    BUILD_IN_SOURCE 1
    BUILD_COMMAND
        ${MAKE_EXECUTABLE} static
             CC=${CMAKE_C_COMPILER}
             AR=${CMAKE_AR}
             STRIP=${CMAKE_STRIP}
             "CFLAGS=--target=${CMAKE_C_COMPILER_TARGET} --sysroot=${CMAKE_SYSROOT} ${CMAKE_C_FLAGS} -fPIC -O2 -DNDEBUG -DFD_SET_DEFINED -DSOCKLEN_T_DEFINED"
    INSTALL_COMMAND ""
    BUILD_BYPRODUCTS
        ${HEV_TUNNEL_SRC_DIR}/bin/libhev-socks5-tunnel.a
        ${HEV_TUNNEL_SRC_DIR}/third-part/lwip/bin/liblwip.a
        ${HEV_TUNNEL_SRC_DIR}/third-part/yaml/bin/libyaml.a
        ${HEV_TUNNEL_SRC_DIR}/third-part/hev-task-system/bin/libhev-task-system.a
)

add_library(hev-socks5-tunnel-core STATIC IMPORTED)
set_target_properties(hev-socks5-tunnel-core PROPERTIES
    IMPORTED_LOCATION ${HEV_TUNNEL_SRC_DIR}/bin/libhev-socks5-tunnel.a
)
add_dependencies(hev-socks5-tunnel-core hev_socks5_tunnel_upstream)

add_library(hev-socks5-tunnel-lwip STATIC IMPORTED)
set_target_properties(hev-socks5-tunnel-lwip PROPERTIES
    IMPORTED_LOCATION ${HEV_TUNNEL_SRC_DIR}/third-part/lwip/bin/liblwip.a
)
add_dependencies(hev-socks5-tunnel-lwip hev_socks5_tunnel_upstream)

add_library(hev-socks5-tunnel-yaml STATIC IMPORTED)
set_target_properties(hev-socks5-tunnel-yaml PROPERTIES
    IMPORTED_LOCATION ${HEV_TUNNEL_SRC_DIR}/third-part/yaml/bin/libyaml.a
)
add_dependencies(hev-socks5-tunnel-yaml hev_socks5_tunnel_upstream)

add_library(hev-socks5-tunnel-task STATIC IMPORTED)
set_target_properties(hev-socks5-tunnel-task PROPERTIES
    IMPORTED_LOCATION ${HEV_TUNNEL_SRC_DIR}/third-part/hev-task-system/bin/libhev-task-system.a
)
add_dependencies(hev-socks5-tunnel-task hev_socks5_tunnel_upstream)

add_library(hev-socks5-tunnel SHARED
    jni_bridge.c
)

find_library(log-lib log)

target_link_libraries(hev-socks5-tunnel
    -Wl,--start-group
    hev-socks5-tunnel-core
    hev-socks5-tunnel-lwip
    hev-socks5-tunnel-yaml
    hev-socks5-tunnel-task
    -Wl,--end-group
    ${log-lib}
)
