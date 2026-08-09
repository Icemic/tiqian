import SwiftUI

/// A macOS app that showcases — and documents by example — how a Swift app drives the
/// Tiqian CJK typesetting engine via its Core Text frontend. It is the Apple-side
/// counterpart to tiqian's Compose demo (`runComposeDemo`).
///
/// The engine integration lives in `TiqianTextView.swift`; the samples (including 拼音 /
/// 注音 / 着重号 / 专名号 / 书名号) are built in Kotlin (`:demo:macos-kit`) and reached via
/// `TiqianSampleInfo` + `TiqianTypesetter.layoutSample`.
@main
struct TiqianDemoApp: App {
    var body: some Scene {
        WindowGroup("提椠 · Core Text 排版示例") {
            ContentView()
                .frame(minWidth: 780, minHeight: 600)
        }
        .windowResizability(.contentMinSize)
    }
}
