#!/usr/bin/env node
// Dev-only link for the @tiqian/prose and @tiqian/core dependencies (ADR 0053 F1/F2).
// Downstream imports resolve "@tiqian/prose" and "@tiqian/core" entries;
// published packages resolve them from the registry, while this script points
// node_modules/@tiqian/prose and node_modules/@tiqian/core at the
// working-tree packages in frontend/web/npm and frontend/web/core so local
// builds and tests exercise the live packages.

import { lstat, mkdir, rm, symlink } from "node:fs/promises";
import { fileURLToPath } from "node:url";

async function linkPackage(name, targetRelative, errorTag) {
  const linkUrl = new URL(`./node_modules/@tiqian/${name}`, import.meta.url);
  try {
    const metadata = await lstat(linkUrl);
    if (!metadata.isSymbolicLink()) {
      throw new Error(`${errorTag}: remove node_modules/@tiqian/${name} first`);
    }
    await rm(linkUrl, { force: true });
  } catch (error) {
    if (error.code !== "ENOENT") throw error;
  }
  await symlink(targetRelative, linkUrl, "dir");
  console.log(`linked @tiqian/${name} -> ${fileURLToPath(linkUrl)} -> ${targetRelative}`);
}

await mkdir(new URL("./node_modules/@tiqian/", import.meta.url), { recursive: true });
await linkPackage("prose", "../../../../web/npm", "LinkProseTargetIsNotASymlink");
await linkPackage("core", "../../../../web/core", "LinkProseCoreTargetIsNotASymlink");
