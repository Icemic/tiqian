// NixOS fallback for loading the linux addon. The platform binaries are
// built by CI against a plain glibc and carry no runpath, so every
// DT_NEEDED entry must resolve through the running process: either the
// runtime already loaded the same SONAME, or the dynamic loader finds it in
// its search path. Plain Linux distributions provide the C++ runtime in a
// default directory; NixOS has none, and the libraries live in store paths
// that only the profiles know about. When the plain load fails on NixOS
// this module names the unresolved libraries and, when patchelf is
// available, loads a cached copy of the addon with a repaired runpath.
//
// Two mechanisms were measured and rejected on 2026-08-22 (ADR 0050):
// setting LD_LIBRARY_PATH in JS before require has no effect, because glibc
// parses the variable once at process startup; and preloading the missing
// library by absolute path needs dlopen of a plain shared object, which
// Node refuses ("Module did not self-register") and undoes.

import { spawnSync } from "node:child_process";
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { homedir } from "node:os";
import { createHash } from "node:crypto";

/** The `os-release(5)` ID value written by NixOS activation. */
export function isNixOsReleaseText(text: string): boolean {
  return /^ID=["']?nixos["']?$/m.test(text);
}

/** True when the running system is NixOS proper, not nix on another distro. */
export function isNixOs(): boolean {
  try {
    return isNixOsReleaseText(readFileSync("/etc/os-release", "utf8"));
  } catch {
    return false;
  }
}

// ---------------------------------------------------------------------------
// ELF64 reading: only the DT_NEEDED names are needed here, and reading them
// without spawning a tool keeps the diagnosis working on minimal profiles.
// The parser accepts exactly what our addons are: 64-bit little-endian
// ELF shared objects. Anything else returns null and the caller rethrows.

const PT_LOAD = 1;
const PT_DYNAMIC = 2;
const DT_NULL = 0;
const DT_NEEDED = 1;
const DT_STRTAB = 5;
const DT_STRSZ = 10;

/** The DT_NEEDED library names of an ELF64 little-endian image, or null. */
export function readElfNeeded(bytes: Buffer): string[] | null {
  if (bytes.length < 64 || bytes[0] !== 0x7f || bytes[1] !== 0x45 || bytes[2] !== 0x4c || bytes[3] !== 0x46) {
    return null;
  }
  if (bytes[4] !== 2 || bytes[5] !== 1) {
    return null;
  }
  const phoff = Number(bytes.readBigUInt64LE(0x20));
  const phentsize = bytes.readUInt16LE(0x36);
  const phnum = bytes.readUInt16LE(0x38);
  let dynamicOffset = -1;
  const loads: { vaddr: number; offset: number; filesz: number }[] = [];
  for (let index = 0; index < phnum; index += 1) {
    const header = phoff + index * phentsize;
    if (header + 56 > bytes.length) {
      return null;
    }
    const type = bytes.readUInt32LE(header);
    if (type === PT_DYNAMIC) {
      dynamicOffset = Number(bytes.readBigUInt64LE(header + 8));
    } else if (type === PT_LOAD) {
      loads.push({
        offset: Number(bytes.readBigUInt64LE(header + 8)),
        vaddr: Number(bytes.readBigUInt64LE(header + 16)),
        filesz: Number(bytes.readBigUInt64LE(header + 32)),
      });
    }
  }
  if (dynamicOffset < 0 || loads.length === 0) {
    return null;
  }
  const vaddrToOffset = (vaddr: number): number | null => {
    for (const load of loads) {
      if (vaddr >= load.vaddr && vaddr < load.vaddr + load.filesz) {
        return vaddr - load.vaddr + load.offset;
      }
    }
    return null;
  };
  let strtabOffset = -1;
  let strsz = 0;
  const neededOffsets: number[] = [];
  for (let cursor = dynamicOffset; cursor + 16 <= bytes.length; cursor += 16) {
    const tag = bytes.readBigInt64LE(cursor);
    const value = Number(bytes.readBigUInt64LE(cursor + 8));
    if (tag === BigInt(DT_NULL)) {
      break;
    }
    if (tag === BigInt(DT_STRTAB)) {
      const offset = vaddrToOffset(value);
      if (offset === null) {
        return null;
      }
      strtabOffset = offset;
    } else if (tag === BigInt(DT_STRSZ)) {
      strsz = value;
    } else if (tag === BigInt(DT_NEEDED)) {
      neededOffsets.push(value);
    }
  }
  if (strtabOffset < 0 || strtabOffset + strsz > bytes.length) {
    return null;
  }
  const names: string[] = [];
  for (const offset of neededOffsets) {
    const start = strtabOffset + offset;
    if (start < strtabOffset || start >= strtabOffset + strsz) {
      return null;
    }
    const end = bytes.indexOf(0, start);
    if (end < 0 || end >= strtabOffset + strsz) {
      return null;
    }
    names.push(bytes.toString("utf8", start, end));
  }
  return names;
}

// ---------------------------------------------------------------------------
// Library search. The dynamic loader consults, in order: the loaded images,
// the addon runpath, LD_LIBRARY_PATH (fixed at process startup), the
// ldconfig cache, and the default directories. NixOS has no default
// directories, so the last two sources cover only the system profile. The
// extra sources below are the ones glibc never looks at: the store
// directories of libraries the running process already mapped, and the
// user profile library directories.

// The pathname is the sixth column and may carry a trailing " (deleted)"
// marker with a space inside it, so the field cannot be split by whitespace.
const mapsPath = (line: string): string | null => {
  const match = line.match(/^\s*(?:\S+\s+){5}(\/\S.*)$/u);
  if (match === null) {
    return null;
  }
  return match[1].replace(/\s+\(deleted\)$/u, "");
};

/** Basenames of every mapped shared object in a `/proc/self/maps` dump. */
export function parseLoadedSonames(mapsText: string): Set<string> {
  const names = new Set<string>();
  for (const line of mapsText.split("\n")) {
    const pathField = mapsPath(line);
    if (pathField === null || !pathField.includes(".so")) {
      continue;
    }
    names.add(pathField.slice(pathField.lastIndexOf("/") + 1));
  }
  return names;
}

/** Directories of the nix store libraries the current process mapped. */
export function parseMapsLibraryDirs(mapsText: string): string[] {
  const dirs = new Set<string>();
  for (const line of mapsText.split("\n")) {
    const pathField = mapsPath(line);
    if (pathField !== null && pathField.startsWith("/nix/store/") && pathField.includes(".so")) {
      dirs.add(dirname(pathField));
    }
  }
  return [...dirs];
}

/** The soname-to-path entries of `ldconfig -p` output. */
export function parseLdconfigEntries(text: string): Map<string, string> {
  const entries = new Map<string, string>();
  for (const line of text.split("\n")) {
    const match = line.match(/^\s*(\S*\.so\S*)\s+\([^)]*\)\s+=>\s+(\S+)/u);
    if (match) {
      entries.set(match[1], match[2]);
    }
  }
  return entries;
}

/** Library directories of the fixed NixOS profiles, most specific first. */
export function profileLibraryDirs(home: string): string[] {
  return [
    "/run/current-system/sw/lib",
    "/nix/var/nix/profiles/default/lib",
    join(home, ".local/state/nix/profiles/profile/lib"),
    join(home, ".nix-profile/lib"),
  ];
}

/**
 * Extra library directories from the environment, for nix-shell runspaces
 * and other store prefixes the fixed profiles do not cover.
 */
export function environmentLibraryDirs(): string[] {
  const value = process.env.TIQIAN_PRECOMPUTE_NIX_LIB_DIRS ?? "";
  return value.split(":").filter((dir) => dir !== "");
}

interface LibraryPlan {
  /** NEEDED entries that neither the process nor the loader search path covers. */
  unresolved: string[];
  /** Directories holding those entries, for the repaired runpath. */
  runpathDirs: string[];
}

const planRepair = (addonPath: string, home: string): LibraryPlan | null => {
  const needed = readElfNeeded(readFileSync(addonPath));
  if (needed === null) {
    return null;
  }
  const readMapsText = (): string => {
    try {
      return readFileSync("/proc/self/maps", "utf8");
    } catch {
      return "";
    }
  };
  const mapsText = readMapsText();
  const loaded = parseLoadedSonames(mapsText);
  const ldconfig = spawnSync("ldconfig", ["-p"], { encoding: "utf8" });
  const cacheEntries = ldconfig.status === 0 ? parseLdconfigEntries(ldconfig.stdout) : new Map<string, string>();
  const candidateDirs: string[] = [...environmentLibraryDirs(), ...parseMapsLibraryDirs(mapsText), ...profileLibraryDirs(home)];
  const unresolved: string[] = [];
  const runpathDirs = new Set<string>();
  for (const library of needed) {
    if (loaded.has(library) || cacheEntries.has(library)) {
      continue;
    }
    const directory = candidateDirs.find((dir) => existsSync(join(dir, library)));
    if (directory === undefined) {
      unresolved.push(library);
      continue;
    }
    runpathDirs.add(directory);
  }
  if (unresolved.length === 0 && runpathDirs.size === 0) {
    // Every dependency already resolves; the failure has another cause and
    // the original error says more about it than a runpath repair would.
    return null;
  }
  return { unresolved, runpathDirs: [...runpathDirs] };
};

const runPatchelf = (arguments_: string[]): { status: number | null; error?: Error } => {
  for (const candidate of ["patchelf", "/run/current-system/sw/bin/patchelf"]) {
    const result = spawnSync(candidate, arguments_, { encoding: "utf8" });
    if (result.error === undefined) {
      return { status: result.status, error: undefined };
    }
  }
  return { status: null, error: new Error("patchelf not found") };
};

const cacheDirectory = (home: string): string => join(home, ".cache", "tiqian-precompute", "nix-rpath");

/**
 * Loads a linux platform package and, on NixOS, retries a failed load with a
 * repaired runpath. `requireFn` is the CommonJS require of `load.ts`; the
 * addon is cached under `~/.cache/tiqian-precompute/nix-rpath` keyed by the
 * source file digest, so the patchelf run happens once per addon build.
 */
export function loadLinuxAddon(requireFn: NodeJS.Require, spec: string): ReturnType<NodeJS.Require> {
  try {
    return requireFn(spec);
  } catch (failure) {
    if (!isNixOs()) {
      throw failure;
    }
    let addonPath: string;
    try {
      addonPath = requireFn.resolve(spec);
    } catch {
      throw failure;
    }
    const home = homedir();
    const source = readFileSync(addonPath);
    const digest = createHash("sha256").update(source).digest("hex");
    const repaired = join(cacheDirectory(home), `${digest}.node`);
    // A previous run already repaired this exact addon build; the diagnosis
    // below stays skipped so a repeat load works even when the environment
    // that produced the repair is gone.
    if (!existsSync(repaired)) {
      const plan = planRepair(addonPath, home);
      if (plan === null) {
        throw failure;
      }
      if (plan.unresolved.length > 0) {
        throw new Error(
          `TiqianPrecomputeNixLibraryMissing: the addon ${addonPath} needs ` +
            `${plan.unresolved.join(", ")}; no directory among the loaded images, ` +
            `the ldconfig cache, and the profile library directories provides it. ` +
            `Install the package that ships the library, or start the process with ` +
            `LD_LIBRARY_PATH pointing at its directory.`,
          { cause: failure },
        );
      }
      const staging = `${repaired}.tmp-${process.pid}`;
      const runpath = plan.runpathDirs.join(":");
      mkdirSync(dirname(repaired), { recursive: true });
      writeFileSync(staging, source);
      const applied = runPatchelf(["--set-rpath", runpath, staging]);
      if (applied.error !== undefined) {
        throw new Error(
          `TiqianPrecomputeNixPatchelfMissing: the addon needs the runpath ` +
            `${runpath}, and patchelf was not found. Install it ` +
            `(nix profile install nixpkgs#patchelf) and retry.`,
          { cause: failure },
        );
      }
      if (applied.status !== 0) {
        throw new Error(
          `TiqianPrecomputeNixPatchelfFailed: patchelf --set-rpath ${runpath} ` +
            `failed with status ${String(applied.status)}.`,
          { cause: failure },
        );
      }
      renameSync(staging, repaired);
    }
    return requireFn(repaired);
  }
}
