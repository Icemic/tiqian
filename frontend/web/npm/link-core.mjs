#!/usr/bin/env node
// Dev-only link for the @tiqian/prose-core dependency (ADR 0053 F2).
// Downstream imports resolve "@tiqian/prose-core" entries; published packages
// resolve it from the registry, while this script points
// node_modules/@tiqian/prose-core at the working-tree package in
// frontend/web/npm-core so local builds and tests exercise the live core package.

import { lstat, mkdir, rm, symlink } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const linkUrl = new URL("./node_modules/@tiqian/prose-core", import.meta.url);

await mkdir(new URL("./node_modules/@tiqian/", import.meta.url), { recursive: true });
try {
  const metadata = await lstat(linkUrl);
  if (!metadata.isSymbolicLink()) {
    throw new Error("LinkCoreTargetIsNotASymlink: remove node_modules/@tiqian/prose-core first");
  }
  await rm(linkUrl, { force: true });
} catch (error) {
  if (error.code !== "ENOENT") throw error;
}
await symlink("../../../npm-core", linkUrl, "dir");
console.log(`linked @tiqian/prose-core -> ${fileURLToPath(linkUrl)} -> ../npm-core`);
