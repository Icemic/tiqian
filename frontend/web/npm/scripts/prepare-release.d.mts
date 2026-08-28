// Type shim for the still-unconverted release helper; package.test.ts
// imports this module dynamically. Delete together with the .mjs when the
// scripts directory converts to TypeScript.
export declare function normalizeReleaseVersion(input: string): string;
export declare function releaseTag(version: string): string;
export declare function releaseCommitSubject(version: string): string;
