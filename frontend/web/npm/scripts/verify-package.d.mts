// Type shim for the still-unconverted package verifier; package.test.ts
// imports this module dynamically. Delete together with the .mjs when the
// scripts directory converts to TypeScript.
export interface VerifiedArtifact {
  path: string;
  size: number;
}
export declare function verifyPackage(packageRoot?: URL): Promise<VerifiedArtifact[]>;
