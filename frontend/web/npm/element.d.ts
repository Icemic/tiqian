export declare class TiqianProseElement extends HTMLElement {
  disabled: boolean;
  emphasisDotGapEm: number | null;
  strongAsEmphasisMarks: boolean;
  snapshotRef: string | null;
}

declare global {
  interface HTMLElementTagNameMap {
    "tiqian-prose": TiqianProseElement;
  }
}
