cmake_minimum_required(VERSION 3.22.1)
project(genie_frontend)

add_library(openjtalk_native SHARED IMPORTED)
set_target_properties(openjtalk_native PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libopenjtalk_native.so")

add_library(genie_frontend SHARED native_frontend_jni.cpp)
target_compile_features(genie_frontend PRIVATE cxx_std_17)
target_link_libraries(genie_frontend openjtalk_native log)
