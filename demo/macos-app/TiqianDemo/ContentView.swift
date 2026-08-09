import SwiftUI
import TiqianUI

struct ContentView: View {
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
            VStack(spacing: 0) {
                // Compose the reusable view with the selected sample's content: ContentView picks
                // WHAT (demoSamples, authored as AttributedString), CJKText does the typesetting.
                CJKText(demoSamples[current].build(CGFloat(fontSize)), fontSize: Float(fontSize))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)

                Divider()

                HStack(spacing: 12) {
                    Image(systemName: "textformat.size")
                    Slider(value: $fontSize, in: 12 ... 30, step: 1).frame(width: 200)
                        .accessibilityLabel("字号")
                    Text("\(Int(fontSize)) px").monospacedDigit().foregroundStyle(.secondary)
                    Spacer()
                    Text("PingFang SC · Core Text · Tiqian")
                        .font(.caption).foregroundStyle(.secondary)
                }
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            }
            .navigationTitle(demoSamples[current].title)
            .navigationSubtitle(demoSamples[current].subtitle)
        }
    }
}
