# Central verification record — oneshot-bisect

Date: 2026-08-27. Central (dispatching session) re-verification of the
agent report, performed independently in the dispatch worktree before
recovery into the repository.

## Audits (static)

- Commit boundary: the three `integrate/oneshot-bisect` commits touch only
  `demo/web-history/**`, `demo/web/tests/oneshot-geometry-history.test.mjs`,
  and its fixture. No product code, no build residue.
- Frozen comparison semantics: `DEEP_GEOMETRY_HELPERS` (md5
  `5f776a1373389509e83bb1f2f9624e28` on both sides), `diffDeepGeometry`,
  and `deepGeometryCounts` are byte-identical between the kit harness and
  `demo/web/tests/helpers/deep-geometry.mjs` at `1ad320ce`.
- Numbers vs evidence: 182 evidence JSONs (153 run/chain records + 29 pair
  diffs); `bed4c791` 20/20 divergent (all valid), `7e2d1909` 20/20 clean;
  chain pairs 25 zero + 4 non-zero (`pair-04` 1159/1642, `pair-08` 539/1634,
  `pair-15` 334/1927, `pair-19` 175/1768); every run JSON's `commit` field
  matches its directory.
- Classification spot-checks (git range inspection):
  - `5c76cf68`: interval holds only the refactor (`frontend/web/**`) plus a
    docs commit. Refactor-caused defect, confirmed.
  - `6ff37b45`: single commit mixing `prepared_dom.rs`
    (`SPACING_EPSILON` 0.01 → `SPACING_DUST_EPSILON` 1e-6), the JS mirror,
    and fixture additions. Indivisible mixed content, 归属不明 confirmed.
  - `5c9d0a30`: interval is the loader-state refactor (`frontend/web/**`)
    plus `3f6db017` (comment-only, 27/27 lines). Refactor-caused defect,
    confirmed.
  - `23e36988`: sets the production loader `validator` to `null`;
    interval adds only CI and docs commits. Repair, confirmed.

## Independent re-runs (dynamic)

Using the same kit, eras, and build recipes as the study, in a fresh
browser:

- `bed4c791` (era E3-session): 5/5 runs divergent, `boxesCompared` 1927,
  page height 4926/4926 — same signature as the study's 20/20.
- `7e2d1909` (era E2b-workspace): 5/5 runs zero divergence — same
  signature as the study's 20/20.
- Ledger test at the integration branch tip: passes (fixture baseline S2
  187/1318/369, page height 4926).
- Post-merge extension: after merging `main` (`72e95777`), a fresh chain
  capture at `72e95777` equals the `1ad320ce` capture (pair-30,
  `divergentBoxes` 0/1927) — S2 persists through `672f14bc` and
  `72e95777`; the frozen baseline stays valid on current main.

## Post-merge correction (same date)

`672f14bc` removed `prose-host-session` (collapsed into the enhanced
element context), so the head-era adapter broke on current main. The first
post-merge capture ran against a stale orphaned
`prose-host-session.js` left in the dispatch worktree's build outputs and
was invalidated. The kit gained the E8-context era
(`adapters/enhance-context.js`, `eras/e8-context.json`, one-shot through
`createEnhanceContext(root, options).mount()` mirroring
`demo/web/main.js`), the worktree build outputs were purged
(`git clean -xfd frontend/web/core frontend/web/npm`) and rebuilt from
true HEAD sources, and the chain capture plus pair-30 above are from that
clean rebuild. The ledger test reads `eras/e8-context.json`.

Archive notes: run numbers 901+ in the point archives are these central
re-runs. `e8752ae4` was an empty probe directory (initial blocked-window
guess, later narrowed; see report §3) and is not archived.
