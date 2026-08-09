# TiqianDemo — macOS Core Text demo

The Apple-side counterpart to the Compose demo (`runComposeDemo`): a native macOS app that
shows — and documents by example — how a Swift app drives Tiqian through its Core Text
frontend. It renders a gallery exercising the current Apple pipeline: 中西自动间距, 避头尾,
标点挤压, 两端对齐, rich text (字重/斜体/颜色/字号/字体族), 列表, 段落缩进, 拼音 ruby,
注音 (ㄅㄆㄇ), and 着重号/专名号/书名号/示亡号.

## Layout

```
demo/macos-kit/     :demo:macos-kit — Kotlin/Native SDK facade over :frontend:coretext-render
                    (Typesetter / authoring builder / Document, internal to TiqianUI), packaged as
                    a static Tiqian.xcframework (macos-arm64 + ios-arm64 + ios-arm64-simulator).
frontend/apple/     TiqianUI — the Swift Package (Apple peer of frontend/compose). Vends the
                    reusable, content-agnostic `CJKText` over native `AttributedString`; the engine
                    SDK links in as a binary target (KN types stay internal to the package).
demo/macos-app/     The SwiftUI app. It only *composes*: authors sample content in Swift
                    (Samples.swift) and hands it to CJKText (ContentView.swift).
```

The app owns the **content** (Swift); the kit owns the **typesetting**; `TiqianUI` owns the
**view**. Pagination is not here — that is the pageflow engine's job.

## Run it

```sh
./build-xcframework.sh          # assembles the engine SDK into frontend/apple (first time / after engine changes)
open TiqianDemo.xcodeproj       # resolves the local TiqianUI package, then press Cmd+R
```

Pick a sample on the left; drag the window edge to re-typeset; move the slider to re-lay-out;
long samples scroll.

## The integration, in a nutshell

```swift
import TiqianUI   // vends CJKText + the CJKBlock / AttributedString authoring API

// Content is native AttributedString grouped into [CJKBlock]; CJKText typesets + scrolls it.
CJKText([
    .paragraph(AttributedString("标题").styled(size: 28, bold: true), indent: .flush),
    .paragraph(AttributedString("诸位好。我叫")
        + AttributedString("提椠").ruby("tíqiàn")
        + AttributedString("。")),
    .list([AttributedString("第一条"), AttributedString("第二条")], marker: .cjkNumber),
], fontSize: 18)
```

Under the hood the engine's `CGContextRef` crosses the Kotlin/Native boundary as an opaque
`void *`; `TiqianTextView` handles that (and the y-up flip) for you. The renderer inherits the
context's fill color, so the view sets `NSColor.textColor` to adapt to light/dark, while colored
spans override per cluster.

## Scope (honest)

Draws base glyphs + rich-text spans (weight/italic/color/size/family) + 拼音/注音 annotation
glyphs + 着重号 dots + 专名号/书名号/示亡号 lines (书名号 is a straight line here; the wavy form
is a Skia-only refinement). Selection/hit-testing and vertical (直排) are not implemented yet.
`Tiqian.xcframework` is a regenerable build artifact (gitignored under frontend/apple) — run
`./build-xcframework.sh`.
