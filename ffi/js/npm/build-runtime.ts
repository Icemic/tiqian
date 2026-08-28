#!/usr/bin/env node

import { spawnSync, type SpawnSyncReturns } from "node:child_process";
import { fileURLToPath } from "node:url";

const repositoryRoot: string = fileURLToPath(new URL("../../..", import.meta.url));
const isWindows: boolean = process.platform === "win32";
const gradleWrapper: string = fileURLToPath(new URL(
  isWindows ? "../../../gradlew.bat" : "../../../gradlew",
  import.meta.url,
));
const gradleArguments: readonly string[] = [
  ":ffi:js:clean",
  ":ffi:js:assembleNpmPackage",
  "--no-build-cache",
];
// WindowsBatchWrapperViaComSpec: .bat files are cmd scripts rather than native
// executables. Invoke the wrapper through ComSpec while keeping Unix on the
// directly executable Gradle wrapper.
const command: string = isWindows
  ? (process.env.ComSpec ?? process.env.COMSPEC ?? "cmd.exe")
  : gradleWrapper;
const commandArguments: readonly string[] = isWindows
  ? ["/d", "/c", "call", gradleWrapper, ...gradleArguments]
  : gradleArguments;
const result: SpawnSyncReturns<Buffer> = spawnSync(command, commandArguments, {
  cwd: repositoryRoot,
  stdio: "inherit",
});

if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
