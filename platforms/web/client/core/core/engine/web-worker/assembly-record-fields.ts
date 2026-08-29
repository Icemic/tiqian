// Generated from ffi/schema/assembly-record.schema.json revision 1. Edit the schema and run python3 tools/schema/generate_ts.py.

export const LAYOUT_REQUEST_FIELDS = Object.freeze([
  "text",
  "maxWidthPx",
  "fontFamilies",
  "fontSizePx",
  "lineHeightPx",
  "locale",
  "fontWeight",
  "italic",
  "firstLineIndentIc",
  "sourceBoundaries",
  "textSpans",
  "inlineBoxes",
  "lineBreakSpans",
  "inlineObjects",
] as const);

export const ASSEMBLY_RECORD_REVISION = 1;

/**
 * @typedef {Object} AssemblyRecordRequest
 * @property {string} text
 * @property {number} maxWidthPx
 * @property {string[]} fontFamilies
 * @property {number} fontSizePx
 * @property {number} lineHeightPx
 * @property {string} locale
 * @property {number} fontWeight
 * @property {boolean} italic
 * @property {number} firstLineIndentIc
 * @property {number[]} sourceBoundaries
 * @property {Array.<{start: number, end: number, fontSizePx: number, fontWeight: number, italic: boolean, baselineShift: number, fontFamilies: string[]}>} textSpans
 * @property {Array.<{start: number, end: number, inlineStart: number, inlineEnd: number, outerSpacing: string}>} inlineBoxes
 * @property {Array.<{start: number, end: number, policy: string}>} lineBreakSpans
 * @property {Array.<{start: number, end: number, advance: number, ascent: number, descent: number}>} inlineObjects
 */
