// Shared string-markup primitives for prepared DOM lowering.

export function escapeText(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

export function escapeAttribute(value) {
  return escapeText(value).replaceAll('"', "&quot;");
}

export function cssString(value) {
  return `"${String(value).replaceAll("\\", "\\\\").replaceAll('"', '\\"')}"`;
}

export function px(value) {
  const normalized = Math.abs(value) < 0.000001 ? 0 : value;
  return `${Number(normalized.toFixed(5))}px`;
}

export function applyDynamicStyles(attributes, styles, styleClassFor) {
  if (styles.length === 0) return;
  const declaration = styles.join(";");
  if (styleClassFor) {
    const generatedClass = styleClassFor(declaration);
    attributes.class = attributes.class ? `${attributes.class} ${generatedClass}` : generatedClass;
  } else {
    attributes.style = declaration;
  }
}

export function renderedElement(tag, attributes = {}, text = null, voidElement = false) {
  const entries = Object.entries(attributes)
    .filter(([, value]) => value != null)
    .map(([name, value]) => [name, String(value)])
    .sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0);
  const serializedAttributes = entries.map(([name, value]) =>
    value === "" ? name : `${name}="${escapeAttribute(value)}"`).join(" ");
  const opening = `<${tag}${serializedAttributes ? ` ${serializedAttributes}` : ""}>`;
  const children = text == null ? [] : [["#", String(text)]];
  return {
    html: voidElement ? opening : `${opening}${text == null ? "" : escapeText(text)}</${tag}>`,
    artifact: [tag, entries, children],
  };
}

export function renderedContainer(tag, attributes = {}) {
  const entries = Object.entries(attributes)
    .filter(([, value]) => value != null)
    .map(([name, value]) => [name, String(value)])
    .sort(([left], [right]) => left < right ? -1 : left > right ? 1 : 0);
  const children = [];
  return {
    children,
    get html() {
      const serializedAttributes = entries.map(([name, value]) =>
        value === "" ? name : `${name}="${escapeAttribute(value)}"`).join(" ");
      return `<${tag}${serializedAttributes ? ` ${serializedAttributes}` : ""}>` +
        `${children.map((child) => child.html).join("")}</${tag}>`;
    },
    get artifact() {
      return [tag, entries, children.map((child) => child.artifact)];
    },
  };
}

export function renderedText(value) {
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
