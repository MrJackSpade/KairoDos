# The shared PC boot test's Windows build of KairoDos's core (KairoDos#140): static libraries built
# with llvm-mingw (PrepareStagingDependencies.ps1 -Abi windows-host puts it first on PATH, where
# vcpkg's MinGW toolchain finds its x86_64-w64-mingw32-gcc and g++).
set(VCPKG_TARGET_ARCHITECTURE x64)
set(VCPKG_CRT_LINKAGE dynamic)
set(VCPKG_LIBRARY_LINKAGE static)
set(VCPKG_CMAKE_SYSTEM_NAME MinGW)
set(VCPKG_ENV_PASSTHROUGH PATH)
set(VCPKG_BUILD_TYPE release)
