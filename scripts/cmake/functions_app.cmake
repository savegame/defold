defold_log("functions_app.cmake:")

# Link application-level system frameworks/libs similar to waf's
# FRAMEWORK_APP, STLIB_APP, LIB_APP, LINKFLAGS_APP selections.
#
# Usage:
#   defold_target_link_app(<target> <platform> [SCOPE <PRIVATE|PUBLIC|INTERFACE>])

function(defold_target_link_app target platform)
  set(options)
  set(oneValueArgs SCOPE)
  set(multiValueArgs)
  cmake_parse_arguments(DAPP "${options}" "${oneValueArgs}" "${multiValueArgs}" ${ARGN})
  if(NOT DAPP_SCOPE)
    set(DAPP_SCOPE PRIVATE)
  endif()

  if(NOT target OR NOT platform)
    message(FATAL_ERROR "defold_target_link_app: target and platform are required")
  endif()

  # Derive OS from tuple (e.g., x86_64-win32 -> win32)
  string(REGEX REPLACE "^[^-]+-" "" _PLAT_OS "${platform}")

  if(_PLAT_OS STREQUAL "macos")
    # FRAMEWORK_APP for macOS: pass frameworks as linker options explicitly.
    # Using "-Wl,-framework,<name>" avoids accidental concatenation where
    # only a single "-framework" appears before multiple names.
    foreach(_fw IN ITEMS AppKit Cocoa IOKit Carbon CoreVideo)
      target_link_options(${target} ${DAPP_SCOPE} "-Wl,-framework,${_fw}")
    endforeach()
  elseif(_PLAT_OS STREQUAL "linux")
    # LIB_APP for Linux
    if(DEFINED ENV{AURORA_BUILD} AND "$ENV{AURORA_BUILD}" STREQUAL "1")
      target_link_libraries(${target} ${DAPP_SCOPE} pthread)
    else()
      target_link_libraries(${target} ${DAPP_SCOPE} Xext X11 Xi pthread)
    endif()
  elseif(_PLAT_OS STREQUAL "aurora")
    # LIB_APP for Aurora OS: no X11, Wayland/EGL are dlopen'ed by GLFW.
    # glib-2.0/gio-2.0/gobject-2.0 are for engine/platform/src/aurora/
    # mce_keepalive.c (MCE display-blanking prevention, GDBus on the system
    # bus - see docs/mce_display_blanking.md). Names and order come from
    # `pkg-config --libs glib-2.0 gio-2.0` run inside sb2 against the
    # target (see agents/t11-mce.md) - gobject-2.0 is a transitive
    # dependency of gio-2.0 (mce_keepalive.c also calls g_object_unref
    # directly) and is not obvious from the two pkg-config package names
    # alone, so it is not safe to type by hand.
    target_link_libraries(${target} ${DAPP_SCOPE} pthread gio-2.0 gobject-2.0 glib-2.0)
  elseif(_PLAT_OS STREQUAL "win32")
    # LINKFLAGS_APP for Windows (plus DINPUT set)
    target_link_libraries(${target} ${DAPP_SCOPE}
      user32.lib shell32.lib dbghelp.lib
      dinput8.lib dxguid.lib xinput9_1_0.lib)
  elseif(_PLAT_OS STREQUAL "xbone")
    target_link_libraries(${target} ${DAPP_SCOPE}
      GameInput.lib
      xgameruntime.lib
      xgameplatform.lib
      PIXEvt.lib)
  else()
    # iOS/Android/Web: no additional app libs beyond platform defaults
  endif()
endfunction()
