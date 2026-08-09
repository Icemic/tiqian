import Foundation
#if canImport(AppKit)
import AppKit
#elseif canImport(UIKit)
import UIKit
#endif

/// 拼音 (above) vs 注音 ㄅㄆㄇ (right side).
public enum RubyKind: String, Codable, Hashable {
    case pinyin
    case bopomofo
}

/// A ruby annotation attached to a run of base characters.
public struct RubyReading: Codable, Hashable {
    public var reading: String
    public var kind: RubyKind
    public init(_ reading: String, kind: RubyKind = .pinyin) {
        self.reading = reading
        self.kind = kind
    }
}

/// Custom `AttributedString` attribute keys for the CJK annotations the engine draws but that
/// `AttributedString` has no native equivalent for. Base rich text (weight / italic / size / family /
/// color / language) uses the platform's **native** attributes (`.font`, `.foregroundColor`,
/// `.languageIdentifier`) — only these CJK-specific roles need custom keys.
public enum TiqianAttributes {
    public struct RubyKey: AttributedStringKey {
        public typealias Value = RubyReading
        public static let name = "org.tiqian.ruby"
    }
    /// Synthetic-oblique italic (a semantic flag, like Compose's `fontStyle`) — NOT carried in the
    /// `.font`, because CJK families such as PingFang have no italic face, so an `NSFont` would drop
    /// the trait. The engine renders it as a shear (ADR 0030 B 档), visible on any family.
    public struct ItalicKey: AttributedStringKey {
        public typealias Value = Bool
        public static let name = "org.tiqian.italic"
    }
    public struct EmphasisKey: AttributedStringKey {
        public typealias Value = Bool
        public static let name = "org.tiqian.emphasis"
    }
    public struct ProperNounKey: AttributedStringKey {
        public typealias Value = Bool
        public static let name = "org.tiqian.properNoun"
    }
    public struct BookTitleKey: AttributedStringKey {
        public typealias Value = Bool
        public static let name = "org.tiqian.bookTitle"
    }
    public struct MourningKey: AttributedStringKey {
        public typealias Value = Bool
        public static let name = "org.tiqian.mourning"
    }
}

public extension AttributeScopes {
    /// The Tiqian scope: the custom CJK keys plus the platform + foundation scopes, so a single
    /// `run.font` / `run.foregroundColor` / `run.languageIdentifier` / `run.ruby` all resolve.
    struct TiqianScope: AttributeScope {
        public let ruby: TiqianAttributes.RubyKey
        public let emphasis: TiqianAttributes.EmphasisKey
        public let properNoun: TiqianAttributes.ProperNounKey
        public let bookTitle: TiqianAttributes.BookTitleKey
        public let mourning: TiqianAttributes.MourningKey
        public let foundation: FoundationAttributes
        #if canImport(AppKit)
        public let appKit: AppKitAttributes
        #endif
        #if canImport(UIKit)
        public let uiKit: UIKitAttributes
        #endif
    }

    var tiqian: TiqianScope.Type { TiqianScope.self }
}

public extension AttributeDynamicLookup {
    // Return type is `T` (the key type), not `T.Value?`: this is Apple's canonical custom-scope
    // dynamic-member pattern — `self[T.self]` here resolves to `T`, and the scope makes native
    // `AttributedString` attribute lookups (e.g. `.languageIdentifier`) reachable on runs.
    subscript<T: AttributedStringKey>(dynamicMember keyPath: KeyPath<AttributeScopes.TiqianScope, T>) -> T {
        self[T.self]
    }
}

// MARK: - Ergonomic authoring helpers (all set NATIVE or custom AttributedString attributes)

public extension AttributedString {
    /// 拼音 over the whole fragment. (Sets the attribute via the key type — the same-named dynamic
    /// member is shadowed by this helper method.)
    func ruby(_ reading: String, _ kind: RubyKind = .pinyin) -> AttributedString {
        var copy = self
        copy[TiqianAttributes.RubyKey.self] = RubyReading(reading, kind: kind)
        return copy
    }

    /// 注音 (ㄅㄆㄇ) over the whole fragment. The paragraph usually also wants `.language("zh-TW")`.
    func bopomofo(_ reading: String) -> AttributedString { ruby(reading, .bopomofo) }

    /// Synthetic-oblique italic (works on CJK families with no italic face; see [TiqianAttributes.ItalicKey]).
    func italic() -> AttributedString { var c = self; c[TiqianAttributes.ItalicKey.self] = true; return c }

    func emphasis() -> AttributedString { var c = self; c[TiqianAttributes.EmphasisKey.self] = true; return c }
    func properNoun() -> AttributedString { var c = self; c[TiqianAttributes.ProperNounKey.self] = true; return c }
    func bookTitle() -> AttributedString { var c = self; c[TiqianAttributes.BookTitleKey.self] = true; return c }
    func mourning() -> AttributedString { var c = self; c[TiqianAttributes.MourningKey.self] = true; return c }

    /// BCP-47 language for this fragment (native `.languageIdentifier`); 注音 needs `"zh-TW"`.
    func language(_ bcp47: String) -> AttributedString {
        var c = self
        c.languageIdentifier = bcp47
        return c
    }

    /// Set a native `.font` (family / size / weight, CJK default PingFang SC) and optional
    /// `.foregroundColor`. `italic` is carried as the semantic [TiqianAttributes.ItalicKey] rather
    /// than a font trait, so it renders as a shear even on CJK families with no italic face.
    func styled(
        size: CGFloat,
        bold: Bool = false,
        italic: Bool = false,
        family: String = "PingFang SC",
        color: PlatformColor? = nil,
    ) -> AttributedString {
        var c = self
        c.font = Self.makeFont(family: family, size: size, bold: bold)
        if italic { c[TiqianAttributes.ItalicKey.self] = true }
        if let color { c.foregroundColor = color }
        return c
    }

    /// Native `.foregroundColor` only (keeps the base face).
    func foreground(_ color: PlatformColor) -> AttributedString {
        var c = self
        c.foregroundColor = color
        return c
    }

    private static func makeFont(family: String, size: CGFloat, bold: Bool) -> PlatformFont {
        #if canImport(AppKit)
        let base = NSFont(name: family, size: size) ?? NSFont.systemFont(ofSize: size)
        guard bold else { return base }
        let descriptor = base.fontDescriptor.withSymbolicTraits(.bold)
        return NSFont(descriptor: descriptor, size: size) ?? base
        #elseif canImport(UIKit)
        let base = UIFont(name: family, size: size) ?? UIFont.systemFont(ofSize: size)
        guard bold, let descriptor = base.fontDescriptor.withSymbolicTraits(.traitBold) else { return base }
        return UIFont(descriptor: descriptor, size: size)
        #endif
    }
}
