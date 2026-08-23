import Foundation
import NaturalLanguage

/// Apple-native word selection for the currently supported simplified-Chinese horizontal frontend.
/// Layout and caret geometry remain engine-owned; only the semantic expansion from one safe UTF-16
/// hit offset to a word range is delegated to `NLTokenizer`.
enum CJKWordTokenizer {
    static func range(in text: String, containingUTF16Offset offset: Int) -> NSRange? {
        let utf16 = text.utf16
        let clamped = Swift.min(Swift.max(offset, 0), utf16.count)
        guard
            clamped < utf16.count,
            let utf16Index = utf16.index(utf16.startIndex, offsetBy: clamped, limitedBy: utf16.endIndex),
            let stringIndex = String.Index(utf16Index, within: text)
        else { return nil }

        let tokenizer = NLTokenizer(unit: .word)
        tokenizer.string = text
        tokenizer.setLanguage(.simplifiedChinese)
        let token = tokenizer.tokenRange(at: stringIndex)
        let range = NSRange(token, in: text)
        return range.length > 0 && NSLocationInRange(clamped, range) ? range : nil
    }
}
