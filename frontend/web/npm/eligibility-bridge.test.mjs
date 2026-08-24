// Unit tests for the eligibility engine module installed by ts-runtime.
// npm-core/core/engine/eligibility.js installs __TiqianEligibility; these
// tests drive that global directly.

import assert from "node:assert/strict";
import test from "node:test";
import { cleanupMounted, loadHostRuntime, mount } from "./runtime-host.mjs";

test("eligibilityBridge_installedByRuntimeBoot", async () => {
  await loadHostRuntime();
  const eligibility = globalThis.__TiqianEligibility;
  assert.ok(eligibility, "runtime boot must install globalThis.__TiqianEligibility");
  for (const name of [
    "shouldTryParagraph",
    "isPureBlockImageParagraph",
    "hasOpaqueInlineCandidate",
    "isNonTextInlineTag",
    "isOpaqueInlineDisplay",
    "isOpaqueInlineLevelDisplay",
  ]) {
    assert.equal(typeof eligibility[name], "function", "missing bridge method: " + name);
  }
});

test("eligibilityBridge_leafListItemAdmittedNestedListItemRejected", async (t) => {
  await loadHostRuntime();
  const eligibility = globalThis.__TiqianEligibility;
  const root = mount(`
    <div data-tiqian-root="true">
      <ul>
        <li id="nested-li"><p>嵌套列表项中的段落</p></li>
        <li id="plain-li">纯文本列表项</li>
      </ul>
    </div>
  `);
  t.after(cleanupMounted);
  const nestedLi = root.querySelector("#nested-li");
  const plainLi = root.querySelector("#plain-li");
  assert.equal(eligibility.shouldTryParagraph(nestedLi), false);
  assert.equal(eligibility.shouldTryParagraph(plainLi), true);
});

test("eligibilityBridge_pureBlockImageParagraphRejectedInlineAdmitted", async (t) => {
  await loadHostRuntime();
  const eligibility = globalThis.__TiqianEligibility;
  const root = mount(`
    <div data-tiqian-root="true">
      <p id="block-img-p"><img src="data:," style="display: block" /></p>
      <p id="inline-img-p"><img src="data:," style="display: inline" /></p>
    </div>
  `);
  t.after(cleanupMounted);
  const blockImgP = root.querySelector("#block-img-p");
  const inlineImgP = root.querySelector("#inline-img-p");
  assert.equal(eligibility.isPureBlockImageParagraph(blockImgP), true);
  assert.equal(eligibility.shouldTryParagraph(blockImgP), false);
  assert.equal(eligibility.isPureBlockImageParagraph(inlineImgP), false);
  assert.equal(eligibility.shouldTryParagraph(inlineImgP), true);
});

test("eligibilityBridge_blankParagraphRejectedUnlessOpaqueCandidate", async (t) => {
  await loadHostRuntime();
  const eligibility = globalThis.__TiqianEligibility;
  const root = mount(`
    <div data-tiqian-root="true">
      <p id="blank-p">   </p>
      <p id="opaque-p"><span style="display: inline-block"></span></p>
    </div>
  `);
  t.after(cleanupMounted);
  const blankP = root.querySelector("#blank-p");
  const opaqueP = root.querySelector("#opaque-p");
  assert.equal(eligibility.hasOpaqueInlineCandidate(blankP), false);
  assert.equal(eligibility.shouldTryParagraph(blankP), false);
  assert.equal(eligibility.hasOpaqueInlineCandidate(opaqueP), true);
  assert.equal(eligibility.shouldTryParagraph(opaqueP), true);
});

test("eligibilityBridge_skippedAncestorsAndDataAttributeRejected", async (t) => {
  await loadHostRuntime();
  const eligibility = globalThis.__TiqianEligibility;
  const root = mount(`
    <div data-tiqian-root="true">
      <div class="not-prose"><p id="not-prose-p">非散文跳过</p></div>
      <p id="skip-attr-p" data-tiqian-skip="true">标记跳过</p>
      <p id="normal-p">正常段落</p>
    </div>
  `);
  t.after(cleanupMounted);
  const notProseP = root.querySelector("#not-prose-p");
  const skipAttrP = root.querySelector("#skip-attr-p");
  const normalP = root.querySelector("#normal-p");
  assert.equal(eligibility.shouldTryParagraph(notProseP), false);
  assert.equal(eligibility.shouldTryParagraph(skipAttrP), false);
  assert.equal(eligibility.shouldTryParagraph(normalP), true);
});

test("eligibilityBridge_tagAndDisplayPredicates", async () => {
  await loadHostRuntime();
  const eligibility = globalThis.__TiqianEligibility;
  assert.equal(eligibility.isNonTextInlineTag("IMG"), true);
  assert.equal(eligibility.isNonTextInlineTag("SPAN"), false);
  assert.equal(eligibility.isOpaqueInlineDisplay("inline-block"), true);
  assert.equal(eligibility.isOpaqueInlineDisplay("inline"), false);
  assert.equal(eligibility.isOpaqueInlineLevelDisplay("inline"), true);
});
