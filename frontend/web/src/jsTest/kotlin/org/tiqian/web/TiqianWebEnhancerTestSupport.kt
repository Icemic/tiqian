@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.tiqian.web

import kotlin.JsFun
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.w3c.dom.Element
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

@JsFun(
    """() => {
      if (globalThis.__TiqianTestAnimationFrames) return;
      var tqInstallFrameState = {
        originalRequest: window.requestAnimationFrame,
        originalCancel: window.cancelAnimationFrame,
        originalRequestIdle: window.requestIdleCallback,
        originalCancelIdle: window.cancelIdleCallback,
        originalSetTimeout: window.setTimeout,
        originalClearTimeout: window.clearTimeout,
        callbacks: new Map(),
        nextId: 1,
        cancelled: 0,
        idleScheduled: 0,
        idleBudget: 50,
      };
      globalThis.__TiqianTestAnimationFrames = tqInstallFrameState;
      window.requestAnimationFrame = (callback) => {
        var tqFrameId = tqInstallFrameState.nextId++;
        tqInstallFrameState.callbacks.set(tqFrameId, callback);
        return tqFrameId;
      };
      window.cancelAnimationFrame = (tqFrameId) => {
        if (tqInstallFrameState.callbacks.delete(tqFrameId)) tqInstallFrameState.cancelled += 1;
      };
      window.requestIdleCallback = (callback) => {
        var tqIdleId = tqInstallFrameState.nextId++;
        tqInstallFrameState.idleScheduled += 1;
        tqInstallFrameState.callbacks.set(tqIdleId, () => callback({
          didTimeout: false,
          timeRemaining: () => tqInstallFrameState.idleBudget,
        }));
        return tqIdleId;
      };
      window.cancelIdleCallback = (tqIdleId) => {
        if (tqInstallFrameState.callbacks.delete(tqIdleId)) tqInstallFrameState.cancelled += 1;
      };
      window.setTimeout = (callback) => {
        var tqTimerId = tqInstallFrameState.nextId++;
        tqInstallFrameState.callbacks.set(tqTimerId, callback);
        return tqTimerId;
      };
      window.clearTimeout = (tqTimerId) => {
        if (tqInstallFrameState.callbacks.delete(tqTimerId)) tqInstallFrameState.cancelled += 1;
      };
    }""",
)
internal external fun installTestAnimationFrames()
@JsFun(
    """() => {
      var tqFlushOneState = globalThis.__TiqianTestAnimationFrames;
      if (!tqFlushOneState) return 0;
      var tqFlushOneCallbacks = Array.from(tqFlushOneState.callbacks.values());
      tqFlushOneState.callbacks.clear();
      for (const tqFlushOneCallback of tqFlushOneCallbacks) tqFlushOneCallback(performance.now());
      return tqFlushOneCallbacks.length;
    }""",
)
internal external fun flushOneTestAnimationFrame(): Int
@JsFun(
    """() => {
      var tqFlushAllState = globalThis.__TiqianTestAnimationFrames;
      if (!tqFlushAllState) return 0;
      var tqFlushAllSlices = 0;
      while (tqFlushAllState.callbacks.size > 0) {
        if (tqFlushAllSlices++ > 1000) throw new Error("animation frame test queue did not settle");
        var tqFlushAllCallbacks = Array.from(tqFlushAllState.callbacks.values());
        tqFlushAllState.callbacks.clear();
        for (const tqFlushAllCallback of tqFlushAllCallbacks) tqFlushAllCallback(performance.now());
      }
      return tqFlushAllSlices;
    }""",
)
internal external fun flushAllTestAnimationFrames(): Int
@JsFun("() => globalThis.__TiqianTestAnimationFrames ? globalThis.__TiqianTestAnimationFrames.callbacks.size : 0")
internal external fun pendingTestAnimationFrameCount(): Int
@JsFun("() => globalThis.__TiqianTestAnimationFrames ? globalThis.__TiqianTestAnimationFrames.cancelled : 0")
internal external fun cancelledTestAnimationFrameCount(): Int
@JsFun("() => globalThis.__TiqianTestAnimationFrames ? globalThis.__TiqianTestAnimationFrames.idleScheduled : 0")
internal external fun scheduledTestIdleCallbackCount(): Int
@JsFun("(value) => { if (globalThis.__TiqianTestAnimationFrames) globalThis.__TiqianTestAnimationFrames.idleBudget = value; }")
internal external fun setTestIdleCallbackBudget(value: Int)
@JsFun("() => window.dispatchEvent(new Event('scroll'))")
internal external fun dispatchTestProgressiveScroll()
@JsFun(
    """() => {
      var tqPreviousWarnCapture = globalThis.__TiqianTestConsoleWarnCapture;
      if (tqPreviousWarnCapture) throw new Error("console.warn capture already installed");
      var tqOriginalWarn = console.warn;
      var tqMessages = [];
      globalThis.__TiqianTestConsoleWarnCapture = { original: tqOriginalWarn, messages: tqMessages };
      console.warn = (...args) => tqMessages.push(args.map(String).join(" "));
    }""",
)
private external fun installTestConsoleWarnCapture()
@JsFun(
    """() => {
      var tqWarnCapture = globalThis.__TiqianTestConsoleWarnCapture;
      return tqWarnCapture ? tqWarnCapture.messages.join("\n") : "";
    }""",
)
private external fun capturedTestConsoleWarnings(): String
@JsFun(
    """() => {
      var tqWarnCapture = globalThis.__TiqianTestConsoleWarnCapture;
      if (!tqWarnCapture) return;
      console.warn = tqWarnCapture.original;
      delete globalThis.__TiqianTestConsoleWarnCapture;
    }""",
)
private external fun restoreTestConsoleWarnCapture()
@JsFun(
    """(element, top, width) => {
      element.getBoundingClientRect = () => new DOMRect(0, top, width, 30);
    }""",
)
internal external fun setElementRect(element: HTMLElement, top: Double, width: Double)
@JsFun("(event) => event.detail && event.detail.stale === true")
internal external fun relayoutEventIsStale(event: Event): Boolean
@JsFun("(event, name) => Number(event.detail && event.detail[name])")
internal external fun eventDetailInt(event: Event, name: String): Int
@JsFun(
    """() => {
      var tqRestoreFrameState = globalThis.__TiqianTestAnimationFrames;
      if (!tqRestoreFrameState) return;
      window.requestAnimationFrame = tqRestoreFrameState.originalRequest;
      window.cancelAnimationFrame = tqRestoreFrameState.originalCancel;
      if (tqRestoreFrameState.originalRequestIdle === undefined) {
        delete window.requestIdleCallback;
      } else {
        window.requestIdleCallback = tqRestoreFrameState.originalRequestIdle;
      }
      if (tqRestoreFrameState.originalCancelIdle === undefined) {
        delete window.cancelIdleCallback;
      } else {
        window.cancelIdleCallback = tqRestoreFrameState.originalCancelIdle;
      }
      window.setTimeout = tqRestoreFrameState.originalSetTimeout;
      window.clearTimeout = tqRestoreFrameState.originalClearTimeout;
      delete globalThis.__TiqianTestAnimationFrames;
    }""",
)
internal external fun restoreTestAnimationFrames()
internal fun installExactFontSessionFixture(
    failShaping: Boolean,
    failFamily: String? = null,
    failText: String? = null,
    varyFaceByText: Boolean = false,
) {
    installExactFontSessionFixtureBridge(failShaping, failFamily, failText, varyFaceByText)
}
@JsFun(
    """(failShaping, failFamily, failText, varyFaceByText) => {
      const shapes = new Map();
      const metrics = new Map();
      let nextHandle = 1;
      globalThis.__TiqianExactPreparedPlan = "";
      globalThis.__TiqianExactPreparedPlans = [];
      globalThis.__TiqianExactPreparedSemantics = [];
      globalThis.__TiqianExactPreparedSemanticElements = [];
      globalThis.__TiqianExactPreparedInlineObjects = [];
      globalThis.__TiqianExactPreparedRenderCount = 0;
      globalThis.__TiqianExactFontShapeCount = 0;
      globalThis.__TiqianExactFontFallbackCount = 0;
      globalThis.__TiqianFontBackend = {
        shape(_session, displayText, families, fontSize, _fontWeight, _italic, _locale, role) {
          if (failShaping ||
              (failFamily && String(families).includes(failFamily)) ||
              (failText && String(displayText).includes(failText))) {
            globalThis.__TiqianExactFontFallbackCount += 1;
            throw new Error("NoExactFontFace:test");
          }
          globalThis.__TiqianExactFontShapeCount += 1;
          const handle = nextHandle++;
          const glyphs = [];
          let glyphIndex = 0;
          for (const _point of displayText) {
            glyphs.push({
              id: 100 + glyphIndex,
              advance: fontSize,
              x: glyphIndex * fontSize,
              y: 0,
              bounds: [0, -fontSize * 0.88, fontSize, fontSize * 0.12],
            });
            glyphIndex++;
          }
          const features = role === "LatinText" && /[‘’“”]/u.test(displayText)
            ? ["pwid", "palt"]
            : [];
          shapes.set(handle, {
            glyphs,
            advance: glyphs.length * fontSize,
            features,
            faceId: varyFaceByText ? `Fixture CJK:${'$'}{displayText}` : "Fixture CJK",
          });
          return handle;
        },
        shapeGlyphCount: (handle) => shapes.get(handle).glyphs.length,
        shapeGlyphId: (handle, index) => shapes.get(handle).glyphs[index].id,
        shapeGlyphAdvance: (handle, index) => shapes.get(handle).glyphs[index].advance,
        shapeGlyphX: (handle, index) => shapes.get(handle).glyphs[index].x,
        shapeGlyphY: (handle, index) => shapes.get(handle).glyphs[index].y,
        shapeGlyphBound: (handle, index, edge) => shapes.get(handle).glyphs[index].bounds[edge],
        shapeAdvance: (handle) => shapes.get(handle).advance,
        shapeFaceId: (handle) => shapes.get(handle).faceId,
        shapeFontInstanceId: () => "fixture:0:default",
        shapeScript: () => "Hani",
        shapeFeatureCount: (handle) => shapes.get(handle).features.length,
        shapeFeature: (handle, index) => shapes.get(handle).features[index],
        shapeUnsafeBreakCount: () => 0,
        releaseShape: (handle) => shapes.delete(handle),
        metrics(_session, families, fontSize) {
          if (failShaping || (failFamily && String(families).includes(failFamily))) {
            globalThis.__TiqianExactFontFallbackCount += 1;
            throw new Error("NoExactFontFace:test");
          }
          const handle = nextHandle++;
          metrics.set(handle, [fontSize, fontSize * 0.25, 0, fontSize * 0.88, fontSize * 0.12]);
          return handle;
        },
        metricValue: (handle, index) => metrics.get(handle)[index],
        releaseMetrics: (handle) => metrics.delete(handle),
      };
      globalThis.__TiqianPreparedDomRenderer = {
        render(host, planJson, locale, options = {}) {
          if (failShaping) throw new Error("Exact renderer must not run after shaping failure");
          globalThis.__TiqianExactPreparedRenderCount += 1;
          globalThis.__TiqianExactPreparedPlan = planJson;
          globalThis.__TiqianExactPreparedPlans.push(planJson);
          globalThis.__TiqianExactPreparedSemantics = Array.from(options.semantics || []);
          globalThis.__TiqianExactPreparedSemanticElements =
            Array.from(options.liveSemanticElements || []);
          globalThis.__TiqianExactPreparedInlineObjects = Array.from(options.inlineObjects || []);
          for (const element of globalThis.__TiqianExactPreparedSemanticElements) {
            if (element && element.setAttribute) {
              element.setAttribute("data-tq-fixture-seen", "semantic");
            }
          }
          for (const entry of globalThis.__TiqianExactPreparedInlineObjects) {
            if (entry && entry.element && entry.element.setAttribute) {
              entry.element.setAttribute("data-tq-fixture-seen", "inline-object");
            }
          }
          if (options.semanticReplay === "live-source") {
            const sourceText = String(options.sourceText || "");
            const semantics = Array.from(options.semantics || []);
            const sourceElements = Array.from(options.liveSemanticElements || []);
            const inlineObjects = Array.from(options.inlineObjects || [])
              .slice()
              .sort(function (left, right) { return left.start - right.start; });
            host.replaceChildren();
            const roots = [];
            const stack = [];
            for (const semantic of semantics) {
              while (stack.length > 0 && semantic.start >= stack.at(-1).end) stack.pop();
              const node = { ...semantic, children: [] };
              const parent = stack.at(-1);
              if (parent) {
                if (semantic.end > parent.end) throw new Error("CrossingLiveSemanticRanges");
                parent.children.push(node);
              } else {
                roots.push(node);
              }
              stack.push(node);
            }
            // Mirror the clone swap of prepared-dom.js: an inline-object range
            // renders as a clone of the live element, never as the replacement
            // character that rides the lowered source text.
            const appendText = (container, from, to) => {
              let cursor = from;
              while (cursor < to) {
                const next = inlineObjects.find(function (entry) {
                  return entry.start >= cursor && entry.start < to;
                });
                if (!next) break;
                if (next.start > cursor) {
                  container.appendChild(document.createTextNode(sourceText.slice(cursor, next.start)));
                }
                const clone = next.element.cloneNode(false);
                clone.setAttribute("data-tq-inline-object", "true");
                container.appendChild(clone);
                cursor = next.end;
              }
              if (cursor < to) {
                container.appendChild(document.createTextNode(sourceText.slice(cursor, to)));
              }
            };
            const appendRange = (container, start, end, children) => {
              let offset = start;
              for (const semantic of children) {
                if (semantic.start > offset) {
                  appendText(container, offset, semantic.start);
                }
                const source = sourceElements[semantic.sourceIndex];
                if (!source) throw new Error(`MissingLiveSemanticSource:${'$'}{semantic.sourceIndex}`);
                const clone = source.cloneNode(false);
                clone.setAttribute("data-tq-source-semantic", "true");
                appendRange(clone, semantic.start, semantic.end, semantic.children);
                container.appendChild(clone);
                offset = semantic.end;
              }
              if (offset < end) {
                appendText(container, offset, end);
              }
            };
            appendRange(host, 0, sourceText.length, roots);
            return {};
          }
          host.innerHTML = `<span data-tq-exact-rendered="${'$'}{locale}"></span>`;
          return {};
        },
      };
      globalThis.__TiqianPreparedDomValidator = { issue: () => null };
    }""",
)
private external fun installExactFontSessionFixtureBridge(
    failShaping: Boolean,
    failFamily: String?,
    failText: String?,
    varyFaceByText: Boolean,
)
@JsFun("() => globalThis.__TiqianExactFontShapeCount || 0")
internal external fun exactFontShapeCount(): Int
@JsFun("() => globalThis.__TiqianExactFontFallbackCount || 0")
internal external fun exactFontFallbackCount(): Int
@JsFun("(detail) => { globalThis.__TiqianPreparedDomValidator = { issue: () => detail }; }")
internal external fun failExactPreparedDomValidation(detail: String)
@JsFun("(detail) => { globalThis.__TiqianPreparedDomRenderer = { render() { throw new Error(detail); } }; }")
internal external fun failExactPreparedDomRender(detail: String)
@JsFun("() => globalThis.__TiqianExactPreparedPlan || ''")
internal external fun exactPreparedPlan(): String
@JsFun("(index) => globalThis.__TiqianExactPreparedPlans[index] || ''")
internal external fun exactPreparedPlanAt(index: Int): String
@JsFun("() => globalThis.__TiqianExactPreparedRenderCount || 0")
internal external fun exactPreparedRenderCount(): Int
@JsFun("() => JSON.stringify(globalThis.__TiqianExactPreparedSemantics || [])")
internal external fun exactPreparedSemanticsJson(): String
@JsFun(
    "() => JSON.stringify((globalThis.__TiqianExactPreparedInlineObjects || []).map(" +
        "function (entry) { return { start: entry.start, end: entry.end, marginRight: entry.marginRight, " +
        "tag: entry.element ? entry.element.tagName.toLowerCase() : null }; }))",
)
internal external fun exactPreparedInlineObjectsJson(): String
@JsFun("(request) => JSON.parse(request).maxWidthPx")
internal external fun jsonMaxWidthPx(request: String): Double
@JsFun("(request) => JSON.parse(request).inlineObjects || ''")
internal external fun jsonInlineObjects(request: String): String

internal fun exactWorkerRequestInlineObjects(root: HTMLElement, paragraph: HTMLElement): String {
    val request = TiqianWeb.workerLayoutRequest(
        root,
        paragraph,
        TiqianWeb.EnhanceOptions(
            exactFontSession = TiqianWeb.ExactFontSessionCapability(
                status = "conforming",
                sessionId = "fixture-grid-session",
                detail = "test",
            ),
        ),
    ) ?: error("worker layout request must succeed for a conforming exact session")
    return jsonInlineObjects(request)
}

internal fun exactWorkerRequestMaxWidth(root: HTMLElement, paragraph: HTMLElement): Double {
    val request = TiqianWeb.workerLayoutRequest(
        root,
        paragraph,
        TiqianWeb.EnhanceOptions(
            exactFontSession = TiqianWeb.ExactFontSessionCapability(
                status = "conforming",
                sessionId = "fixture-grid-session",
                detail = "test",
            ),
        ),
    ) ?: error("worker layout request must succeed for a conforming exact session")
    return jsonMaxWidthPx(request)
}
@JsFun("(detail) => { globalThis.__TiqianLayoutWorker = { take: () => null, issue: () => detail }; }")
internal external fun installPreparedWorkerIssue(detail: String)
@JsFun(
    """() => {
      globalThis.__TiqianLayoutWorker = {
        take(_element, _sessionKey, requestText) {
          const request = JSON.parse(requestText);
          const semantics = Array.from(request.semantics || [], function (semantic, sourceIndex) {
            return {
              start: semantic.start,
              end: semantic.end,
              tagName: semantic.tagName,
              sourceIndex: Number.isSafeInteger(semantic.sourceIndex)
                ? semantic.sourceIndex
                : sourceIndex,
              order: Number.isSafeInteger(semantic.order) ? semantic.order : sourceIndex,
            };
          }).sort(function (left, right) {
            return left.start - right.start || right.end - left.end || left.order - right.order;
          }).map(function (semantic) {
            return {
              start: semantic.start,
              end: semantic.end,
              tagName: semantic.tagName,
              sourceIndex: semantic.sourceIndex,
            };
          });
          return JSON.stringify({
            plan: { fixture: "worker-live-source" },
            semanticReplay: "live-source",
            semantics,
            inlineBoxes: request.renderInlineBoxes || [],
          });
        },
        issue: () => null,
      };
    }""",
)
internal external fun installPreparedWorkerLivePlan()
@JsFun("() => { delete globalThis.__TiqianFontBackend; delete globalThis.__TiqianPreparedDomRenderer; delete globalThis.__TiqianPreparedDomValidator; delete globalThis.__TiqianLayoutWorker; delete globalThis.__TiqianExactPreparedPlan; delete globalThis.__TiqianExactPreparedPlans; delete globalThis.__TiqianExactPreparedSemantics; delete globalThis.__TiqianExactPreparedSemanticElements; delete globalThis.__TiqianExactPreparedInlineObjects; delete globalThis.__TiqianExactPreparedRenderCount; delete globalThis.__TiqianExactFontShapeCount; delete globalThis.__TiqianExactFontFallbackCount; }")
internal external fun clearExactFontSessionFixture()
internal fun dispatchEnhanceWithoutOptions(root: HTMLElement) {
    TiqianWeb.enhance(root)
}

internal fun dispatchEnhanceWithStrongAsEmphasisMarks(root: HTMLElement) {
    TiqianWeb.enhance(root, TiqianWeb.EnhanceOptions(strongAsEmphasisMarks = true))
}

internal fun dispatchRelayout(root: HTMLElement) {
    TiqianWeb.relayout(root)
}
@JsFun("(element, type) => element.dispatchEvent(new Event(type))")
private external fun dispatchDomEvent(element: HTMLElement, type: String)
@JsFun(
    """(element) => {
      const selection = getSelection();
      const range = document.createRange();
      range.selectNodeContents(element);
      selection.removeAllRanges();
      selection.addRange(range);
      const clipboardData = new DataTransfer();
      element.dispatchEvent(new ClipboardEvent('copy', {
        bubbles: true,
        cancelable: true,
        clipboardData
      }));
      const text = clipboardData.getData('text/plain') || selection.toString();
      selection.removeAllRanges();
      return text;
    }""",
)
internal external fun copySelection(element: HTMLElement): String
@JsFun(
    """(element) => {
      const selection = getSelection();
      const range = document.createRange();
      range.selectNodeContents(element);
      selection.removeAllRanges();
      selection.addRange(range);
      const event = new ClipboardEvent('copy', {
        bubbles: true,
        cancelable: true,
        clipboardData: new DataTransfer()
      });
      element.dispatchEvent(event);
      selection.removeAllRanges();
      return event.defaultPrevented;
    }""",
)
internal external fun copySelectionWasIntercepted(element: HTMLElement): Boolean
@JsFun("(element) => element.innerText")
internal external fun nativeInnerText(element: HTMLElement): String
@JsFun(
    """(paragraph) => Array.from(paragraph.querySelectorAll('.tq-line'))
      .filter((line) => line.dataset.tqLineEmpty === 'true')
      .length""",
)
internal external fun emptyRenderedLineCount(paragraph: HTMLElement): Int
@JsFun(
    """(paragraph) => Array.from(paragraph.querySelectorAll('.tq-line'))
      .map((line) => [
        line.dataset.tqLineRange,
        line.dataset.tqLineWidth,
        line.dataset.tqLineEnd
      ].join('\u001f'))
      .join('\u001e')""",
)
internal external fun renderedLineSignature(paragraph: HTMLElement): String
@JsFun(
    """(paragraph) => Array.from(paragraph.childNodes)
      .filter((node) => node.nodeType === Node.TEXT_NODE)
      .map((node) => node.data)
      .join('')""",
)
internal external fun directTextContent(paragraph: HTMLElement): String
@JsFun(
    """(paragraph) => {
      const leaves = Array.from(paragraph.querySelectorAll('[data-tq-geometry]'))
        .filter((element) => !element.classList.contains('tq-line') && element.textContent.length > 0);
      return leaves.length === 0 ? null : leaves[leaves.length - 1];
    }""",
)
internal external fun lastTextLeaf(paragraph: HTMLElement): HTMLElement?
@JsFun(
    """(paragraph, text) => Array.from(paragraph.querySelectorAll('[data-tq-geometry]'))
      .find((element) => element.textContent === text) || null""",
)
internal external fun geometryLeafWithText(paragraph: HTMLElement, text: String): HTMLElement?
@JsFun(
    """(paragraph) => {
      const rects = [];
      const visit = (node) => {
        if (node.nodeType === Node.TEXT_NODE) {
          if (!node.data || (node.parentElement && node.parentElement.closest('[data-tq-copy-ignore]'))) return;
          const range = document.createRange();
          range.selectNodeContents(node);
          for (const rect of range.getClientRects()) {
            if (rect.width || rect.height) rects.push(rect);
          }
          return;
        }
        if (node.nodeType !== Node.ELEMENT_NODE || node.hasAttribute('data-tq-copy-ignore')) return;
        for (const child of node.childNodes) visit(child);
      };
      for (const child of paragraph.childNodes) visit(child);
      if (!rects.length) return 0;
      return Math.max(...rects.map((rect) => rect.right)) -
        Math.min(...rects.map((rect) => rect.left));
    }""",
)
internal external fun renderedSingleLineFlowWidth(paragraph: HTMLElement): Float
@JsFun(
    """(element) => {
      const node = element.firstChild;
      if (!node || node.nodeType !== Node.TEXT_NODE) return '';
      const widths = [];
      for (let index = 0; index < node.data.length; index += 1) {
        const range = document.createRange();
        range.setStart(node, index);
        range.setEnd(node, index + 1);
        widths.push(range.getBoundingClientRect().width);
      }
      return widths.join(',');
    }""",
)
internal external fun textNodeCharacterWidths(element: HTMLElement): String
@JsFun("(element, property) => getComputedStyle(element).getPropertyValue(property)")
internal external fun computedStyleValue(element: HTMLElement, property: String): String
@JsFun(
    """(element, pseudo) => {
      const content = getComputedStyle(element, pseudo).getPropertyValue("content").trim();
      if ((content.startsWith('"') && content.endsWith('"')) ||
          (content.startsWith("'") && content.endsWith("'"))) {
        return content.slice(1, -1);
      }
      return content;
    }""",
)
internal external fun computedPseudoContent(element: HTMLElement, pseudo: String): String
@JsFun("(element, property) => getComputedStyle(element).getPropertyValue(property)")
internal external fun computedStyleValueElement(element: Element, property: String): String
@JsFun(
    """(container, target) => {
      const range = document.createRange();
      range.selectNodeContents(container);
      const selected = range.getBoundingClientRect();
      const expected = target.getBoundingClientRect();
      return selected.left <= expected.left + 0.1 && selected.right >= expected.right - 0.1;
    }""",
)
internal external fun selectionCoversElement(container: HTMLElement, target: HTMLElement): Boolean
@JsFun("(element) => element.getBoundingClientRect().width")
internal external fun elementWidth(element: HTMLElement): Double
@JsFun("(element) => Array.from(element.getClientRects()).filter((rect) => rect.width > 0).map((rect) => rect.width)")
internal external fun elementFragmentWidths(element: HTMLElement): Array<Double>

internal fun Char.isCurlyQuoteForWebTest(): Boolean =
    this == '\u2018' || this == '\u2019' || this == '\u201C' || this == '\u201D'

internal fun assertEnginePunctuationFeatureLock(
    element: HTMLElement,
    proportionalQuote: Boolean = false,
) {
    val features = computedStyleValue(element, "font-feature-settings")
    assertTrue(Regex("""["']halt["']\s+0""").containsMatchIn(features), features)
    assertTrue(Regex("""["']chws["']\s+0""").containsMatchIn(features), features)
    val palt = Regex("""["']palt["'](?:\s+(-?\d+))?""").find(features)
    assertNotNull(palt, features)
    val paltValue = palt.groupValues[1].ifEmpty { "1" }
    assertEquals(if (proportionalQuote) "1" else "0", paltValue, features)
}

internal fun cssPx(value: String): Float = value.removeSuffix("px").toFloatOrNull() ?: 0f

// GrantController test double: a plain object shaped like the coordinator's
// per-grant controller. Its shouldStop closure mirrors the coordinator's
// (paragraph quota plus coarse-clock deadline); tests pass a deadline of 0,
// already in the past, so one slice commits one paragraph.
@JsFun(
    """(root, generation, deadlineMs, quota) => ({
      root: root,
      generation: generation,
      deadline: deadlineMs,
      quota: quota,
      shouldStop: function (processed) {
        return processed >= quota || Date.now() >= deadlineMs;
      },
    })""",
)
internal external fun testGrantController(
    root: HTMLElement,
    generation: Int,
    deadlineMs: Double,
    quota: Int,
): GrantController
