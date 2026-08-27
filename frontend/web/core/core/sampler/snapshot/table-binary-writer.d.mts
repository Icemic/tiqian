import type {
  SnapshotMetricRow,
  SnapshotProbe,
  SnapshotRevisions,
} from "./snapshot-table-binary.js";

export interface BinaryTableInput {
  replayStrings?: readonly string[];
  metrics?: readonly SnapshotMetricRow[];
  probes?: readonly SnapshotProbe[];
  faces?: readonly unknown[];
  typographies?: readonly unknown[];
  valueStyles?: readonly string[];
  fontPreloads?: readonly string[];
  revisions?: SnapshotRevisions | Record<string, unknown>;
}

export function writeBinaryTable(table: BinaryTableInput): Uint8Array;
