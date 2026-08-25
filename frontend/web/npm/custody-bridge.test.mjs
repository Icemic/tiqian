// Unit tests for the custody engine module behind ts-runtime.
// npm-core/core/engine/custody.js exports createCustody(); these tests drive
// a factory-constructed instance directly.

import assert from "node:assert/strict";
import test from "node:test";
import { cleanupMounted, mount } from "./runtime-host.mjs";
import { createCustody } from "@tiqian/prose-core/core/engine/custody.js";

const custody = createCustody();

function custodyParagraph(t, markup) {
  const root = mount(markup);
  t.after(cleanupMounted);
  return root.querySelector("p");
}

function beginDefaults(paragraph) {
  custody.begin(
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

test("custodyBridge_exportsFullApiSurface", () => {
  for (const name of [
    "begin",
    "take",
    "commit",
    "stampRendered",
    "renderedMatches",
    "custodyMatches",
    "captureLive",
    "rollback",
    "restoreParagraph",
    "restoreShell",
    "ensureContainingBlock",
  ]) {
    assert.equal(typeof custody[name], "function", "missing api method: " + name);
  }
});

test("custodyBridge_takeMovesSourceIntoCustodyAndCommitPublishes", (t) => {
  const paragraph = custodyParagraph(t, "<div data-tiqian-root='true'><p>语义正文先托管。</p></div>");
  const child = paragraph.firstChild;
  assert.ok(child);

  beginDefaults(paragraph);
  custody.take(paragraph, null);

  assert.equal(paragraph.firstChild, null);
  assert.equal(custody.custodyMatches(paragraph), false);
  assert.equal(paragraph.__tqCustodyFragment, undefined);

  custody.commit(paragraph, null);

  const fragment = paragraph.__tqCustodyFragment;
  assert.ok(fragment);
  assert.equal(fragment.firstChild, child);
  assert.equal(custody.custodyMatches(paragraph), true);
  assert.equal(paragraph.__tqCustodyForwarding, true);
});

test("custodyBridge_hostCommitsRouteIntoCustody", (t) => {
  const paragraph = custodyParagraph(t, "<div data-tiqian-root='true'><p>宿主提交要进入托管。</p></div>");
  beginDefaults(paragraph);
  custody.take(paragraph, null);
  custody.commit(paragraph, null);

  const node = globalThis.document.createTextNode("宿主新增");
  paragraph.appendChild(node);
  assert.equal(node.parentNode, paragraph.__tqCustodyFragment);
  assert.equal(custody.custodyMatches(paragraph), false);

  paragraph.removeChild(node);
  assert.equal(node.parentNode, null);
  assert.equal(custody.custodyMatches(paragraph), true);
});

test("custodyBridge_engineWritesBypassForwarding", (t) => {
  const paragraph = custodyParagraph(t, "<div data-tiqian-root='true'><p>引擎写入走原生。</p></div>");
  beginDefaults(paragraph);
  custody.take(paragraph, null);
  custody.commit(paragraph, null);

  paragraph.__tqCustodyEngineWrites = 1;
  const node = globalThis.document.createTextNode("引擎输出");
  paragraph.appendChild(node);
  assert.equal(node.parentNode, paragraph);
  paragraph.__tqCustodyEngineWrites = 0;

  const hostNode = globalThis.document.createTextNode("宿主输出");
  paragraph.appendChild(hostNode);
  assert.equal(hostNode.parentNode, paragraph.__tqCustodyFragment);
});

test("custodyBridge_renderedDriftDetection", (t) => {
  const paragraph = custodyParagraph(t, "<div data-tiqian-root='true'><p>渲染漂移要能被发现。</p></div>");
  beginDefaults(paragraph);
  custody.take(paragraph, null);
  custody.commit(paragraph, null);

  paragraph.__tqCustodyEngineWrites = 1;
  const rendered = globalThis.document.createElement("span");
  paragraph.appendChild(rendered);
  paragraph.__tqCustodyEngineWrites = 0;
  custody.stampRendered(paragraph);
  assert.equal(custody.renderedMatches(paragraph), true);

  paragraph.__tqCustodyEngineWrites = 1;
  paragraph.removeChild(rendered);
  paragraph.__tqCustodyEngineWrites = 0;
  assert.equal(custody.renderedMatches(paragraph), false);

  custody.stampRendered(paragraph);
  assert.equal(custody.renderedMatches(paragraph), true);
});

test("custodyBridge_captureLiveRollbackRoundTrip", (t) => {
  const paragraph = custodyParagraph(t, "<div data-tiqian-root='true'><p>回滚要复现快照内容。</p></div>");
  beginDefaults(paragraph);
  custody.take(paragraph, null);
  custody.commit(paragraph, null);

  paragraph.__tqCustodyEngineWrites = 1;
  const rendered = globalThis.document.createElement("span");
  paragraph.appendChild(rendered);
  paragraph.setAttribute("data-tq-rendered", "true");
  paragraph.setAttribute("lang", "zh-Hans");
  paragraph.__tqCustodyEngineWrites = 0;
  custody.stampRendered(paragraph);

  const snapshot = custody.captureLive(paragraph, 3.5);
  assert.equal(paragraph.firstChild, null);
  assert.equal(snapshot.content.firstChild, rendered);
  assert.equal(snapshot.lastMeasure, 3.5);
  assert.equal(snapshot.originalContentHadChildren, true);
  assert.equal(snapshot.renderedAttribute, "true");
  assert.equal(snapshot.langAttribute, "zh-Hans");

  paragraph.__tqCustodyEngineWrites = 1;
  const newer = globalThis.document.createElement("div");
  paragraph.appendChild(newer);
  paragraph.setAttribute("lang", "ja");
  paragraph.__tqCustodyEngineWrites = 0;

  const results = custody.rollback([snapshot]);
  assert.equal(results.length, 1);
  assert.equal(results[0].source, paragraph);
  assert.equal(results[0].lastMeasure, 3.5);
  assert.equal(paragraph.firstChild, rendered);
  assert.equal(paragraph.getAttribute("lang"), "zh-Hans");
  assert.equal(paragraph.getAttribute("data-tq-rendered"), "true");
  assert.equal(custody.renderedMatches(paragraph), true);
});

test("custodyBridge_rollbackReadoptsCustodyAfterRestore", (t) => {
  const paragraph = custodyParagraph(t, "<div data-tiqian-root='true'><p>恢复后再回滚要重新收养。</p></div>");
  const originalChild = paragraph.firstChild;
  beginDefaults(paragraph);
  custody.take(paragraph, null);
  custody.commit(paragraph, null);

  paragraph.__tqCustodyEngineWrites = 1;
  const rendered = globalThis.document.createElement("span");
  paragraph.appendChild(rendered);
  paragraph.__tqCustodyEngineWrites = 0;
  custody.stampRendered(paragraph);

  const snapshot = custody.captureLive(paragraph, null);
  assert.equal(snapshot.content.firstChild, rendered);
  assert.equal(snapshot.originalContentHadChildren, true);

  custody.restoreParagraph(paragraph);
  assert.equal(paragraph.firstChild, originalChild);
  assert.equal(paragraph.__tqCustodyFragment.firstChild, null);

  const results = custody.rollback([snapshot]);
  assert.equal(results.length, 1);
  assert.equal(paragraph.firstChild, rendered);
  assert.equal(paragraph.__tqCustodyFragment.firstChild, originalChild);
  assert.equal(custody.custodyMatches(paragraph), true);
  assert.equal(custody.renderedMatches(paragraph), true);
});

test("custodyBridge_restoreParagraphRestoresShell", (t) => {
  const paragraph = custodyParagraph(
    t,
    "<div data-tiqian-root='true'><p>还原要清掉引擎覆盖。</p></div>",
  );
  // The fake style proxy does not parse the style attribute into its map,
  // so initialize the host-owned declaration through the proxy itself.
  paragraph.style.setProperty("width", "10px");
  const originalChild = paragraph.firstChild;
  custody.begin(
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
  custody.take(paragraph, "18px");
  paragraph.style.setProperty("font-size", "18px", "important");
  custody.commit(paragraph, "123px");
  paragraph.setAttribute("data-tq-rendered", "true");
  paragraph.setAttribute("data-tq-runtime-render-font", "true");
  paragraph.style.setProperty("inline-size", "123px", "important");
  paragraph.setAttribute("data-tq-host-inline-size", "true");

  custody.restoreParagraph(paragraph);

  assert.equal(paragraph.firstChild, originalChild);
  assert.equal(paragraph.getAttribute("data-tq-rendered"), null);
  assert.equal(paragraph.getAttribute("data-tq-runtime-render-font"), null);
  assert.equal(paragraph.getAttribute("data-tq-host-inline-size"), null);
  assert.equal(paragraph.style.getPropertyValue("inline-size"), "");
  assert.equal(paragraph.style.getPropertyValue("font-size"), "");
  assert.equal(paragraph.getAttribute("style"), "width: 10px");
});

test("custodyBridge_restoreShellKeepsOriginalInlineSize", (t) => {
  const paragraph = custodyParagraph(
    t,
    "<div data-tiqian-root='true'><p>原始 inline-size 要写回。</p></div>",
  );
  paragraph.style.setProperty("inline-size", "55px");
  custody.begin(
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
  custody.take(paragraph, null);
  custody.commit(paragraph, "123px");
  paragraph.style.setProperty("inline-size", "123px", "important");
  paragraph.setAttribute("data-tq-host-inline-size", "true");

  custody.restoreShell(paragraph);

  assert.equal(paragraph.style.getPropertyValue("inline-size"), "55px");
  assert.equal(paragraph.style.getPropertyPriority("inline-size"), "");
});

test("custodyBridge_ensureContainingBlockAppliesAndRestores", (t) => {
  const paragraph = custodyParagraph(t, "<div data-tiqian-root='true'><p>生成包含块。</p></div>");
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
  custody.ensureContainingBlock(paragraph);
  assert.equal(paragraph.style.getPropertyValue("position"), "relative");
  assert.equal(paragraph.style.getPropertyPriority("position"), "important");

  custody.ensureContainingBlock(paragraph);
  assert.equal(paragraph.style.getPropertyValue("position"), "relative");

  custody.restoreShell(paragraph);
  assert.equal(paragraph.style.getPropertyValue("position"), "");
  assert.equal(paragraph.getAttribute("style"), null);
  // restoreShell only touches attributes and inline style; the live children
  // stay untouched, so the text child is still present.
  assert.ok(paragraph.firstChild);
});
