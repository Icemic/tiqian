# @tiqian/precompute

Native font session and precompute entry points for
[Tiqian](https://github.com/tiqian-cjk/tiqian), the CJK paragraph layout
engine. The layout rules live in the Kotlin core; this package runs the font
session (decoding, shaping, coverage, metrics) in a Rust addon built with
[Neon](https://neon-rs.dev).

Status: the font session API (`createFontSession`) is implemented against the
same byte-level contract as the Kotlin/JS implementation, which remains the
parity oracle. The precompute entry points (`createPrecomputer`,
`createHtmlPreparer`, bundle rendering) land slice by slice; see
`docs/adr/0050-native-precompute-rust-bindings.md` and `docs/roadmap.md` in
the repository.

## Install

```sh
npm install @tiqian/precompute
```

Platform binaries arrive as optional dependencies for `linux-x64-gnu`,
`linux-arm64-gnu`, `darwin-arm64`, and `win32-x64-msvc`. Loading on any other
platform reports `UnsupportedPrecomputePlatform:<platform>`.

### NixOS

The linux binaries carry no runpath: every `DT_NEEDED` entry resolves through
the running process (the runtime's loaded SONAMEs, the ldconfig cache, the
default directories). NixOS has no default library directories, so on a load
failure the loader diagnoses the addon's `DT_NEEDED` list against the loaded
images, `ldconfig -p`, the mapped nix store directories, and the profile
library directories. When every missing library is found, a copy of the
addon with the repaired runpath is written once to
`~/.cache/tiqian-precompute/nix-rpath` (requires `patchelf`) and loaded;
later loads reuse that copy without re-diagnosing. When a library is found
nowhere, the error names it and the searched directories. Libraries under
store prefixes the profiles do not cover can be added through
`TIQIAN_PRECOMPUTE_NIX_LIB_DIRS` (colon-separated directories, searched
first). Setting `LD_LIBRARY_PATH` from JavaScript does not work: glibc
parses it once at process startup.

## Font session

```js
import { createFontSession } from "@tiqian/precompute";

const session = await createFontSession(
  [
    {
      family: "Han Sans",
      publicUrl: "/fonts/han-sans.woff2",
      source: await readFile("./han-sans.woff2"),
    },
  ],
  { sessionPrefix: "site" },
);

session.faces;
session.shape("提椠排版", ["Han Sans"], 20, 400, false, "zh-CN", "body");
session.metrics(["Han Sans"], 20, 400, false);
session.renderFamilies(["Han Sans"]);
session.sourceBoundaries(text, baseStyle, textSpans);
session.beginCapture();
session.captureEvidence();
session.close();
```

## Worker threads

The batch entries (`prepareParagraphs`, `prepareFontContracts`, `prepareHtml`)
spread their items over worker threads. `TIQIAN_PRECOMPUTE_THREADS` sets the
worker count:

- unset or malformed: the machine's available parallelism
- `1`: the plain sequential loop
- `n > 1`: at most `n` scoped threads; batches smaller than two items always
  run inline

Every value produces identical output. Results land in input order, the
reported error is the one of the lowest failing index, and a worker panic
leaves the batch instead of vanishing. Paragraph-level evidence capture and
the metric cache are shared across workers; each paragraph owns its capture
window.

```js
import { createPrecomputer } from "@tiqian/precompute/precompute";

const precomputer = await createPrecomputer({ typography, fonts });
const entries = await precomputer.prepareParagraphs(paragraphs);
const contracts = await precomputer.prepareFontContracts(contractInputs);
```

`prepareHtml` spreads inside one call: the document walk, the projection, and
the output reassembly stay sequential in document order, while each element's
snapshot attempt and contract fallback run over the workers with the same
guarantees as the explicit batch entries.

Singular entries (`prepareParagraph`, `prepareFontContract`) stay sequential;
font contract retries are rare and their evidence windows belong to one
paragraph each.

## Host table transport

`@tiqian/precompute/transport` owns the delivery side of ADR 0052's snapshot
tables: serving frozen table bytes under one URL per sha, and the two bundle
orderings a build must not get wrong.

```js
import { createSnapshotTableFileTransport } from "@tiqian/precompute/transport";

const tables = createSnapshotTableFileTransport({
  directory: ".cache/tiqian/tables",  // created on first write
  urlPrefix: "tiqian-tables",         // served as /tiqian-tables/<sha>.tiqtbl
});

const url = tables.write(finalized); // "/tiqian-tables/<sha256>.tiqtbl"
tables.read(sha256);                 // the bytes, or undefined
tables.listShas();                   // every sha on disk
tables.sweep(referencedShas);        // deletes unreferenced files, returns the count
```

`createSnapshotTableSession()` drives one build's shared table: `absorb` and
`renderData` calls interleave article by article, `finish()` freezes the union
once, and every bundle assembles against the same frozen rows. Absorb and
data-phase calls share one mutable table; both throw once the table froze.
`renderStandaloneSnapshotBundle(plan, id)` is the per-item fallback: the item
absorbs into and freezes its own table beside the session's.

## Cache persistence and contexts

`createSqliteCacheStore` (subpath `sqlite-store`) keeps computed snapshots in
one SQLite file, and `createPersistentCache` (subpath `persistence`) binds a
precomputer to such a store.

A precomputer's cache identity is its **context fingerprint**: a hash the
engine computes over everything that changes snapshot bytes. That covers the
engine and shaping revisions, the font files, and the full typography config,
including `fontSizePx` and `lineHeightPx`. The engine does not read
stylesheets: the numbers in `typography` are the CSS values snapshots are
computed under, transcribed by the host. Hosts never read or invent the
fingerprint; `precomputer.cache.context()` returns it for passing along.

The split principle: **one precomputer = one typography config = one
context**. Instantiate one precomputer per distinct config the site's CSS
uses (body text, list items, footnotes). Page kinds share a precomputer
whenever their config is identical, and per-paragraph inputs (text,
`maxWidthPx`) never split contexts.

```js
const store = await createSqliteCacheStore(".cache/tiqian/cache.db", {
  contexts: [precomputer.cache.context()], // one entry per precomputer
});
```

## Local development

From this directory:

```sh
npm install
npm run debug:native   # cargo build + neon dist into platforms/<current>/
npm run lint           # bans `any` and `as unknown`; no inline disable
npm run build          # tsc emit into lib/
npm run typecheck      # src + test against the built lib/, no emit
npm test               # node --test over test/, against the built lib/
```

`npm run build:native` produces the release build. The sources are TypeScript
(`src/`); the published package ships the compiled `lib/`, and the tests run
against that output. The CI lane (`build-neon-precompute.yml`) builds every
supported platform and uploads the platform packages as artifacts; publishing
is a separate workflow.

Releases go to npmjs.org from annotated `@tiqian/precompute@<version>` tags
(`publish-precompute.yml`). Snapshot publication is manual-only
(`snapshot-precompute.yml`): each dispatch stamps its own
`precompute-snapshot-<UTC timestamp>` tag, temporarily swaps the five
manifests to the running repository owner's scope (GitHub Packages requires
the npm scope to equal the owner; `@tiqian-cjk` on the canonical
repository), publishes `<base>-snapshot.<timestamp>` versions with the
`snapshot` dist-tag to GitHub Packages, and restores the
manifests. The addon loader resolves the platform packages under whatever
scope its own manifest carries, so the swapped packages load without a source
patch. Consumers install by exact version with
`--registry=https://npm.pkg.github.com` and a token carrying `read:packages`.
