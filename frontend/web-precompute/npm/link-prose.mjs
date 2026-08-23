#!/usr/bin/env node
// Dev-only link for the @tiqian/prose dependency (ADR 0053 F1). Downstream
// imports resolve "@tiqian/prose" entries; published packages resolve it
// from the registry, while this script points node_modules/@tiqian/prose at
// the working-tree package in frontend/web/npm so local builds and tests
// exercise the live prose package.

import { lstat, mkdir, rm, symlink } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const linkUrl = new URL("./node_modules/@tiqian/prose", import.meta.url);

await mkdir(new URL("./node_modules/@tiqian/", import.meta.url), { recursive: true });
try {
  const metadata = await lstat(linkUrl);
  if (!metadata.isSymbolicLink()) {
    throw new Error("LinkProseTargetIsNotASymlink: remove node_modules/@tiqian/prose first");
  }
  await rm(linkUrl, { force: true });
} catch (error) {
  if (error.code !== "ENOENT") throw error;
}
await symlink("../../../../web/npm", linkUrl, "dir");
console.log(`linked @tiqian/prose -> ${fileURLToPath(linkUrl)} -> web/npm`);
