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
@JsFun("(event) => event.detail && event.detail.stale === true")
internal external fun relayoutEventIsStale(event: Event): Boolean
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
// DefaultPreparedDomFixture: the production runtime always lowers through the
// prepared DOM renderer, so the test environment needs one from the first
// mount. This fixture records the plan and lowers it into the line
// vocabulary tests assert on: one span.tq-line per plan line carrying the
// line's display text, engine-break br elements between lines (mandatory
// breaks stay copyable, soft wraps carry the copy-ignore markers), and the
// live-source replay for paragraphs that re-lower through semantic clones.
// Geometry and paint live in the npm lane; this stub only reflects what the
// plan already determines.
@JsFun(
    """() => {
      globalThis.__TiqianInstallDefaultPreparedFixture = function () {
      globalThis.__TiqianExactPreparedPlan = "";
      globalThis.__TiqianExactPreparedPlans = [];
      globalThis.__TiqianExactPreparedSemantics = [];
      globalThis.__TiqianExactPreparedCjkStrong = [];
      globalThis.__TiqianExactPreparedSemanticElements = [];
      globalThis.__TiqianExactPreparedInlineObjects = [];
      globalThis.__TiqianExactPreparedRenderCount = 0;
      globalThis.__TiqianPreparedDomRenderer = {
        schema: 1,
        layoutRevision: "tiqian-layout-v2",
        render(host, planJson, locale, options = {}) {
          globalThis.__TiqianExactPreparedRenderCount += 1;
          globalThis.__TiqianExactPreparedPlan = planJson;
          globalThis.__TiqianExactPreparedPlans.push(planJson);
          globalThis.__TiqianExactPreparedSemantics = Array.from(options.semantics || []);
          globalThis.__TiqianExactPreparedCjkStrong = Array.from(options.cjkStrongSemantics || []);
          globalThis.__TiqianExactPreparedSemanticElements =
            Array.from(options.liveSemanticElements || []);
          globalThis.__TiqianExactPreparedInlineObjects = Array.from(options.inlineObjects || []);
          if (globalThis.__TiqianFontBackend) {
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
          }
          const plan = typeof planJson === "string" ? JSON.parse(planJson) : (planJson || {});
          const lines = Array.from(plan.lines || []);
          const inlineStartByOffset = new Map();
          const inlineEndByOffset = new Map();
          for (const edge of Array.from((plan && plan.inlineEdges) || [])) {
            const offset = Number(edge.offset);
            if (edge.inlineStart != null) {
              inlineStartByOffset.set(
                offset,
                (inlineStartByOffset.get(offset) || 0) + Number(edge.inlineStart),
              );
            }
            if (edge.inlineEnd != null) {
              inlineEndByOffset.set(
                offset,
                (inlineEndByOffset.get(offset) || 0) + Number(edge.inlineEnd),
              );
            }
          }
          const inlineObjects = Array.from(options.inlineObjects || [])
            .slice()
            .sort(function (left, right) { return left.start - right.start; });
          // EmphasisDotColorBeforeSwap: computed colors must be read while the
          // live semantic elements are still connected, before the host swap.
          const semanticColors = [];
          for (const element of Array.from(options.liveSemanticElements || [])) {
            let color = "";
            try {
              color = String(globalThis.getComputedStyle(element).color || "");
            } catch (error) {
              color = "";
            }
            if (!color.trim() && element.style) color = String(element.style.color || "");
            semanticColors.push(color.trim());
          }
          host.replaceChildren();
          const marker = document.createElement("span");
          marker.setAttribute("data-tq-exact-rendered", String(locale));
          host.appendChild(marker);
          // Pending plain text flushes only when a different container or an
          // element is appended, so semantic clones attach in source order.
          let pendingText = "";
          let pendingContainer = null;
          const flushText = () => {
            if (pendingContainer) {
              pendingContainer.appendChild(document.createTextNode(pendingText));
            }
            pendingText = "";
            pendingContainer = null;
          };
          const emitText = (container, text) => {
            if (pendingContainer !== container) flushText();
            if (!pendingContainer) pendingContainer = container;
            pendingText += text;
          };
          let containers = null;
          let semanticRoots = [];
          let coveringSignature = function () { return ""; };
          if (options.semanticReplay === "live-source") {
            const semantics = Array.from(options.semantics || []);
            const sourceElements = Array.from(options.liveSemanticElements || []);
            const cjkStrongSemantics = Array.from(options.cjkStrongSemantics || []);
            const roots = [];
            const stack = [];
            for (const semantic of semantics) {
              while (stack.length > 0 && semantic.start >= stack.at(-1).end) stack.pop();
              const node = {
                start: semantic.start,
                end: semantic.end,
                sourceIndex: semantic.sourceIndex,
                children: [],
                clone: null,
              };
              const parent = stack.at(-1);
              if (parent) {
                if (semantic.end > parent.end) throw new Error("CrossingLiveSemanticRanges");
                parent.children.push(node);
              } else {
                roots.push(node);
              }
              stack.push(node);
            }
            // LiveSourceSemanticReplay: geometry renders inside shallow clones
            // of the source elements, created lazily so host child order
            // follows source order. An inline-object range renders as a deep
            // clone of the live element, never as the replacement character
            // that rides the lowered source text.
            const attach = (node, container) => {
              if (!node.clone) {
                const source = sourceElements[node.sourceIndex];
                if (!source) throw new Error("MissingLiveSemanticSource:" + node.sourceIndex);
                const clone = source.cloneNode(false);
                clone.setAttribute("data-tq-source-semantic", "true");
                const cjkStrong = cjkStrongSemantics.find(function (entry) {
                  return Number(entry.start) === node.start && Number(entry.end) === node.end;
                });
                if (cjkStrong) {
                  clone.setAttribute("data-tq-cjk-emphasis", "true");
                  clone.style.setProperty("font-weight", String(cjkStrong.weight), "important");
                }
                node.clone = clone;
              }
              if (!node.clone.parentNode) container.appendChild(node.clone);
              return node.clone;
            };
            const coveringPath = (nodes, start, end, path) => {
              for (const node of nodes) {
                if (start >= node.start && end <= node.end) {
                  return coveringPath(node.children, start, end, path.concat([node]));
                }
              }
              return path;
            };
            const crossingPath = (nodes, offset, path) => {
              let deepest = path;
              for (const node of nodes) {
                if (node.start < offset && offset < node.end) {
                  deepest = crossingPath(node.children, offset, path.concat([node]));
                }
              }
              return deepest;
            };
            const descend = (path) => {
              let container = host;
              for (const node of path) {
                flushText();
                container = attach(node, container);
              }
              return container;
            };
            containers = {
              range: (start, end) => descend(coveringPath(roots, start, end, [])),
              crossing: (offset) => descend(crossingPath(roots, offset, [])),
            };
            semanticRoots = roots;
            coveringSignature = function (start, end) {
              return coveringPath(roots, start, end, [])
                .map(function (node) { return node.sourceIndex; })
                .join("/");
            };
          }
          const spacingEpsilon = 0.01;
          const numberOr = (value, fallback) => {
            const number = Number(value);
            return Number.isFinite(number) ? number : fallback;
          };
          const preparedSpacing = (display, naturalWidth, trailingGap) => {
            if (Math.abs(trailingGap) < spacingEpsilon) return { kind: "none", px: 0 };
            if (display.length === 1 && naturalWidth + trailingGap >= 0) {
              return { kind: "letter", px: trailingGap };
            }
            if (trailingGap < 0) return { kind: "overlap", px: trailingGap };
            return { kind: "trailing-letter", px: trailingGap };
          };
          const featureSignatureOf = (run) => run.openTypeFeatures.join(",");
          const canMergeRun = (left, right) =>
            left.rangeEnd === right.rangeStart &&
            left.semanticSignature === right.semanticSignature &&
            !left.shapingBoundary && !right.shapingBoundary &&
            featureSignatureOf(left) === featureSignatureOf(right) &&
            left.renderFontFamily === right.renderFontFamily &&
            left.dashStrategy == null && right.dashStrategy == null &&
            left.styleSignature === right.styleSignature &&
            left.punctuationSignature === right.punctuationSignature &&
            left.italicEffect === right.italicEffect &&
            ((left.spacing.kind === "none" && right.spacing.kind === "none") ||
              (left.spacing.kind === "letter" && right.spacing.kind === "letter" &&
                Math.abs(left.spacing.px - right.spacing.px) < spacingEpsilon));
          const mergeRun = (left, right) => {
            left.rangeEnd = right.rangeEnd;
            left.source += right.source;
            left.display += right.display;
            left.naturalWidth += right.naturalWidth;
            left.trailingGap += right.trailingGap;
            left.rawTrailingGap += right.rawTrailingGap;
          };
          // Runs and plain text append at the host level when no live-source
          // replay is active; line markers ride alongside as empty spans.
          const emitRun = (run) => {
            const container = containers
              ? containers.range(run.rangeStart, run.rangeEnd)
              : host;
            let text = String(run.display != null ? run.display : "");
            if (text === "" && run.source) text = String(run.source);
            if (text === "") return;
            const features = featureSignatureOf(run);
            const needsElement = run.shapingBoundary || features ||
              run.renderFontFamily != null || run.source !== run.display ||
              run.spacing.kind !== "none" ||
              (run.style && Object.keys(run.style).length > 0) ||
              run.italicEffect || run.dashStrategy != null ||
              run.punctuationInkFloor != null;
            if (!needsElement) {
              emitText(container, text);
              return;
            }
            flushText();
            const runSpan = document.createElement("span");
            runSpan.setAttribute(
              "data-tq-advance",
              String(
                run.spacing.kind === "letter" || run.spacing.kind === "trailing-letter"
                  ? run.naturalWidth + run.trailingGap
                  : run.naturalWidth,
              ),
            );
            runSpan.setAttribute("data-tq-geometry", "true");
            runSpan.setAttribute("data-tq-x", String(run.drawX));
            if (run.shapingBoundary || features) {
              runSpan.setAttribute("data-tq-shaping-boundary", "");
            }
            if (features) {
              runSpan.setAttribute("data-tq-open-type-features", features);
            }
            if (run.source !== run.display) {
              runSpan.setAttribute("data-tq-src", String(run.source));
            }
            if (run.dashStrategy != null) {
              runSpan.setAttribute("data-tq-dash-strategy", String(run.dashStrategy));
              runSpan.setAttribute("data-tq-dash-advance", String(run.naturalWidth));
            }
            if (run.punctuationInkFloor != null) {
              runSpan.setAttribute("data-tq-punctuation-ink-floor", String(run.punctuationInkFloor));
              if (run.punctuationBodyWidth != null) {
                runSpan.setAttribute("data-tq-punctuation-body-width", String(run.punctuationBodyWidth));
              }
            }
            if (run.renderFontFamily != null) {
              runSpan.setAttribute("data-tq-render-font-projection", "true");
              runSpan.style.setProperty("font-family", String(run.renderFontFamily), "important");
            }
            if (run.style && run.style.fontSize != null) {
              runSpan.style.setProperty("font-size", String(run.style.fontSize) + "px", "important");
            }
            if (run.style && run.style.fontWeight != null) {
              runSpan.style.setProperty("font-weight", String(run.style.fontWeight), "important");
            }
            if (run.italicEffect) {
              runSpan.style.setProperty("font-style", "italic", "important");
            } else if (run.style && run.style.italic === false) {
              runSpan.style.setProperty("font-style", "normal", "important");
            }
            if (run.spacing.kind === "letter") {
              runSpan.style.setProperty("letter-spacing", String(run.spacing.px) + "px", "important");
            } else if (run.spacing.kind === "overlap") {
              runSpan.style.setProperty("margin-right", String(run.spacing.px) + "px", "important");
            }
            if (run.spacing.kind === "trailing-letter") {
              runSpan.appendChild(document.createTextNode(text));
              const carrier = document.createElement("span");
              carrier.setAttribute("aria-hidden", "true");
              carrier.setAttribute("data-tq-copy-ignore", "true");
              carrier.setAttribute("data-tq-geometry", "true");
              carrier.setAttribute("data-tq-spacing-carrier", "true");
              carrier.style.setProperty("display", "inline-block", "important");
              carrier.style.setProperty("inline-size", String(run.spacing.px) + "px", "important");
              carrier.style.setProperty("height", "0", "important");
              carrier.style.setProperty("line-height", "0", "important");
              carrier.style.setProperty("letter-spacing", String(run.spacing.px) + "px", "important");
              carrier.style.setProperty("overflow", "hidden", "important");
              carrier.style.setProperty("vertical-align", "baseline", "important");
              carrier.style.setProperty("white-space", "pre", "important");
              carrier.appendChild(document.createTextNode(" "));
              runSpan.appendChild(carrier);
              container.appendChild(runSpan);
              return;
            }
            runSpan.appendChild(document.createTextNode(text));
            container.appendChild(runSpan);
          };
          for (let index = 0; index < lines.length; index++) {
            if (index > 0) {
              const previous = lines[index - 1];
              const engineBreak = document.createElement("br");
              engineBreak.setAttribute(
                "data-tq-engine-break",
                String(previous.endReason || "AutoWrap"),
              );
              if (previous.endReason !== "MandatoryBreak") {
                engineBreak.setAttribute("aria-hidden", "true");
                engineBreak.setAttribute("data-tq-copy-ignore", "true");
              }
              flushText();
              if (containers) containers.crossing(previous.rangeEnd).appendChild(engineBreak);
              else host.appendChild(engineBreak);
            }
            const line = lines[index];
            const cells = Array.from(line.cells || []);
            const first = cells[0];
            const flowStart = first
              ? numberOr(first.drawX, 0) - numberOr(first.leadingLayoutAdvance, 0)
              : 0;
            const firstInlineStart = first ? inlineStartByOffset.get(first.rangeStart) || 0 : 0;
            if (
              first &&
              Math.abs(numberOr(first.leadingLayoutAdvance, 0) - firstInlineStart) > 0.01
            ) {
              throw new Error("SnapshotRenderFlowMismatch:line=" + index + ";leading-layout-advance");
            }
            const runs = [];
            for (let cellIndex = 0; cellIndex < cells.length; cellIndex++) {
              const cell = cells[cellIndex];
              const next = cells[cellIndex + 1];
              const naturalWidth = numberOr(cell.naturalWidth, 0);
              const trailingInlineEdge = inlineEndByOffset.get(cell.rangeEnd) || 0;
              const nextLeadingInlineEdge = next ? inlineStartByOffset.get(next.rangeStart) || 0 : 0;
              const rawTrailingGap = next
                ? numberOr(next.drawX, 0) - numberOr(cell.drawX, 0) - naturalWidth -
                  trailingInlineEdge - nextLeadingInlineEdge
                : numberOr(line.hyphenAdvance, 0) > 0
                  ? 0
                  : numberOr(line.indent, 0) + numberOr(line.visualWidth, 0) -
                    numberOr(cell.drawX, 0) - naturalWidth - trailingInlineEdge;
              const trailingGap = Math.abs(rawTrailingGap) < spacingEpsilon ? 0 : rawTrailingGap;
              runs.push({
                rangeStart: cell.rangeStart,
                rangeEnd: cell.rangeEnd,
                source: cell.source,
                display: cell.display,
                drawX: numberOr(cell.drawX, 0),
                naturalWidth: naturalWidth,
                shapingBoundary: cell.shapingBoundary === true,
                openTypeFeatures: Array.from(cell.openTypeFeatures || [], String),
                renderFontFamily: cell.renderFontFamily != null ? String(cell.renderFontFamily) : null,
                trailingGap: trailingGap,
                rawTrailingGap: rawTrailingGap,
                spacing: preparedSpacing(
                  String(cell.display != null ? cell.display : ""),
                  naturalWidth,
                  trailingGap,
                ),
                style: cell.style || null,
                italicEffect: !!(cell.style && cell.style.italic === true),
                dashStrategy: cell.dashStrategy != null ? cell.dashStrategy : null,
                punctuationInkFloor: cell.punctuationInkFloor != null ? cell.punctuationInkFloor : null,
                punctuationBodyWidth: cell.punctuationBodyWidth != null ? cell.punctuationBodyWidth : null,
                semanticSignature: coveringSignature(cell.rangeStart, cell.rangeEnd),
                styleSignature: JSON.stringify(cell.style || null),
                punctuationSignature: JSON.stringify([
                  cell.punctuationInkFloor != null ? cell.punctuationInkFloor : null,
                  cell.punctuationBodyWidth != null ? cell.punctuationBodyWidth : null,
                ]),
              });
            }
            const children = [];
            let pendingRun = null;
            const flushRun = () => {
              if (pendingRun == null) return;
              children.push({ kind: "run", run: pendingRun });
              pendingRun = null;
            };
            for (const run of runs) {
              const inlineObject = inlineObjects.find(function (entry) {
                return entry.start === run.rangeStart && entry.end === run.rangeEnd;
              });
              if (inlineObject) {
                flushRun();
                children.push({ kind: "inlineObject", entry: inlineObject, run: run });
                continue;
              }
              const record = Object.assign({}, run, { spacing: Object.assign({}, run.spacing) });
              if (pendingRun && canMergeRun(pendingRun, record)) {
                mergeRun(pendingRun, record);
              } else {
                flushRun();
                pendingRun = record;
              }
            }
            flushRun();
            const last = cells[cells.length - 1];
            const flowEnd = last
              ? numberOr(last.drawX, 0) + numberOr(last.naturalWidth, 0) +
                (inlineEndByOffset.get(last.rangeEnd) || 0)
              : 0;
            const hyphenAdvance = numberOr(line.hyphenAdvance, 0);
            const hyphenLeadingGap = hyphenAdvance > 0
              ? numberOr(line.indent, 0) + numberOr(line.visualWidth, 0) - flowEnd
              : 0;
            let inlineEdgeWidth = 0;
            for (const cell of cells) {
              inlineEdgeWidth += (inlineStartByOffset.get(cell.rangeStart) || 0) +
                (inlineEndByOffset.get(cell.rangeEnd) || 0);
            }
            let expectedFlowWidth = flowStart + inlineEdgeWidth + hyphenLeadingGap + hyphenAdvance;
            for (const child of children) {
              // FlowValidationUsesRawGaps: per-cell gaps below the spacing
              // epsilon snap to zero for the emitted spacing, but the flow
              // identity must sum the raw values; a stretched line with many
              // sub-epsilon gaps would otherwise lose n-times-epsilon width
              // and read as an arithmetic mismatch.
              expectedFlowWidth += child.run.naturalWidth + child.run.rawTrailingGap;
            }
            const coreLineWidth =
              numberOr(line.indent, 0) + numberOr(line.visualWidth, 0) + hyphenAdvance;
            if (Math.abs(expectedFlowWidth - coreLineWidth) > 0.01) {
              throw new Error(
                "SnapshotRenderFlowMismatch:line=" + index +
                  ";expected=" + expectedFlowWidth +
                  ";core=" + coreLineWidth +
                  ";flowStart=" + flowStart +
                  ";edges=" + inlineEdgeWidth,
              );
            }
            const markerSpan = document.createElement("span");
            markerSpan.setAttribute("aria-hidden", "true");
            markerSpan.className = "tq-line";
            markerSpan.setAttribute("data-tq-copy-ignore", "true");
            markerSpan.setAttribute("data-tq-geometry", "true");
            markerSpan.setAttribute("data-tq-line-empty", String(cells.length === 0));
            markerSpan.setAttribute("data-tq-line-end", String(line.endReason || "AutoWrap"));
            markerSpan.setAttribute("data-tq-line-top", String(line.top));
            markerSpan.setAttribute("data-tq-line-bottom", String(line.bottom));
            markerSpan.setAttribute("data-tq-line-baseline", String(line.baseline));
            markerSpan.setAttribute("data-tq-line-flow-width", String(expectedFlowWidth));
            markerSpan.setAttribute("data-tq-line-index", String(index));
            markerSpan.setAttribute(
              "data-tq-line-range",
              String(line.rangeStart) + "-" + String(line.rangeEnd),
            );
            markerSpan.setAttribute("data-tq-line-width", String(coreLineWidth));
            markerSpan.setAttribute("data-tq-paragraph-height", String(numberOr(plan.height, 0)));
            markerSpan.style.setProperty(
              "--tq-line-height",
              String(numberOr(line.bottom, 0) - numberOr(line.top, 0)) + "px",
              "important",
            );
            markerSpan.style.setProperty(
              "--tq-line-baseline-offset",
              String(-(numberOr(line.bottom, 0) - numberOr(line.baseline, 0))) + "px",
              "important",
            );
            if (Math.abs(flowStart) >= spacingEpsilon) {
              markerSpan.setAttribute("data-tq-line-shift", "true");
              markerSpan.style.setProperty(
                "--tq-line-flow-start",
                String(flowStart) + "px",
                "important",
              );
            }
            const markerContainer = containers && first
              ? containers.range(first.rangeStart, first.rangeEnd)
              : host;
            flushText();
            markerContainer.appendChild(markerSpan);
            for (const child of children) {
              if (child.kind === "run") {
                emitRun(child.run);
                continue;
              }
              flushText();
              const clone = child.entry.element.cloneNode(true);
              clone.setAttribute("data-tq-inline-object", "true");
              const container = containers
                ? containers.range(child.run.rangeStart, child.run.rangeEnd)
                : host;
              container.appendChild(clone);
            }
            if (hyphenAdvance > 0) {
              flushText();
              const hyphenSpan = document.createElement("span");
              hyphenSpan.setAttribute("aria-hidden", "true");
              hyphenSpan.setAttribute("data-tq-advance", String(hyphenAdvance));
              hyphenSpan.setAttribute("data-tq-copy-ignore", "true");
              hyphenSpan.setAttribute("data-tq-engine-hyphen", "true");
              hyphenSpan.setAttribute("data-tq-geometry", "true");
              hyphenSpan.setAttribute(
                "data-tq-x",
                String(numberOr(line.indent, 0) + numberOr(line.visualWidth, 0)),
              );
              hyphenSpan.setAttribute("lang", String(locale));
              if (Math.abs(hyphenLeadingGap) >= spacingEpsilon) {
                hyphenSpan.style.setProperty(
                  "margin-left",
                  String(hyphenLeadingGap) + "px",
                  "important",
                );
              }
              hyphenSpan.appendChild(document.createTextNode("-"));
              markerContainer.appendChild(hyphenSpan);
            }
            flushText();
            const sentinel = document.createElement("span");
            sentinel.setAttribute("aria-hidden", "true");
            sentinel.setAttribute("data-tq-copy-ignore", "true");
            sentinel.setAttribute("data-tq-geometry", "true");
            sentinel.setAttribute("data-tq-line-end-sentinel", String(index));
            const boundaryContainer = containers
              ? containers.crossing(line.rangeEnd)
              : host;
            boundaryContainer.appendChild(sentinel);
            if (line.endReason === "MandatoryBreak") {
              const hardBreak = document.createElement("span");
              hardBreak.setAttribute("data-tq-geometry", "true");
              hardBreak.setAttribute("data-tq-hard-break", "true");
              hardBreak.setAttribute("data-tq-src", "\n");
              boundaryContainer.appendChild(hardBreak);
            }
          }
          if (lines.length > 0) {
            const selectionEnd = document.createElement("span");
            selectionEnd.setAttribute("aria-hidden", "true");
            selectionEnd.setAttribute("data-tq-copy-ignore", "true");
            selectionEnd.setAttribute("data-tq-selection-end", "true");
            selectionEnd.appendChild(document.createTextNode("​"));
            host.appendChild(selectionEnd);
          }
          const dots = Array.from(plan.emphasisDots || []);
          if (dots.length > 0) {
            const semantics = Array.from(options.semantics || []);
            // EmphasisDotColorBeforeSwap: colors were captured while the live
            // semantic elements were still connected; the elements themselves
            // are detached after the host swap.
            const resolveDotColor = (offset) => {
              if (!semantics.length) return null;
              let maxOrder = -Infinity;
              let selected = null;
              for (const semantic of semantics) {
                if (offset >= Number(semantic.start) && offset < Number(semantic.end)) {
                  const order = Number(semantic.order || 0);
                  if (order > maxOrder) {
                    maxOrder = order;
                    selected = semantic;
                  }
                }
              }
              if (!selected) return null;
              const color = semanticColors[selected.sourceIndex];
              return typeof color === "string" && color.length > 0 ? color : null;
            };
            const svg = document.createElement("svg");
            svg.setAttribute("data-tq-geometry", "true");
            svg.setAttribute(
              "style",
              "--tq-overlay-width:" + Number(plan.overlayWidth) +
                "px;--tq-overlay-height:" + Number(plan.height) + "px",
            );
            for (const dot of dots) {
              const color = resolveDotColor(dot.clusterRangeStart);
              const dotColor = color || "currentColor";
              const circle = document.createElement("circle");
              circle.setAttribute("cx", String(Number(dot.anchorX)));
              circle.setAttribute("cy", String(Number(dot.anchorY)));
              circle.setAttribute("data-tq-decoration-dot", "true");
              circle.setAttribute("fill", dotColor);
              circle.setAttribute("r", String(Number(dot.dotDiameter) / 2));
              circle.setAttribute("style", "--tq-decoration-color:" + dotColor);
              svg.appendChild(circle);
            }
            host.appendChild(svg);
          }
          return {};
        },
        release() { return true; },
        releaseRoot() { return true; },
      };
      globalThis.__TiqianPreparedDomValidator = { issue: () => null };
      };
      if (!globalThis.__TiqianPreparedFixtureOverride) {
        globalThis.__TiqianInstallDefaultPreparedFixture();
      }
    }""",
)
private external fun installDefaultPreparedDomFixtureBridge()
internal fun installDefaultPreparedDomFixture() {
    installDefaultPreparedDomFixtureBridge()
}
@JsFun("() => globalThis.__TiqianExactFontShapeCount || 0")
internal external fun exactFontShapeCount(): Int
@JsFun("() => globalThis.__TiqianExactFontFallbackCount || 0")
internal external fun exactFontFallbackCount(): Int
@JsFun("(detail) => { globalThis.__TiqianPreparedFixtureOverride = true; globalThis.__TiqianPreparedDomValidator = { issue: () => detail }; }")
internal external fun failExactPreparedDomValidation(detail: String)
@JsFun(
    """(detail) => {
      globalThis.__TiqianPreparedFixtureOverride = true;
      const previous = globalThis.__TiqianPreparedDomValidator;
      let spent = false;
      globalThis.__TiqianPreparedDomValidator = {
        issue: (host, width) => {
          if (!spent) { spent = true; return detail; }
          return previous && typeof previous.issue === 'function' ? previous.issue(host, width) : null;
        }
      };
    }""",
)
internal external fun failNextExactPreparedDomValidation(detail: String)
internal fun dispatchEnhanceWithoutOptions(root: HTMLElement) {
    TiqianWeb.enhance(root)
}

internal fun dispatchEnhanceWithStrongAsEmphasisMarks(root: HTMLElement) {
    TiqianWeb.enhance(root, TiqianWeb.EnhanceOptions(strongAsEmphasisMarks = true))
}

internal fun dispatchRelayout(root: HTMLElement) {
    TiqianWeb.relayout(root)
}
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
@JsFun("(element) => element.getBoundingClientRect().width")
internal external fun elementWidth(element: HTMLElement): Double
@JsFun("(element) => Array.from(element.getClientRects()).filter((rect) => rect.width > 0).map((rect) => rect.width)")
internal external fun elementFragmentWidths(element: HTMLElement): Array<Double>

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
