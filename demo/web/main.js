import '@tiqian/prose/element';

// Fixed Benchmark HUD Controls
const slider = document.getElementById('width-slider');
const widthVal = document.getElementById('width-val');
const pageWrapper = document.querySelector('.page-wrapper');
const relayoutMsVal = document.getElementById('relayout-ms-val');
const fpsVal = document.getElementById('fps-val');
const proseElements = document.querySelectorAll('tiqian-prose');

// 1. Fluid Width Slider
if (slider && pageWrapper && widthVal) {
  slider.addEventListener('input', (e) => {
    const width = e.target.value;
    widthVal.textContent = width + 'px';
    pageWrapper.style.maxWidth = width + 'px';
  });
}

// 2. Monitor Tiqian Relayout Latency
window.addEventListener('tiqian:relayout-ready', (e) => {
  if (e.detail && typeof e.detail.durationMs === 'number') {
    relayoutMsVal.textContent = e.detail.durationMs.toFixed(1) + 'ms (' + (e.detail.enhancedCount || 0) + '段)';
  }
});

window.addEventListener('tiqian:ready', (e) => {
  if (e.detail && typeof e.detail.durationMs === 'number') {
    relayoutMsVal.textContent = e.detail.durationMs.toFixed(1) + 'ms (' + (e.detail.enhancedCount || 0) + '段)';
  }
});

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

console.log('[Tiqian Demo] Initialized with ' + proseElements.length + ' <tiqian-prose> roots.');
