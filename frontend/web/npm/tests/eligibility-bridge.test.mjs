// Unit tests for the eligibility engine module behind ts-runtime.
// core/core/engine/eligibility.js exports the eligibility predicates as
// plain named functions; these tests drive them directly.

import assert from "node:assert/strict";
import test from "node:test";
import { cleanupMounted, mount } from "./runtime-host.mjs";
import {
  shouldTryParagraph,
  isPureBlockImageParagraph,
  hasOpaqueInlineCandidate,
  isNonTextInlineTag,
  isOpaqueInlineDisplay,
  isOpaqueInlineLevelDisplay,
} from "@tiqian/core/core/engine/eligibility.js";
import { initializeGlobalServices } from "@tiqian/core/core/services/global-services.js";
initializeGlobalServices();


test("eligibilityBridge_exportsFullApiSurface", () => {
  for (const predicate of [
    shouldTryParagraph,
    isPureBlockImageParagraph,
    hasOpaqueInlineCandidate,
    isNonTextInlineTag,
    isOpaqueInlineDisplay,
    isOpaqueInlineLevelDisplay,
  ]) {
    assert.equal(typeof predicate, "function", "missing eligibility predicate");
  }
});

test("eligibilityBridge_leafListItemAdmittedNestedListItemRejected", (t) => {
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
  assert.equal(shouldTryParagraph(nestedLi), false);
  assert.equal(shouldTryParagraph(plainLi), true);
});

test("eligibilityBridge_pureBlockImageParagraphRejectedInlineAdmitted", (t) => {
  const root = mount(`
    <div data-tiqian-root="true">
      <p id="block-img-p"><img src="data:," style="display: block" /></p>
      <p id="inline-img-p"><img src="data:," style="display: inline" /></p>
    </div>
  `);
  t.after(cleanupMounted);
  const blockImgP = root.querySelector("#block-img-p");
  const inlineImgP = root.querySelector("#inline-img-p");
  assert.equal(isPureBlockImageParagraph(blockImgP), true);
  assert.equal(shouldTryParagraph(blockImgP), false);
  assert.equal(isPureBlockImageParagraph(inlineImgP), false);
  assert.equal(shouldTryParagraph(inlineImgP), true);
});

test("eligibilityBridge_blankParagraphRejectedUnlessOpaqueCandidate", (t) => {
  const root = mount(`
    <div data-tiqian-root="true">
      <p id="blank-p">   </p>
      <p id="opaque-p"><span style="display: inline-block"></span></p>
    </div>
  `);
  t.after(cleanupMounted);
  const blankP = root.querySelector("#blank-p");
  const opaqueP = root.querySelector("#opaque-p");
  assert.equal(hasOpaqueInlineCandidate(blankP), false);
  assert.equal(shouldTryParagraph(blankP), false);
  assert.equal(hasOpaqueInlineCandidate(opaqueP), true);
  assert.equal(shouldTryParagraph(opaqueP), true);
});

test("eligibilityBridge_skippedAncestorsAndDataAttributeRejected", (t) => {
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
  assert.equal(shouldTryParagraph(notProseP), false);
  assert.equal(shouldTryParagraph(skipAttrP), false);
  assert.equal(shouldTryParagraph(normalP), true);
});

test("eligibilityBridge_tagAndDisplayPredicates", () => {
  assert.equal(isNonTextInlineTag("IMG"), true);
  assert.equal(isNonTextInlineTag("SPAN"), false);
  assert.equal(isOpaqueInlineDisplay("inline-block"), true);
  assert.equal(isOpaqueInlineDisplay("inline"), false);
  assert.equal(isOpaqueInlineLevelDisplay("inline"), true);
});
