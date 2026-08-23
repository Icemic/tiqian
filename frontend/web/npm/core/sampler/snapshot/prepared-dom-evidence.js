// Render-evidence lowering (ADR 0053 SinglePlanLowerer step 2): annotation
// spans, inline-object placeholders and SVG overlays built purely from the
// prepared plan's evidence fields. Attribute names and geometry mirror
// DomParagraphRenderer so the plan-driven DOM matches the Kotlin path.

import {
  applyDynamicStyles,
  cssString,
  px,
  renderedContainer,
  renderedElement,
} from "./prepared-dom-markup.js";

const SPACING_EPSILON = 0.01;
// Fallback annotation ascent ratio, mirroring the Kotlin no-metrics branch.
const RUBY_ASCENT_RATIO = 0.8;
const BOPOMOFO_LANG = "zh-Hant-TW";
const BOPOMOFO_TONE_TARGET_INK_WIDTH_SCALE = 0.82;
const BOPOMOFO_TONE_SLASH_INK_WIDTH_EM_REGULAR = 0.404;
const BOPOMOFO_TONE_SLASH_INK_WIDTH_EM_SEMIBOLD = 0.446;
const BOPOMOFO_TONE_CARON_INK_WIDTH_EM_REGULAR = 0.644;
const BOPOMOFO_TONE_CARON_INK_WIDTH_EM_SEMIBOLD = 0.682;
const LINE_THICKNESS_EM = 0.08;
const WAVY_HALF_WAVE_EM = 0.2;
const WAVY_AMPLITUDE_EM = 0.06;
const WAVY_ENDPOINT_EPSILON_PX = 0.01;

export function inlineObjectPlaceholder(cell, trailingGap, styleClassFor) {
  const attributes = {
    "data-tq-advance": String(cell.naturalWidth),
    "data-tq-geometry": "true",
    "data-tq-inline-object": "pending",
    "data-tq-object-range": `${cell.rangeStart}-${cell.rangeEnd}`,
    "data-tq-x": String(cell.drawX),
  };
  applyDynamicStyles(attributes, [
    "display:inline-block!important",
    "box-sizing:border-box!important",
    `inline-size:${px(cell.naturalWidth)}!important`,
    ...(Math.abs(trailingGap) >= SPACING_EPSILON
      ? [`margin-right:${px(trailingGap)}!important`]
      : []),
  ], styleClassFor);
  return renderedElement("span", attributes);
}

export function rubyAnnotationSpan(ruby, lineTop, styleClassFor) {
  const fontSize = Number(ruby.fontSize);
  // RubyAscentRatioFallback: the string builder has no canvas. The measured
  // ascent joins this path only if B7.4 puts it in the plan.
  const ascent = fontSize * RUBY_ASCENT_RATIO;
  const families = Array.from(ruby.fontFamilies ?? [], String);
  const attributes = {
    "data-tq-geometry": "true",
    "data-tq-src": `（${ruby.text}）`,
  };
  applyDynamicStyles(attributes, [
    "color:currentColor!important",
    ...(families.length > 0
      ? [`font-family:${families.map(cssString).join(",")}!important`]
      : []),
    `font-size:${px(fontSize)}!important`,
    `font-weight:${Number(ruby.fontWeight)}!important`,
    `left:${px(Number(ruby.centerX))}!important`,
    "line-height:1!important",
    "position:absolute!important",
    `top:${px(Number(ruby.baselineY) - lineTop - ascent)}!important`,
    "transform:translateX(-50%)!important",
    "white-space:pre!important",
  ], styleClassFor);
  return renderedElement("span", attributes, String(ruby.text));
}

function bopomofoToneInkWidthEm(text, fontWeight) {
  const regular = text === "ˇ"
    ? BOPOMOFO_TONE_CARON_INK_WIDTH_EM_REGULAR
    : BOPOMOFO_TONE_SLASH_INK_WIDTH_EM_REGULAR;
  const semibold = text === "ˇ"
    ? BOPOMOFO_TONE_CARON_INK_WIDTH_EM_SEMIBOLD
    : BOPOMOFO_TONE_SLASH_INK_WIDTH_EM_SEMIBOLD;
  const t = Math.min(Math.max((fontWeight - 400) / 300, 0), 1);
  return regular + (semibold - regular) * t;
}

function bopomofoCssPlacement(text, role, fontWeight, boxLeft, boxTop, boxWidth, boxHeight) {
  if (role === "Symbol") {
    return { left: boxLeft, top: boxTop, fontSize: boxHeight, lineHeight: boxWidth };
  }
  if (role === "Neutral") {
    const fontSize = boxWidth;
    return {
      left: boxLeft,
      top: boxTop + (boxHeight - fontSize) / 2,
      fontSize,
      lineHeight: boxWidth,
    };
  }
  const inkWidthEm = Math.max(
    bopomofoToneInkWidthEm(text, fontWeight),
    0.1,
  );
  const fontSize = boxWidth * BOPOMOFO_TONE_TARGET_INK_WIDTH_SCALE / inkWidthEm;
  return {
    left: boxLeft,
    top: boxTop + (boxHeight - fontSize) / 2,
    fontSize,
    lineHeight: boxWidth,
  };
}

function bopomofoZoneLeft(placements) {
  const symbol = placements.find((placement) => String(placement.role) === "Symbol");
  if (symbol) return Number(symbol.left) - Number(symbol.width) / 9;
  if (placements.length === 0) return 0;
  return Math.min(...placements.map((placement) => Number(placement.left)));
}

export function bopomofoAnnotationSpan(z, width, lineTop, lineHeight, styleClassFor) {
  const fontWeight = Number(z.fontWeight);
  const families = Array.from(z.fontFamilies ?? [], String);
  const placements = Array.from(z.placements ?? []);
  const attributes = {
    "data-tq-geometry": "true",
    "data-tq-src": `（${z.text}）`,
    lang: BOPOMOFO_LANG,
  };
  applyDynamicStyles(attributes, [
    "box-sizing:border-box!important",
    "display:inline-block!important",
    `height:${px(lineHeight)}!important`,
    `line-height:${px(lineHeight)}!important`,
    "overflow:visible!important",
    "position:relative!important",
    "user-select:all!important",
    "vertical-align:top!important",
    "-webkit-user-select:all!important",
    "white-space:pre!important",
    `width:${px(width)}!important`,
  ], styleClassFor);
  const container = renderedContainer("span", attributes);
  const zoneLeft = bopomofoZoneLeft(placements);
  for (const placement of placements) {
    const text = String(placement.text);
    const css = bopomofoCssPlacement(
      text,
      String(placement.role),
      fontWeight,
      Number(placement.left),
      Number(placement.top),
      Number(placement.width),
      Number(placement.height),
    );
    const glyphAttributes = {
      "data-tq-geometry": "true",
      lang: BOPOMOFO_LANG,
    };
    applyDynamicStyles(glyphAttributes, [
      "color:currentColor!important",
      ...(families.length > 0
        ? [`font-family:${families.map(cssString).join(",")}!important`]
        : []),
      "font-feature-settings:'vert' 1, 'vrt2' 1!important",
      `font-size:${px(css.fontSize)}!important`,
      "font-style:normal!important",
      `font-weight:${fontWeight}!important`,
      `left:${px(css.left - zoneLeft)}!important`,
      `line-height:${px(css.lineHeight)}!important`,
      "overflow:visible!important",
      "pointer-events:none!important",
      "position:absolute!important",
      `top:${px(css.top - lineTop)}!important`,
      "white-space:pre!important",
      "display:inline-block!important",
      "text-orientation:upright!important",
      "writing-mode:vertical-rl!important",
    ], styleClassFor);
    container.children.push(renderedElement("span", glyphAttributes, text));
  }
  return container;
}

function wavyLinePath(left, right, y, fontSize) {
  const halfWave = Math.max(fontSize * WAVY_HALF_WAVE_EM, 1);
  const amplitude = fontSize * WAVY_AMPLITUDE_EM;
  const path = [`M ${left} ${y}`];
  let x = left;
  let up = true;
  while (x < right - WAVY_ENDPOINT_EPSILON_PX) {
    const rawNextX = x + halfWave;
    const nextX = rawNextX >= right - WAVY_ENDPOINT_EPSILON_PX ? right : rawNextX;
    const controlY = up ? y - amplitude * 2 : y + amplitude * 2;
    path.push(` Q ${(x + nextX) / 2} ${controlY} ${nextX} ${y}`);
    x = nextX;
    up = !up;
  }
  return path.join("");
}

function overlayAttributes(width, height) {
  return {
    "aria-hidden": "true",
    "data-tq-copy-ignore": "true",
    "data-tq-geometry": "true",
    style: `--tq-overlay-width:${px(width)};--tq-overlay-height:${px(height)}`,
  };
}

// Appends the engine-owned interlinear and emphasis overlays after the flow
// content.
export function appendEvidenceOverlays(nodes, plan) {
  const segments = Array.from(plan.decorationSegments ?? []);
  const dots = Array.from(plan.emphasisDots ?? []);
  if (segments.length > 0) {
    const fontSize = Number(plan.fontSize);
    const width = Number(plan.overlayWidth);
    const height = Number(plan.height);
    if (!Number.isFinite(fontSize) || !Number.isFinite(width) || !Number.isFinite(height)) {
      throw new Error("InvalidPreparedOverlayGeometry");
    }
    const strokeWidth = fontSize * LINE_THICKNESS_EM;
    const svg = renderedContainer("svg", overlayAttributes(width, height));
    for (const segment of segments) {
      const left = Number(segment.left);
      const top = Number(segment.top);
      const right = Number(segment.right);
      const style = `--tq-decoration-color:currentColor;--tq-decoration-stroke-width:${px(strokeWidth)}`;
      if (segment.kind === "ProperNoun") {
        svg.children.push(renderedElement("line", {
          "data-tq-decoration-line": "true",
          stroke: "currentColor",
          style,
          "stroke-linecap": "butt",
          "stroke-width": String(strokeWidth),
          x1: String(left),
          x2: String(right),
          y1: String(top),
          y2: String(top),
        }));
      } else if (segment.kind === "BookTitle") {
        svg.children.push(renderedElement("path", {
          "data-tq-decoration-wave": "true",
          d: wavyLinePath(left, right, top, fontSize),
          fill: "none",
          stroke: "currentColor",
          style,
          "stroke-linecap": "butt",
          "stroke-linejoin": "round",
          "stroke-width": String(strokeWidth),
        }));
      } else {
        throw new Error(`UnsupportedPreparedDecorationSegment:${segment.kind}`);
      }
    }
    nodes.push(svg);
  }
  if (dots.length > 0) {
    const width = Number(plan.overlayWidth);
    const height = Number(plan.height);
    if (!Number.isFinite(width) || !Number.isFinite(height)) {
      throw new Error("InvalidPreparedOverlayGeometry");
    }
    const svg = renderedContainer("svg", overlayAttributes(width, height));
    for (const dot of dots) {
      svg.children.push(renderedElement("circle", {
        cx: String(Number(dot.anchorX)),
        cy: String(Number(dot.anchorY)),
        "data-tq-decoration-dot": "true",
        fill: "currentColor",
        r: String(Number(dot.dotDiameter) / 2),
        style: "--tq-decoration-color:currentColor",
      }));
    }
    nodes.push(svg);
  }
}
