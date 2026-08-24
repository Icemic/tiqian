// Shared string-markup primitives for prepared DOM lowering.

export interface MarkupAttributes {
  class?: string;
  style?: string;
  [name: string]: unknown;
}

// Structure snapshot a markup node carries for digest and evidence consumers:
// element and container nodes carry [tag, sorted entries, child artifacts];
// text nodes carry ["#", text].
export type MarkupArtifact =
  | readonly [string, readonly (readonly [string, string])[], readonly MarkupArtifact[]]
  | readonly [string, string];

export interface MarkupNode {
  readonly html: string;
  readonly artifact: MarkupArtifact;
}

export interface MarkupContainer extends MarkupNode {
  children: MarkupNode[];
}

type MarkupStyleClassForFn = (declaration: string) => string;

export function escapeText(value: unknown): string {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

export function escapeAttribute(value: unknown): string {
  return escapeText(value).replaceAll('"', "&quot;");
}

export function cssString(value: unknown): string {
  return `"${String(value).replaceAll("\\", "\\\\").replaceAll('"', '\\"')}"`;
}

export function px(value: number): string {
  const normalized = Math.abs(value) < 0.000001 ? 0 : value;
  return `${Number(normalized.toFixed(5))}px`;
}

export function applyDynamicStyles(attributes: MarkupAttributes, styles: readonly string[], styleClassFor?: MarkupStyleClassForFn | null): void {
  if (styles.length === 0) return;
  const declaration = styles.join(";");
  if (styleClassFor) {
    const generatedClass = styleClassFor(declaration);
    attributes.class = attributes.class ? `${attributes.class} ${generatedClass}` : generatedClass;
  } else {
    attributes.style = declaration;
  }
}

export function renderedElement(tag: string, attributes: MarkupAttributes = {}, text: string | null = null, voidElement: boolean = false): MarkupNode {
  const entries: [string, string][] = Object.entries(attributes)
    .filter(([, value]) => value != null)
    .map(([name, value]): [string, string] => [name, String(value)])
    .sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0);
  const serializedAttributes = entries.map(([name, value]) =>
    value === "" ? name : `${name}="${escapeAttribute(value)}"`).join(" ");
  const opening = `<${tag}${serializedAttributes ? ` ${serializedAttributes}` : ""}>`;
  const children: MarkupArtifact[] = text == null ? [] : [["#", String(text)]];
  return {
    html: voidElement ? opening : `${opening}${text == null ? "" : escapeText(text)}</${tag}>`,
    artifact: [tag, entries, children],
  };
}

export function renderedContainer(tag: string, attributes: MarkupAttributes = {}): MarkupContainer {
  const entries: [string, string][] = Object.entries(attributes)
    .filter(([, value]) => value != null)
    .map(([name, value]): [string, string] => [name, String(value)])
    .sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0);
  const children: MarkupNode[] = [];
  return {
    children,
    get html() {
      const serializedAttributes = entries.map(([name, value]) =>
        value === "" ? name : `${name}="${escapeAttribute(value)}"`).join(" ");
      return `<${tag}${serializedAttributes ? ` ${serializedAttributes}` : ""}>` +
        `${children.map((child) => child.html).join("")}</${tag}>`;
    },
    get artifact(): MarkupArtifact {
      return [tag, entries, children.map((child) => child.artifact)];
    },
  };
}

export function renderedText(value: string): MarkupNode {
  const text = String(value);
  return {
    html: escapeText(text),
    // CanonicalSnapshotTextNode: this must be byte-for-byte the same shape as
    // precomputed.js derives from the browser-parsed template DOM. A synthetic
    // `#text` element wrapper made every sparse/native Text node snapshot miss
    // with SnapshotArtifactDigestMismatch.
    artifact: ["#", text],
  };
}
