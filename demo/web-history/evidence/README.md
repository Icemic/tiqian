# oneshot-bisect evidence archive

Historical diagnostic reference for the `b6498412..1ad320ce` box-geometry
history study (spec `.agent-specs/wc-arch-correction/spec-oneshot-bisect.md`,
report `oneshot-bisect-report.md` in this directory). Retained so future
geometry diagnostics can compare against recorded runs instead of
re-bisecting the same range.

Layout:

- `<commit>.tar.xz` — one archive per sampled commit (40 points, xz -9e).
  Each contains the raw run records for that point: probe runs `1..N.json`,
  central verification re-runs `901+.json`, and chain captures
  `chain-p*.json`.
- `chain-diffs/pair-01..30.json` — adjacent-pair diffs, kept plain for
  direct reading. Each records both sides' counts, `equal`,
  `divergentBoxes`/`boxesCompared`, and examples.
- `manifest.json` — per-point run counts (probe / central / divergent /
  invalid), chain samples, eras, archive sizes, and the pair table.
- `oneshot-bisect-report.md` — the study report (copy of the agent
  original).
- `central-verification.md` — the central re-verification record
  (independent boundary re-runs, audits, and the post-merge extension
  check through `72e95777`).

Browse a point with:

```sh
tar -I xz -xf demo/web-history/evidence/bed4c791.tar.xz -C /tmp/inspect
less /tmp/inspect/bed4c791/1.json
```

Comparison semantics are frozen in `demo/web/tests/helpers/deep-geometry.mjs`;
the kit (`demo/web-history/oneshot-history-harness.diag.ts`) inlines them
verbatim. Run numbering: `1..N` are the study's probe runs, `901+` are the
central verification re-runs from 2026-08-27.
