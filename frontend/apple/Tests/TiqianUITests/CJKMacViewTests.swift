import XCTest
@testable import TiqianUI

#if os(macOS)
import AppKit

@MainActor
final class CJKMacViewTests: XCTestCase {
    func testNativeTokenizerExpandsSimplifiedChineseWords() {
        let source = "测试中文排版。"
        XCTAssertEqual(
            CJKWordTokenizer.range(in: source, containingUTF16Offset: 3),
            NSRange(location: 2, length: 2)
        )
        XCTAssertNil(CJKWordTokenizer.range(in: source, containingUTF16Offset: 6))
    }

    func testBopomofoLanguageDoesNotReplaceBaseLanguage() throws {
        let text = AttributedString("您").bopomofo("ㄋㄧㄣˊ")
        let run = try XCTUnwrap(text.runs.first)
        XCTAssertNil(run.languageIdentifier)
        XCTAssertEqual(run.ruby?.languageIdentifier, "zh-TW")
    }

    func testAppKitSelectionUsesDocumentGeometryAndPreservesSource() throws {
        let source = "提椠中文排版。Second line for selection."
        let scrollView = CJKTextView([.paragraph(AttributedString(source))], fontSize: 20)
        scrollView.frame = NSRect(x: 0, y: 0, width: 260, height: 220)
        scrollView.layoutSubtreeIfNeeded()
        let canvas = try XCTUnwrap(scrollView.documentView as? CJKCanvas)

        canvas.selectAll(nil)
        XCTAssertEqual(canvas.selectedSourceText, source)
        XCTAssertEqual(canvas.accessibilitySelectedText(), source)
        XCTAssertEqual(canvas.accessibilitySelectedTextRange(), NSRange(location: 0, length: (source as NSString).length))

        let rects = canvas.selectionRectsForDisplay
        XCTAssertFalse(rects.isEmpty)
        XCTAssertTrue(rects.allSatisfy { !$0.isEmpty && canvas.bounds.intersects($0) })
        let first = try XCTUnwrap(rects.first)
        let hit = canvas.selectionOffset(at: NSPoint(x: first.minX + 3, y: first.midY))
        XCTAssertTrue((0...1).contains(hit))

        XCTAssertTrue(canvas.validateUserInterfaceItem(ValidationItem(action: #selector(CJKCanvas.copy(_:)))))
        XCTAssertFalse(canvas.validateUserInterfaceItem(ValidationItem(action: #selector(CJKCanvas.selectAll(_:)))))
        canvas.setAccessibilitySelectedTextRange(NSRange(location: 0, length: 2))
        XCTAssertEqual(canvas.selectedSourceText, "提椠")
        XCTAssertTrue(canvas.validateUserInterfaceItem(ValidationItem(action: #selector(CJKCanvas.copy(_:)))))
        let menuEvent = try XCTUnwrap(NSEvent.mouseEvent(
            with: .rightMouseDown,
            location: .zero,
            modifierFlags: [],
            timestamp: 0,
            windowNumber: 0,
            context: nil,
            eventNumber: 0,
            clickCount: 1,
            pressure: 0
        ))
        XCTAssertEqual(canvas.menu(for: menuEvent)?.items.map(\.action), [#selector(CJKCanvas.copy(_:)), #selector(CJKCanvas.selectAll(_:))])

        scrollView.setFrameSize(NSSize(width: 360, height: 220))
        scrollView.layoutSubtreeIfNeeded()
        XCTAssertEqual(canvas.selectedSourceText, "提椠", "width-only reflow must preserve source offsets")
    }

    func testClipboardAddsParenthesizedRubyWithoutChangingAccessibilitySource() throws {
        let source = "提椠与您"
        let text = AttributedString("提椠").ruby("tíqiàn")
            + AttributedString("与")
            + AttributedString("您").bopomofo("ㄋㄧㄣˊ")
        let scrollView = CJKTextView([.paragraph(text, indent: .flush)], fontSize: 20)
        scrollView.frame = NSRect(x: 0, y: 0, width: 260, height: 200)
        scrollView.layoutSubtreeIfNeeded()
        let canvas = try XCTUnwrap(scrollView.documentView as? CJKCanvas)

        canvas.selectAll(nil)
        XCTAssertEqual(canvas.selectedSourceText, source)
        XCTAssertEqual(canvas.accessibilitySelectedText(), source)
        XCTAssertEqual(canvas.selectedClipboardText, "提椠（tíqiàn）与您（ㄋㄧㄣˊ）")
    }

    func testNativeAttributedStringLinkUsesEngineGeometryAndOpenURLHook() throws {
        let target = try XCTUnwrap(URL(string: "https://www.w3.org/TR/clreq/"))
        var linked = AttributedString("CLREQ")
        linked.link = target
        let source = "读「CLREQ」。"
        let text = AttributedString("读「") + linked + AttributedString("」。")
        let scrollView = CJKTextView([.paragraph(text, indent: .flush)], fontSize: 20)
        scrollView.frame = NSRect(x: 0, y: 0, width: 260, height: 200)
        scrollView.layoutSubtreeIfNeeded()
        let canvas = try XCTUnwrap(scrollView.documentView as? CJKCanvas)
        let link = try XCTUnwrap(canvas.linkRectsForDisplay.first)
        let point = NSPoint(x: link.rect.midX, y: link.rect.midY)

        var opened: URL?
        scrollView.onOpenURL = { opened = $0 }
        XCTAssertEqual(link.target, target.absoluteString)
        XCTAssertEqual(canvas.linkTarget(at: point), target.absoluteString)
        XCTAssertTrue(canvas.activateLink(at: point))
        XCTAssertEqual(opened, target)
        canvas.selectAll(nil)
        XCTAssertEqual(canvas.selectedSourceText, source)
    }
}

private final class ValidationItem: NSObject, NSValidatedUserInterfaceItem {
    let action: Selector?
    let tag = 0

    init(action: Selector) {
        self.action = action
    }
}
#endif
