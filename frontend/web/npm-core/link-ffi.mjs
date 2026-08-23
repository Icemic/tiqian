#!/usr/bin/env node
// Dev-only link for the @tiqian/ffi dependency (ADR 0053 A4). The layout
// worker imports the engine face from "@tiqian/ffi"; published packages
// resolve it from the registry, while this script points
// node_modules/@tiqian/ffi at the working-tree package in ffi/js/npm so
// `npm test` exercises the locally built engine runtime.

import { lstat, mkdir, rm, symlink } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const linkUrl = new URL("./node_modules/@tiqian/ffi", import.meta.url);

await mkdir(new URL("./node_modules/@tiqian/", import.meta.url), { recursive: true });
try {
  const metadata = await lstat(linkUrl);
  if (!metadata.isSymbolicLink()) {
    throw new Error("LinkFfiTargetIsNotASymlink: remove node_modules/@tiqian/ffi first");
  }
  await rm(linkUrl, { force: true });
} catch (error) {
  if (error.code !== "ENOENT") throw error;
}
await symlink("../../../../../ffi/js/npm", linkUrl, "dir");
console.log(`linked @tiqian/ffi -> ${fileURLToPath(linkUrl)} -> ffi/js/npm`);
