cmake_minimum_required(VERSION 3.22.1)
project(wekit-zygisk)

macro(strip_and_move NAME)
    add_custom_command(TARGET ${NAME} POST_BUILD
            COMMAND ${CMAKE_COMMAND} -E make_directory ${DEBUG_SYMBOLS_PATH}
            COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:${NAME}> ${DEBUG_SYMBOLS_PATH}/$<TARGET_FILE_NAME:${NAME}>
            COMMAND ${CMAKE_STRIP} --strip-all $<TARGET_FILE:${NAME}>
    )
endmacro()

set(CXX_FLAGS "${CXX_FLAGS} -fno-exceptions -fno-rtti -fvisibility=hidden -fvisibility-inlines-hidden")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} ${CXX_FLAGS}")

add_library(${MODULE_NAME} SHARED
    main.cpp
    art_hook.cpp
    so_hider.cpp
)

target_link_libraries(${MODULE_NAME} PRIVATE log android)
strip_and_move(${MODULE_NAME})
