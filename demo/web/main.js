import '@tiqian/prose/auto';
import { registerTiqianProse } from '@tiqian/prose/element';
import { createProseHostSession } from '@tiqian/core/core/engine/prose-host-session.js';

// One-shot replay entry for the demo/web tests: parcel bundles module
// instances so the page cannot re-import them by URL, and the retired document
// event channel (ADR 0053 C1) no longer accepts replays. Tests replay captured
// root options through this public surface. Replaces the retired enhance() verb
// (wc-s6 scope 9): options apply through the factory, and mount returns the
// completion promise covering the one-time work, runtime loading included.
globalThis.__tiqianOneShot = (root, options) => createProseHostSession(root, options).mount();

// Explicit registration entry point for programmatic hosts. The /auto entry
// already registers <tiqian-prose> with default options on import, but tests
// and controlled environments can call this directly for parameterized setup.
globalThis.__tiqianRegister = registerTiqianProse;

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
