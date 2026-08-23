// SSR externals for hosts whose server build bundles dependencies (Vite,
// SvelteKit). The native addon resolves relative to this package's own files,
// so bundling any entry point moves the addon and manifest lookups inside the
// output chunks and breaks them; hosts keep every entry external instead.
// The list derives from this package's manifest at run time, so it carries
// the installed scope and grows with the exports map without a host-side
// copy of entry names.

import { readFile } from "node:fs/promises";

interface PackageManifest {
  name: string;
  exports: Record<string, unknown>;
}

/**
 * Derives the importable entry specifiers of this package from its manifest:
 * the bare name plus one specifier per exports key. Reads the shipped
 * manifest by default; a URL argument serves tests and other manifests.
 */
export const tiqianSsrExternals = async (
  manifestUrl: URL = new URL("../package.json", import.meta.url),
): Promise<string[]> => {
  const manifest = JSON.parse(await readFile(manifestUrl, "utf8")) as PackageManifest;
  if (
    typeof manifest.name !== "string" ||
    manifest.exports === undefined ||
    typeof manifest.exports !== "object"
  ) {
    throw new Error("TiqianViteManifestInvalid");
  }
  return Object.keys(manifest.exports).map((entry) =>
    entry === "." ? manifest.name : `${manifest.name}${entry.slice(1)}`
  );
};
