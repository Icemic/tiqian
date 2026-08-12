#!/usr/bin/env python3
"""Generate the pinned Unicode Script evidence table."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path


UNICODE_VERSION = "17.0.0"
SOURCE_URL = f"https://www.unicode.org/Public/{UNICODE_VERSION}/ucd/Scripts.txt"
SOURCE_SHA256 = "9f5e50d3abaee7d6ce09480f325c706f485ae3240912527e651954d2d6b035bf"
NEUTRAL_SCRIPTS = {"Common", "Inherited", "Unknown"}
EAST_ASIAN_SCRIPTS = {"Bopomofo", "Han", "Hangul", "Hiragana", "Katakana"}


def parse_range(field: str) -> tuple[int, int]:
    if ".." in field:
        start, end = field.split("..", 1)
        return int(start, 16), int(end, 16)
    value = int(field, 16)
    return value, value


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("scripts_txt", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    source = args.scripts_txt.read_bytes()
    actual_sha = hashlib.sha256(source).hexdigest()
    if actual_sha != SOURCE_SHA256:
        raise SystemExit(f"unexpected Scripts.txt SHA-256: {actual_sha}")

    source_ranges: list[tuple[int, int, int]] = []
    for raw_line in source.decode("utf-8").splitlines():
        content = raw_line.split("#", 1)[0].strip()
        if not content:
            continue
        code_points, script = (part.strip() for part in content.split(";", 1))
        if script in NEUTRAL_SCRIPTS:
            continue
        group = 1 if script in EAST_ASIAN_SCRIPTS else 2
        start, end = parse_range(code_points)
        source_ranges.append((start, end, group))

    ranges: list[tuple[int, int, int]] = []
    for start, end, group in sorted(source_ranges):
        if ranges and ranges[-1][2] == group and start == ranges[-1][1] + 1:
            ranges[-1] = (ranges[-1][0], end, group)
        else:
            ranges.append((start, end, group))

    entries = "\n".join(
        f"        0x{start:X}, 0x{end:X}, {group}," for start, end, group in ranges
    )
    output = f'''package org.tiqian.core

/*
 * GENERATED from Unicode {UNICODE_VERSION} Scripts.txt by
 * tools/unicode-script/generate_script_evidence_data.py.
 * Source: {SOURCE_URL}
 * SHA-256: {SOURCE_SHA256}
 * Copyright © 2025 Unicode, Inc.
 * Terms of Use: https://www.unicode.org/terms_of_use.html
 */
internal object UnicodeScriptEvidenceData {{
    fun classify(codePoint: Int): UnicodeScriptEvidence {{
        var low = 0
        var high = RANGES.size / 3 - 1
        while (low <= high) {{
            val middle = (low + high).ushr(1)
            val base = middle * 3
            when {{
                codePoint < RANGES[base] -> high = middle - 1
                codePoint > RANGES[base + 1] -> low = middle + 1
                else -> return if (RANGES[base + 2] == 1) {{
                    UnicodeScriptEvidence.EastAsian
                }} else {{
                    UnicodeScriptEvidence.Other
                }}
            }}
        }}
        return UnicodeScriptEvidence.Neutral
    }}

    private val RANGES = intArrayOf(
{entries}
    )
}}
'''
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(output, encoding="utf-8")


if __name__ == "__main__":
    main()
