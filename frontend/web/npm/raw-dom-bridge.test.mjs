// Unit tests for the raw-DOM backup engine module behind ts-runtime.
// npm-core/core/engine/raw-dom.js exports deriveRawDom(); these tests drive
// a factory-constructed instance directly.

import assert from "node:assert/strict";
import test from "node:test";
import { cleanupMounted, mount } from "./runtime-host.mjs";
import { deriveRawDom } from "@tiqian/prose-core/core/engine/raw-dom.js";
import { getOrCreateEnhanceContext } from "@tiqian/prose-core/core/engine/context/enhance-context.js";

const rawDom = deriveRawDom();

function rawDomParagraph(t, markup) {
  const root = mount(markup);
  t.after(cleanupMounted);
  return root.querySelector("p");
}

function beginDefaults(paragraph) {
  rawDom.begin(
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

test("rawDomBridge_exportsFullApiSurface", () => {
  for (const name of [
    "begin",
    "take",
    "commit",
    "stampRendered",
    "renderedMatches",
    "rawDomMatches",
    "captureLive",
    "rollback",
    "restoreParagraph",
    "restoreShell",
    "ensureContainingBlock",
  ]) {
    assert.equal(typeof rawDom[name], "function", "missing api method: " + name);
  }
});

test("rawDomBridge_takeMovesSourceIntoRawDomAndCommitPublishes", (t) => {
  const paragraph = rawDomParagraph(t, "<div data-tiqian-root='true'><p>语义正文先托管。</p></div>");
  const child = paragraph.firstChild;
  assert.ok(child);

  beginDefaults(paragraph);
  rawDom.take(paragraph, null);

  assert.equal(paragraph.firstChild, null);
  assert.equal(rawDom.rawDomMatches(paragraph), false);
  assert.equal(getOrCreateEnhanceContext(paragraph).rawDomParagraphs.get(paragraph)?.fragment, undefined);

  rawDom.commit(paragraph, null);

  const fragment = getOrCreateEnhanceContext(paragraph).rawDomParagraphs.get(paragraph)?.fragment;
  assert.ok(fragment);
  assert.equal(fragment.firstChild, child);
  assert.equal(rawDom.rawDomMatches(paragraph), true);
  assert.equal(getOrCreateEnhanceContext(paragraph).rawDomParagraphs.get(paragraph)?.forwarding, true);
});

test("rawDomBridge_hostCommitsRouteIntoRawDom", (t) => {
  const paragraph = rawDomParagraph(t, "<div data-tiqian-root='true'><p>宿主提交要进入托管。</p></div>");
  beginDefaults(paragraph);
  rawDom.take(paragraph, null);
  rawDom.commit(paragraph, null);

  const node = globalThis.document.createTextNode("宿主新增");
  paragraph.appendChild(node);
  assert.equal(node.parentNode, getOrCreateEnhanceContext(paragraph).rawDomParagraphs.get(paragraph)?.fragment);
  assert.equal(rawDom.rawDomMatches(paragraph), false);

  paragraph.removeChild(node);
  assert.equal(node.parentNode, null);
  assert.equal(rawDom.rawDomMatches(paragraph), true);
});

test("rawDomBridge_engineWritesBypassForwarding", (t) => {
  const paragraph = rawDomParagraph(t, "<div data-tiqian-root='true'><p>引擎写入走原生。</p></div>");
  beginDefaults(paragraph);
  rawDom.take(paragraph, null);
  rawDom.commit(paragraph, null);

  rawDom.suspendEngineWrites(paragraph, () => {
    const node = globalThis.document.createTextNode("引擎输出");
    paragraph.appendChild(node);
    assert.equal(node.parentNode, paragraph);
  });

  const hostNode = globalThis.document.createTextNode("宿主输出");
  paragraph.appendChild(hostNode);
  assert.equal(hostNode.parentNode, getOrCreateEnhanceContext(paragraph).rawDomParagraphs.get(paragraph)?.fragment);
});

test("rawDomBridge_renderedDriftDetection", (t) => {
  const paragraph = rawDomParagraph(t, "<div data-tiqian-root='true'><p>渲染漂移要能被发现。</p></div>");
  beginDefaults(paragraph);
  rawDom.take(paragraph, null);
  rawDom.commit(paragraph, null);

  let rendered;
  rawDom.suspendEngineWrites(paragraph, () => {
    rendered = globalThis.document.createElement("span");
    paragraph.appendChild(rendered);
  });
  rawDom.stampRendered(paragraph);
  assert.equal(rawDom.renderedMatches(paragraph), true);

  rawDom.suspendEngineWrites(paragraph, () => {
    paragraph.removeChild(rendered);
  });
  assert.equal(rawDom.renderedMatches(paragraph), false);

  rawDom.stampRendered(paragraph);
  assert.equal(rawDom.renderedMatches(paragraph), true);
});

test("rawDomBridge_captureLiveRollbackRoundTrip", (t) => {
  const paragraph = rawDomParagraph(t, "<div data-tiqian-root='true'><p>回滚要复现快照内容。</p></div>");
  beginDefaults(paragraph);
  rawDom.take(paragraph, null);
  rawDom.commit(paragraph, null);

  let rendered;
  rawDom.suspendEngineWrites(paragraph, () => {
    rendered = globalThis.document.createElement("span");
    paragraph.appendChild(rendered);
    paragraph.setAttribute("data-tq-rendered", "true");
    paragraph.setAttribute("lang", "zh-Hans");
  });
  rawDom.stampRendered(paragraph);

  const snapshot = rawDom.captureLive(paragraph, 3.5);
  assert.equal(paragraph.firstChild, null);
  assert.equal(snapshot.content.firstChild, rendered);
  assert.equal(snapshot.lastMeasure, 3.5);
  assert.equal(snapshot.originalContentHadChildren, true);
  assert.equal(snapshot.renderedAttribute, "true");
  assert.equal(snapshot.langAttribute, "zh-Hans");

  rawDom.suspendEngineWrites(paragraph, () => {
    const newer = globalThis.document.createElement("div");
    paragraph.appendChild(newer);
    paragraph.setAttribute("lang", "ja");
  });

  const results = rawDom.rollback([snapshot]);
  assert.equal(results.length, 1);
  assert.equal(results[0].source, paragraph);
  assert.equal(results[0].lastMeasure, 3.5);
  assert.equal(paragraph.firstChild, rendered);
  assert.equal(paragraph.getAttribute("lang"), "zh-Hans");
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(rawDom.renderedMatches(paragraph), true);
});

test("rawDomBridge_rollbackReadoptsRawDomAfterRestore", (t) => {
  const paragraph = rawDomParagraph(t, "<div data-tiqian-root='true'><p>恢复后再回滚要重新收养。</p></div>");
  const originalChild = paragraph.firstChild;
  beginDefaults(paragraph);
  rawDom.take(paragraph, null);
  rawDom.commit(paragraph, null);

  let rendered;
  rawDom.suspendEngineWrites(paragraph, () => {
    rendered = globalThis.document.createElement("span");
    paragraph.appendChild(rendered);
  });
  rawDom.stampRendered(paragraph);

  const snapshot = rawDom.captureLive(paragraph, null);
  assert.equal(snapshot.content.firstChild, rendered);
  assert.equal(snapshot.originalContentHadChildren, true);

  rawDom.restoreParagraph(paragraph);
  assert.equal(paragraph.firstChild, originalChild);
  assert.equal(getOrCreateEnhanceContext(paragraph).rawDomParagraphs.get(paragraph)?.fragment.firstChild, null);

  const results = rawDom.rollback([snapshot]);
  assert.equal(results.length, 1);
  assert.equal(paragraph.firstChild, rendered);
  assert.equal(getOrCreateEnhanceContext(paragraph).rawDomParagraphs.get(paragraph)?.fragment.firstChild, originalChild);
  assert.equal(rawDom.rawDomMatches(paragraph), true);
  assert.equal(rawDom.renderedMatches(paragraph), true);
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
  rawDom.begin(
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
  rawDom.take(paragraph, "18px");
  paragraph.style.setProperty("font-size", "18px", "important");
  rawDom.commit(paragraph, "123px");
  paragraph.setAttribute("data-tq-rendered", "true");
  paragraph.setAttribute("data-tq-runtime-render-font", "true");
  paragraph.style.setProperty("inline-size", "123px", "important");
  paragraph.setAttribute("data-tq-host-inline-size", "true");

  rawDom.restoreParagraph(paragraph);

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
  rawDom.begin(
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
  rawDom.take(paragraph, null);
  rawDom.commit(paragraph, "123px");
  paragraph.style.setProperty("inline-size", "123px", "important");
  paragraph.setAttribute("data-tq-host-inline-size", "true");

  rawDom.restoreShell(paragraph);

  assert.equal(paragraph.style.getPropertyValue("inline-size"), "55px");
  assert.equal(paragraph.style.getPropertyPriority("inline-size"), "");
});

test("rawDomBridge_ensureContainingBlockAppliesAndRestores", (t) => {
  const paragraph = rawDomParagraph(t, "<div data-tiqian-root='true'><p>生成包含块。</p></div>");
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

  beginDefaults(paragraph);
  rawDom.ensureContainingBlock(paragraph);
  assert.equal(paragraph.style.getPropertyValue("position"), "relative");
  assert.equal(paragraph.style.getPropertyPriority("position"), "important");

  rawDom.ensureContainingBlock(paragraph);
  assert.equal(paragraph.style.getPropertyValue("position"), "relative");

  rawDom.restoreShell(paragraph);
  assert.equal(paragraph.style.getPropertyValue("position"), "");
  assert.equal(paragraph.getAttribute("style"), null);
  // restoreShell only touches attributes and inline style; the live children
  // stay untouched, so the text child is still present.
  assert.ok(paragraph.firstChild);
});
