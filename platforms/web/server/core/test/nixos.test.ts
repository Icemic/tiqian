// Unit coverage for the NixOS addon fallback (ADR 0050). The composition
// runs only on NixOS, so these tests cover the pure pieces: the os-release
// check, the ELF64 DT_NEEDED reader over a synthesized image, and the
// parsers for /proc/self/maps and ldconfig output.

import { test } from "node:test";
import assert from "node:assert/strict";

import type * as NixosModule from "../src/nixos.js";

let nixos: typeof NixosModule;
try {
  nixos = (await import("../lib/nixos.js")) as typeof NixosModule;
} catch {
  nixos = (await import("../src/nixos.js")) as typeof NixosModule;
}

const { isNixOsReleaseText, parseLdconfigEntries, parseLoadedSonames, parseMapsLibraryDirs, profileLibraryDirs, readElfNeeded } = nixos;

test("the os-release check accepts NixOS in every quoting and rejects lookalikes", () => {
  assert.equal(isNixOsReleaseText('NAME="NixOS"\nID=nixos\n'), true);
  assert.equal(isNixOsReleaseText('ID="nixos"\n'), true);
  assert.equal(isNixOsReleaseText("ID=nixos-extra\n"), false);
  assert.equal(isNixOsReleaseText('NAME="Ubuntu"\nID=ubuntu\n'), false);
  assert.equal(isNixOsReleaseText(""), false);
});

/** Packs a minimal ELF64 little-endian shared object around `needed`. */
function elfImage(needed: string[]): Buffer {
  const header = Buffer.alloc(64);
  header.write("\x7fELF", 0, "binary");
  header[4] = 2; // ELFCLASS64
  header[5] = 1; // little-endian
  const phoff = 64;
  const phentsize = 56;
  const phnum = 2;
  header.writeBigUInt64LE(BigInt(phoff), 0x20);
  header.writeUInt16LE(phentsize, 0x36);
  header.writeUInt16LE(phnum, 0x38);

  // One PT_LOAD covering everything at vaddr 0x400000, then PT_DYNAMIC.
  const strtab = ["", ...needed].map((name) => `${name}\0`).join("");
  const dynamicCount = needed.length + 3; // NEEDED entries, STRTAB, STRSZ, NULL
  const dynamicOffset = phoff + phnum * phentsize;
  const strtabOffset = dynamicOffset + dynamicCount * 16;
  const totalSize = strtabOffset + strtab.length;

  const program = Buffer.alloc(phentsize);
  program.writeUInt32LE(1, 0); // PT_LOAD
  program.writeBigUInt64LE(BigInt(0), 8); // p_offset
  program.writeBigUInt64LE(BigInt(0x400000), 16); // p_vaddr
  program.writeBigUInt64LE(BigInt(totalSize), 32); // p_filesz
  const dynamic = Buffer.alloc(phentsize);
  dynamic.writeUInt32LE(2, 0); // PT_DYNAMIC
  dynamic.writeBigUInt64LE(BigInt(dynamicOffset), 8);
  dynamic.writeBigUInt64LE(BigInt(0x400000 + dynamicOffset), 16); // p_vaddr
  dynamic.writeBigUInt64LE(BigInt(dynamicCount * 16), 32);

  const image = Buffer.alloc(totalSize);
  header.copy(image, 0);
  program.copy(image, phoff);
  dynamic.copy(image, phoff + phentsize);
  let cursor = dynamicOffset;
  const writeEntry = (tag: number, value: number | bigint): void => {
    image.writeBigInt64LE(BigInt(tag), cursor);
    image.writeBigUInt64LE(BigInt(value), cursor + 8);
    cursor += 16;
  };
  for (const name of needed) {
    const offset = Buffer.byteLength(["", ...needed.slice(0, needed.indexOf(name))].map((part) => `${part}\0`).join(""));
    writeEntry(1, offset); // DT_NEEDED, offset of this name in the strtab
  }
  writeEntry(5, 0x400000 + strtabOffset); // DT_STRTAB (vaddr)
  writeEntry(10, strtab.length); // DT_STRSZ
  writeEntry(0, 0); // DT_NULL
  image.write(strtab, strtabOffset, "binary");
  return image;
}

test("the ELF reader returns the DT_NEEDED names of a synthesized image", () => {
  const image = elfImage(["libstdc++.so.6", "libm.so.6"]);
  assert.deepEqual(readElfNeeded(image), ["libstdc++.so.6", "libm.so.6"]);
});

test("the ELF reader rejects non-ELF and non-64-bit inputs", () => {
  assert.equal(readElfNeeded(Buffer.from("not an elf")), null);
  assert.equal(readElfNeeded(elfImage(["liba.so.1"]).subarray(0, 8)), null);
  const thirtyTwoBit = elfImage(["liba.so.1"]);
  thirtyTwoBit[4] = 1; // ELFCLASS32
  assert.equal(readElfNeeded(thirtyTwoBit), null);
});

test("the maps parser collects shared-object basenames and nix store dirs", () => {
  const maps = [
    "7f0000000000-7f0000001000 r--p 00000000 00:01 1  /nix/store/aaa-glibc-2.42/lib/libc.so.6",
    "7f0000001000-7f0000002000 r--p 00000000 00:01 2  /nix/store/bbb-gcc-lib/lib/libstdc++.so.6",
    "7f0000002000-7f0000003000 r--p 00000000 00:01 3  /nix/store/aaa-glibc-2.42/lib/libc.so.6",
    "7f0000003000-7f0000004000 r--p 00000000 00:01 4  /home/alice/.cache/tiqian-precompute/nix-rpath/dead.node",
    "7f0000004000-7f0000005000 r--p 00000000 00:01 5  /nix/store/ccc-foo/lib/libbar.so.1 (deleted)",
    "",
  ].join("\n");
  assert.deepEqual(
    [...parseLoadedSonames(maps)].sort(),
    ["libbar.so.1", "libc.so.6", "libstdc++.so.6"],
  );
  assert.deepEqual(parseMapsLibraryDirs(maps).sort(), [
    "/nix/store/aaa-glibc-2.42/lib",
    "/nix/store/bbb-gcc-lib/lib",
    "/nix/store/ccc-foo/lib",
  ]);
});

test("the ldconfig parser maps sonames to paths", () => {
  const entries = parseLdconfigEntries(
    [
      "\tlibstdc++.so.6 (libc6,x86-64) => /run/current-system/sw/lib/libstdc++.so.6",
      "\tlibm.so.6 (libc6,x86-64, OS ABI: 3.2) => /nix/store/aaa-glibc-2.42/lib/libm.so.6",
      "24 libs found in cache `/etc/ld.so.cache'",
      "",
    ].join("\n"),
  );
  assert.equal(entries.size, 2);
  assert.equal(entries.get("libstdc++.so.6"), "/run/current-system/sw/lib/libstdc++.so.6");
  assert.equal(entries.get("libm.so.6"), "/nix/store/aaa-glibc-2.42/lib/libm.so.6");
});

test("the profile directories list the system profile before the user ones", () => {
  assert.deepEqual(profileLibraryDirs("/home/alice"), [
    "/run/current-system/sw/lib",
    "/nix/var/nix/profiles/default/lib",
    "/home/alice/.local/state/nix/profiles/profile/lib",
    "/home/alice/.nix-profile/lib",
  ]);
});
