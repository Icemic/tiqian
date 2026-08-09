#if os(macOS)
import SwiftUI
import AppKit
import Tiqian

/// A reusable SwiftUI view that typesets and scrolls a CJK document with the Tiqian engine — the
/// Apple peer of Compose's `CjkText`. Content is native `AttributedString` wrapped in [CJKBlock]s;
/// the view owns a `Typesetter` (from [fontSize]), lowers the blocks, lays out at the current
/// width, and scrolls. It exposes no `Tiqian`-prefixed engine types.
///
/// (macOS/AppKit for now; the iOS `UIViewRepresentable` peer lands with the iOS reader.)
public struct CJKText: NSViewRepresentable {
    public let blocks: [CJKBlock]
    public let fontSize: Float

    public init(_ blocks: [CJKBlock], fontSize: Float) {
        self.blocks = blocks
        self.fontSize = fontSize
    }

    public func makeNSView(context: Context) -> NSScrollView {
        let scrollView = CJKScrollView()
        scrollView.hasVerticalScroller = true
        scrollView.hasHorizontalScroller = false
        scrollView.autohidesScrollers = true
        scrollView.drawsBackground = true
        scrollView.backgroundColor = .textBackgroundColor

        let canvas = CJKCanvas()
        scrollView.documentView = canvas
        canvas.configure(blocks: blocks, fontSize: fontSize)
        return scrollView
    }

    public func updateNSView(_ scrollView: NSScrollView, context: Context) {
        guard let canvas = scrollView.documentView as? CJKCanvas else { return }
        canvas.configure(blocks: blocks, fontSize: fontSize)
    }
}

/// Scroll view that reflows its `CJKCanvas` once a live resize *settles* rather than on every frame.
/// A full engine relayout is O(paragraphs) of line-breaking (tens of ms for a long document), so
/// re-laying-out on every pixel of a live window/pane resize drops frames; the canvas skips relayout
/// while `inLiveResize` is true and this flushes the one deferred reflow when the drag ends.
final class CJKScrollView: NSScrollView {
    override func viewDidEndLiveResize() {
        super.viewDidEndLiveResize()
        (documentView as? CJKCanvas)?.flushDeferredResize()
    }
}

/// The scroll view's document view. It is **flipped** (top-left origin) so the scroll view shows the
/// first line first; `CoreTextLayoutRenderer` expects a y-up context, so `draw(_:)` flips the
/// CGContext back to y-up before drawing — the two flips cancel.
final class CJKCanvas: NSView {
    private let padding: CGFloat = 28

    private var blocks: [CJKBlock] = []
    private var fontSize: Float = 18
    private var typesetter = Typesetter(fontSize: 18, cjkFamily: "PingFang SC", latinFamily: "Helvetica Neue")
    private var builder: DocBuilder?        // AttributedString lowered ONCE per (blocks, fontSize)
    // Qualified as `Tiqian.Document`: bare `Document` is ambiguous in an Xcode app build where
    // SwiftUI also exports a `Document` symbol (a clean-room build hit "'Document' is ambiguous").
    private var document: Tiqian.Document?
    private var laidOutBucket: CGFloat = -1 // grid-column bucket the current document was laid out at
    private var deferredResize = false      // a live resize crossed a bucket; reflow when it settles

    override var isFlipped: Bool { true }

    /// Rebuilds the (lowered) engine builder and reflows only when the content or font size actually
    /// changed. On a plain window resize the builder is reused — see `relayout()`.
    func configure(blocks: [CJKBlock], fontSize: Float) {
        var changed = false
        if fontSize != self.fontSize {
            self.fontSize = fontSize
            typesetter = Typesetter(fontSize: fontSize, cjkFamily: "PingFang SC", latinFamily: "Helvetica Neue")
            changed = true
        }
        if blocks != self.blocks {
            self.blocks = blocks
            changed = true
        }
        if changed {
            builder = blocks.isEmpty ? nil : Lowering.builder(blocks, baseSize: fontSize, typesetter: typesetter)
            laidOutBucket = -1 // force a relayout for the new content
            relayout()
            scrollToTop()
            needsDisplay = true
        }
    }

    override func viewDidMoveToSuperview() {
        super.viewDidMoveToSuperview()
        // Fires again on reattach / clip change: drop any prior observer first so observers don't
        // accumulate (each would fire a redundant relayout).
        NotificationCenter.default.removeObserver(self, name: NSView.frameDidChangeNotification, object: nil)
        guard let clip = superview as? NSClipView else { return }
        clip.postsFrameChangedNotifications = true
        NotificationCenter.default.addObserver(
            self, selector: #selector(viewportChanged),
            name: NSView.frameDidChangeNotification, object: clip,
        )
        relayout()
    }

    @objc private func viewportChanged() {
        // A full relayout re-runs the engine's line-break for every paragraph (~13ms for the essay), so
        // reflowing on every frame of a live window/pane drag drops frames. During a live resize just
        // remember that the viewport moved and reflow once, when `CJKScrollView.viewDidEndLiveResize`
        // fires — the drag then stays smooth (the current layout is left-aligned in the new width until
        // it settles). Non-live changes (initial layout, split-view snaps, zoom) reflow immediately.
        if enclosingScrollView?.inLiveResize == true || inLiveResize {
            deferredResize = true
            return
        }
        if relayout() { needsDisplay = true }
    }

    /// Flush the one relayout deferred during a live resize (called when the drag ends).
    func flushDeferredResize() {
        guard deferredResize else { return }
        deferredResize = false
        if relayout() { needsDisplay = true }
    }

    private var viewportWidth: CGFloat { enclosingScrollView?.contentView.bounds.width ?? bounds.width }
    private var viewportHeight: CGFloat { enclosingScrollView?.contentView.bounds.height ?? bounds.height }

    /// Re-lay-out only when the grid-column *bucket* changes. The engine grids line length to whole
    /// characters (font-size units), so the layout is identical for any width within a bucket — a
    /// live horizontal drag then re-lays-out about once per character-width instead of every pixel
    /// (and height-only resizes / sub-pixel ticks reuse the current document). Returns whether the
    /// visible layout or the view size changed (so the caller only repaints when needed).
    @discardableResult
    private func relayout() -> Bool {
        let em = CGFloat(fontSize)
        let columnWidth = viewportWidth - padding * 2
        guard columnWidth > 0, em > 0, let builder else {
            let changed = document != nil || frame.size != .zero
            document = nil
            laidOutBucket = -1
            if frame.size != .zero { setFrameSize(.zero) }
            return changed
        }
        // Grid-column bucket: the engine grids line length to whole characters, so the layout — and
        // thus the content-sized frame — is identical for any width within a bucket. Nothing to do
        // until a whole column is gained/lost, so a within-bucket resize (any direction) is free.
        let bucket = (columnWidth / em).rounded(.down)
        if bucket == laidOutBucket, document != nil { return false }
        let gridWidth = bucket * em
        document = builder.layout(width: Float(gridWidth))
        laidOutBucket = bucket
        let contentHeight = (document.map { CGFloat($0.height) } ?? 0) + padding * 2
        setFrameSize(NSSize(width: gridWidth + padding * 2, height: contentHeight))
        return true
    }

    private func scrollToTop() {
        scroll(NSPoint(x: 0, y: 0)) // flipped: top is y == 0
    }

    override func draw(_ dirtyRect: NSRect) {
        NSColor.textBackgroundColor.setFill()
        dirtyRect.fill()

        guard let document, let ctx = NSGraphicsContext.current?.cgContext else { return }

        ctx.saveGState()
        // Flipped (y-down) view; the renderer expects y-up. Undo the flip so CTFontDrawGlyphs draws
        // upright and `canvasHeight - y` lands where it should.
        ctx.translateBy(x: 0, y: bounds.height)
        ctx.scaleBy(x: 1, y: -1)
        // Base text/decorations inherit this fill color (colored spans override per cluster). Top
        // inset is folded into canvasHeight; left inset is a translate.
        NSColor.textColor.setFill()
        ctx.translateBy(x: padding, y: 0)
        let contextPointer = Unmanaged.passUnretained(ctx).toOpaque()
        document.draw(context: contextPointer, canvasHeight: Double(bounds.height) - Double(padding))
        ctx.restoreGState()
    }

    // Accessibility (VoiceOver): the canvas only paints glyphs, so expose the document's source text
    // as a static-text value. This is a paragraph-agnostic first version — per-paragraph elements and
    // selection semantics are follow-ups; a reader must at least be able to READ the prose aloud.
    override func isAccessibilityElement() -> Bool { true }
    override func accessibilityRole() -> NSAccessibility.Role? { .staticText }
    override func accessibilityLabel() -> String? { "正文" }
    override func accessibilityValue() -> Any? { document?.text }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }
}
#endif
