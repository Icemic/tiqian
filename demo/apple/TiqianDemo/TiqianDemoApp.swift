import SwiftUI

/// A native Apple app that showcases how Swift drives the shared CJK typesetting frontend. It is
/// the Apple-side counterpart to the Compose demo (`runComposeDemo`).
///
/// The reusable engine integration lives in `platforms/apple/frontend`; the samples (including 拼音 /
/// 注音 / 着重号 / 专名号 / 书名号) are authored here as native `AttributedString` values.
@main
struct TiqianDemoApp: App {
    var body: some Scene {
        #if os(macOS)
        WindowGroup("提椠 · Core Text 排版示例") {
            ContentView()
                .frame(minWidth: 780, minHeight: 600)
        }
        .windowResizability(.contentMinSize)
        #else
        WindowGroup {
            ContentView()
        }
        #endif
    }
}
