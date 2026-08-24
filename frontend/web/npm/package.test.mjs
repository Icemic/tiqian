import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

test("published package ships the TS runtime modules and no repository-only bin", async () => {
  const manifest = JSON.parse(await readFile(new URL("./package.json", import.meta.url), "utf8"));
  const lock = JSON.parse(await readFile(new URL("./package-lock.json", import.meta.url), "utf8"));

  assert.equal(manifest.name, "@tiqian/prose");
  assert.equal(lock.version, manifest.version);
  assert.equal(lock.packages[""].version, manifest.version);
  assert.equal(manifest.license, "MPL-2.0");
  assert.equal(manifest.types, "./api.d.ts");
  assert.equal(manifest.engines.node, ">=22");
  assert.deepEqual(manifest.publishConfig, { access: "public", tag: "alpha" });
  assert.ok(manifest.files.includes("LICENSE"));
  assert.ok(manifest.files.includes("README.md"));
  assert.equal(manifest.files.includes("runtime/"), false, "runtime/ moved to @tiqian/prose-core");
  assert.equal(manifest.files.includes("core/"), false, "core/ moved to @tiqian/prose-core");
  assert.equal(
    manifest.files.includes("precompute-runtime/"),
    false,
    "the engine runtime ships as @tiqian/ffi instead",
  );
  assert.equal(manifest.files.includes("browser-font-replay.js"), false);
  assert.equal(manifest.files.includes("browser-fonts.js"), false);
  assert.equal(manifest.files.includes("font-face-boundaries.js"), false);
  assert.equal(manifest.files.includes("lazy-capabilities.js"), false, "lazy-capabilities.js must not ship");
  assert.equal(manifest.files.includes("layout-worker.js"), false);
  assert.ok(manifest.files.includes("prepared-dom.js"));
  assert.equal(manifest.files.includes("snapshot-manifest.js"), false);
  assert.equal(manifest.files.includes("snapshot-source.js"), false);
  assert.ok(manifest.files.includes("snapshot-client.js"));
  assert.equal(manifest.files.includes("worker-layout.js"), false);
  // The server-side precompute entries moved to @tiqian/precompute; only the
  // client snapshot adoption module and web-component integration remain.
  assert.deepEqual(Object.keys(manifest.exports).sort(), [
    ".",
    "./element",
    "./prepared-dom",
    "./snapshot-client",
    "./styles.css",
  ]);
  assert.equal(manifest.bin, undefined);
  assert.equal(manifest.exports["./build-runtime"], undefined);
  assert.deepEqual(manifest.dependencies, { "@tiqian/prose-core": "0.1.0-alpha.5" });
  for (const removed of ["precompute.js", "precompute-html.js", "precompute-fonts.js", "precompute-node-fonts.js"]) {
    assert.equal(manifest.files.includes(removed), false, `${removed} must not ship`);
  }
  assert.ok(manifest.sideEffects.includes("./prepared-dom.js"));
  assert.equal(
    manifest.scripts.prepack,
    "npm test && npm run verify:package",
  );
  assert.equal(
    manifest.scripts["verify:release"],
    "npm run prepack && node ./verify-release.mjs",
  );
  assert.equal(manifest.scripts["release:prepare"], "node ./prepare-release.mjs");
  assert.equal(manifest.files.includes("verify-release.mjs"), false);
  assert.equal(manifest.files.includes("prepare-release.mjs"), false);

  const coreManifest = JSON.parse(await readFile(new URL("../npm-core/package.json", import.meta.url), "utf8"));
  assert.equal(coreManifest.name, "@tiqian/prose-core");
  assert.equal(coreManifest.version, manifest.version);
  assert.deepEqual(coreManifest.dependencies, { "@tiqian/ffi": "0.1.0-alpha.1" });
  assert.ok(coreManifest.files.includes("core/"));
  assert.equal(coreManifest.files.includes("runtime/"), false, "the Kotlin bundle directory is retired");
  assert.ok(coreManifest.files.includes("runtime.js"));
  assert.ok(coreManifest.files.includes("layout-worker.js"));
});

test("the release helper derives the repository tag and commit subject from one version", async () => {
  const { normalizeReleaseVersion, releaseCommitSubject, releaseTag } = await import(
    "./prepare-release.mjs"
  );

  assert.equal(normalizeReleaseVersion("0.1.0-alpha.3"), "0.1.0-alpha.3");
  assert.equal(releaseTag("0.1.0-alpha.3"), "@tiqian/prose@0.1.0-alpha.3");
  assert.equal(releaseCommitSubject("0.1.0-alpha.3"), "chore(web): prepare alpha.3 release");
  assert.equal(releaseCommitSubject("0.1.0"), "chore(web): prepare 0.1.0 release");
  assert.throws(() => normalizeReleaseVersion("v0.1.0-alpha.3"), /InvalidReleaseVersion/u);
  assert.throws(() => normalizeReleaseVersion("0.1.0-alpha.03"), /InvalidReleaseVersion/u);
  assert.throws(() => normalizeReleaseVersion("0.1.0+local"), /InvalidReleaseVersion/u);
});

test("the ffi release workflow mirrors the prose one for the engine package", async () => {
  const workflow = await readFile(
    new URL("../../../.github/workflows/publish-ffi.yml", import.meta.url),
    "utf8",
  );

  assert.match(workflow, /tags:\s*\n\s*- "@tiqian\/ffi@\*"/u);
  assert.match(workflow, /working-directory: ffi\/js\/npm/u);
  assert.match(workflow, /TIQIAN_RELEASE_ARTIFACT_DIR/u);
  assert.match(workflow, /tiqian-ffi-release/u);
  assert.match(workflow, /npm publish "\$\{tarball\}" --ignore-scripts --access public --tag alpha/u);
  assert.match(workflow, /npm dist-tag add "@tiqian\/ffi@\$\{RELEASE_VERSION\}" latest/u);
  assert.match(workflow, /tags\.alpha !== version \|\| tags\.latest !== version/u);
});

test("the release workflow publishes one verified artifact and synchronizes both dist-tags", async () => {
  const workflow = await readFile(
    new URL("../../../.github/workflows/publish-prose.yml", import.meta.url),
    "utf8",
  );

  assert.match(workflow, /tags:\s*\n\s*- "@tiqian\/prose@\*"/u);
  assert.match(workflow, /id-token: write/u);
  assert.match(workflow, /TIQIAN_RELEASE_ARTIFACT_DIR/u);
  assert.match(workflow, /npm publish "\$\{tarball\}" --ignore-scripts --access public --tag alpha/u);
  assert.match(workflow, /NODE_AUTH_TOKEN: \$\{\{ secrets\.NPM_DIST_TAG_TOKEN \}\}/u);
  assert.match(workflow, /npm dist-tag add "@tiqian\/prose@\$\{RELEASE_VERSION\}" latest/u);
  assert.match(workflow, /tags\.alpha !== version \|\| tags\.latest !== version/u);
});

test("the release verifier accepts the verified package files", async () => {
  const { verifyPackage } = await import("./verify-package.mjs");
  const artifacts = await verifyPackage();

  assert.ok(artifacts.length > 0);
  assert.ok(artifacts.every((artifact) => artifact.size > 0));
});

test("the custom element validates a snapshot before dynamically loading the browser runtime", async () => {
  const elementSource = await readFile(new URL("./element.js", import.meta.url), "utf8");
  const elementDeclarations = await readFile(new URL("./element.d.ts", import.meta.url), "utf8");
  const apiSource = await readFile(new URL("./api.js", import.meta.url), "utf8");
  const apiDeclarations = await readFile(new URL("./api.d.ts", import.meta.url), "utf8");
  const browserFontsSource = await readFile(
    new URL("../npm-core/core/measurement/browser-fonts.js", import.meta.url),
    "utf8",
  );
  const layoutWorkerSource = await readFile(new URL("../npm-core/layout-worker.js", import.meta.url), "utf8");
  const loadedSnapshotsSource = await readFile(
    new URL("../npm-core/core/sampler/snapshot/loaded-snapshots.js", import.meta.url),
    "utf8",
  );
  for (const shim of ["prepared-dom.js", "snapshot-client.js"]) {
    const shimSource = await readFile(new URL(`./${shim}`, import.meta.url), "utf8");
    assert.match(shimSource, /export \* from "@tiqian\/prose-core\/core\/sampler\/snapshot\//u);
  }
  for (const shim of ["precomputed.js", "snapshot-source.js"]) {
    const shimSource = await readFile(new URL(`../npm-core/${shim}`, import.meta.url), "utf8");
    assert.match(shimSource, /export \* from "\.\/core\/sampler\/snapshot\//u);
  }
  const runtimeSource = await readFile(
    new URL("../npm-core/core/engine/loaders/runtime-loader.js", import.meta.url),
    "utf8",
  );
  const fontLoaderSource = await readFile(
    new URL("../npm-core/core/engine/loaders/font-loader.js", import.meta.url),
    "utf8",
  );
  const stylesSource = await readFile(new URL("./styles.css", import.meta.url), "utf8");
  // core/engine/loaders/styles.js resolves ../../../styles.css from inside
  // @tiqian/prose-core, so the stylesheet ships in both packages and the two
  // copies must stay byte-identical.
  const coreStylesSource = await readFile(new URL("../npm-core/styles.css", import.meta.url), "utf8");
  assert.equal(coreStylesSource, stylesSource, "npm-core styles.css copy must match @tiqian/prose");
  const adoption = elementSource.indexOf("snapshot = await tryAdoptRequestedSnapshot(");
  const connectedStart = elementSource.indexOf("  connectedCallback() {");
  const initialSnapshotSource = elementSource.slice(connectedStart, adoption);
  const runtimeLoad = elementSource.indexOf("await (runtimePromise ?? loadTiqianRuntime());", adoption);
  const invalidationStart = elementSource.indexOf("  #invalidateSnapshotAndEnhance(");
  const invalidationEnd = elementSource.indexOf(
    "  #tryReadoptSnapshotAtMaximumMeasure()",
    invalidationStart,
  );
  const invalidationSource = elementSource.slice(invalidationStart, invalidationEnd);
  const invalidationRuntimeLoad = invalidationSource.indexOf("loadTiqianRuntime()");
  const invalidationDispatch = invalidationSource.indexOf("this.#dispatchProgressiveEnhance(");
  const readoptionStart = elementSource.indexOf("  #tryReadoptSnapshotAtMaximumMeasure() {");
  const readoptionEnd = elementSource.indexOf("  #recoverRuntimeAfterSnapshotMiss(", readoptionStart);
  const readoptionSource = elementSource.slice(readoptionStart, readoptionEnd);
  const mixedCompletionStart = elementSource.indexOf("MixedSnapshotRuntimeCompletion");
  const mixedCompletionEnd = elementSource.indexOf(
    "          if (!this.#runtimeStateActive)",
    mixedCompletionStart,
  );
  const mixedCompletionSource = elementSource.slice(mixedCompletionStart, mixedCompletionEnd);
  const viewportListenerStart = elementSource.indexOf("  #ensureViewportResizeListener() {");
  const viewportListenerEnd = elementSource.indexOf(
    "  #handleResponsiveGeometryChange() {",
    viewportListenerStart,
  );
  const viewportListenerSource = elementSource.slice(viewportListenerStart, viewportListenerEnd);

  assert.ok(adoption >= 0);
  assert.match(initialSnapshotSource, /#beginLayoutWork\(\{ captureSignatures: false \}\)/u);
  assert.doesNotMatch(initialSnapshotSource, /#lastTypography = this\.#typographySignature\(\)/u);
  assert.match(
    initialSnapshotSource,
    /bypassesFontWait: \(\) => this\.hasAttribute\("snapshot-ref"\) &&[\s\S]*?!strongEmphasisRuntimeRequired/u,
  );
  assert.ok(runtimeLoad > adoption);
  assert.match(
    elementSource,
    /OptInStrongSnapshotExclusion[\s\S]*?this\.strongAsEmphasisMarks && this\.querySelector\("strong"\) !== null/u,
  );
  assert.match(
    elementSource,
    /SnapshotFirstInputBeforeRuntimeCompile[\s\S]*?this\.hasAttribute\("snapshot-ref"\) &&[\s\S]*?!strongEmphasisRuntimeRequired[\s\S]*?\? null[\s\S]*?: loadTiqianRuntime\(\)/u,
  );
  assert.doesNotMatch(initialSnapshotSource, /initialCompletionSelector/u);
  assert.match(
    elementSource,
    /if \(!strongEmphasisRuntimeRequired\) \{[\s\S]*?tryAdoptRequestedSnapshot\(/u,
  );
  assert.match(runtimeSource, /import\("\.\/ts-runtime\.js"\)/u);
  assert.doesNotMatch(elementSource, /from "\.\/runtime\/tiqian-web\.js"/u);
  assert.match(fontLoaderSource, /import\("\.\.\/\.\.\/measurement\/browser-fonts\.js"\)/u);
  assert.match(fontLoaderSource, /import\("\.\.\/\.\.\/sampler\/snapshot\/prepared-dom\.js"\)/u);
  assert.match(elementSource, /import\("@tiqian\/prose-core\/core\/engine\/web-worker\/worker-channel\.js"\)/u);
  assert.match(fontLoaderSource, /preparedDom\.installPreparedDomRendererBridge\(\)/u);
  assert.doesNotMatch(elementSource, /from "\.\/browser-fonts\.js"/u);
  assert.doesNotMatch(elementSource, /from "\.\/precomputed\.js"/u);
  assert.doesNotMatch(elementSource, /from "\.\/font-shaping\.js"/u);
  assert.doesNotMatch(apiSource, /from "\.\/precomputed\.js"/u);
  assert.doesNotMatch(apiSource, /from "\.\/font-shaping\.js"/u);
  assert.match(loadedSnapshotsSource, /import\("\.\/precomputed\.js"\)/u);
  assert.doesNotMatch(loadedSnapshotsSource, /font-shaping\.js/u);
  assert.doesNotMatch(elementSource, /lazy-capabilities/u);
  assert.doesNotMatch(apiSource, /lazy-capabilities/u);
  assert.doesNotMatch(layoutWorkerSource, /precompute-fonts\.js|harfbuzzjs|woff2-encoder/u);
  assert.match(layoutWorkerSource, /from "@tiqian\/ffi"/u);
  assert.doesNotMatch(layoutWorkerSource, /precompute-runtime/u);
  assert.match(layoutWorkerSource, /workerExactSubsetSourceBoundaries\(session\.faces, request\)/u);
  assert.doesNotMatch(browserFontsSource, /harfbuzzjs|woff2-encoder/u);
  assert.doesNotMatch(elementSource, /tiqian:retry-cjk-dash/u);
  assert.match(elementSource, /BrowserDashCapabilityBeforeDispatch/u);
  assert.doesNotMatch(elementSource, /#exactFontSession\?\.reference === reference/u);
  assert.doesNotMatch(apiSource, /existing\?\.reference === reference/u);
  assert.match(browserFontsSource, /await requirePreparedOrExactContract\(root\)/u);
  assert.match(
    browserFontsSource,
    /if \(prepared\?\.matches\)\s*return prepared;[\s\S]*?return requireExactContract\(root\)/u,
  );
  assert.match(browserFontsSource, /ExistingSessionLiveContractRevalidation/u);
  assert.match(browserFontsSource, /ServerReplayNeedsNoBrowserFontBytes/u);
  assert.doesNotMatch(browserFontsSource, /fetchImplementation|createRenderFontFace/u);
  assert.match(browserFontsSource, /export const prepareBrowserRenderFonts/u);
  assert.match(elementSource, /ExactFontSessionLiveRevalidation/u);
  assert.match(elementSource, /await existing\.revalidate\(this, existing\.handle\)/u);
  assert.match(
    elementSource,
    /HostRenderFontReadyBeforeCommit[\s\S]*?await this\.#exactFontSession\.prepareRenderFont\(this, exactFontSession\)/u,
  );
  assert.match(
    elementSource,
    /await coordinator\.runPrepare\([\s\S]*?engineFace\.enhanceProgressively\(this, preparedOptions\)/u,
  );
  assert.match(
    elementSource,
    /const layoutOperation = this\.#beginLayoutWork\(\{ usesCapturedMeasure: true \}\)[\s\S]*?request === this\.#enhanceRequest && layoutOperation === this\.#layoutOperation/u,
  );
  assert.match(
    elementSource,
    /observedAttributes = \[[\s\S]*?"disabled",[\s\S]*?"emphasis-dot-gap-em",[\s\S]*?"strong-as-emphasis-marks",[\s\S]*?"snapshot-ref",[\s\S]*?\]/u,
  );
  assert.match(elementSource, /get disabled\(\)[\s\S]*?hasAttribute\("disabled"\)/u);
  assert.match(elementDeclarations, /get disabled\(\): boolean/u);
  assert.match(
    elementSource,
    /ReversibleDisabledEnhancement[\s\S]*?if \(this\.disabled\)\s*return/u,
  );
  assert.match(
    elementSource,
    /DisabledAttributeOwnsTeardown[\s\S]*?#restartConnectedLifecycle\(\)/u,
  );
  assert.match(elementSource, /get strongAsEmphasisMarks\(\)[\s\S]*?hasAttribute\("strong-as-emphasis-marks"\)/u);
  assert.match(elementDeclarations, /get strongAsEmphasisMarks\(\): boolean/u);
  assert.match(apiDeclarations, /strongAsEmphasisMarks\?: boolean/u);
  assert.match(
    elementSource,
    /UpgradeAttributeReactionGuard[\s\S]*?if \(this\.#connected\)\s*this\.#restartConnectedLifecycle\(\)/u,
  );
  assert.match(
    elementSource,
    /ExactFontValidationRenderProjection[\s\S]*?this\.setAttribute\(EXACT_RENDER_FONT_ATTRIBUTE, "true"\)/u,
  );
  assert.match(
    elementSource,
    /ExactPreparedDomFallbackSingleFlight[\s\S]*?#exactFontRejectedAttempt = this\.#exactFontAttemptSignature\(\)/u,
  );
  assert.match(
    elementSource,
    /#exactFontRejectedAttempt === this\.#exactFontAttemptSignature\(reference\)/u,
  );
  assert.match(elementSource, /#restartConnectedLifecycle\(\)/u);
  assert.match(elementSource, /function snapshotCompletionSelector\(root\)/u);
  assert.match(elementSource, /:is\(p, li\):not\(\[data-tq-snapshot-key\]\)/u);
  assert.match(elementSource, /!strongEmphasisRuntimeRequired\) \{/u);
  assert.match(
    elementSource,
    /MixedSnapshotRuntimeCompletion[\s\S]*?#dispatchProgressiveEnhance\(generation, \{[\s\S]*?paragraphSelector: completionSelector/u,
  );
  assert.match(elementSource, /paragraphSelector:\s*completionSelector/u);
  assert.doesNotMatch(
    mixedCompletionSource,
    /restoreLoadedSnapshot\(this\)/u,
  );
  assert.doesNotMatch(elementSource, /runtimeCoversSnapshotParagraphs|preserveSnapshotRenderFont/u);
  assert.match(
    elementSource,
    /restoreImmediatelyBeforeDispatch[\s\S]*?#snapshotAdopted = false/u,
  );
  assert.match(
    readoptionSource,
    /const runtimeSnapshotBackingRestored = this\.#runtimeStateActive/u,
  );
  assert.match(readoptionSource, /RuntimeSnapshotBackingRestore/u);
  assert.ok(
    readoptionSource.indexOf("engineFace.destroy(this)") <
      readoptionSource.indexOf("tryAdoptRequestedSnapshot("),
  );
  assert.match(
    elementSource,
    /#recoverRuntimeAfterSnapshotMiss\(operation, reason, runtimeSnapshotBackingRestored = false\)/u,
  );
  assert.doesNotMatch(elementSource, /tq-inline-size-probe/u);
  const observersSource = await readFile(
    new URL("../npm-core/core/sampler/observers.js", import.meta.url),
    "utf8",
  );
  assert.match(observersSource, /observer\??\.observe\([^)]+, \{ box: "border-box" \}\)/u);
  assert.match(
    elementSource,
    /ResponsiveInlineSizeObservation[\s\S]*?onWidthsChanged[\s\S]*?#scheduleResponsiveGeometryCommit/u,
  );
  assert.match(observersSource, /Math\.abs\(width - previous\) >= 0\.5/u);
  assert.doesNotMatch(stylesSource, /tq-inline-size-probe/u);
  assert.match(elementSource, /#paragraphWidthSignature\(\)/u);
  const signaturesSource = await readFile(
    new URL("../npm-core/core/sampler/signatures.js", import.meta.url),
    "utf8",
  );
  assert.match(signaturesSource, /function fragmentedBorderBoxInlineSize\(element\)/u);
  assert.match(
    signaturesSource,
    /responsiveGeometrySignature\(root\)[\s\S]*?fragmentedBorderBoxInlineSize\(root\)/u,
  );
  assert.doesNotMatch(elementSource, /RESPONSIVE_LAYOUT_SETTLE_MS|#resizeSettleTimer/u);
  assert.doesNotMatch(elementSource, /RESPONSIVE_LATEST_RETARGET_QUIET_MS/u);
  assert.match(
    elementSource,
    /#scheduleResponsiveRetarget\(\)[\s\S]*?#responsiveRetargetFrame = requestAnimationFrame/u,
  );
  assert.match(viewportListenerSource, /ViewportResizeValidatesCapturedLayoutInputs/u);
  assert.match(
    viewportListenerSource,
    /#layoutWorkInFlight && this\.#layoutWorkUsesCapturedMeasure[\s\S]*?#responsiveCommitRequired = true[\s\S]*?#scheduleResponsiveRetarget\(\)/u,
  );
  assert.doesNotMatch(
    viewportListenerSource,
    /#cancelCapturedLayoutForLatestGeometry|#cancelPendingLayoutForLatestGeometry|#restoreRuntimeSourceForRetarget/u,
  );
  assert.match(
    elementSource,
    /#cancelCapturedLayoutForLatestGeometry\(\)[\s\S]*?engineFace\.cancelLayoutWork\(this\)[\s\S]*?#responsiveRelayoutRequired = true/u,
  );
  assert.match(
    elementSource,
    /ProgressiveOutputTypographyBaseline[\s\S]*?#layoutWorkTypographySignature = this\.#typographySignature\(\)/u,
  );
  assert.match(
    signaturesSource,
    /NativeSourceViewportTypographySignature[\s\S]*?!element\.isConnected[\s\S]*?element\.closest\("\[data-tq-rendered='true'\]"\)[\s\S]*?elementTypographySignature\(element, includeGenerated, properties\) !== signature/u,
  );
  assert.match(
    signaturesSource,
    /ROOT_VIEWPORT_TYPOGRAPHY_PROPERTIES = TYPOGRAPHY_PROPERTIES\.filter\([\s\S]*?property !== "margin-left" && property !== "margin-right"/u,
  );
  assert.match(
    elementSource,
    /ResponsiveRetargetNativeRollback[\s\S]*?engineFace\.destroy\(this\)[\s\S]*?#runtimeStateActive = false/u,
  );
  assert.match(
    elementSource,
    /#scheduleResponsiveGeometryCommit\(\) \{[\s\S]*?coordinator\.requestFrame/u,
  );
  assert.ok(invalidationRuntimeLoad >= 0);
  assert.ok(invalidationDispatch > invalidationRuntimeLoad);
  assert.equal(invalidationSource.match(/restoreLoadedSnapshot\(this\)/gu)?.length, 1);
  assert.match(
    invalidationSource,
    /const restoreImmediatelyBeforeDispatch = \(\) => \{[\s\S]*?restoreLoadedSnapshot\(this\)/u,
  );
  assert.match(invalidationSource, /beforeDispatch: restoreImmediatelyBeforeDispatch/u);
  assert.match(
    elementSource,
    /ResponsiveSnapshotRollbackAtFirstSafeSignal[\s\S]*?#invalidateSnapshotAndEnhance\(\{ restoreBeforeLoad: true \}\)/u,
  );
  assert.match(
    elementSource,
    /ResponsiveRuntimeDirectInPlaceRelayout[\s\S]*?#scheduleResponsiveGeometryCommit\(\)/u,
  );
  assert.match(
    elementSource,
    /MixedSnapshotCompletionResume[\s\S]*?completionSelector && !this\.#runtimeStateActive[\s\S]*?paragraphSelector: completionSelector/u,
  );
  assert.match(
    elementSource,
    /if \(!this\.#runtimeStateActive\) \{[\s\S]*?ReadoptionMissMustReclaimSource[\s\S]*?#dispatchProgressiveEnhance\(generation\)/u,
  );
  assert.match(elementSource, /PreparedSnapshotTransition/u);
  assert.match(
    elementSource,
    /beforeDispatch\?\.\(\);[\s\S]*?usesCapturedMeasure: true[\s\S]*?engineFace\.enhanceProgressively\(this, preparedOptions\)/u,
  );
  assert.match(
    elementSource,
    /ResponsiveNativeBacking[\s\S]*?engineFace\.destroy\(this\)[\s\S]*?#dispatchProgressiveEnhance\(generation, \{ revalidateExactFont \}\)/u,
  );
  assert.match(
    elementSource,
    /const exactFontSessionAlreadyPrepared = !revalidateExactFont[\s\S]*?this\.#exactFontSession\?\.reference/u,
  );
  assert.match(
    elementSource,
    /WidthOnlyExactFontSessionReuse[\s\S]*?if \(!exactFontSessionAlreadyPrepared\)/u,
  );
  assert.match(
    elementSource,
    /ResponsiveNativeRetargetSingleFlight[\s\S]*?#responsiveRelayoutRequired && !this\.#runtimeStateActive/u,
  );
  assert.match(elementSource, /this\.addEventListener\("tiqian:relayout-ready"/u);
  assert.match(elementSource, /loadedSnapshotMaximumMeasureMatches\(this\)/u);
  assert.match(elementSource, /this\.#geometryRevision !== this\.#layoutWorkRevision/u);
  assert.match(elementSource, /#paragraphMeasureSignature\(\)/u);
  assert.match(elementSource, /ObserverBaselineAfterUncapturedLayout/u);
  assert.match(
    elementSource,
    /const currentParagraphWidths =[\s\S]*?this\.#paragraphWidthSignature\(\)[\s\S]*?this\.#lastParagraphWidths = currentParagraphWidths/u,
  );
  assert.match(elementSource, /!widthsChanged && !measuresChanged/u);
  assert.match(
    elementSource,
    /hostInlineSizeRefresh = widthsChanged[\s\S]*?\[data-tq-host-inline-size\][\s\S]*?!hostInlineSizeRefresh/u,
  );
  assert.match(elementSource, /usesCapturedMeasure: true/u);
  assert.match(elementSource, /currentMeasures !== this\.#layoutWorkMeasureSignature/u);
  assert.match(elementSource, /RenderOutputTypographyIsNotAnInputChange/u);
  assert.match(
    elementSource,
    /RendererOwnedProgressiveStyleMutation[\s\S]*?rendererOwnedProgressiveStyleMutation\(record, this\)/u,
  );
  assert.match(observersSource, /attributeOldValue: true/u);
  assert.doesNotMatch(
    elementSource,
    /const capturedTypographyChanged = this\.#layoutWorkUsesCapturedMeasure/u,
  );
  assert.match(
    elementSource,
    /this\.#responsiveRelayoutRequired = !this\.#layoutWorkUsesCapturedMeasure/u,
  );
  assert.match(elementSource, /RESPONSIVE_SNAPSHOT_GEOMETRY_MISSES/u);
  assert.match(elementSource, /if \(stale\)\s*this\.#responsiveCommitRequired = true/u);
  assert.doesNotMatch(elementSource, /tiqian:enhance-atomically/u);
  assert.match(elementSource, /engineFace\.cancelLayoutWork\(this\)/u);
  assert.match(elementSource, /this\.#dispatchProgressiveEnhance\(generation\)/u);
  assert.match(elementSource, /#responsiveGeometrySignature\(\) !== this\.#layoutWorkGeometrySignature/u);
  assert.match(elementSource, /#runtimeStateActive = false/u);
  assert.match(elementSource, /operation === this\.#layoutOperation/u);
  assert.doesNotMatch(elementSource, /#snapshotBackedByRuntime/u);
  assert.match(elementSource, /let initialReadyReported = false/u);
  assert.match(
    elementSource,
    /if \(!initialReadyReported\)[\s\S]*?this\.dataset\.tiqianLoadMs/u,
  );
  assert.doesNotMatch(elementSource, /addEventListener\("DOMContentLoaded"/u);
  assert.doesNotMatch(elementSource, /\.then\(\(\) => document\.fonts\?\.ready/u);
  assert.match(elementSource, /\.then\(nextFrame\)[\s\S]*?awaitInitialTypographyFonts/u);
  assert.match(fontLoaderSource, /waitForTypographyFonts/u);
  assert.match(fontLoaderSource, /DEFAULT_TYPOGRAPHY_FONT_WAIT_MS = 3_000/u);
  assert.match(
    fontLoaderSource,
    /fontWait\.status !== "timeout"[\s\S]*?tiqianFontWait = "timeout"[\s\S]*?deferUntilFontsSettle/u,
  );
  assert.match(
    fontLoaderSource,
    /deferUntilFontsSettle[\s\S]*?"loadingdone"[\s\S]*?"loadingerror"[\s\S]*?Promise\.resolve\(completion\)\.then\(restart\)/u,
  );
  assert.match(
    elementSource,
    /LatestObservedAttributeGeneration[\s\S]*?if \(!this\.#hasDispatched\) \{[\s\S]*?this\.#restartConnectedLifecycle\(\)/u,
  );
  assert.match(
    elementSource,
    /attributeChangedCallback\([\s\S]*?#snapshotAdopted \|\| isLoadedSnapshotAdopted\(this\)[\s\S]*?#invalidateSnapshotAndEnhance\(\)[\s\S]*?#refreshRuntimeFromSource\(\)/u,
  );
  assert.match(
    elementSource,
    /#scheduleTypographyCheck\([\s\S]*?#snapshotAdopted \|\| isLoadedSnapshotAdopted\(this\)[\s\S]*?#invalidateSnapshotAndEnhance\(\)[\s\S]*?#refreshRuntimeFromSource\(\)/u,
  );
  assert.match(
    elementSource,
    /disconnectedCallback\(\)[\s\S]*?\+\+this\.#generation[\s\S]*?this\.#clearInitialFontRetry\(\)/u,
  );
  assert.match(stylesSource, /\[data-tq-geometry="true"\]::before/u);
  assert.match(stylesSource, /\[data-tq-rendered="true"\]::before,[\s\S]*?content: none !important/u);
  assert.match(
    stylesSource,
    /\[data-tq-rendered="true"\] span\[data-tq-geometry="true"\][\s\S]*?all: unset !important/u,
  );
  assert.match(
    stylesSource,
    /\[data-tq-rendered="true"\] svg\[data-tq-geometry="true"\][\s\S]*?display: block !important/u,
  );
  assert.match(
    stylesSource,
    /svg\[data-tq-geometry="true"\] circle\[data-tq-decoration-dot\][\s\S]*?fill: var\(--tq-decoration-color\) !important/u,
  );
  assert.match(stylesSource, /\[data-tq-shaping-boundary\]::first-letter/u);
  assert.match(stylesSource, /\[data-tq-rendered="true"\]::first-letter,[\s\S]*?all: unset !important/u);
  assert.match(stylesSource, /text-spacing-trim: space-all !important/u);
  assert.match(
    stylesSource,
    /\[data-tq-rendered="true"\] \{[\s\S]*?text-align: start !important;[\s\S]*?text-justify: none !important;/u,
  );
  assert.match(
    stylesSource,
    /\[data-tq-rendered="true"\] \.tq-line\[data-tq-geometry="true"\]/u,
  );
  assert.match(
    stylesSource,
    /\[data-tq-rendered="true"\],[\s\S]*?\[data-tq-rendered="true"\] \[data-tq-source-semantic\]/u,
  );
  assert.match(stylesSource, /height: var\(--tq-line-height\) !important/u);
  assert.match(stylesSource, /vertical-align: var\(--tq-line-baseline-offset\) !important/u);
  assert.match(
    stylesSource,
    /\[data-tq-canonical-source="true"\] \[data-tq-line-end-sentinel\]/u,
  );
  assert.match(
    stylesSource,
    /\[data-tq-canonical-source="true"\] \[data-tq-engine-hyphen\]/u,
  );
  assert.match(
    stylesSource,
    /\[data-tq-open-type-features="pwid,palt"\]/u,
  );
  assert.match(stylesSource, /font-variant-east-asian: proportional-width !important/u);
  assert.match(
    stylesSource,
    /font-feature-settings: "halt" 0, "chws" 0, "palt" 1 !important/u,
  );
});

test("layout coordinator implements visual prominence scoring, proportional backoff and anti-starvation aging", async () => {
  const elementSource = await readFile(new URL("./element.js", import.meta.url), "utf8");
  const coordinatorSource = await readFile(
    new URL("../npm-core/core/engine/coordinator/coordinator.js", import.meta.url),
    "utf8",
  );

  // 1. Visual prominence scoring formula: visibleArea * (1 + ratio) + inlineSize
  assert.match(
    coordinatorSource,
    /visibleScore[AB] = entry[AB][\s\S]*?\(entry[AB]\.visibleArea \|\| entry[AB]\.area \|\| 0\) \* \(1\.0 \+ \(entry[AB]\.intersectionRatio \|\| 0\)\) \+ \(entry[AB]\.inlineSize \|\| 0\)/u,
  );

  // 2. VisibleClassBeforeScore: visibility is a strict class comparison —
  // the off-screen `visibleArea || area` fallback can exceed any additive
  // in-viewport bonus, and pollWorkers derives visibleCount from the sorted
  // prefix — with anti-starvation aging ordering only within a class.
  assert.match(
    coordinatorSource,
    /if \(inViewA !== inViewB\)\s*return inViewB - inViewA;/u,
  );
  assert.match(
    coordinatorSource,
    /priority[AB] = visibleScore[AB] \+ \([ab]\.deferCount \|\| 0\) \* 50000/u,
  );
  assert.match(
    coordinatorSource,
    /priority[AB] = visibleScore[AB] \+ Math\.min\([ab]\.deferCount \* 50000, 900000\)/u,
  );

  // 3. RefreshAnchoredFrameBudget: the budget follows the measured cadence
  // only; the event-driven regulator and the shared slice EMA are gone.
  assert.match(
    coordinatorSource,
    /this\.#budgetMs = Math\.min\(6\.0, Math\.max\(2\.5, this\.#measuredFrameInterval \* 0\.4\)\);/u,
  );
  assert.doesNotMatch(coordinatorSource, /#estimatedSliceMs/u);
  assert.doesNotMatch(coordinatorSource, /#consecutiveIdleFrames/u);

  // 4. DeadlineGate: grants stop on the real deadline, and a workless frame
  // still grants once so oversized slices keep making progress.
  assert.match(
    coordinatorSource,
    /const guaranteeForwardProgress = workDone === 0;/u,
  );
  assert.match(
    coordinatorSource,
    /if \(!guaranteeForwardProgress && now >= deadline\) \{/u,
  );

  // 5. Lifecycle ready events bubble up for document-level observation
  assert.match(
    elementSource,
    /new CustomEvent\("tiqian:relayout-ready", \{[\s\S]*?bubbles: true,[\s\S]*?composed: true,/u,
  );

  // 6. SliceCommitAnchorCompensation: both grant lanes bracket their slice
  // drains with a same-task viewport anchor capture/compensate pair, and the
  // element excludes itself from native scroll anchoring while a worker is
  // attached.
  assert.match(
    coordinatorSource,
    /const viewportAnchor = captureViewportAnchor\(element\);[\s\S]*?compensateViewportAnchor\(element, viewportAnchor\);/u,
  );
  assert.match(
    coordinatorSource,
    /viewportAnchor = captureViewportAnchor\(slot\.element\);[\s\S]*?const processed = slot\.runtime\.workerRunSlice\(/u,
  );
  assert.match(
    coordinatorSource,
    /if \(grantProcessed > 0\)\s*compensateViewportAnchor\(slot\.element, viewportAnchor\);/u,
  );
  // NativeAnchoringHandover: capture holds the scroller's native anchoring
  // for the job window; every path that ends or abandons a job releases it.
  assert.match(coordinatorSource, /if \(!slot\.active\)\s*releaseNativeScrollAnchoring\(element\);/u);
  assert.match(coordinatorSource, /releaseNativeScrollAnchoring\(slot\.element\);/u);
  assert.match(elementSource, /releaseNativeScrollAnchoring\(this\);/u);
});

test("offscreen deferred lane keeps every pending callback per element", async () => {
  const coordinatorSource = await readFile(
    new URL("../npm-core/core/engine/coordinator/coordinator.js", import.meta.url),
    "utf8",
  );

  // OffscreenRequestQueue: an element can queue distinct callbacks while off
  // screen (initial enhance plus responsive commits). The deferred lane must
  // bucket tasks per element; a single task per element lets the newest
  // request silently drop the older ones, which stalled initial enhancement
  // for every root below the fold when a resize re-queued a commit.
  // 1. A request lands in the element's bucket, not a single task slot.
  assert.match(coordinatorSource, /bucket\.tasks\.set\(callback, task\);/u);
  // 2. A due or promoted bucket moves every task it holds.
  const promoted = coordinatorSource.match(
    /for \(const task of bucket\.tasks\.values\(\)\) \{\s*this\.#callbacks\.set\(task\.callback, task\);/gu,
  );
  assert.ok(
    promoted && promoted.length >= 2,
    "flush and promote must both drain the whole bucket",
  );
  // 3. Cancelling one callback must not drop the element's other tasks.
  assert.match(coordinatorSource, /bucket\.tasks\.delete\(callback\);/u);
  // 4. The single-slot regression must stay gone.
  assert.doesNotMatch(coordinatorSource, /this\.#deferred\.set\(element, task\);/u);
});
