#!/usr/bin/env bash

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
xcframework="$repo_root/kmp/build/XCFrameworks/release/OneSignalKMP.xcframework"
simulator_framework="$xcframework/ios-arm64_x86_64-simulator"
catalyst_framework="$xcframework/ios-arm64_x86_64-maccatalyst"
temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT

source_file="$temporary_directory/main.swift"
cat > "$source_file" <<'EOF'
import OneSignalKMP

let factory: LoggerFactory = LoggerFactory.shared
print(factory)
EOF

xcrun --sdk iphonesimulator swiftc \
  -target arm64-apple-ios12.0-simulator \
  -F "$simulator_framework" \
  -typecheck \
  "$source_file"

for architecture in arm64 x86_64; do
  xcrun --sdk macosx swiftc \
    -target "$architecture-apple-ios14.0-macabi" \
    -F "$catalyst_framework" \
    -framework OneSignalKMP \
    "$source_file" \
    -o "$temporary_directory/catalyst-$architecture"
done

"$temporary_directory/catalyst-$(uname -m)"
