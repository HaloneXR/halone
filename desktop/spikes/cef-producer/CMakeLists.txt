cmake_minimum_required(VERSION 3.21)
project(halone_cef_spike LANGUAGES C CXX OBJC OBJCXX)
set(CMAKE_OBJCXX_STANDARD 20)

file(GLOB CEF_ROOT LIST_DIRECTORIES true "${CMAKE_CURRENT_SOURCE_DIR}/third_party/cef_binary_*_macosarm64")
if(NOT CEF_ROOT)
  message(FATAL_ERROR "Run ./fetch-cef.sh first")
endif()
set(PROJECT_ARCH arm64)
set(USE_SANDBOX OFF)
set(CMAKE_MODULE_PATH ${CMAKE_MODULE_PATH} "${CEF_ROOT}/cmake")
find_package(CEF REQUIRED)
add_subdirectory(${CEF_LIBCEF_DLL_WRAPPER_PATH} libcef_dll_wrapper)

set(CEF_TARGET "Halone CEF Spike")
set(CEF_HELPER_TARGET "Halone_CEF_Spike_Helper")
set(CEF_HELPER_OUTPUT_NAME "Halone CEF Spike Helper")
SET_CEF_TARGET_OUT_DIR()
list(APPEND CEF_COMPILER_FLAGS -fobjc-arc)
set(CEF_APP "${CEF_TARGET_OUT_DIR}/${CEF_TARGET}.app")

add_executable(producer MACOSX_BUNDLE producer.mm)
SET_EXECUTABLE_TARGET_PROPERTIES(producer)
add_dependencies(producer libcef_dll_wrapper)
target_include_directories(producer PRIVATE ${CEF_ROOT} ${CMAKE_CURRENT_SOURCE_DIR}/../../native)
target_link_libraries(producer libcef_dll_wrapper ${CEF_STANDARD_LIBS} "-framework Metal" "-framework IOSurface")
set(EXECUTABLE_NAME "${CEF_TARGET}")
set(PRODUCT_NAME "${CEF_TARGET}")
configure_file(mac/Info.plist.in "${CMAKE_CURRENT_BINARY_DIR}/Info.plist")
set_target_properties(producer PROPERTIES
  OUTPUT_NAME "${CEF_TARGET}"
  MACOSX_BUNDLE_INFO_PLIST "${CMAKE_CURRENT_BINARY_DIR}/Info.plist")
COPY_MAC_FRAMEWORK(producer "${CEF_BINARY_DIR}" "${CEF_APP}")

foreach(_suffix_list ${CEF_HELPER_APP_SUFFIXES})
  string(REPLACE ":" ";" _suffix_list ${_suffix_list})
  list(GET _suffix_list 0 _name_suffix)
  list(GET _suffix_list 1 _target_suffix)
  list(GET _suffix_list 2 _plist_suffix)
  set(_helper_target "${CEF_HELPER_TARGET}${_target_suffix}")
  set(_helper_output_name "${CEF_HELPER_OUTPUT_NAME}${_name_suffix}")
  set(EXECUTABLE_NAME "${_helper_output_name}")
  set(PRODUCT_NAME "${_helper_output_name}")
  set(BUNDLE_ID_SUFFIX "${_plist_suffix}")
  set(_helper_info_plist "${CMAKE_CURRENT_BINARY_DIR}/helper-Info${_target_suffix}.plist")
  configure_file(mac/helper-Info.plist.in "${_helper_info_plist}")
  add_executable(${_helper_target} MACOSX_BUNDLE helper.mm)
  SET_EXECUTABLE_TARGET_PROPERTIES(${_helper_target})
  add_dependencies(${_helper_target} libcef_dll_wrapper)
  target_include_directories(${_helper_target} PRIVATE ${CEF_ROOT})
  target_link_libraries(${_helper_target} libcef_dll_wrapper ${CEF_STANDARD_LIBS})
  set_target_properties(${_helper_target} PROPERTIES
    MACOSX_BUNDLE_INFO_PLIST ${_helper_info_plist}
    OUTPUT_NAME ${_helper_output_name})
  add_dependencies(producer "${_helper_target}")
  add_custom_command(TARGET producer POST_BUILD
    COMMAND ${CMAKE_COMMAND} -E copy_directory
            "${CEF_TARGET_OUT_DIR}/${_helper_output_name}.app"
            "${CEF_APP}/Contents/Frameworks/${_helper_output_name}.app"
    VERBATIM)
endforeach()

add_executable(consumer consumer.m)
target_compile_options(consumer PRIVATE -fobjc-arc -Wall -mmacosx-version-min=14.0)
target_include_directories(consumer PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/../../native)
target_link_libraries(consumer "-framework Cocoa" "-framework OpenGL" "-framework IOSurface" "-framework QuartzCore")
set_target_properties(consumer PROPERTIES RUNTIME_OUTPUT_DIRECTORY "${CEF_TARGET_OUT_DIR}")
