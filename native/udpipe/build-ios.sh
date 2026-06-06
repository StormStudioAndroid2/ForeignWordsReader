#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SRC_DIR="$ROOT_DIR/upstream/src"
ADAPTER_DIR="$ROOT_DIR/adapter"
BUILD_DIR="$ROOT_DIR/build/ios"

build_arch() {
  local target_name="$1"
  local sdk="$2"
  local target_triple="$3"
  local out_dir="$BUILD_DIR/$target_name"
  local obj_dir="$out_dir/obj"
  local sdk_path

  sdk_path="$(xcrun --sdk "$sdk" --show-sdk-path)"
  rm -rf "$obj_dir"
  mkdir -p "$obj_dir"

  local sources=()
  while IFS= read -r source; do
    sources+=("$source")
  done < <(find "$SRC_DIR" -name '*.cpp' \
      ! -path "$SRC_DIR/rest_server/*" \
      ! -name 'udpipe.cpp' \
      ! -name 'derivator_dictionary_encoder.cpp' \
      | sort)
  sources+=("$ADAPTER_DIR/udpipe_adapter.cpp")

  local objects=()
  for source in "${sources[@]}"; do
    local rel="${source#$ROOT_DIR/}"
    local object="$obj_dir/${rel//\//_}.o"
    objects+=("$object")
    xcrun --sdk "$sdk" clang++ \
      -std=c++11 \
      -fexceptions \
      -frtti \
      -isysroot "$sdk_path" \
      -target "$target_triple" \
      -miphoneos-version-min=15.2 \
      -I"$SRC_DIR" \
      -I"$ADAPTER_DIR" \
      -Wno-unused-parameter \
      -Wno-sign-compare \
      -c "$source" \
      -o "$object"
  done

  xcrun --sdk "$sdk" libtool -static -o "$out_dir/libforeignwords_udpipe.a" "${objects[@]}"
}

build_arch "iosArm64" "iphoneos" "arm64-apple-ios15.2"
build_arch "iosX64" "iphonesimulator" "x86_64-apple-ios15.2-simulator"
build_arch "iosSimulatorArm64" "iphonesimulator" "arm64-apple-ios15.2-simulator"
