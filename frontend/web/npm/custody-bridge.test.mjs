// Unit tests for the custody engine embedded in the Kotlin runtime bundle.
// The generator embeds npm/core/engine/custody.js into tiqian-web.js; these
// tests drive the installed globalThis.__TiqianCustody copy directly.

import assert from "node:assert/strict";
import test from "node:test";
import { cleanupMounted, loadHostRuntime, mount } from "./runtime-host.mjs";

function custodyParagraph(t, markup) {
  const root = mount(markup);
  t.after(cleanupMounted);
  return root.querySelector("p");
}

function beginDefaults(paragraph) {
  globalThis.__TiqianCustody.begin(
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

test("custodyBridge_installedByRuntimeBoot", async (t) => {
  await loadHostRuntime();
  const custody = globalThis.__TiqianCustody;
  assert.ok(custody, "runtime boot must install globalThis.__TiqianCustody");
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
    assert.equal(typeof custody[name], "function", "missing bridge method: " + name);
  }
});

test("custodyBridge_takeMovesSourceIntoCustodyAndCommitPublishes", async (t) => {
  await loadHostRuntime();
  const custody = globalThis.__TiqianCustody;
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

test("custodyBridge_hostCommitsRouteIntoCustody", async (t) => {
  await loadHostRuntime();
  const custody = globalThis.__TiqianCustody;
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

test("custodyBridge_engineWritesBypassForwarding", async (t) => {
  await loadHostRuntime();
  const custody = globalThis.__TiqianCustody;
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

test("custodyBridge_renderedDriftDetection", async (t) => {
  await loadHostRuntime();
  const custody = globalThis.__TiqianCustody;
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

test("custodyBridge_captureLiveRollbackRoundTrip", async (t) => {
  await loadHostRuntime();
  const custody = globalThis.__TiqianCustody;
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

test("custodyBridge_rollbackReadoptsCustodyAfterRestore", async (t) => {
  await loadHostRuntime();
  const custody = globalThis.__TiqianCustody;
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

test("custodyBridge_restoreParagraphRestoresShell", async (t) => {
  await loadHostRuntime();
  const custody = globalThis.__TiqianCustody;
  const paragraph = custodyParagraph(
    t,
    "<div data-tiqian-root='true'><p>还原要清掉引擎覆盖。</p></div>",
  );
  // The fake style proxy does not parse the style attribute into its map,
  // so initialize the host-owned declaration through the proxy itself.
  paragraph.style.setProperty("width", "10px");
  const originalChild = paragraph.firstChild;
  globalThis.__TiqianCustody.begin(
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

test("custodyBridge_restoreShellKeepsOriginalInlineSize", async (t) => {
  await loadHostRuntime();
  const custody = globalThis.__TiqianCustody;
  const paragraph = custodyParagraph(
    t,
    "<div data-tiqian-root='true'><p>原始 inline-size 要写回。</p></div>",
  );
  paragraph.style.setProperty("inline-size", "55px");
  globalThis.__TiqianCustody.begin(
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

test("custodyBridge_ensureContainingBlockAppliesAndRestores", async (t) => {
  await loadHostRuntime();
  const custody = globalThis.__TiqianCustody;
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

test("custodyBridge_existingInstallWinsOverEmbeddedScript", async () => {
  const sentinel = { marker: "host-installed" };
  const previous = globalThis.__TiqianCustody;
  globalThis.__TiqianCustody = sentinel;
  try {
    await loadHostRuntime();
    assert.notEqual(globalThis.__TiqianCustody, undefined);
    // The embedded script must not replace an existing installation.
    // loadHostRuntime may reuse an already-loaded module; either way the
    // sentinel or the embedded bridge must be present, never an error state.
    assert.ok(globalThis.__TiqianCustody === sentinel || typeof globalThis.__TiqianCustody.begin === "function");
    if (globalThis.__TiqianCustody !== sentinel) {
      globalThis.__TiqianCustody = sentinel;
      assert.equal(typeof globalThis.__TiqianCustody.begin, undefined);
    }
  } finally {
    if (previous === undefined) {
      delete globalThis.__TiqianCustody;
    } else {
      globalThis.__TiqianCustody = previous;
    }
  }
});
