#!/usr/bin/env python3
"""Generate the compact UAX #14 punctuation-class table used at runtime."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path


UNICODE_VERSION = "17.0.0"
SOURCE_URL = f"https://www.unicode.org/Public/{UNICODE_VERSION}/ucd/LineBreak.txt"
SOURCE_SHA256 = "e6a18fa91f8f6a6f8e534b1d3f128c21ada45bfe152eb6b1bcc5e15fd8ac92e6"
CLASS_IDS = {
    "BA": 0,
    "B2": 1,
    "CL": 2,
    "CP": 3,
    "EX": 4,
    "HH": 5,
    "HY": 6,
    "IN": 7,
    "IS": 8,
    "NS": 9,
    "OP": 10,
    "QU": 11,
    "SY": 12,
}


def parse_range(field: str) -> tuple[int, int]:
    if ".." in field:
        start, end = field.split("..", 1)
        return int(start, 16), int(end, 16)
    value = int(field, 16)
    return value, value


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("line_break_txt", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    source = args.line_break_txt.read_bytes()
    actual_sha = hashlib.sha256(source).hexdigest()
    if actual_sha != SOURCE_SHA256:
        raise SystemExit(f"unexpected LineBreak.txt SHA-256: {actual_sha}")

    ranges: list[tuple[int, int, int]] = []
    for raw_line in source.decode("utf-8").splitlines():
        content = raw_line.split("#", 1)[0].strip()
        if not content:
            continue
        code_points, class_name = (part.strip() for part in content.split(";", 1))
        class_id = CLASS_IDS.get(class_name)
        if class_id is None:
            continue
        start, end = parse_range(code_points)
        if ranges and ranges[-1][1] + 1 == start and ranges[-1][2] == class_id:
            ranges[-1] = (ranges[-1][0], end, class_id)
        else:
            ranges.append((start, end, class_id))

    entries = "\n".join(
        f"        0x{start:X}, 0x{end:X}, {class_id},"
        for start, end, class_id in ranges
    )
    output = f'''package org.tiqian.linebreak

/*
 * GENERATED from Unicode {UNICODE_VERSION} LineBreak.txt by
 * tools/unicode-line-break/generate_punctuation_data.py.
 * Source: {SOURCE_URL}
 * SHA-256: {SOURCE_SHA256}
 * Copyright © 2025 Unicode, Inc.
 * Terms of Use: https://www.unicode.org/terms_of_use.html
 *
 * Only punctuation classes consumed by UnicodePunctuationLineBreak are retained.
 */
internal object UnicodePunctuationLineBreakData {{
    fun lookup(codePoint: Int): Int {{
        var low = 0
        var high = RANGES.size / 3 - 1
        while (low <= high) {{
            val middle = (low + high).ushr(1)
            val base = middle * 3
            when {{
                codePoint < RANGES[base] -> high = middle - 1
                codePoint > RANGES[base + 1] -> low = middle + 1
                else -> return RANGES[base + 2]
            }}
        }}
        return -1
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
