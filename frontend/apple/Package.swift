// swift-tools-version:5.9
import PackageDescription

// TiqianUI — the SwiftUI / AppKit frontend for the Tiqian CJK typesetting engine, the Apple peer of
// `frontend/compose` (which vends the Compose `CjkText`). It provides reusable, content-agnostic
// views (`TiqianTextView`; selection/hit-testing later) over the Core Text pipeline, and re-exports
// the engine SDK (`Tiqian.xcframework`). Apps — the bundled demo and AthenaReader — depend on this
// package rather than copying the view. Pagination is NOT here: that belongs to the pageflow engine.
let package = Package(
    name: "TiqianUI",
    platforms: [.macOS(.v14), .iOS(.v17)],
    products: [
        .library(name: "TiqianUI", targets: ["TiqianUI"]),
    ],
    targets: [
        // The compiled engine + Core Text SDK (Kotlin/Native). Produced by
        // `:demo:macos-kit:assembleTiqianDebugXCFramework` and copied next to this Package.swift by
        // `demo/macos-app/build-xcframework.sh`. Static, so it links straight into the consumer.
        .binaryTarget(name: "Tiqian", path: "Tiqian.xcframework"),
        .target(
            name: "TiqianUI",
            dependencies: ["Tiqian"],
            // The static engine slice references Core Text; propagate the system-framework link.
            linkerSettings: [.linkedFramework("CoreText")],
        ),
    ],
)
