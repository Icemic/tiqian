import type { Component, Snippet } from "svelte";
import type { PreparedTiqianProse } from "./server.js";

export interface TiqianProseProps {
  readonly html?: string;
  readonly prepared?: PreparedTiqianProse;
  readonly disabled?: boolean;
  readonly strongAsEmphasisMarks?: boolean;
  readonly emphasisDotGapEm?: number;
  readonly class?: string;
  readonly children?: Snippet;
  readonly [attribute: string]: unknown;
}

declare const TiqianProse: Component<TiqianProseProps>;
export default TiqianProse;
export { TiqianProse };
export type { PreparedTiqianProse } from "./server.js";

