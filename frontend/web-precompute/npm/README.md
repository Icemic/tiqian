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
