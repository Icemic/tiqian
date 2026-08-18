# ADR 0036: Compose TextStyle interop and rich-text capability report

- Status: Accepted
- Date: 2026-06-24
- Amendment 2026-07-07: `LinkAnnotation` pointer clicks are supported by hit-testing Tiqian's
  own `LayoutResult` geometry; URL links fall back to `LocalUriHandler`.
- Amendment 2026-07-07: `SpanStyle.baselineShift` is supported by lowering it to
  `TextStyle.baselineShift` and stacking that explicit author shift on the engine's cluster
  baseline geometry.
- Amendment 2026-07-27: opt-in static-text selection is supported through
  `CjkSelectionContainer`, using Tiqian source ranges and final `LayoutResult` geometry rather than
  a hidden Compose text layout.
- Amendment 2026-07-28: selection presentation and gesture recognition reuse Compose Foundation's
  platform implementations through a version-isolated compatibility boundary; Tiqian no longer
  draws its own handles or maintains a parallel long-press/double-click recognizer.
- Amendment 2026-07-28: a selection container can share its host's `ScrollState`; edge auto-scroll
  is armed only after a real drag crosses touch slop, so stationary long-press selection does not
  inherit Foundation's forward-creep behavior.
- Amendment 2026-07-28: measured Compose inline objects are supported through `CjkInlineObject`.
  Their advance, ascent, and descent enter the core `InlineObjectSpan`; Compose content is placed
  against the final Tiqian line baseline rather than a `PlaceholderVerticalAlign` approximation.
- Amendment 2026-07-28: inline-object providers may explicitly expose safe boundaries for uniform
  stretch, a measured natural blank plus absolute preferred target, and measured trailing blank for
  last-resort compression. Attached Chinese or ASCII point marks still see the object as their
  visible base for kinsoku, including across source-preserved separator spaces whose layout width is
  collapsed to zero.
- Amendment 2026-07-28: inline-object ascent/descent first consume the complete existing space
  between adjacent base-text faces. Line-box boundaries may be redistributed inside that space;
  baseline distance grows only by a measured `InlineObjectInterlineCollision` deficit after retaining
  the configurable 0.1em default minimum clearance between adjacent visible content.
- Amendment 2026-07-28: an inline-object trailing boundary may mark measured advance as discardable
  only when that boundary becomes an automatic line end. Formula operators stay on the preceding
  line, while their post-operator math glue disappears at the break and remains present otherwise.
- Amendment 2026-08-08: renderer-owned dashed and dotted underlines enter `CjkText` as
  `CjkInlineDecoration`; Compose lowers their color and dash geometry into the normal
  `RichTextRole.Underline`. Solid, dashed and dotted lines therefore share source boundaries, outer-glue
  trim, underline height and skip-ink instead of letting a Markdown host repaint final geometry.
- Amendment 2026-08-18: `tiqian-compose` remains Foundation-neutral. The optional
  `tiqian-compose-material3` adapter exposes the single-paragraph `CjkText` call shape with
  `LocalTextStyle` and `LocalContentColor` defaults. It forwards the resolved Compose inputs to the
  base frontend and does not own another TextStyle lowering or layout path. For an unstyled
  `LinkAnnotation`, it also applies Material 3's current primary-colour underline before forwarding;
  author-provided link styles remain unchanged.
- Amendment 2026-08-08: background spans use one continuous typographic box per visual line. The
  box retains every internal word/CJK/Latin/punctuation gap, trims only layout space outside the
  marked run, and uses the marked faces rather than the complete paragraph line box vertically.
- Amendment 2026-08-09: renderer-owned highlight fills enter `CjkText` as
  `CjkInlineBackground`. Unlike generic `SpanStyle.background`, one highlight run uses a single
  resolved text-style metric box, so Latin/fallback faces inside it cannot change its height. The
  frontend supplies explicit vertical padding and corner radius; both replay from the same final
  `RichTextLineSegment` geometry on Skia and Android. Source-adjacent ranges with the same role and
  visible paint share a 1 dp gap (half yielded by each side); unlike styles do not avoid one another.
- Amendment 2026-08-09: inline code reuses the same uniform rounded-background geometry. Its fixed
  4 dp inner horizontal padding enters layout through `InlineBoxSpan`, while any outer gap remains
  the engine's ordinary Unicode East Asian wide/narrow spacing rather than a painted margin. The
  default monospace run is 7/8 of the surrounding font size. `InlineBoxSpan` presents a generic
  Narrow outer-edge contract even when its source begins with `.` or `/`, so every independent
  inline box stays one configured 中西文间距 away from adjacent CJK body text without role-specific
  code. A measurement-only wrapper can opt back into source-character edges; an unboxed slash-led
  text run therefore keeps its ordinary Unicode Other edge. The named decision is
  `InlineBoxOuterAutoSpace`.
- Amendment 2026-08-14: a wrapped inline-code background keeps its 3 dp radius only at the true
  source start and end. A side continued from or onto another visual line uses a 1 dp continuation
  radius, so the fragment reads as open without looking mechanically clipped. The radius pair is
  carried by `RichTextBackgroundPaint`, and `RichTextLineSegment` resolves all four corners from its
  final source range; Skia and Android replay those resolved corners rather than inferring them from
  glyphs or line width. Generic highlights retain their authored radius on continuation sides unless
  they explicitly opt into a different value.
- Amendment 2026-08-09: keyboard-input runs reuse inline code's font family, size, weight and box
  dimensions. Their only default visual difference is a 1 dp border in place of the fill. Fill and
  border replay the same final `RichTextLineSegment`; the renderer insets the centered stroke by
  half its width so it cannot consume the box's inner or outer spacing.
- Amendment 2026-08-09: shrinking inline-code text to 7/8 em does not shrink its surrounding box.
  Inline code and keyboard input use the paragraph's reference font metrics plus the same vertical
  padding as highlights; only the glyphs use the smaller monospace style. This keeps all three box
  styles on one vertical rhythm in body text and headings.
- Amendment 2026-08-10: line-through paint bisects the resolved text style's ideographic metric box.
  It uses the platform's recorded `IdeographicEmBox` when available and the shared 0.88/0.12 em box
  only when metrics are absent; renderers no longer place Chinese strike-throughs from a generic
  baseline offset.
- Amendment 2026-08-10: selection menus participate in Foundation's version-pinned text-context-menu
  session instead of calling the legacy toolbar as a detached one-shot. Android therefore uses the
  system `ActionMode` provider, including host filters/components and `PROCESS_TEXT`; Desktop uses
  Compose's current `LocalTextContextMenu` right-click contract. The menu content rect and handles
  share Foundation's ancestor-clipped visible bounds, and descendant movement during scrolling
  invalidates the system anchor without creating another text layout. The Android artifact declares
  the `ACTION_PROCESS_TEXT` / `text/plain` package-visibility query, so Android 11+ does not silently
  reduce the external-app menu to packages already visible to the host. Clearing a selection closes
  its `ActionMode` before publishing the empty selection state, preventing an intermediate
  copy-disabled / select-all-only menu from flashing during dismissal.

## Context

ADR 0030 deliberately kept `CjkTextStyle` narrow: author-written Tiqian text should only expose
fields the engine actually consumes. Dogfooding Tiqian inside a real Compose application exposed a
different migration problem: application text rarely starts as `String + CjkTextStyle`. It often
already exists as `AnnotatedString + androidx.compose.ui.text.TextStyle`, plus renderer-owned
links, inline widgets, string annotations, and span styles.

If the integration point is Markdown/HTML/AST, Tiqian must reconstruct a reduced `AnnotatedString`
and will inevitably drop renderer semantics. The application then cannot tell whether Tiqian
preserved the paragraph or merely drew a similar-looking subset. That is not a real migration path.

## Decision

Keep the explicit Tiqian-native API, and add a Compose interop API beside it:

- `Compose TextStyle -> CjkTextStyle` bridge via `TextStyle.toCjkTextStyle()`:
  paragraph-level `.sp` font sizes pass through, and paragraph-level `.em` font
  sizes resolve against the bridge's explicit default `CjkTextStyle.fontSize`
  before entering the engine;
- `CjkText` overloads that accept Compose `style: TextStyle`;
- `CjkText(text: String | AnnotatedString, ...)` as the source-faithful Compose Text replacement
  facade (see ADR 0037). It accepts the Compose Text call-site shape Tiqian can own without routing
  back to host text (`modifier`, `color`, `fontSize`, `fontStyle`, `fontWeight`, `fontFamily`,
  `textDecoration`, `textAlign`, `lineHeight`, `overflow`, `softWrap`, `maxLines`, `minLines`,
  `style`, `onTextLayout`). Source `\n`, CRLF, and Unicode mandatory breaks are hard breaks inside
  this plain-text flow, not a request to enter the structured block/list API. The implemented
  overflow modes are `TextOverflow.Clip` and `TextOverflow.Visible`; `Ellipsis` is reported as a
  capability gap until Tiqian has an explicit overflow-marker model instead of a renderer-side text
  rewrite;
- `CjkText(blocks = ...)` remains the explicit block/list document API. It is not the migration path
  for renderer-produced Compose rich text;
- `ParagraphMeasurer.measure(AnnotatedString, ..., style: TextStyle)` for pre-layout;
- `AnnotatedString.cjkTextCompatibility(style)` returning structured
  `CjkTextCapabilityIssue`s.

The compatibility report is the renderer boundary, but it is not a host-renderer switch.
Tiqian accepts the input shape; the report returns `canPreserveAllKnownSemantics = true` only when
the current Compose frontend can preserve every detected feature. Inline placeholders without a
matching measured `CjkInlineObject`, unknown
string annotations, brush foregrounds, shadows, draw styles,
geometric transforms, locale lists, synthesis, font-feature settings, letter spacing,
non-generic font families, platform styles, paragraph style ranges, and Compose paragraph controls
are reported as Tiqian capability issues.

Supported Compose rich text remains the subset already wired through the real pipeline:

- source text unchanged;
- paragraph `TextStyle` color, font size, line height, generic font family, weight, and italic
  lowered through `CjkTextStyle`;
- `SpanStyle.color` as render-only `ColorSpan`;
- `SpanStyle.fontSize` / `fontWeight` / `fontStyle` / generic `fontFamily` as layout-affecting
  `TextSpan`s; span-level `.em` font size remains relative to the resolved paragraph font size;
- `SpanStyle.baselineShift` as layout-affecting `TextSpan.baselineShift`: Compose multipliers
  resolve against the span's final font size, flip into Tiqian's +down coordinates, and stack with
  the engine's metric baseline alignment;
- `SpanStyle.background`, `TextDecoration.Underline`, and `TextDecoration.LineThrough` as
  `RichTextSpan`s painted from `LayoutResult` geometry; their source edges are also passed as
  cluster-boundary hints (`SourceRangeBoundaryClusterSplit`) so a link/underline ending before
  trailing punctuation such as `template.` does not fall back to proportional slicing through one
  Latin cluster. Underline reuses Tiqian's skip-ink primitive instead of drawing a raw line through
  glyph ink;
- `LinkAnnotation` ranges as `RichTextRole.Link` source ranges plus pointer click actions backed by
  Tiqian geometry: taps are mapped through `LayoutResult.getOffsetForPosition`, verified against
  `LayoutResult.getBoundingBoxes`, then dispatched to `linkInteractionListener` or, for
  `LinkAnnotation.Url`, `LocalUriHandler.openUri`. Legacy `UrlAnnotation` uses the same Tiqian
  geometry and URI handler. The complete source `AnnotatedString`, including link, URL, TTS and
  supported style annotations, remains on Compose text semantics, so Android can convert those
  annotations to its accessibility spans;
- an opt-in `CjkSelectionContainer` for read-only text. Its `CjkSelectionState` registers descendant
  `CjkText` nodes in geometric reading order, hit-tests endpoints through
  `LayoutResult.getSelectionOffsetForPosition`, paints the returned occupied boxes in both Skia and
  Android renderers, and copies slices of the original `AnnotatedString`. Separate `CjkText` nodes
  are joined with a source newline during copy; `CjkDisableSelection` creates an explicit exclusion
  subtree;
- mouse drag and double-click word selection, touch long-press selection and draggable handles,
  triple-click paragraph selection, the platform text-context-menu session, keyboard copy/Escape,
  and Compose selection/copy semantics. Android's session uses the system `ActionMode` provider
  with copy/select-all and `PROCESS_TEXT`; Desktop uses Compose's native/right-click text menu.
  Platform presentation comes from Foundation's actual
  `SelectionHandle`, selection gesture detector, and Android text-default magnifier; Tiqian only
  adapts their positions and adjustment requests to its source/layout queries. Link taps remain
  active, while a drag that becomes a selection consumes movement and cancels the pending link
  click. A primary tap outside the settled Tiqian selection clears it with Foundation's release
  timing; focus transfer between Tiqian and editable Compose text clears the previous owner, while
  a container with no Tiqian selection does not hide another child's shared platform toolbar;
- a host using `verticalScroll` passes the same `ScrollState` to `CjkSelectionContainer`. Mouse,
  touch, and handle drags use a quadratic velocity ramp inside the viewport edge bands and refresh
  the source endpoint as content moves. Touch auto-scroll is not armed until accumulated movement
  crosses `ViewConfiguration.touchSlop`; a stationary long press therefore retains its initially
  selected interaction unit. The same position notifications recompute the ancestor-clipped menu
  content rect and hide non-dragged handles outside the visible viewport, matching Foundation's
  scroll behavior. Lazy layouts remain outside this contract because virtualized
  `CjkText` nodes can leave composition;
- selection replay keeps one immutable `LayoutResultReplayIndex` per measured result, including
  positioned clusters grouped by line and glyph/source lookup tables. `CjkSelectionState` caches
  geometric selectable order and the selected range for each node; pointer samples that stay on the
  same source boundary return without invalidation, and a changed range redraws only affected
  `CjkText` nodes. Foundation remains the only owner of handle popup positioning;
- source interaction endpoints keep UTF-16 as the public ABI while snapping surrogate pairs,
  combining/variation sequences, emoji modifiers and tags, regional-indicator pairs, Hangul
  sequences, and ZWJ-connected sequences to stable boundaries. `SourceInteractionWordExpansion`
  expands letter/digit words independently of shaping clusters and keeps Han ideographs atomic;
- paragraph-level `TextStyle.textDecoration` / background reach the same rich-text render-role path
  by wrapping the source `AnnotatedString` in an outer span; source text and existing annotations are
  preserved;
- `TextAlign.Start/Left/Justify/Center/End/Right` lowers only to Tiqian's existing
  `ParagraphStyle.lastLineAlignment` degree of freedom. Non-last lines remain CLREQ justified;
- `softWrap=false` measures with an unbounded line width; `maxLines` trims the visible line boxes;
  `minLines` reserves extra measured height without inventing hidden clusters; `TextOverflow.Clip`
  clips true overflow to the measured visible box but preserves engine-owned legal paint overhang
  (`LineEndHangingPunctuation`, `LineEndHangingHyphen`, and ink overhang from emitted clusters);
  `TextOverflow.Visible` leaves all overhang visible;
- source mandatory breaks (`\n`, coalesced CRLF, UAX#14 mandatory controls) create zero-advance,
  unshaped break clusters; consecutive and trailing breaks preserve blank lines, while long source
  lines still auto-wrap before the hard break;
- Tiqian `inlineCode { ... }` builder as `RichTextRole.InlineCode` plus generic monospace
  `TextSpan`; source text stays unchanged;
- a non-empty source range plus `CjkInlineObject(range, advance, ascent, descent, content)` lowers
  to `InlineObjectSpan`; U+FFFC remains available only when no meaningful textual fallback exists.
  The object is an indivisible, unshaped cluster whose ascent and descent first consume the existing
  inter-line space and expand the baseline grid only when adjacent visible extents would collide;
  the Compose child is then placed at
  `finalLineBaseline - objectAscent`, so measurement and drawing share the same baseline. Its two
  boundaries are fixed by default. A provider may expose a measured natural blank and absolute
  preferred target at an edge. Formula providers target 0.5em and classify those resources as
  punctuation-trailing, relation, or binary-operator spacing; Tiqian consumes the three classes in
  that order after word and sino-western spacing, then includes every opted edge in the final
  uniform pass. Adjustment-only edges can
  explicitly prohibit a line break, independently of the formula's real post-operator baseline break points. A
  provider may also expose already-measured trailing blank as tier-eight compression, after all
  seven CLREQ text tiers; content glyphs are never scaled. Leading blank cannot shrink because that
  would require moving the object's paint origin. Separately, a real post-operator break may mark
  the same measured blank as line-end discardable: it is removed only when the break is chosen,
  leaving the operator at the old line end and no blank at either edge. A forbidden Chinese mark or ASCII `, . : ; ! ?`
  remains bound to the object when directly attached or separated only by source spaces. Such
  separator spaces keep their source ranges but collapse to zero layout advance, and every boundary
  from the object through the mark is closed to stretching. When the pair is itself wider than the
  line, a point mark hangs instead of remaining at the next line's start;
- Tiqian builders for emphasis, proper noun, mourning, book title, ruby, and bopomofo.

## Integration rule

Applications should integrate Tiqian after their rich-text renderer has produced its Compose
paragraph model, not before. A Markdown renderer should hand Tiqian the same `AnnotatedString` and
style it would hand to Compose text, then use `cjkTextCompatibility` to expose what Tiqian still
needs to implement:

```kotlin
val compatibility = annotated.cjkTextCompatibility(style, overflow = overflow)
check(compatibility.canPreserveAllKnownSemantics) { compatibility.issues }
CjkText(annotated, style = style, overflow = overflow)
```

Measured inline widgets use the explicit `CjkInlineObject` contract. An unmodeled object replacement
character or renderer-owned placeholder annotation is still reported as a model gap; Tiqian must not
guess its geometry or baseline. Letter spacing needs to enter shaping/layout as a real advance-affecting text style,
not as renderer-side glyph spreading. A Markdown AST or HTML wrapper may still provide
application-owned emergency containment, but Tiqian itself must not route around its own renderer
during dogfooding.

## Consequences

- Existing `CjkTextStyle` call sites keep their narrow, honest authoring surface.
- Compose applications get a low-friction migration shape without pretending Tiqian supports every
  Compose text feature.
- Capability gaps become explainable and testable instead of being hidden by a host-renderer detour.
- Frontend modules still do not make CLREQ/font-fallback/glue/kinsoku/justification decisions; they only
  lower style values and expose capability reports.
- Foundation's public `SelectionContainer` cannot directly host Tiqian geometry: its internal
  `Selectable`/`SelectionLayoutBuilder` contract requires a Compose `TextLayoutResult`. A transparent
  Compose `Text` would create a conflicting second layout, so `FoundationSelectionCompat` is the one
  version-pinned internal compatibility boundary. Compose upgrades must compile and device-check it.
- `CjkText` exposes the complete source `AnnotatedString` to Compose semantics for screen-reader
  text and Android accessibility spans. Link pointer actions are backed by Tiqian `LayoutResult`
  queries such as offset/box hit testing, not by a hidden Compose Text layout. Static selection
  exposes source-safe set-selection and advertises copy only while a non-empty local range exists.
  Compose's Android bridge requires a real `TextLayoutResult` for line/page traversal and
  `EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY`; Tiqian deliberately does not provide a false second
  layout, so editor/IME behavior and TalkBack character-location support remain explicit gaps.
- Vertical writing and JLREQ remain out of scope. The compatibility report can grow new reasons or
  supported features without changing source text semantics.

## Verification

```shell
./gradlew :frontend:compose:jvmTest --tests 'org.tiqian.compose.CjkTextCompatibilityTest'
./gradlew :frontend:compose:jvmTest --tests 'org.tiqian.compose.CjkInlineObjectTest'
./gradlew :frontend:compose:jvmTest --tests 'org.tiqian.compose.CjkTextLinkClickTest'
./gradlew :frontend:compose:jvmTest --tests 'org.tiqian.compose.CjkSelectionTest'
./gradlew :frontend:compose:jvmTest --tests 'org.tiqian.compose.CjkTextRenderTest'
./gradlew :frontend:compose:compileAndroidMain
```

## Amendment (2026-08-14): link/code annotations affect line layout

`LinkAnnotation.Url`、`LinkAnnotation.Clickable` 与 Tiqian `inlineCode` 除原有 `RichTextRole` 外，
还统一降为核心 `LineBreakSpan(ProgressiveTechnical)`。因此新增、删除或移动链接 annotation 已不是
纯绘制变化，必须使 Compose 的 `LayoutInput` 缓存失效并重新断行；点击、复制和无障碍仍使用原始
source range。Compose 只负责投影这一语义，不自行选择符号、音节或硬断点。
