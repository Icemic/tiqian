#!/usr/bin/env bash
# Rebuild the Apple frontend's Tiqian.xcframework next to Package.swift. Run after changing the
# engine, Core Text renderer or Swift-facing facade.
set -euo pipefail
cd "$(dirname "$0")"                 # platforms/apple/frontend
TIQIAN_ROOT="$(cd ../../.. && pwd)"  # tiqian repo root
DEST="$PWD/Tiqian.xcframework"

# Respect an explicit toolchain selection; otherwise use the standard full-Xcode installation.
# Kotlin/Native needs xcodebuild and cannot link Apple frameworks through CommandLineTools alone.
if [[ -z "${DEVELOPER_DIR:-}" && -d /Applications/Xcode.app/Contents/Developer ]]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi

echo "▸ Assembling Tiqian.xcframework (:platforms:apple:frontend)…"
( cd "$TIQIAN_ROOT" && ./gradlew :platforms:apple:frontend:assembleTiqianDebugXCFramework )

echo "▸ Syncing Tiqian.xcframework into the Swift package…"
rm -rf "$DEST"
cp -R "$TIQIAN_ROOT/platforms/apple/frontend/build/XCFrameworks/debug/Tiqian.xcframework" "$DEST"

echo "✓ Tiqian.xcframework refreshed. Rebuild the app in Xcode (Cmd+R)."
