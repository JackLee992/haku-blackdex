#!/usr/bin/env bash
set -euo pipefail
# Build the inherited native library plus Haku sources, without Gradle or a device.
project_root=$(cd "$(dirname "$0")/.." && pwd)
android_sdk=${ANDROID_SDK_ROOT:-"$HOME/Library/Android/sdk"}
ndk_root=${HAKU_NDK_ROOT:-"$android_sdk/ndk/23.1.7779620"}
cmake_root=${HAKU_CMAKE_ROOT:-"$android_sdk/cmake/3.22.1"}
build_root=${HAKU_NATIVE_BUILD_ROOT:-"$project_root/build/haku-native"}
for abi in arm64-v8a armeabi-v7a; do
  "$cmake_root/bin/cmake" -S "$project_root/Bcore/src/main/cpp" -B "$build_root/$abi" -G Ninja \
    -DCMAKE_MAKE_PROGRAM="$cmake_root/bin/ninja" \
    -DCMAKE_TOOLCHAIN_FILE="$ndk_root/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI="$abi" -DANDROID_PLATFORM=android-21 -DCMAKE_BUILD_TYPE=Debug
  "$cmake_root/bin/cmake" --build "$build_root/$abi" --target blackdex --parallel 8
done
