import * as preparedDom from "../../sampler/snapshot/prepared-dom.js";
import type { PreparedDomValidatorInterface } from "../../sampler/snapshot/precomputed.js";
// Prepared-dom renderer and commit validator accessors for the Tiqian
// engine. This module owns the prepared-dom state record with its test
// overrides, plus the commit validator oracle.
//
// The prepared-dom state lives in this module's Symbol.for registry (wc-s5
// R9): it carries function members, which the Service container does not
// admit, and every consumer resolves it through this module's accessors.

export type RendererModuleFn = () => typeof preparedDom | null;
export type CommitValidatorFn = () => PreparedDomValidatorInterface | null;
export type SetRendererForTestingFn = (renderer: typeof preparedDom | null | undefined) => void;
export type SetCommitValidatorForTestingFn = (validator: PreparedDomValidatorInterface | null | undefined) => void;

// PreparedDomState: accessors for the prepared pipeline. The renderer
// reference is a static import, not a lazy load; the test override swaps the
// module and an explicit null makes rendererModule answer null. The commit
// validator oracle is a test-world instrument: it reads live geometry per
// node, production commits run without one (QA3 parity policy), test worlds
// install it through setCommitValidatorForTesting.
export type PreparedDomState = {
  rendererModule: RendererModuleFn;
  commitValidator: CommitValidatorFn;
  setRendererForTesting: SetRendererForTestingFn;
  setCommitValidatorForTesting: SetCommitValidatorForTestingFn;
};

function createPreparedDomState(): PreparedDomState {
  let rendererOverride: typeof preparedDom | null | undefined = undefined;
  let validatorOverride: PreparedDomValidatorInterface | null | undefined = undefined;

  return {
    rendererModule: () => rendererOverride !== undefined ? rendererOverride : preparedDom,
    commitValidator: () => validatorOverride !== undefined ? validatorOverride : null,
    setRendererForTesting: (renderer) => { rendererOverride = renderer; },
    setCommitValidatorForTesting: (validator) => { validatorOverride = validator; },
  };
}

// The prepared-dom record lives in this module's Symbol.for registry (wc-s5
// R9): module copies in one document reach the same record, and the Service
// container admits behavior services only, not function-carrying records.
const PREPARED_DOM_STATE_KEY: unique symbol = Symbol.for("@tiqian/prose.prepared-dom-state.v1");

type PreparedDomStateRegistry = Record<symbol, PreparedDomState | undefined>;

function preparedDomState(): PreparedDomState {
  const registry = globalThis as PreparedDomStateRegistry;
  return registry[PREPARED_DOM_STATE_KEY] ??= createPreparedDomState();
}

// Nullable slot read: the test override when installed (explicit null
// included), otherwise the statically imported module.
export function preparedDomRendererModule(): typeof preparedDom | null {
  return preparedDomState().rendererModule();
}

// The renderer module for consumers that need it: the override when present,
// otherwise the static import. Never null; font-loader's bridges resolve
// through this.
export function preparedDomRenderer(): typeof preparedDom {
  return preparedDomState().rendererModule() ?? preparedDom;
}

// Commit validator oracle for the prepared pipeline: null in production,
// installed by test worlds.
export function commitValidator(): PreparedDomValidatorInterface | null {
  return preparedDomState().commitValidator();
}

export function setPreparedDomRendererForTesting(renderer: typeof preparedDom | null | undefined): void {
  preparedDomState().setRendererForTesting(renderer);
}

export function setCommitValidatorForTesting(validator: PreparedDomValidatorInterface | null | undefined): void {
  preparedDomState().setCommitValidatorForTesting(validator);
}
