import SwiftUI
import TiqianUI

struct ContentView: View {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @State private var selection: Int? = 0
    @State private var fontSize: Double = 18

    private var current: Int { selection ?? 0 }

    var body: some View {
        NavigationSplitView {
            List(0 ..< demoSamples.count, id: \.self, selection: $selection) { i in
                VStack(alignment: .leading, spacing: 2) {
                    Text(demoSamples[i].title).font(.body)
                    Text(demoSamples[i].subtitle).font(.caption).foregroundStyle(.secondary)
                }
                .accessibilityElement(children: .combine)
                .tag(i)
            }
            .navigationSplitViewColumnWidth(min: 220, ideal: 260)
        } detail: {
            detail
        }
    }

    @ViewBuilder
    private var detail: some View {
        let content = VStack(spacing: 0) {
            // The app chooses authored content; CJKText owns typesetting, drawing and selection.
            CJKText(demoSamples[current].build(CGFloat(fontSize)), fontSize: Float(fontSize))
                .frame(maxWidth: .infinity, maxHeight: .infinity)

            Divider()

            HStack(spacing: 12) {
                Image(systemName: "textformat.size")
                Slider(value: $fontSize, in: 12 ... 30, step: 1)
                    .frame(maxWidth: 200)
                    .accessibilityLabel("字号")
                Text("\(Int(fontSize)) pt").monospacedDigit().foregroundStyle(.secondary)
                if horizontalSizeClass != .compact {
                    Spacer()
                    Text("PingFang SC · Core Text")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
        }

        #if os(macOS)
        content
            .navigationTitle(demoSamples[current].title)
            .navigationSubtitle(demoSamples[current].subtitle)
        #else
        content.navigationTitle(demoSamples[current].title)
        #endif
    }
}
