# Sets the minimum CMake version required.
cmake_minimum_required(VERSION 3.22.1)

# Declares the project name.
project("mantra_matcher_project")

# Creates the shared library "mantra_matcher" from its source file.
add_library(mantra_matcher SHARED
        audio_matcher.cpp)

# Find the Android logging library (liblog).
find_library(log-lib log)

# Link mantra_matcher against the logging library.
if(log-lib)
    target_link_libraries(mantra_matcher ${log-lib})
else()
    message(WARNING "Android log library not found. mantra_matcher might not link correctly.")
endif()