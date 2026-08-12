#!/usr/bin/env python3
"""Generate the pinned Unicode Letter/Mark/Number table used for word boundaries."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path


UNICODE_VERSION = "17.0.0"
SOURCE_URL = (
    f"https://www.unicode.org/Public/{UNICODE_VERSION}/ucd/extracted/"
    "DerivedGeneralCategory.txt"
)
SOURCE_SHA256 = "d62e5bab70ca74f099343f71224fa051cb1fdd61a1ab45c0488c44cfc0b6102e"


def parse_range(field: str) -> tuple[int, int]:
    if ".." in field:
        start, end = field.split("..", 1)
        return int(start, 16), int(end, 16)
    value = int(field, 16)
    return value, value


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("derived_general_category_txt", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    source = args.derived_general_category_txt.read_bytes()
    actual_sha = hashlib.sha256(source).hexdigest()
    if actual_sha != SOURCE_SHA256:
        raise SystemExit(f"unexpected DerivedGeneralCategory.txt SHA-256: {actual_sha}")

    source_ranges: list[tuple[int, int]] = []
    for raw_line in source.decode("utf-8").splitlines():
        content = raw_line.split("#", 1)[0].strip()
        if not content:
            continue
        code_points, category = (part.strip() for part in content.split(";", 1))
        if category.startswith(("L", "M", "N")):
            source_ranges.append(parse_range(code_points))

    ranges: list[tuple[int, int]] = []
    for start, end in sorted(source_ranges):
        if ranges and start <= ranges[-1][1] + 1:
            ranges[-1] = (ranges[-1][0], max(ranges[-1][1], end))
        else:
            ranges.append((start, end))

    entries = "\n".join(f"        0x{start:X}, 0x{end:X}," for start, end in ranges)
    output = f'''package org.tiqian.core

/*
 * GENERATED from Unicode {UNICODE_VERSION} DerivedGeneralCategory.txt by
 * tools/unicode-general-category/generate_word_character_data.py.
 * Source: {SOURCE_URL}
 * SHA-256: {SOURCE_SHA256}
 * Copyright © 2025 Unicode, Inc.
 * Terms of Use: https://www.unicode.org/terms_of_use.html
 */
internal object UnicodeWordCharacterData {{
    fun contains(codePoint: Int): Boolean {{
        var low = 0
        var high = RANGES.size / 2 - 1
        while (low <= high) {{
            val middle = (low + high).ushr(1)
            val base = middle * 2
            when {{
                codePoint < RANGES[base] -> high = middle - 1
                codePoint > RANGES[base + 1] -> low = middle + 1
                else -> return true
            }}
        }}
        return false
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
