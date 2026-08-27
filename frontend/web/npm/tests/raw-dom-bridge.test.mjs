// Unit tests for the raw-DOM backup engine module behind ts-runtime.
// core/core/engine/raw-dom.js exports named functions; these tests drive
// them directly with an enhance context.

import assert from "node:assert/strict";
import test from "node:test";
import { cleanupMounted, mount } from "./runtime-host.mjs";
import {
  rawDomBegin,
  rawDomTake,
  rawDomCommit,
  rawDomStampRendered,
  rawDomRenderedMatches,
  rawDomMatches,
  rawDomCaptureLive,
  rawDomRollback,
  rawDomRestoreParagraph,
  rawDomRestoreShell,
  rawDomEnsureContainingBlock,
  rawDomSuspendEngineWrites,
} from "@tiqian/core/core/engine/raw-dom.js";
import { createEnhanceContext } from "@tiqian/core/core/engine/context/enhance-context.js";
import { initializeGlobalServices } from "@tiqian/core/core/services/global-services.js";
initializeGlobalServices();


function rawDomParagraph(t, markup) {
  const root = mount(markup);
  t.after(cleanupMounted);
  return root.querySelector("p");
}

function beginDefaults(context, paragraph) {
  rawDomBegin(
    context,
    paragraph,
    null,
    null,
    null,
    null,
    null,
    null,
    "",
    "",
    "",
    "",
    "",
    "",
    null,
  );
}

import * as rawDomModule from "@tiqian/core/core/engine/raw-dom.js";

test("rawDomBridge_exportsFullApiSurface", () => {
  for (const name of [
    "rawDomBegin",
    "rawDomTake",
    "rawDomCommit",
    "rawDomStampRendered",
    "rawDomRenderedMatches",
    "rawDomMatches",
    "rawDomCaptureLive",
    "rawDomRollback",
    "rawDomRestoreParagraph",
    "rawDomRestoreShell",
    "rawDomEnsureContainingBlock",
    "rawDomSuspendEngineWrites",
  ]) {
    assert.equal(typeof rawDomModule[name], "function", "missing export: " + name);
  }
});

test("rawDomBridge_takeMovesSourceIntoRawDomAndCommitPublishes", (t) => {
  const paragraph = rawDomParagraph(t, "<div data-tiqian-root='true'><p>语义正文先托管。</p></div>");
  const context = createEnhanceContext(paragraph);
  const child = paragraph.firstChild;
  assert.ok(child);

  beginDefaults(context, paragraph);
  rawDomTake(context, paragraph, null);

  assert.equal(paragraph.firstChild, null);
  assert.equal(rawDomMatches(context, paragraph), false);
  assert.equal(context.rawDomParagraphs.get(paragraph)?.fragment, null);

  rawDomCommit(context, paragraph, null);

  const fragment = context.rawDomParagraphs.get(paragraph)?.fragment;
  assert.ok(fragment);
  assert.equal(fragment.firstChild, child);
  assert.equal(rawDomMatches(context, paragraph), true);
  assert.equal(context.rawDomParagraphs.get(paragraph)?.forwarding, true);
});

test("rawDomBridge_hostCommitsRouteIntoRawDom", (t) => {
  const paragraph = rawDomParagraph(t, "<div data-tiqian-root='true'><p>宿主提交要进入托管。</p></div>");
  const context = createEnhanceContext(paragraph);
  beginDefaults(context, paragraph);
  rawDomTake(context, paragraph, null);
  rawDomCommit(context, paragraph, null);

  const node = globalThis.document.createTextNode("宿主新增");
  paragraph.appendChild(node);
  assert.equal(node.parentNode, context.rawDomParagraphs.get(paragraph)?.fragment);
  assert.equal(rawDomMatches(context, paragraph), false);

  paragraph.removeChild(node);
  assert.equal(node.parentNode, null);
  assert.equal(rawDomMatches(context, paragraph), true);
});

test("rawDomBridge_engineWritesBypassForwarding", (t) => {
  const paragraph = rawDomParagraph(t, "<div data-tiqian-root='true'><p>引擎写入走原生。</p></div>");
  const context = createEnhanceContext(paragraph);
  beginDefaults(context, paragraph);
  rawDomTake(context, paragraph, null);
  rawDomCommit(context, paragraph, null);

  rawDomSuspendEngineWrites(context, paragraph, () => {
    const node = globalThis.document.createTextNode("引擎输出");
    paragraph.appendChild(node);
    assert.equal(node.parentNode, paragraph);
  });

  const hostNode = globalThis.document.createTextNode("宿主输出");
  paragraph.appendChild(hostNode);
  assert.equal(hostNode.parentNode, context.rawDomParagraphs.get(paragraph)?.fragment);
});

test("rawDomBridge_renderedDriftDetection", (t) => {
  const paragraph = rawDomParagraph(t, "<div data-tiqian-root='true'><p>渲染漂移要能被发现。</p></div>");
  const context = createEnhanceContext(paragraph);
  beginDefaults(context, paragraph);
  rawDomTake(context, paragraph, null);
  rawDomCommit(context, paragraph, null);

  let rendered;
  rawDomSuspendEngineWrites(context, paragraph, () => {
    rendered = globalThis.document.createElement("span");
    paragraph.appendChild(rendered);
  });
  rawDomStampRendered(context, paragraph);
  assert.equal(rawDomRenderedMatches(context, paragraph), true);

  rawDomSuspendEngineWrites(context, paragraph, () => {
    paragraph.removeChild(rendered);
  });
  assert.equal(rawDomRenderedMatches(context, paragraph), false);

  rawDomStampRendered(context, paragraph);
  assert.equal(rawDomRenderedMatches(context, paragraph), true);
});

test("rawDomBridge_captureLiveRollbackRoundTrip", (t) => {
  const paragraph = rawDomParagraph(t, "<div data-tiqian-root='true'><p>回滚要复现快照内容。</p></div>");
  const context = createEnhanceContext(paragraph);
  beginDefaults(context, paragraph);
  rawDomTake(context, paragraph, null);
  rawDomCommit(context, paragraph, null);

  let rendered;
  rawDomSuspendEngineWrites(context, paragraph, () => {
    rendered = globalThis.document.createElement("span");
    paragraph.appendChild(rendered);
    paragraph.setAttribute("data-tq-rendered", "true");
    paragraph.setAttribute("lang", "zh-Hans");
  });
  rawDomStampRendered(context, paragraph);

  const snapshot = rawDomCaptureLive(context, paragraph, 3.5);
  assert.equal(paragraph.firstChild, null);
  assert.equal(snapshot.content.firstChild, rendered);
  assert.equal(snapshot.lastMeasure, 3.5);
  assert.equal(snapshot.originalContentHadChildren, true);
  assert.equal(snapshot.renderedAttribute, "true");
  assert.equal(snapshot.langAttribute, "zh-Hans");

  rawDomSuspendEngineWrites(context, paragraph, () => {
    const newer = globalThis.document.createElement("div");
    paragraph.appendChild(newer);
    paragraph.setAttribute("lang", "ja");
  });

  const results = rawDomRollback(context, [snapshot]);
  assert.equal(results.length, 1);
  assert.equal(results[0].source, paragraph);
  assert.equal(results[0].lastMeasure, 3.5);
  assert.equal(paragraph.firstChild, rendered);
  assert.equal(paragraph.getAttribute("lang"), "zh-Hans");
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(rawDomRenderedMatches(context, paragraph), true);
});

test("rawDomBridge_rollbackReadoptsRawDomAfterRestore", (t) => {
  const paragraph = rawDomParagraph(t, "<div data-tiqian-root='true'><p>恢复后再回滚要重新收养。</p></div>");
  const context = createEnhanceContext(paragraph);
  const originalChild = paragraph.firstChild;
  beginDefaults(context, paragraph);
  rawDomTake(context, paragraph, null);
  rawDomCommit(context, paragraph, null);

  let rendered;
  rawDomSuspendEngineWrites(context, paragraph, () => {
    rendered = globalThis.document.createElement("span");
    paragraph.appendChild(rendered);
  });
  rawDomStampRendered(context, paragraph);

  const snapshot = rawDomCaptureLive(context, paragraph, null);
  assert.equal(snapshot.content.firstChild, rendered);
  assert.equal(snapshot.originalContentHadChildren, true);

  rawDomRestoreParagraph(context, paragraph);
  assert.equal(paragraph.firstChild, originalChild);
  assert.equal(context.rawDomParagraphs.get(paragraph)?.fragment.firstChild, null);

  const results = rawDomRollback(context, [snapshot]);
  assert.equal(results.length, 1);
  assert.equal(paragraph.firstChild, rendered);
  assert.equal(context.rawDomParagraphs.get(paragraph)?.fragment.firstChild, originalChild);
  assert.equal(rawDomMatches(context, paragraph), true);
  assert.equal(rawDomRenderedMatches(context, paragraph), true);
});

test("rawDomBridge_restoreParagraphRestoresShell", (t) => {
  const paragraph = rawDomParagraph(
    t,
    "<div data-tiqian-root='true'><p>还原要清掉引擎覆盖。</p></div>",
  );
  // The fake style proxy does not parse the style attribute into its map,
  // so initialize the host-owned declaration through the proxy itself.
  paragraph.style.setProperty("width", "10px");
  const originalChild = paragraph.firstChild;
  const context = createEnhanceContext(paragraph);
  rawDomBegin(
    context,
    paragraph,
    null,
    null,
    null,
    null,
    null,
    paragraph.getAttribute("style"),
    "",
    "",
    "",
    "",
    "",
    "",
    null,
  );
  rawDomTake(context, paragraph, "18px");
  paragraph.style.setProperty("font-size", "18px", "important");
  rawDomCommit(context, paragraph, "123px");
  paragraph.setAttribute("data-tq-rendered", "true");
  paragraph.setAttribute("data-tq-runtime-render-font", "true");
  paragraph.style.setProperty("inline-size", "123px", "important");
  paragraph.setAttribute("data-tq-host-inline-size", "true");

  rawDomRestoreParagraph(context, paragraph);

  assert.equal(paragraph.firstChild, originalChild);
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
  assert.equal(paragraph.getAttribute("data-tq-runtime-render-font"), null);
  assert.equal(paragraph.getAttribute("data-tq-host-inline-size"), null);
  assert.equal(paragraph.style.getPropertyValue("inline-size"), "");
  assert.equal(paragraph.style.getPropertyValue("font-size"), "");
  assert.equal(paragraph.getAttribute("style"), "width: 10px");
});

test("rawDomBridge_restoreShellKeepsOriginalInlineSize", (t) => {
  const paragraph = rawDomParagraph(
    t,
    "<div data-tiqian-root='true'><p>原始 inline-size 要写回。</p></div>",
  );
  paragraph.style.setProperty("inline-size", "55px");
  const context = createEnhanceContext(paragraph);
  rawDomBegin(
    context,
    paragraph,
    null,
    null,
    null,
    null,
    null,
    paragraph.getAttribute("style"),
    "",
    "",
    "55px",
    "",
    "",
    "",
    null,
  );
  rawDomTake(context, paragraph, null);
  rawDomCommit(context, paragraph, "123px");
  paragraph.style.setProperty("inline-size", "123px", "important");
  paragraph.setAttribute("data-tq-host-inline-size", "true");

  rawDomRestoreShell(context, paragraph);

  assert.equal(paragraph.style.getPropertyValue("inline-size"), "55px");
  assert.equal(paragraph.style.getPropertyPriority("inline-size"), "");
});

test("rawDomBridge_ensureContainingBlockAppliesAndRestores", (t) => {
  const paragraph = rawDomParagraph(t, "<div data-tiqian-root='true'><p>生成包含块。</p></div>");
  const context = createEnhanceContext(paragraph);
  const realGetComputedStyle = globalThis.getComputedStyle;
  globalThis.getComputedStyle = (element, pseudo) => {
    const style = realGetComputedStyle(element, pseudo);
    return {
      getPropertyValue(name) {
        if (name === "position") return "static";
        return style.getPropertyValue(name);
      },
    };
  };
  t.after(() => {
    globalThis.getComputedStyle = realGetComputedStyle;
  });

  beginDefaults(context, paragraph);
  rawDomEnsureContainingBlock(context, paragraph);
  assert.equal(paragraph.style.getPropertyValue("position"), "relative");
  assert.equal(paragraph.style.getPropertyPriority("position"), "important");

  rawDomEnsureContainingBlock(context, paragraph);
  assert.equal(paragraph.style.getPropertyValue("position"), "relative");

  rawDomRestoreShell(context, paragraph);
  assert.equal(paragraph.style.getPropertyValue("position"), "");
  assert.equal(paragraph.getAttribute("style"), null);
  // restoreShell only touches attributes and inline style; the live children
  // stay untouched, so the text child is still present.
  assert.ok(paragraph.firstChild);
});
