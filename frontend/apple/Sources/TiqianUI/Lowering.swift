import Foundation
import Tiqian

#if canImport(AppKit)
import AppKit
#elseif canImport(UIKit)
import UIKit
#endif

/// Translates the idiomatic Swift authoring surface (`[CJKBlock]` of `AttributedString`) down to the
/// engine SDK's builder (`Typesetter` / `DocBuilder` / `ParagraphContent`), reading native
/// `.font` / `.foregroundColor` / `.languageIdentifier` and the custom CJK attribute keys. This is
/// the only place the `Tiqian` framework's (prefix-free) KN types are used — the app never sees them.
enum Lowering {
    /// Lower `[CJKBlock]` (AttributedString content) into a reusable engine `DocBuilder` — done ONCE
    /// per (blocks, fontSize). Re-laying out at a new width is then just `builder.layout(width:)`,
    /// so a window-resize reflow doesn't re-walk the AttributedStrings or re-cross the Swift↔Kotlin
    /// boundary per piece each tick.
    static func builder(
        _ blocks: [CJKBlock],
        baseSize: Float,
        typesetter: Typesetter,
    ) -> DocBuilder {
        let builder = typesetter.documentBuilder()
        for block in blocks {
            switch block.kind {
            case let .paragraph(text, indent):
                let content = paragraphContent(text, baseSize: baseSize)
                switch indent.kind {
                case .firstLine: builder.paragraph(content: content)
                case .flush: builder.flushParagraph(content: content)
                case let .quote(amount): builder.quote(content: content, amount: amount)
                case let .hanging(amount): builder.hangingParagraph(content: content, amount: amount)
                }
            case let .list(items, marker):
                let contents = items.map { paragraphContent($0, baseSize: baseSize) }
                switch marker {
                case .decimal: builder.decimalList(items: contents)
                case .cjkNumber: builder.numberedList(items: contents)
                case .bullet: builder.bulletList(items: contents)
                }
            case .section:
                builder.section()
            }
        }
        return builder
    }

    /// Walk an `AttributedString`'s runs and replay them onto a `ParagraphContent`. Each run is a
    /// ruby, a decoration, a styled run (native font/color), or plain text — mutually exclusive as
    /// the demo authors them.
    private static func paragraphContent(_ attr: AttributedString, baseSize: Float) -> ParagraphContent {
        let content = ParagraphContent()
        for run in attr.runs {
            let text = String(attr[run.range].characters)
            if text.isEmpty { continue }
            if let language = run.languageIdentifier { content.locale(bcp47: language) }

            // Ruby is a distinct base+reading construct. Combining ruby with other aspects on the
            // same run is a v1 limitation (ruby takes precedence); a run is rarely both ruby and
            // bold/coloured/decorated.
            if let ruby = run.ruby {
                switch ruby.kind {
                case .pinyin: content.pinyin(base: text, reading: ruby.reading)
                case .bopomofo: content.bopomofo(base: text, reading: ruby.reading)
                }
                continue
            }

            // Everything else is collected independently over the SAME range and emitted as one
            // `piece`, so a run that is e.g. bold + red + 着重号 keeps all three instead of only the
            // first-matched aspect. Read the platform font/color via the explicit key — the bare
            // `.font`/`.foregroundColor` dynamic members are ambiguous across AppKit/UIKit/SwiftUI.
            // Italic is a semantic flag (not a font trait) so it survives on faceless-italic CJK.
            #if canImport(AppKit)
            let font = run[AttributeScopes.AppKitAttributes.FontAttribute.self]
            let color = run[AttributeScopes.AppKitAttributes.ForegroundColorAttribute.self]
            #elseif canImport(UIKit)
            let font = run[AttributeScopes.UIKitAttributes.FontAttribute.self]
            let color = run[AttributeScopes.UIKitAttributes.ForegroundColorAttribute.self]
            #endif
            let italic = run[TiqianAttributes.ItalicKey.self] == true
            let emphasis = run.emphasis == true
            let properNoun = run.properNoun == true
            let bookTitle = run.bookTitle == true
            let mourning = run.mourning == true

            let hasStyle = font != nil || color != nil || italic
            let hasDecoration = emphasis || properNoun || bookTitle || mourning
            if !hasStyle, !hasDecoration {
                content.text(s: text)
            } else {
                content.piece(
                    s: text,
                    bold: isBold(font),
                    italic: italic,
                    sizeEm: font.map { Float($0.pointSize) / baseSize } ?? 1,
                    family: font?.familyName,
                    argb: argb(of: color),
                    emphasis: emphasis,
                    properNoun: properNoun,
                    bookTitle: bookTitle,
                    mourning: mourning,
                )
            }
        }
        return content
    }

    private static func isBold(_ font: PlatformFont?) -> Bool {
        guard let font else { return false }
        #if canImport(AppKit)
        return font.fontDescriptor.symbolicTraits.contains(.bold)
        #elseif canImport(UIKit)
        return font.fontDescriptor.symbolicTraits.contains(.traitBold)
        #endif
    }

    /// Platform color → `0xAARRGGBB` (0 = no color / inherit the context fill).
    private static func argb(of color: PlatformColor?) -> Int64 {
        guard let color else { return 0 }
        var r: CGFloat = 0, g: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
        #if canImport(AppKit)
        (color.usingColorSpace(.sRGB) ?? color).getRed(&r, green: &g, blue: &b, alpha: &a)
        #elseif canImport(UIKit)
        color.getRed(&r, green: &g, blue: &b, alpha: &a)
        #endif
        let A = Int64((a * 255).rounded())
        let R = Int64((r * 255).rounded())
        let G = Int64((g * 255).rounded())
        let B = Int64((b * 255).rounded())
        return (A << 24) | (R << 16) | (G << 8) | B
    }
}
