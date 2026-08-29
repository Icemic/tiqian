// relayoutSession (TsHost runtime port, Slice 5a). Ports the Kotlin
// relayout session from WebEnhancer.kt (lines 407-477).
// Manages incremental paragraph layout commits during a progressive pass,
// tracking live raw-DOM backup snapshots for transactional rollback, updating
// lastMeasure on success, and reporting/ejecting unsupported paragraphs.
//
// Stateless module: openRelayoutSession(context) is a named function that
// receives the EnhancedElementContext and returns a fresh session object for
// one run; the per-run Map and arrays live on that session, never on module
// state. The live paragraph and issue lists are the context's own arrays,
// spliced and pushed by reference. The stateless lifecycle and
// commit-prepared-paragraph helpers are imported directly.

// Ambient global declarations pulled in via import type from owner modules.
import type { PrepareLayoutResult } from "./prepare-paragraph-layout.js";
import type { CommitResult } from "./commit-prepared-paragraph.js";
import { commitPreparedParagraph } from "./commit-prepared-paragraph.js";
import type { TrackedParagraph } from "./enhance/context-state.js";
import type { DiagnosisIssueRecord } from "./context/diagnosis-manager.js";
import type { EnhancedElementContext } from "./context/enhance-context.js";
import type { ResolvedEnhanceOptions } from "./lifecycle.js";
import type { RawDomSnapshot } from "./raw-dom.js";
import {
  rawDomCaptureLive,
  rawDomRestoreParagraph,
  rawDomRollback,
} from "./raw-dom.js";
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
 * @param {Object} context
 * @returns {Object}
 */
export function openRelayoutSession(context: EnhancedElementContext): RelayoutSession {
    const paragraphs = context.contextState.paragraphs.slice();
    const options = context.contextState.runtimeOptions as ResolvedEnhanceOptions;
    const browserFallback = context.typography.browserFallback;
    const snapshots = new Map<TrackedParagraph, RawDomSnapshot>();
    const successful: Array<[TrackedParagraph, number]> = [];
    const unsupported: Array<[TrackedParagraph, DiagnosisIssueRecord]> = [];
    const stateParagraphsBefore = context.contextState.paragraphs.slice();
    const stateIssuesBefore = context.diagnosis.issues.slice();

    // ProcessItem: dispatches layout preparation for a single paragraph item.
    // Unchanged preparations are no-ops. Unsupported and ready preparations
    // capture live raw-DOM backup snapshots before commit or restore.
    function processItem(index: number, preparation: PrepareLayoutResult): void {
      const paragraph = paragraphs[index];
      if (preparation.kind === 'unchanged') {
        return;
      }
      if (preparation.kind === 'unsupported') {
        snapshots.set(paragraph, rawDomCaptureLive(context, paragraph.source, paragraph.lastMeasure));
        unsupported.push([paragraph, preparation]);
        rawDomRestoreParagraph(context, paragraph.source);
        return;
      }
      if (preparation.kind === 'ready') {
        snapshots.set(paragraph, rawDomCaptureLive(context, paragraph.source, paragraph.lastMeasure));
        const result: CommitResult = commitPreparedParagraph(
          context,
          {
            paragraph: paragraph,
            preparation: preparation,
            options: options,
            browserFallback: browserFallback,
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
          rawDomRestoreParagraph(context, paragraph.source);
        }
      }
    }

    // Finish: finalizes the session upon completion of all items. Paragraphs
    // that succeeded without subsequent failure keep their lastMeasure.
    // Unsupported paragraphs are removed from the context's tracked
    // paragraphs, normalized, and reported to lifecycle.
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
      const stateParagraphs = context.contextState.paragraphs;
      const stateIssues = context.diagnosis.issues;
      for (let i = 0; i < unsupported.length; i += 1) {
        const unsupportedPair = unsupported[i];
        const unsuppParagraph = unsupportedPair[0];
        const issue = unsupportedPair[1];
        const indexInState = stateParagraphs.indexOf(unsuppParagraph);
        if (indexInState !== -1) {
          stateParagraphs.splice(indexInState, 1);
        }
        if (issue.element == null) {
          issue.element = unsuppParagraph.source;
        }
        if (issue.reportToConsole == null) {
          issue.reportToConsole = true;
        }
        stateIssues.push(issue);
        reportIssue(issue);
      }
    }

    // Rollback: reverts the context's tracked paragraphs and issue ledger to
    // their initial arrays, restores live DOM snapshots via rawDom.rollback
    // in insertion order, and updates paragraph lastMeasure by source element
    // identity.
    function rollback(): void {
      const stateParagraphs = context.contextState.paragraphs;
      const stateIssues = context.diagnosis.issues;
      stateParagraphs.length = 0;
      for (let p = 0; p < stateParagraphsBefore.length; p += 1) {
        stateParagraphs.push(stateParagraphsBefore[p]);
      }
      stateIssues.length = 0;
      for (let is = 0; is < stateIssuesBefore.length; is += 1) {
        stateIssues.push(stateIssuesBefore[is]);
      }
      const snapshotsArray = Array.from(snapshots.values());
      const results = rawDomRollback(context, snapshotsArray);
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
