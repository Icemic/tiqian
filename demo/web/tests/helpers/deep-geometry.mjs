// Shared box-by-box geometry capture. Injected into the page as
// __deepGeometry(), then diffed on the node side with diffDeepGeometry().
// The measured surface: every tiqian-prose root box, every paragraph box,
// every line-marker span box (zero-width spans whose positions carry the
// line breaks), and a box for every direct child of each paragraph — run
// elements through getBoundingClientRect, text nodes through Range. This is
// browser-measured layout truth, independent of the geometry attributes the
// engine writes into the DOM.

export const DEEP_GEOMETRY_HELPERS = `
  globalThis.__deepGeometry = () => {
    const round = (v) => Math.round(v * 100) / 100;
    const sx = scrollX;
    const sy = scrollY;
    const boxOf = (r) => [round(r.x + sx), round(r.y + sy), round(r.width), round(r.height)];
    const elBox = (el) => boxOf(el.getBoundingClientRect());
    const textBox = (node) => {
      const range = document.createRange();
      range.selectNodeContents(node);
      return boxOf(range.getBoundingClientRect());
    };
    return {
      pageHeight: document.documentElement.scrollHeight,
      roots: Array.from(document.querySelectorAll("tiqian-prose")).map((root, ri) => ({
        root: elBox(root),
        paras: Array.from(root.querySelectorAll("p, li")).map((p, pi) => ({
          key: ri + ":" + pi,
          rect: elBox(p),
          lineMarks: Array.from(p.querySelectorAll("[data-tq-line-index]"), elBox),
          kids: Array.from(p.childNodes)
            .filter((n) => n.nodeType === 1 || (n.nodeType === 3 && n.data.length > 0))
            .map((n) => ({ k: n.nodeType === 3 ? "t" : "e", b: n.nodeType === 3 ? textBox(n) : elBox(n) })),
        })),
      })),
    };
  };
`;

// Compares two __deepGeometry() reports box by box. Returns the compared
// box count (a vacuity guard for callers), the divergent box count, and up
// to ten located examples. Structural mismatches (root, paragraph, or child
// count differences, page height) surface as examples with explicit labels.
export function diffDeepGeometry(a, b) {
  const stats = { equal: false, boxesCompared: 0, divergentBoxes: 0, examples: [] };
  const note = (msg) => {
    if (stats.examples.length < 10) stats.examples.push(msg);
  };
  const cmpBox = (x, y, path) => {
    stats.boxesCompared += 1;
    const same = Array.isArray(x) && Array.isArray(y) &&
      x.length === y.length && x.every((v, i) => v === y[i]);
    if (!same) {
      stats.divergentBoxes += 1;
      note(`${path} [${(x ?? []).join(",")}] vs [${(y ?? []).join(",")}]`);
    }
  };
  if ((a?.pageHeight ?? -1) !== (b?.pageHeight ?? -1)) {
    note(`pageHeight ${a?.pageHeight} vs ${b?.pageHeight}`);
  }
  const rootsA = a?.roots ?? [];
  const rootsB = b?.roots ?? [];
  if (rootsA.length !== rootsB.length) note(`rootCount ${rootsA.length} vs ${rootsB.length}`);
  rootsA.forEach((rootA, ri) => {
    const rootB = rootsB[ri];
    if (!rootB) return;
    cmpBox(rootA.root, rootB.root, `root#${ri}`);
    const parasA = rootA.paras ?? [];
    const parasB = rootB.paras ?? [];
    if (parasA.length !== parasB.length) note(`root#${ri} paraCount ${parasA.length} vs ${parasB.length}`);
    parasA.forEach((paraA, pi) => {
      const paraB = parasB[pi];
      if (!paraB) return;
      const tag = `p${paraA.key ?? pi}`;
      cmpBox(paraA.rect, paraB.rect, `${tag}.rect`);
      const marksA = paraA.lineMarks ?? [];
      const marksB = paraB.lineMarks ?? [];
      if (marksA.length !== marksB.length) note(`${tag} lineMarkCount ${marksA.length} vs ${marksB.length}`);
      marksA.forEach((box, mi) => cmpBox(box, marksB[mi], `${tag}.lineMark[${mi}]`));
      const kidsA = paraA.kids ?? [];
      const kidsB = paraB.kids ?? [];
      if (kidsA.length !== kidsB.length) note(`${tag} childCount ${kidsA.length} vs ${kidsB.length}`);
      kidsA.forEach((kidA, ki) => {
        const kidB = kidsB[ki];
        if (kidB && kidA.k !== kidB.k) note(`${tag}.kids[${ki}] kind ${kidA.k} vs ${kidB.k}`);
        cmpBox(kidA?.b, kidB?.b, `${tag}.kids[${ki}](${kidA?.k ?? "?"})`);
      });
    });
  });
  stats.equal = stats.divergentBoxes === 0 && stats.examples.length === 0;
  return stats;
}

// Counts of the measured surfaces, used to prove a comparison was not
// vacuous (a page that failed to enhance measures zero line markers).
export function deepGeometryCounts(report) {
  let lineMarks = 0;
  let runEls = 0;
  let textNodes = 0;
  for (const root of report?.roots ?? []) {
    for (const para of root.paras ?? []) {
      lineMarks += (para.lineMarks ?? []).length;
      for (const kid of para.kids ?? []) {
        if (kid.k === "t") textNodes += 1;
        else runEls += 1;
      }
    }
  }
  return { lineMarks, runEls, textNodes };
}
