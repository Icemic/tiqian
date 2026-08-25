// relayoutSession (TsHost runtime port, Slice 5a). Ports the Kotlin
// relayout session from WebEnhancer.kt (lines 407-477).
// Manages incremental paragraph layout commits during a progressive pass,
// tracking live custody snapshots for transactional rollback, updating
// lastMeasure on success, and reporting/ejecting unsupported paragraphs.
//
// Stateless module: openRelayoutSession(deps, argument) is a named
// function that receives the custody and commit-prepared-paragraph
// collaborators as an explicit first parameter and returns a fresh session
// object for one run; the per-run Map and arrays live on that session, never
// on module state. The engine bootstrap wires the deps object; tests pass
// fakes. The stateless lifecycle helper is imported directly.

// Ambient global declarations pulled in via import type from owner modules.
import type { PrepareLayoutResult } from "./prepare-paragraph-layout.js";
import type { CommitPreparedParagraphBundle, CommitResult } from "./commit-prepared-paragraph.js";
import type {
  TrackedParagraph,
  SessionArgument,
  RootStateIssueRecord,
} from "./root-state.js";
import type { EngineFfiFacade } from "./ffi-face.js";
import type { CustodyApi, CustodySnapshot } from "./custody.js";
import type { CapabilityIssueRecord } from "./lifecycle.js";
import { reportIssue } from "./lifecycle.js";
import {
  preparedCjkStrongSemanticsJson,
  preparedInlineObjectMetaJson,
  preparedSemanticReplayJson,
} from "./prepared-metadata.js";

type RelayoutSessionProcessItemFn = (
  index: number,
  preparation: PrepareLayoutResult,
) => void;
type RelayoutSessionFinishFn = () => void;
type RelayoutSessionRollbackFn = () => void;

// Live session handed back to the driver: processItem dispatches one item,
// finish finalizes committed measures, rollback restores the pre-session
// state lists and the live DOM snapshots.
export type RelayoutSession = {
  processItem: RelayoutSessionProcessItemFn;
  finish: RelayoutSessionFinishFn;
  rollback: RelayoutSessionRollbackFn;
  stale: boolean;
};

export interface RelayoutSessionDeps {
  custody: CustodyApi;
  commitPreparedParagraph: CommitPreparedParagraphBundle;
}

/**
 * Open a relayout session for one run.
 *
 * @param {Object} deps
 * @param {Object} argument
 * @param {Array} argument.paragraphs
 * @param {Object} argument.state
 * @returns {Object}
 */
export function openRelayoutSession(deps: RelayoutSessionDeps, argument: SessionArgument): RelayoutSession {
    const paragraphs = argument.paragraphs.slice();
    const state = argument.state;
    const snapshots = new Map<TrackedParagraph, CustodySnapshot>();
    const successful: Array<[TrackedParagraph, number]> = [];
    const unsupported: Array<[TrackedParagraph, RootStateIssueRecord]> = [];
    const stateParagraphsBefore = state.paragraphs.slice();
    const stateIssuesBefore = state.issues.slice();

    // ProcessItem: dispatches layout preparation for a single paragraph item.
    // Unchanged preparations are no-ops. Unsupported and ready preparations
    // capture live custody snapshots before commit or restore.
    function processItem(index: number, preparation: PrepareLayoutResult): void {
      const paragraph = paragraphs[index];
      if (preparation.kind === 'unchanged') {
        return;
      }
      const custody = deps.custody;
      if (preparation.kind === 'unsupported') {
        snapshots.set(paragraph, custody.captureLive(paragraph.source, paragraph.lastMeasure));
        unsupported.push([paragraph, preparation]);
        custody.restoreParagraph(paragraph.source);
        return;
      }
      if (preparation.kind === 'ready') {
        snapshots.set(paragraph, custody.captureLive(paragraph.source, paragraph.lastMeasure));
        const commitPreparedParagraph = deps.commitPreparedParagraph.commitPreparedParagraph;
        const result: CommitResult = commitPreparedParagraph(
          { custody: deps.custody },
          {
            ffi: state.ffi as EngineFfiFacade,
            paragraph: paragraph,
            preparation: preparation,
            options: state.options,
            browserFallback: state.browserFallback,
            onExactPreparedDomFallback: state.onDisableExactPreparedDom,
            semanticReplayJson: preparedSemanticReplayJson(paragraph.lowered),
            inlineObjectMetaJson: preparedInlineObjectMetaJson(paragraph.lowered),
            cjkStrongSemanticsJson: preparedCjkStrongSemanticsJson(paragraph.lowered),
          }
        );
        if (result.kind === 'success') {
          paragraph.lastMeasure = result.measure;
          successful.push([paragraph, result.measure]);
        } else {
          unsupported.push([paragraph, result]);
          custody.restoreParagraph(paragraph.source);
        }
      }
    }

    // Finish: finalizes the session upon completion of all items. Paragraphs
    // that succeeded without subsequent failure keep their lastMeasure.
    // Unsupported paragraphs are removed from state.paragraphs, normalized,
    // and reported to lifecycle.
    function finish(): void {
      for (let s = 0; s < successful.length; s += 1) {
        const successPair = successful[s];
        const successParagraph = successPair[0];
        const measure = successPair[1];
        let isUnsupported = false;
        for (let u = 0; u < unsupported.length; u += 1) {
          if (unsupported[u][0] === successParagraph) {
            isUnsupported = true;
            break;
          }
        }
        if (!isUnsupported) {
          successParagraph.lastMeasure = measure;
        }
      }
      for (let i = 0; i < unsupported.length; i += 1) {
        const unsupportedPair = unsupported[i];
        const unsuppParagraph = unsupportedPair[0];
        const issue = unsupportedPair[1];
        const indexInState = state.paragraphs.indexOf(unsuppParagraph);
        if (indexInState !== -1) {
          state.paragraphs.splice(indexInState, 1);
        }
        if (issue.element == null) {
          issue.element = unsuppParagraph.source;
        }
        if (issue.reportToConsole == null) {
          issue.reportToConsole = true;
        }
        state.issues.push(issue);
        reportIssue(issue as CapabilityIssueRecord);
      }
    }

    // Rollback: reverts state.paragraphs and state.issues to their initial
    // arrays, restores live DOM snapshots via custody.rollback in insertion
    // order, and updates paragraph lastMeasure by source element identity.
    function rollback(): void {
      state.paragraphs.length = 0;
      for (let p = 0; p < stateParagraphsBefore.length; p += 1) {
        state.paragraphs.push(stateParagraphsBefore[p]);
      }
      state.issues.length = 0;
      for (let is = 0; is < stateIssuesBefore.length; is += 1) {
        state.issues.push(stateIssuesBefore[is]);
      }
      const snapshotsArray = Array.from(snapshots.values());
      const results = deps.custody.rollback(snapshotsArray);
      const paragraphBySource = new Map<Element, TrackedParagraph>();
      for (let j = 0; j < paragraphs.length; j += 1) {
        paragraphBySource.set(paragraphs[j].source, paragraphs[j]);
      }
      if (results && results.length) {
        for (let r = 0; r < results.length; r += 1) {
          const result = results[r];
          const para = paragraphBySource.get(result.source);
          if (para) {
            para.lastMeasure = result.lastMeasure;
          }
        }
      }
    }

    return {
      processItem: processItem,
      finish: finish,
      rollback: rollback,
      stale: false,
    };
  }
