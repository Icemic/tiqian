# @tiqian/ffi

Kotlin/JS engine face for Tiqian paragraph precompute. The package carries the
`:ffi:js` Gradle build output: one ES module entry with the `@JsExport` surface,
its TypeScript declarations, and per-module source maps with embedded sources.

`@tiqian/prose` depends on this package for the layout worker engine. The two
packages release in lockstep from the same commit with exact-pinned versions, so
the worker engine and the browser runtime always come from one engine build.

## API

- `precomputePlainParagraph(...)` — plain paragraph plan; the full signature
  lives in `runtime/Tiqian-tiqian-ffi-js.d.mts`.
- `precomputeParagraph(...)` — paragraph plan with source semantics; the full
  signature lives in `runtime/Tiqian-tiqian-ffi-js.d.mts`.

## Build

The `runtime/` directory is generated. Rebuild it with:

```shell
npm run build:runtime
```

## Release

The first release ships the version already recorded in `package.json`
(`0.1.0-alpha.1`). Verify the package and create the annotated tag directly:

```shell
npm run verify:release
git tag -a "@tiqian/ffi@0.1.0-alpha.1" -m "@tiqian/ffi@0.1.0-alpha.1"
```

Later releases bump the version first:

```shell
npm run release:prepare -- 0.1.0-alpha.2
```

The command runs the same verification, commits the version bump, and creates
the annotated tag. Pushing a tag runs the publish workflow. Publish
`@tiqian/ffi` before the matching `@tiqian/prose` release so the registry
dependency resolves.
