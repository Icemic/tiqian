// Temporary manifest and source swap for the snapshot publication lane.
// GitHub Packages requires the npm scope to equal the repository owner and
// links a package to the repository named in its manifest, so a snapshot
// published from a fork must carry the fork's name while registry releases
// keep @tiqian and the canonical repository. `apply` rewrites the precompute
// manifests, the astro and sveltekit integration manifests, and the
// `@tiqian/precompute` references embedded in the integration sources, and
// keeps a backup; `restore` puts everything back. The swapped sources no
// longer resolve the local `@tiqian/precompute` dev links, so publish with
// `--ignore-scripts` after the unsapped tree passed its tests. Only the
// manual snapshot workflow runs this; release packaging never does.

import { existsSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const root = dirname(fileURLToPath(import.meta.url));
const backupPath = join(root, ".snapshot-swap.json");
const platforms = [
  "darwin-arm64",
  "linux-arm64-gnu",
  "linux-x64-gnu",
  "win32-x64-msvc",
];
const manifestPaths = [
  join(root, "package.json"),
  ...platforms.map((platform) => join(root, "platforms", platform, "package.json")),
  join(root, "../../web/integrations/astro/package.json"),
  join(root, "../../web/integrations/sveltekit/package.json"),
];
// The integration sources import `@tiqian/precompute` by name and embed the
// name in generated module text; installed under the fork scope they must
// reference the installed name instead.
const sourcePaths = [
  join(root, "../../web/integrations/astro/integration.js"),
  join(root, "../../web/integrations/astro/tables.js"),
  join(root, "../../web/integrations/sveltekit/server.js"),
];
const PRECOMPUTE_NAME = "@tiqian/precompute";

const swapScope = (name, scope) => {
  const slash = name.indexOf("/");
  if (!name.startsWith("@") || slash < 0) {
    throw new Error(`SnapshotSwapNameUnscoped: ${name}`);
  }
  return `${scope}${name.slice(slash)}`;
};

// Replaces the github.com owner/repo segment of a repository URL, keeping any
// prefix (git+https://) and the .git suffix; the directory field is untouched.
const swapRepositoryUrl = (url, repository) => {
  const marker = "github.com/";
  const start = url.indexOf(marker);
  const tail = start < 0 ? "" : url.slice(start + marker.length);
  const withoutGit = tail.replace(/\.git$/u, "");
  if (start < 0 || !/^[^/]+\/[^/]+$/u.test(withoutGit)) {
    throw new Error(`SnapshotSwapRepositoryUrlUnsupported: ${url}`);
  }
  const suffix = tail.endsWith(".git") ? ".git" : "";
  return `${url.slice(0, start + marker.length)}${repository}${suffix}`;
};

const apply = (version, scope, repository) => {
  if (!scope.startsWith("@") || scope.includes("/")) {
    throw new Error(`SnapshotSwapScopeInvalid: ${scope}`);
  }
  if (!/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/u.test(repository)) {
    throw new Error(`SnapshotSwapRepositoryInvalid: ${repository}`);
  }
  if (version === "" || scope === "" || repository === "") {
    throw new Error("SnapshotSwapArgumentsMissing");
  }
  const backup = {};
  for (const path of manifestPaths) {
    const original = readFileSync(path, "utf8");
    const manifest = JSON.parse(original);
    manifest.name = swapScope(manifest.name, scope);
    manifest.version = version;
    if (manifest.optionalDependencies !== undefined) {
      const swapped = {};
      for (const [name] of Object.entries(manifest.optionalDependencies)) {
        swapped[swapScope(name, scope)] = version;
      }
      manifest.optionalDependencies = swapped;
    }
    if (manifest.peerDependencies !== undefined) {
      // Only the precompute peer follows the fork scope; @tiqian/prose and
      // the framework peers keep their registry names. The swapped peer
      // tracks the snapshot being published so hosts resolve one version.
      const swapped = {};
      for (const [name, peerVersion] of Object.entries(manifest.peerDependencies)) {
        if (name === PRECOMPUTE_NAME) {
          swapped[swapScope(name, scope)] = version;
        } else {
          swapped[name] = peerVersion;
        }
      }
      manifest.peerDependencies = swapped;
    }
    if (manifest.repository !== undefined) {
      const repositoryField = manifest.repository;
      if (
        typeof repositoryField !== "object" ||
        repositoryField === null ||
        typeof repositoryField.url !== "string"
      ) {
        throw new Error("SnapshotSwapRepositoryFieldInvalid");
      }
      repositoryField.url = swapRepositoryUrl(repositoryField.url, repository);
    }
    backup[path] = original;
    writeFileSync(path, `${JSON.stringify(manifest, null, 2)}\n`);
  }
  const swappedName = swapScope(PRECOMPUTE_NAME, scope);
  for (const path of sourcePaths) {
    const original = readFileSync(path, "utf8");
    if (!original.includes(PRECOMPUTE_NAME)) continue;
    backup[path] = original;
    writeFileSync(path, original.split(PRECOMPUTE_NAME).join(swappedName));
  }
  writeFileSync(backupPath, JSON.stringify(backup));
  console.log(`snapshot swap applied: ${scope} at ${version} for ${repository}`);
};

const restore = () => {
  if (!existsSync(backupPath)) {
    throw new Error("SnapshotSwapBackupMissing");
  }
  const backup = JSON.parse(readFileSync(backupPath, "utf8"));
  for (const [path, original] of Object.entries(backup)) {
    writeFileSync(path, original);
  }
  rmSync(backupPath);
  console.log("snapshot swap restored");
};

const [command, ...args] = process.argv.slice(2);
if (command === "apply") {
  if (args.length !== 3) {
    throw new Error("usage: snapshot-swap.mjs apply <version> <scope> <owner/repo>");
  }
  apply(args[0], args[1], args[2]);
} else if (command === "restore") {
  if (args.length !== 0) {
    throw new Error("usage: snapshot-swap.mjs restore");
  }
  restore();
} else {
  throw new Error("usage: snapshot-swap.mjs <apply|restore> [...]");
}
