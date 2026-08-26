import '@tiqian/prose/element';
import { enhance } from '@tiqian/prose';
import { getContextForElement } from '@tiqian/core/core/engine/context/enhance-context.js';

// One-shot replay entry for the demo/web tests: parcel bundles module
// instances so the page cannot re-import them by URL, and the retired document
// event channel (ADR 0053 C1) no longer accepts replays. Tests replay captured
// root options through this public surface. The returned promise resolves when
// the one-shot runtime work (including runtime loading) has finished.
globalThis.__tiqianOneShot = (root, options) => enhance(root, options);

// Host-side test probe: ADR 0053 rules that the product carries no DOM
// property for the raw-DOM fragment, so tests dig through this seam instead;
// the library face is unchanged. The engine keys both the per-element context
// and the rawDomParagraphs entry by the paragraph element itself.
globalThis.__tiqianRawDomFragment = (paragraph) => {
  const context = getContextForElement(paragraph);
  const record = context?.rawDomParagraphs.get(paragraph);
  return record?.fragment ?? null;
};

// Fixed Benchmark HUD Controls
const slider = document.getElementById('width-slider');
const widthVal = document.getElementById('width-val');
const pageWrapper = document.querySelector('.page-wrapper');
const relayoutMsVal = document.getElementById('relayout-ms-val');
const fpsVal = document.getElementById('fps-val');

// 1. Fluid Width Slider
if (slider && pageWrapper && widthVal) {
  slider.addEventListener('input', (e) => {
    const width = Number(e.target.value);
    widthVal.textContent = width + 'px';
    pageWrapper.style.maxWidth = width + 'px';
  });
}

// 2. Monitor Tiqian Relayout Latency
function handleMetrics(e) {
  if (e.detail && typeof e.detail.durationMs === 'number' && relayoutMsVal) {
    relayoutMsVal.textContent = e.detail.durationMs.toFixed(1) + 'ms';
  }
}
document.addEventListener('tiqian:relayout-ready', handleMetrics);
document.addEventListener('tiqian:ready', handleMetrics);

// 3. Live 60 FPS Monitor
let frameCount = 0;
let lastTime = performance.now();
function updateFps() {
  frameCount++;
  const now = performance.now();
  if (now - lastTime >= 500) {
    const fps = Math.round((frameCount * 1000) / (now - lastTime));
    if (fpsVal) fpsVal.textContent = fps + ' FPS';
    frameCount = 0;
    lastTime = now;
  }
  requestAnimationFrame(updateFps);
}
requestAnimationFrame(updateFps);
