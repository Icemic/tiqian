import Foundation

/// A block in a CJK document — a paragraph, a list, or a section break — the Swift peer of the
/// Compose demo's `CjkBlock`. Paragraph/list content is a native `AttributedString`.
public struct CJKBlock: Equatable {
    enum Kind: Equatable {
        case paragraph(AttributedString, Indent)
        case list([AttributedString], ListMarker)
        case section
    }

    let kind: Kind

    /// A body paragraph. `indent` defaults to the CLREQ adaptive first-line indent.
    public static func paragraph(_ text: AttributedString, indent: Indent = .firstLine) -> CJKBlock {
        CJKBlock(kind: .paragraph(text, indent))
    }

    /// 编号/项目 list; each item is a native `AttributedString`. Marker defaults to 汉字数字.
    public static func list(_ items: [AttributedString], marker: ListMarker = .cjkNumber) -> CJKBlock {
        CJKBlock(kind: .list(items, marker))
    }

    /// One blank line of vertical space (CLREQ 节).
    public static let section = CJKBlock(kind: .section)
}

/// 段首缩排 (CLREQ §6.2.1). Amounts are in 字 (ic).
public struct Indent: Equatable {
    enum Kind: Equatable {
        case firstLine
        case flush
        case hanging(Float)
        case quote(Float)
    }

    let kind: Kind

    /// 段首缩进 (adaptive/base) — the engine decides the amount by measure.
    public static let firstLine = Indent(kind: .firstLine)
    /// No indent (titles, lead-ins).
    public static let flush = Indent(kind: .flush)
    /// 凸排: first line flush, the rest indent [amount] 字.
    public static func hanging(_ amount: Float = 2) -> Indent { Indent(kind: .hanging(amount)) }
    /// 段落缩排: the whole paragraph indents [amount] 字 (引用/诗词).
    public static func quote(_ amount: Float = 2) -> Indent { Indent(kind: .quote(amount)) }
}

/// A list item's marker style (CLREQ 凸排).
public enum ListMarker: Equatable {
    case decimal
    case cjkNumber
    case bullet
}
