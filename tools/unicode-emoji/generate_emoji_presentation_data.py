#!/usr/bin/env python3
"""Generate pinned Unicode 17 Emoji property and variation tables."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path


UNICODE_VERSION = "17.0.0"
EMOJI_DATA_URL = f"https://www.unicode.org/Public/{UNICODE_VERSION}/ucd/emoji/emoji-data.txt"
EMOJI_DATA_SHA256 = "2cb2bb9455cda83e8481541ecf5b6dfda66a3bb89efa3fa7c5297eccf607b72b"
EMOJI_VARIATION_SEQUENCES_URL = f"https://www.unicode.org/Public/{UNICODE_VERSION}/ucd/emoji/emoji-variation-sequences.txt"
EMOJI_VARIATION_SEQUENCES_SHA256 = "bb3d09ef03f206012c7532dd52dc0a21c9efddba0135ea4cf0d9201b8b9bba7e"
EMOJI_TEST_URL = f"https://www.unicode.org/Public/{UNICODE_VERSION}/emoji/emoji-test.txt"
EMOJI_TEST_SHA256 = "1d8a944f88d7952f7ef7c5167fef3c67995bcae24543949710231b03a201acda"

PROPERTY_TABLES = (
    ("Emoji_Presentation", "UnicodeEmojiPresentationData", "engine/src/commonMain/kotlin/org/tiqian/font"),
    ("Emoji", "UnicodeEmojiData", "engine/src/commonMain/kotlin/org/tiqian/font"),
    ("Emoji_Modifier_Base", "UnicodeEmojiModifierBaseData", "engine/src/commonMain/kotlin/org/tiqian/core"),
    ("Extended_Pictographic", "UnicodeExtendedPictographicData", "engine/src/commonMain/kotlin/org/tiqian/core"),
)


def parse_range(field: str) -> tuple[int, int]:
    if ".." in field:
        start, end = field.split("..", 1)
        return int(start, 16), int(end, 16)
    value = int(field, 16)
    return value, value


def coalesce_ranges(source_ranges: list[tuple[int, int]]) -> list[tuple[int, int]]:
    ranges: list[tuple[int, int]] = []
    for start, end in sorted(source_ranges):
        if ranges and start <= ranges[-1][1] + 1:
            ranges[-1] = (ranges[-1][0], max(ranges[-1][1], end))
        else:
            ranges.append((start, end))
    return ranges


def kotlin_table(
    package: str,
    object_name: str,
    source_name: str,
    source_url: str,
    source_sha256: str,
    ranges: list[tuple[int, int]],
) -> str:
    entries = "\n".join(f"        0x{start:X}, 0x{end:X}," for start, end in ranges)
    return f'''package {package}

/*
 * GENERATED from Unicode {UNICODE_VERSION} {source_name} by
 * tools/unicode-emoji/generate_emoji_presentation_data.py.
 * Source: {source_url}
 * SHA-256: {source_sha256}
 * Copyright © 2025 Unicode, Inc.
 * Terms of Use: https://www.unicode.org/terms_of_use.html
 */
internal object {object_name} {{
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


def rgi_audit_test(sequences: list[str]) -> str:
    entries = "\n".join(f'        "{sequence}",' for sequence in sequences)
    return f'''package org.tiqian.layout

import org.tiqian.clreq.ClreqProfile
import org.tiqian.core.TextRange
import org.tiqian.font.CjkFontRoleClassifier
import org.tiqian.font.FontRole
import org.tiqian.font.FontRoleContext
import kotlin.test.Test
import kotlin.test.assertEquals

/*
 * GENERATED from Unicode {UNICODE_VERSION} emoji-test.txt by
 * tools/unicode-emoji/generate_emoji_presentation_data.py.
 * Source: {EMOJI_TEST_URL}
 * SHA-256: {EMOJI_TEST_SHA256}
 * Copyright © 2025 Unicode, Inc.
 * Terms of Use: https://www.unicode.org/terms_of_use.html
 */
class UnicodeEmoji17RgiRoleAuditTest {{
    @Test
    fun fullyQualifiedEmojiSequencesResolveToOneEmojiRange() {{
        assertEquals({len(sequences)}, FULLY_QUALIFIED_CODE_POINT_SEQUENCES.size)
        val classifier = CjkFontRoleClassifier()
        val failures = FULLY_QUALIFIED_CODE_POINT_SEQUENCES.mapNotNull {{ codePoints ->
            val text = codePoints.toUnicodeString()
            val ranges = clusterRoleRanges(
                text = text,
                classifier = classifier,
                context = FontRoleContext(),
                profile = ClreqProfile.MainlandHorizontal,
                spanBoundaries = emptySet(),
                emojiShapingBoundaries = emptySet(),
            )
            val expected = listOf(TextRange(0, text.length) to FontRole.Emoji)
            val actual = ranges.map {{ it.range to it.role }}
            if (actual == expected) null else "$codePoints: expected=$expected actual=$actual"
        }}
        assertEquals(
            0,
            failures.size,
            "${{failures.size}} RGI role mismatches: ${{failures.take(20)}}",
        )
    }}

    private fun String.toUnicodeString(): String = buildString {{
        this@toUnicodeString.split(Regex("\\\\s+")).forEach {{ appendCodePoint(it.toInt(16)) }}
    }}

    private companion object {{
        val FULLY_QUALIFIED_CODE_POINT_SEQUENCES = listOf(
{entries}
        )
    }}
}}
'''


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("emoji_data_txt", type=Path)
    parser.add_argument("emoji_variation_sequences_txt", type=Path)
    parser.add_argument("emoji_test_txt", type=Path)
    parser.add_argument("repository_root", type=Path)
    args = parser.parse_args()

    emoji_data = args.emoji_data_txt.read_bytes()
    actual_emoji_data_sha = hashlib.sha256(emoji_data).hexdigest()
    if actual_emoji_data_sha != EMOJI_DATA_SHA256:
        raise SystemExit(f"unexpected emoji-data.txt SHA-256: {actual_emoji_data_sha}")

    variation_sequences = args.emoji_variation_sequences_txt.read_bytes()
    actual_variation_sequences_sha = hashlib.sha256(variation_sequences).hexdigest()
    if actual_variation_sequences_sha != EMOJI_VARIATION_SEQUENCES_SHA256:
        raise SystemExit(
            "unexpected emoji-variation-sequences.txt SHA-256: "
            f"{actual_variation_sequences_sha}",
        )

    emoji_test = args.emoji_test_txt.read_bytes()
    actual_emoji_test_sha = hashlib.sha256(emoji_test).hexdigest()
    if actual_emoji_test_sha != EMOJI_TEST_SHA256:
        raise SystemExit(f"unexpected emoji-test.txt SHA-256: {actual_emoji_test_sha}")

    properties: dict[str, list[tuple[int, int]]] = {}
    for raw_line in emoji_data.decode("utf-8").splitlines():
        content = raw_line.split("#", 1)[0].strip()
        if not content:
            continue
        code_points, property_name = (part.strip() for part in content.split(";", 1))
        properties.setdefault(property_name, []).append(parse_range(code_points))

    for property_name, object_name, output_directory in PROPERTY_TABLES:
        package = output_directory.rsplit("/", 1)[-1]
        output = args.repository_root / output_directory / f"{object_name}.kt"
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(
            kotlin_table(
                package=f"org.tiqian.{package}",
                object_name=object_name,
                source_name="emoji-data.txt",
                source_url=EMOJI_DATA_URL,
                source_sha256=EMOJI_DATA_SHA256,
                ranges=coalesce_ranges(properties[property_name]),
            ),
            encoding="utf-8",
        )

    emoji_style_bases: list[tuple[int, int]] = []
    for raw_line in variation_sequences.decode("utf-8").splitlines():
        content = raw_line.split("#", 1)[0].strip()
        if not content:
            continue
        code_points, description = (part.strip() for part in content.split(";", 1))
        if not description.startswith("emoji style"):
            continue
        base, variation_selector = (int(code_point, 16) for code_point in code_points.split())
        if variation_selector != 0xFE0F:
            raise SystemExit(f"unexpected emoji style sequence: {raw_line}")
        emoji_style_bases.append((base, base))

    output = args.repository_root / "engine/src/commonMain/kotlin/org/tiqian/font/UnicodeEmojiStyleVariationData.kt"
    output.write_text(
        kotlin_table(
            package="org.tiqian.font",
            object_name="UnicodeEmojiStyleVariationData",
            source_name="emoji-variation-sequences.txt",
            source_url=EMOJI_VARIATION_SEQUENCES_URL,
            source_sha256=EMOJI_VARIATION_SEQUENCES_SHA256,
            ranges=coalesce_ranges(emoji_style_bases),
        ),
        encoding="utf-8",
    )

    fully_qualified_sequences = []
    for raw_line in emoji_test.decode("utf-8").splitlines():
        if not raw_line or raw_line.startswith("#"):
            continue
        content = raw_line.split("#", 1)[0].strip()
        if "; fully-qualified" not in content:
            continue
        code_points = content.split(";", 1)[0].strip()
        fully_qualified_sequences.append(code_points)

    output = args.repository_root / "engine/src/jvmTest/kotlin/org/tiqian/layout/UnicodeEmoji17RgiRoleAuditTest.kt"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(rgi_audit_test(fully_qualified_sequences), encoding="utf-8")


if __name__ == "__main__":
    main()
