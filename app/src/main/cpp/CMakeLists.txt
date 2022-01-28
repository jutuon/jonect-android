# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at https://mozilla.org/MPL/2.0/.


cmake_minimum_required(VERSION 3.10.2)

project("jonect_android_cpp") # Set CMake project name.

add_library( jonect_android_cpp # Set library target name.
             SHARED             # Compile shared library.
             # Code files.
             jonect.cpp)

target_compile_options(jonect_android_cpp PRIVATE -Wall -Wextra -Ofast)

# Build Oboe and import it's headers.
add_subdirectory (./oboe)
include_directories (./oboe/include)

target_link_libraries(  jonect_android_cpp
                        # Link to the following libraries.
                        log
                        oboe)
