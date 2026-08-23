import XCTest
@testable import TiqianUI

#if os(iOS)
import UIKit

@MainActor
final class CJKIOSViewTests: XCTestCase {
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

    func testUIKitViewLaysOutDrawsAndExposesSourceText() throws {
        let prefix = AttributedString("提椠").ruby("tíqiàn")
        let body = AttributedString(String(repeating: "中文正文用于验证旋转重排与滚动。", count: 12))
        let source = String((prefix + body).characters)
        let scrollView = CJKTextView([.paragraph(prefix + body)], fontSize: 20)
        scrollView.frame = CGRect(x: 0, y: 0, width: 240, height: 240)
        scrollView.overrideUserInterfaceStyle = .light
        scrollView.layoutIfNeeded()

        let canvas = try XCTUnwrap(scrollView.subviews.compactMap { $0 as? CJKCanvas }.first)
        XCTAssertEqual(scrollView.contentSize.width, 240, accuracy: 0.5)
        XCTAssertGreaterThan(scrollView.contentSize.height, scrollView.bounds.height)
        XCTAssertEqual(canvas.accessibilityTraits, .staticText)
        XCTAssertEqual(canvas.accessibilityValue, source)

        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        let image = UIGraphicsImageRenderer(bounds: canvas.bounds, format: format).image { _ in
            canvas.draw(canvas.bounds)
        }
        let cgImage = try XCTUnwrap(image.cgImage)
        let data = try XCTUnwrap(cgImage.dataProvider?.data)
        let bytes = try XCTUnwrap(CFDataGetBytePtr(data))
        var darkPixels = 0
        for y in 0..<cgImage.height {
            let row = y * cgImage.bytesPerRow
            for x in 0..<cgImage.width {
                let offset = row + x * 4
                if bytes[offset] < 230, bytes[offset + 1] < 230, bytes[offset + 2] < 230 {
                    darkPixels += 1
                }
            }
        }
        XCTAssertGreaterThan(darkPixels, 100, "the UIKit canvas should contain rendered glyph ink")
    }

    func testWidthChangeReflowsWithoutChangingSourceSemantics() throws {
        let source = String(repeating: "提椠中文排版。", count: 20)
        let scrollView = CJKTextView(frame: CGRect(x: 0, y: 0, width: 220, height: 240))
        scrollView.setContent(
            [.paragraph(AttributedString(source))],
            fontSize: 20
        )
        scrollView.layoutIfNeeded()
        let narrowHeight = scrollView.contentSize.height

        scrollView.frame.size.width = 360
        scrollView.setNeedsLayout()
        scrollView.layoutIfNeeded()

        let canvas = try XCTUnwrap(scrollView.subviews.compactMap { $0 as? CJKCanvas }.first)
        XCTAssertEqual(scrollView.contentSize.width, 360, accuracy: 0.5)
        XCTAssertLessThan(scrollView.contentSize.height, narrowHeight)
        XCTAssertEqual(canvas.accessibilityValue, source)
    }

    func testNativeTextInteractionUsesEngineGeometryAndCopiesSource() throws {
        let source = "提椠😀 native selection 用于验证原生手柄。"
        let selectedSource = "提椠😀"
        let scrollView = CJKTextView(frame: CGRect(x: 0, y: 0, width: 260, height: 240))
        scrollView.setContent(
            [.paragraph(AttributedString(source))],
            fontSize: 20
        )
        scrollView.layoutIfNeeded()
        let canvas = try XCTUnwrap(scrollView.subviews.compactMap { $0 as? CJKCanvas }.first)

        let interaction = try XCTUnwrap(canvas.interactions.compactMap { $0 as? UITextInteraction }.first)
        XCTAssertEqual(interaction.textInteractionMode, .nonEditable)
        XCTAssertTrue(interaction.textInput === canvas)

        let selectedLength = (selectedSource as NSString).length
        canvas.selectedTextRange = CJKTextRange(start: 0, end: selectedLength)
        let selection = try XCTUnwrap(canvas.selectedTextRange)
        XCTAssertEqual(canvas.text(in: selection), selectedSource)
        let selectionRects = canvas.selectionRects(for: selection)
        XCTAssertFalse(selectionRects.isEmpty)
        XCTAssertTrue(selectionRects.allSatisfy { !$0.rect.isEmpty && canvas.bounds.intersects($0.rect) })

        XCTAssertEqual(canvas.selectedSourceText, selectedSource)
        XCTAssertTrue(canvas.canPerformAction(#selector(CJKCanvas.copy(_:)), withSender: nil))
        canvas.copy(nil)

        // UTF-16 offset 3 is inside 😀. A foreign invalid caret is normalized to its trailing
        // grapheme boundary instead of exposing a half-surrogate position to UITextInteraction.
        canvas.selectedTextRange = CJKTextRange(start: 3, end: 3)
        let safeCaret = try XCTUnwrap(canvas.selectedTextRange as? CJKTextRange)
        XCTAssertEqual(safeCaret.lower.offset, 4)
        XCTAssertEqual(safeCaret.upper.offset, 4)

        canvas.selectedTextRange = CJKTextRange(start: 0, end: selectedLength)
        scrollView.frame.size.width = 360
        scrollView.setNeedsLayout()
        scrollView.layoutIfNeeded()
        let preserved = try XCTUnwrap(canvas.selectedTextRange as? CJKTextRange)
        XCTAssertEqual(preserved.lower.offset, 0)
        XCTAssertEqual(preserved.upper.offset, selectedLength)
    }

    func testClipboardAddsParenthesizedRubyWithoutChangingAccessibilitySource() throws {
        let source = "提椠与您"
        let text = AttributedString("提椠").ruby("tíqiàn")
            + AttributedString("与")
            + AttributedString("您").bopomofo("ㄋㄧㄣˊ")
        let scrollView = CJKTextView([.paragraph(text, indent: .flush)], fontSize: 20)
        scrollView.frame = CGRect(x: 0, y: 0, width: 260, height: 200)
        scrollView.layoutIfNeeded()
        let canvas = try XCTUnwrap(scrollView.subviews.compactMap { $0 as? CJKCanvas }.first)

        canvas.selectedTextRange = CJKTextRange(start: 0, end: (source as NSString).length)
        XCTAssertEqual(canvas.selectedSourceText, source)
        XCTAssertEqual(canvas.accessibilityValue, source)
        XCTAssertEqual(canvas.selectedClipboardText, "提椠（tíqiàn）与您（ㄋㄧㄣˊ）")
    }

    func testNativeAttributedStringLinkUsesEngineGeometryAndOpenURLHook() throws {
        let target = try XCTUnwrap(URL(string: "https://www.w3.org/TR/clreq/"))
        var linked = AttributedString("CLREQ")
        linked.link = target
        let source = "读「CLREQ」。"
        let text = AttributedString("读「") + linked + AttributedString("」。")
        let scrollView = CJKTextView([.paragraph(text, indent: .flush)], fontSize: 20)
        scrollView.frame = CGRect(x: 0, y: 0, width: 260, height: 200)
        scrollView.layoutIfNeeded()
        let canvas = try XCTUnwrap(scrollView.subviews.compactMap { $0 as? CJKCanvas }.first)
        let link = try XCTUnwrap(canvas.linkRectsForDisplay.first)
        let point = CGPoint(x: link.rect.midX, y: link.rect.midY)

        var opened: URL?
        scrollView.onOpenURL = { opened = $0 }
        XCTAssertEqual(link.target, target.absoluteString)
        XCTAssertEqual(canvas.linkTarget(at: point), target.absoluteString)
        XCTAssertTrue(canvas.activateLink(at: point))
        XCTAssertEqual(opened, target)
        XCTAssertEqual(canvas.accessibilityValue, source)
        XCTAssertTrue(canvas.gestureRecognizers?.contains { $0 is UITapGestureRecognizer } == true)
    }
}
#endif
