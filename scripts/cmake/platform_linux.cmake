defold_log("platform_linux.cmake:")

# Derive target triple from TARGET_PLATFORM (matches waf_dynamo.py)
if(TARGET_PLATFORM MATCHES "^arm64-")
  set(_DEFOLD_CLANG_TRIPLE "aarch64-unknown-linux-gnu")
else()
  set(_DEFOLD_CLANG_TRIPLE "x86_64-unknown-linux-gnu")
endif()

# Defines
target_compile_definitions(defold_sdk INTERFACE DM_PLATFORM_LINUX)
target_compile_definitions(defold_sdk INTERFACE DM_HOSTFS=\"\")

# Compile options
target_compile_options(defold_sdk INTERFACE --target=${_DEFOLD_CLANG_TRIPLE})

# C++ specific flags are set globally in platform.cmake (-fno-rtti, etc.)

# Link options
target_link_options(defold_sdk INTERFACE
  --target=${_DEFOLD_CLANG_TRIPLE}
  -fuse-ld=lld)

# Aurora OS prototype build (arm64-linux with Aurora sysroot)
# Sysroot must be injected ONLY for the target arm64-linux configuration.
# Host tools (x86_64-linux) are built with the same CMake project in a single
# build_engine invocation and must use host headers/libraries.
if(TARGET_PLATFORM MATCHES "^arm64-" AND "$ENV{AURORA_BUILD}" STREQUAL "1")
  set(_AURORA_SYSROOT "$ENV{AURORA_SYSROOT}")
  if(_AURORA_SYSROOT)
    set(_AURORA_GCC_DIR "${_AURORA_SYSROOT}/usr/lib/gcc/aarch64-meego-linux-gnu/12.3.1")
    target_compile_definitions(defold_sdk INTERFACE DM_PLATFORM_AURORA WL_EGL_PLATFORM)
    target_compile_options(defold_sdk INTERFACE
      --sysroot=${_AURORA_SYSROOT}
      --gcc-install-dir=${_AURORA_GCC_DIR})
    target_link_options(defold_sdk INTERFACE
      --sysroot=${_AURORA_SYSROOT}
      --gcc-install-dir=${_AURORA_GCC_DIR})
    target_link_directories(defold_sdk INTERFACE ${_AURORA_SYSROOT}/usr/lib)
  endif()
endif()
