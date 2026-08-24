// progressiveRelayoutSession (TsHost runtime port, Slice 5a). Ports the
// ProgressiveRelayoutSession class from WebEnhancer.kt (lines 407-477).
// Manages incremental paragraph layout commits during a progressive pass,
// tracking live custody snapshots for transactional rollback, updating
// lastMeasure on success, and reporting/ejecting unsupported paragraphs.
//
// Consumes __TiqianCustody, __TiqianCommitPreparedParagraph,
// __TiqianPreparedMetadata, and __TiqianLifecycle.
//
// Plain script, no exports: running it installs
// globalThis.__TiqianProgressiveRelayoutSession. Two consumers share this
// file as the single source of truth: the npm host (importing it for the
// side effect) and the Kotlin runtime bundle, into which a future gradle
// bridge task will embed this source verbatim. Double installation is guarded.
//
// Embedding constraint: the generator wraps this file in a Kotlin raw string,
// so the source must contain no dollar sign and no triple double-quote
// sequence. Use string concatenation, never template literals.

// Ambient global declarations pulled in via import type from owner modules.
import type { PrepareLayoutResult } from "./prepare-paragraph-layout.js";
import type { CommitResult, TiqianCommitPreparedParagraphGlobal } from "./commit-prepared-paragraph.js";
import type {
  TrackedParagraph,
  SessionArgument,
  RootStateIssueRecord,
} from "./root-state.js";
import type { EngineFfiFacade } from "./ffi-face.js";
import type { CustodyApi, CustodySnapshot } from "./custody.js";
import type { CapabilityIssueRecord, LifecycleApi } from "./lifecycle.js";
import type { PreparedMetadataGlobal } from "./prepared-metadata.js";

type ProgressiveRelayoutSessionProcessItemFn = (
  index: number,
  preparation: PrepareLayoutResult,
) => void;
type ProgressiveRelayoutSessionFinishFn = () => void;
type ProgressiveRelayoutSessionRollbackFn = () => void;
type ProgressiveRelayoutSessionCreateFn = (
  argument: SessionArgument,
) => ProgressiveRelayoutSession;

// Live session handed back to the driver: processItem dispatches one item,
// finish finalizes committed measures, rollback restores the pre-session
// state lists and the live DOM snapshots.
export type ProgressiveRelayoutSession = {
  processItem: ProgressiveRelayoutSessionProcessItemFn;
  finish: ProgressiveRelayoutSessionFinishFn;
  rollback: ProgressiveRelayoutSessionRollbackFn;
  stale: boolean;
};

export type ProgressiveRelayoutSessionApi = {
  createProgressiveRelayoutSession: ProgressiveRelayoutSessionCreateFn;
};

declare global {
  var __TiqianProgressiveRelayoutSession: ProgressiveRelayoutSessionApi | undefined;
}

(function () {
  if (globalThis.__TiqianProgressiveRelayoutSession) return;

  /**
   * Create a progressive relayout session.
   *
   * @param {Object} argument
   * @param {Array} argument.paragraphs
   * @param {Object} argument.state
   * @returns {Object}
   */
  function createProgressiveRelayoutSession(argument: SessionArgument): ProgressiveRelayoutSession {
    var paragraphs = argument.paragraphs.slice();
    var state = argument.state;
    var snapshots = new Map<TrackedParagraph, CustodySnapshot>();
    var successful: Array<[TrackedParagraph, number]> = [];
    var unsupported: Array<[TrackedParagraph, RootStateIssueRecord]> = [];
    var stateParagraphsBefore = state.paragraphs.slice();
    var stateIssuesBefore = state.issues.slice();

    // ProcessItem: dispatches layout preparation for a single paragraph item.
    // Unchanged preparations are no-ops. Unsupported and ready preparations
    // capture live custody snapshots before commit or restore.
    function processItem(index: number, preparation: PrepareLayoutResult): void {
      var paragraph = paragraphs[index];
      if (preparation.kind === 'unchanged') {
        return;
      }
      var custody = globalThis.__TiqianCustody!;
      if (preparation.kind === 'unsupported') {
        snapshots.set(paragraph, custody.captureLive(paragraph.source, paragraph.lastMeasure));
        unsupported.push([paragraph, preparation]);
        custody.restoreParagraph(paragraph.source);
        return;
      }
      if (preparation.kind === 'ready') {
        snapshots.set(paragraph, custody.captureLive(paragraph.source, paragraph.lastMeasure));
        var metadata = globalThis.__TiqianPreparedMetadata!;
        var commitPreparedParagraph = globalThis.__TiqianCommitPreparedParagraph!.commitPreparedParagraph;
        var result: CommitResult = commitPreparedParagraph({
          ffi: state.ffi as EngineFfiFacade,
          paragraph: paragraph,
          preparation: preparation,
          options: state.options,
          browserFallback: state.browserFallback,
          onExactPreparedDomFallback: state.onDisableExactPreparedDom,
          semanticReplayJson: metadata.preparedSemanticReplayJson(paragraph.lowered),
          inlineObjectMetaJson: metadata.preparedInlineObjectMetaJson(paragraph.lowered),
          cjkStrongSemanticsJson: metadata.preparedCjkStrongSemanticsJson(paragraph.lowered),
        });
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
      for (var s = 0; s < successful.length; s += 1) {
        var successPair = successful[s];
        var successParagraph = successPair[0];
        var measure = successPair[1];
        var isUnsupported = false;
        for (var u = 0; u < unsupported.length; u += 1) {
          if (unsupported[u][0] === successParagraph) {
            isUnsupported = true;
            break;
          }
        }
        if (!isUnsupported) {
          successParagraph.lastMeasure = measure;
        }
      }
      for (var i = 0; i < unsupported.length; i += 1) {
        var unsupportedPair = unsupported[i];
        var unsuppParagraph = unsupportedPair[0];
        var issue = unsupportedPair[1];
        var indexInState = state.paragraphs.indexOf(unsuppParagraph);
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
        globalThis.__TiqianLifecycle!.reportIssue(issue as CapabilityIssueRecord);
      }
    }

    // Rollback: reverts state.paragraphs and state.issues to their initial
    // arrays, restores live DOM snapshots via custody.rollback in insertion
    // order, and updates paragraph lastMeasure by source element identity.
    function rollback(): void {
      state.paragraphs.length = 0;
      for (var p = 0; p < stateParagraphsBefore.length; p += 1) {
        state.paragraphs.push(stateParagraphsBefore[p]);
      }
      state.issues.length = 0;
      for (var is = 0; is < stateIssuesBefore.length; is += 1) {
        state.issues.push(stateIssuesBefore[is]);
      }
      var snapshotsArray = Array.from(snapshots.values());
      var results = globalThis.__TiqianCustody!.rollback(snapshotsArray);
      var paragraphBySource = new Map<Element, TrackedParagraph>();
      for (var j = 0; j < paragraphs.length; j += 1) {
        paragraphBySource.set(paragraphs[j].source, paragraphs[j]);
      }
      if (results && results.length) {
        for (var r = 0; r < results.length; r += 1) {
          var result = results[r];
          var para = paragraphBySource.get(result.source);
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

  globalThis.__TiqianProgressiveRelayoutSession = {
    createProgressiveRelayoutSession: createProgressiveRelayoutSession,
  };
})();
