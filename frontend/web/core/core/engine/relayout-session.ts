// relayoutSession (TsHost runtime port, Slice 5a). Ports the Kotlin
// relayout session from WebEnhancer.kt (lines 407-477).
// Manages incremental paragraph layout commits during a progressive pass,
// tracking live raw-DOM backup snapshots for transactional rollback, updating
// lastMeasure on success, and reporting/ejecting unsupported paragraphs.
//
// Stateless module: openRelayoutSession(rawDom, argument) is a named
// function that receives the raw-DOM collaborator as an explicit first
// parameter and returns a fresh session object for one run; the per-run Map
// and arrays live on that session, never on module state. The engine
// bootstrap passes the shared raw-DOM instance; tests pass a fake. The
// stateless lifecycle and commit-prepared-paragraph helpers are imported
// directly.

// Ambient global declarations pulled in via import type from owner modules.
import type { PrepareLayoutResult } from "./prepare-paragraph-layout.js";
import type { CommitResult } from "./commit-prepared-paragraph.js";
import { commitPreparedParagraph } from "./commit-prepared-paragraph.js";
import type {
  TrackedParagraph,
  SessionArgument,
  RootStateIssueRecord,
} from "./root-state.js";
import type { EnhancedElementContext } from "./context/enhance-context.js";
import type { RawDomSnapshot } from "./raw-dom.js";
import {
  rawDomCaptureLive,
  rawDomRestoreParagraph,
  rawDomRollback,
} from "./raw-dom.js";
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

/**
 * Open a relayout session for one run.
 *
 * @param {Object} rawDomContext
 * @param {Object} argument
 * @param {Array} argument.paragraphs
 * @param {Object} argument.state
 * @returns {Object}
 */
export function openRelayoutSession(rawDomContext: EnhancedElementContext, argument: SessionArgument): RelayoutSession {
    const paragraphs = argument.paragraphs.slice();
    const state = argument.state;
    const snapshots = new Map<TrackedParagraph, RawDomSnapshot>();
    const successful: Array<[TrackedParagraph, number]> = [];
    const unsupported: Array<[TrackedParagraph, RootStateIssueRecord]> = [];
    const stateParagraphsBefore = state.paragraphs.slice();
    const stateIssuesBefore = state.issues.slice();

    // ProcessItem: dispatches layout preparation for a single paragraph item.
    // Unchanged preparations are no-ops. Unsupported and ready preparations
    // capture live raw-DOM backup snapshots before commit or restore.
    function processItem(index: number, preparation: PrepareLayoutResult): void {
      const paragraph = paragraphs[index];
      if (preparation.kind === 'unchanged') {
        return;
      }
      if (preparation.kind === 'unsupported') {
        snapshots.set(paragraph, rawDomCaptureLive(rawDomContext, paragraph.source, paragraph.lastMeasure));
        unsupported.push([paragraph, preparation]);
        rawDomRestoreParagraph(rawDomContext, paragraph.source);
        return;
      }
      if (preparation.kind === 'ready') {
        snapshots.set(paragraph, rawDomCaptureLive(rawDomContext, paragraph.source, paragraph.lastMeasure));
        const result: CommitResult = commitPreparedParagraph(
          rawDomContext,
          {
            paragraph: paragraph,
            preparation: preparation,
            options: state.options,
            browserFallback: state.browserFallback,
            onSnapshotPreparedDomFallback: state.onDisableSnapshotPreparedDom,
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
          rawDomRestoreParagraph(rawDomContext, paragraph.source);
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
    // arrays, restores live DOM snapshots via rawDom.rollback in insertion
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
      const results = rawDomRollback(rawDomContext, snapshotsArray);
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
