#!/usr/bin/env bash
# Rebuild Tiqian.xcframework from :demo:macos-kit and drop it into the TiqianUI Swift package
# (frontend/apple), whose binary target the app depends on. Run after changing the engine or the
# kit facade.
set -euo pipefail
cd "$(dirname "$0")"                 # demo/macos-app
TIQIAN_ROOT="$(cd ../.. && pwd)"     # tiqian repo root
DEST="$TIQIAN_ROOT/frontend/apple/Tiqian.xcframework"

echo "▸ Assembling Tiqian.xcframework (:demo:macos-kit)…"
( cd "$TIQIAN_ROOT" && ./gradlew :demo:macos-kit:assembleTiqianDebugXCFramework )

echo "▸ Copying into the TiqianUI package (frontend/apple)…"
rm -rf "$DEST"
cp -R "$TIQIAN_ROOT/demo/macos-kit/build/XCFrameworks/debug/Tiqian.xcframework" "$DEST"

echo "✓ Tiqian.xcframework refreshed at frontend/apple. Rebuild the app in Xcode (Cmd+R)."
