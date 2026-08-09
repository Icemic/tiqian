# TiqianUI — Apple (SwiftUI / AppKit) frontend

The Apple peer of [`frontend/compose`](../compose): a reusable Swift Package that vends
content-agnostic views over the Tiqian CJK typesetting engine, so apps consume a component instead
of copying view code.

> **Scope: macOS frontend + iOS backend.** The engine, the Core Text pipeline, and
> `Tiqian.xcframework` build for iOS (`iosArm64` + simulator) too, but the SwiftUI/AppKit view
> (`CJKText`) is **macOS-only** for now — it is inside `#if os(macOS)`. The iOS `UIViewRepresentable`
> peer that actually presents body text on iOS is a follow-up; there is no iOS view yet.

- **`CJKText`** — an `NSViewRepresentable` that takes a `[CJKBlock]` (content authored as native
  `AttributedString`) plus a font size, lays out at the viewport width, and scrolls. It knows nothing
  about *what* the content is (the analogue of Compose's `CjkText`). Selection / hit-testing will land
  here later (engine `LayoutQueries`).
- Links the engine SDK (`Tiqian.xcframework`) as a binary target, so consumers only `import TiqianUI`
  to reach `CJKText` + the `CJKBlock` / `AttributedString` authoring API; the engine's Kotlin/Native
  types stay internal to the package.

Pagination is **not** here — that is the [pageflow](https://github.com/tiqian-cjk/pageflow) engine's
job; the reading view will *consume* pageflow's page model.

## Build

The binary target expects `Tiqian.xcframework` next to `Package.swift`. Produce it with:

```shell
demo/macos-app/build-xcframework.sh   # assembles :demo:macos-kit and copies the xcframework here
```

Then any Xcode project or SwiftPM target can depend on this local package and `import TiqianUI`.
The bundled demo (`demo/macos-app`) does exactly that.
