import assert from "node:assert/strict";
import test from "node:test";

import { registerSnapshotBundle } from "../src/sampler/snapshot/snapshot-client.js";
import type { ClientSnapshotBundle } from "../src/sampler/snapshot/snapshot-client.js";

interface FakeElement {
  tagName: string;
  attributes: Map<string, string>;
  setAttribute: SetAttributeFn;
  rel?: string;
  as?: string;
}

type SetAttributeFn = (key: string, value: string) => void;

interface FakeTemplateContent {
  firstElementChild: FakeTemplateNode | null;
  childElementCount: number;
  querySelector: ContentQuerySelectorFn;
}

type ContentQuerySelectorFn = (selector: string) => QuerySelectorResult | null;

interface QuerySelectorResult {
  textContent: string;
}

interface FakeTemplateNode {
  tagName: string;
  id: string;
  content: TemplateContent;
  replaceWith: ReplaceWithFn;
}

interface TemplateContent {
  querySelector: ContentQuerySelectorFn;
}

type ReplaceWithFn = (replacement: FakeTemplateNode) => void;

type HeadChild = FakeElement | FakeTemplateNode;

interface FakeDocument {
  baseURI: string;
  head: DocumentObjectHead;
  createElement: DocumentObjectCreateElement;
  getElementById: DocumentObjectGetElementById;
  querySelector: DocumentObjectQuerySelector;
  querySelectorAll: DocumentObjectQuerySelectorAll;
}

interface DocumentObjectHead {
  append: DocumentObjectHeadAppend;
}

type DocumentObjectHeadAppend = (node: HeadChild) => void;

type DocumentObjectCreateElement = (name: string) => unknown;

type DocumentObjectGetElementById = (id: string) => FakeTemplateNode | null;

type DocumentObjectQuerySelector = (selector: string) => FakeElement | null;

type DocumentObjectQuerySelectorAll = (selector: string) => FakeElement[];

interface FakeDocumentSetup {
  documentObject: FakeDocument;
  elements: Map<string, HeadChild>;
  headChildren: HeadChild[];
  replacements: GetReplacementsCountFn;
}

type GetReplacementsCountFn = () => number;

type TemplateNodeFactory = (id: string, manifest: string) => FakeTemplateNode;

function isFakeElement(node: HeadChild): node is FakeElement {
  return "attributes" in node;
}

function fakeDocument(): FakeDocumentSetup {
  const elements = new Map<string, HeadChild>();
  const headChildren: HeadChild[] = [];
  let replacements = 0;

  const templateNode: TemplateNodeFactory = (id: string, manifest: string): FakeTemplateNode => ({
    tagName: "TEMPLATE",
    id,
    content: {
      querySelector(selector) {
        return selector === "[data-tq-snapshot-manifest]" ? { textContent: manifest } : null;
      },
    },
    replaceWith(replacement) {
      replacements += 1;
      elements.set(id, replacement);
    },
  });

  const headAppend: DocumentObjectHeadAppend = (node) => {
    headChildren.push(node);
    if (node.tagName === "TEMPLATE") elements.set((node as FakeTemplateNode).id, node);
  };

  const documentObjectHead: DocumentObjectHead = {
    append: headAppend,
  };

  const createElementImpl: DocumentObjectCreateElement = (name) => {
    if (name === "template") {
      const content: FakeTemplateContent = { firstElementChild: null, childElementCount: 0, querySelector: () => null };
      return {
        content,
        set innerHTML(value: string) {
          const id = /<template\s+id="([^"]+)"/u.exec(value)?.[1] ?? "";
          const manifest = /<script[^>]*data-tq-snapshot-manifest[^>]*>([^<]*)<\/script>/u
            .exec(value)?.[1] ?? "";
          content.firstElementChild = templateNode(id, manifest);
          content.childElementCount = 1;
        },
      };
    }
    const element: FakeElement = {
      tagName: name.toUpperCase(),
      attributes: new Map(),
      setAttribute(key, value) {
        this.attributes.set(key, value);
      },
    };
    if (name === "link") {
      let href = "";
      Object.defineProperty(element, "href", {
        get: () => href,
        set: (value: string) => {
          // Using hardcoded baseURI to avoid circular reference
          href = new URL(value, "https://example.test/post/").href;
        },
      });
    }
    return element;
  };

  const getElementByIdImpl: DocumentObjectGetElementById = (id) => {
    return (elements.get(id) as FakeTemplateNode) ?? null;
  };

  const querySelectorImpl: DocumentObjectQuerySelector = (selector) => {
    if (!selector.startsWith("style[")) return null;
    const found = headChildren.find((node) => node.tagName === "STYLE" &&
      isFakeElement(node) &&
      (node.attributes.get("data-tq-initial-snapshot") === "tq-page" ||
        node.attributes.get("data-tq-client-snapshot") === "tq-page"));
    return (found as FakeElement) ?? null;
  };

  const querySelectorAllImpl: DocumentObjectQuerySelectorAll = (selector) => {
    assert.equal(selector, 'link[rel="preload"][as="font"]');
    return headChildren.filter(isPreloadFontLink);
  };

  const documentObject: FakeDocument = {
    baseURI: "https://example.test/post/",
    head: documentObjectHead,
    createElement: createElementImpl,
    getElementById: getElementByIdImpl,
    querySelector: querySelectorImpl,
    querySelectorAll: querySelectorAllImpl,
  };
  elements.set("tq-page", templateNode("tq-page", "stale"));
  return { documentObject, elements, headChildren, replacements: () => replacements };
}

function isPreloadFontLink(node: HeadChild): node is FakeElement {
  return node.tagName === "LINK" &&
    isFakeElement(node) &&
    node.rel === "preload" && node.as === "font";
}

test("client navigation replaces a stale manifest and registers assets once", () => {
  const setup = fakeDocument();
  const bundle: ClientSnapshotBundle = {
    id: "tq-page",
    clientTemplate: '<template id="tq-page"><script data-tq-snapshot-manifest>{"v":1}</script></template>',
    initialStyle: ':root{--fixture:1}',
    fontPreloads: ["/fonts/fixture-deadbeef.woff2"],
  };

  const docRef = setup.documentObject as unknown;
  assert.equal(registerSnapshotBundle(bundle, docRef as Document), "tq-page");
  assert.equal(setup.replacements(), 1);
  const templateAfterFirst = setup.elements.get("tq-page") as FakeTemplateNode;
  if (templateAfterFirst == null) throw new Error("Template not found after first registration");
  const manifestElement = templateAfterFirst.content.querySelector("[data-tq-snapshot-manifest]");
  if (manifestElement == null) throw new Error("Manifest element not found");
  assert.equal(
    manifestElement.textContent,
    '{"v":1}',
  );
  const styleCount = setup.headChildren.filter((node) => node.tagName === "STYLE").length;
  assert.equal(styleCount, 1);
  const linkCount = setup.headChildren.filter((node) => node.tagName === "LINK").length;
  assert.equal(linkCount, 1);

  assert.equal(registerSnapshotBundle(bundle, docRef as Document), "tq-page");
  assert.equal(setup.replacements(), 1);
  const styleCountAfterSecond = setup.headChildren.filter((node) => node.tagName === "STYLE").length;
  assert.equal(styleCountAfterSecond, 1);
  const linkCountAfterSecond = setup.headChildren.filter((node) => node.tagName === "LINK").length;
  assert.equal(linkCountAfterSecond, 1);
});

test("client snapshot registration rejects an id/template mismatch", () => {
  const setup = fakeDocument();
  const docRef = setup.documentObject as unknown;
  type RegisterSnapshotBundleFn = (bundle: ClientSnapshotBundle, documentObject?: Document) => string;
  const registerFn: RegisterSnapshotBundleFn = registerSnapshotBundle;
  assert.throws(() => registerFn({
    id: "tq-page",
    clientTemplate: '<template id="tq-other"></template>',
    initialStyle: "",
    fontPreloads: [],
  }, docRef as Document), /SnapshotClientTemplateInvalid/u);
});
