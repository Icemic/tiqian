#!/usr/bin/env python3
"""Chinese documentation style self-check for tiqian docs.

Scans Chinese text files (ADR, design docs, README) for vocabulary and
rhetorical patterns that the project style rules ban: metaphor verbs used
as technical terms, internet jargon, coined compression words, contrast
constructions, and decorative adjectives.

Usage:
    python3 tools/doc-style/check.py             # scan README.md and docs/
    python3 tools/doc-style/check.py FILE ...    # scan specific files or dirs

Exit status: 0 when no hits remain, 1 when hits remain after the allowlist.
Each hit is a candidate for manual judgment, not an automatic violation.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

# Metaphor verbs, internet jargon, coined compression words, and colloquial
# shorthand named in style corrections. In writing, replace each with a plain
# verb or noun; swapping one metaphor for a near-synonym metaphor is not a
# fix (收窄 was such a failed replacement for 瘦身 and is banned as well).
WORDS = [
    # gate and doorway metaphors
    "门面", "扇门", "门控", "缓存门", "字体门",
    # bookkeeping and finance metaphors
    "闭环", "台账", "账",
    # targeting
    "钉在", "钉住", "钉死", "锁死",
    # motion and body metaphors
    "瘦身", "收口", "兜底", "退路", "穿透", "镜像", "升格", "降格", "摘出",
    "收窄", "寄生", "换装", "落地", "退役", "落档", "留档", "落点", "对拍",
    "钳位", "落盘", "射程",
    # internet jargon verbs
    "链路", "打通", "拉齐", "沉淀", "反哺", "赋能", "抓手", "打磨", "深耕",
    # misattributed or vague causal wording
    "根因", "归因", "掩盖", "口径", "挡住", "契约", "缺口", "夹具", "刀次",
    # measurement metaphors and coined measurement words
    "车道", "lane", "wall", "墙钟", "仪表", "亚毫", "膨胀", "显形", "重录",
    "冷构建", "热构建", "冷热", "全冷", "多重集", "构建链", "排空", "惰性",
    "互不推导", "三面", "三段式", "会话级", "进程级", "字节级", "内容级",
    "全 0",
    # coined technical-sounding words replaced by plain statements
    "失配", "真源", "转出口", "合批", "同批", "执行位", "线格式", "零违例",
    # colloquial shorthand
    "毛躁", "全绿", "全红", "锁相", "塞进", "收进", "测试绿", "测试红",
    # decorative adjectives and vague quantifiers: judge each line by context
    "恒", "恰好", "巨大的", "完整的", "真实", "合法", "归一", "缝隙", "大概率",
    "当日",
    # meta phrasing about the document itself: state facts instead
    "本记录", "终版", "不进仓库", "够支撑", "记录在案",
]

# Rhetorical sentence patterns: negate-first contrast, intensifiers, em-dash.
PATTERNS = [
    (r"不是[^。；！？\n]{0,60}而是", "contrast"),
    (r"，而是", "contrast"),
    (r"而不是", "contrast"),
    (r"，不是", "contrast"),
    (r"，而非", "contrast"),
    (r"更是", "contrast"),
    (r"——", "em-dash"),
]

# Known accepted uses. A line matching this regex is skipped entirely, so
# keep entries narrow: a line holding both an accepted use and a real
# violation would be missed, and the skip is per line, not per match.
ALLOW = re.compile(
    r"回退路径"  # contains the substring 退路 but is a standard term
    r"|`lane`"  # the grant voucher's literal field name in backticks
)


def iter_targets(args: list[str]):
    if args:
        for arg in args:
            path = Path(arg)
            if path.is_dir():
                yield from sorted(path.rglob("*.md"))
            else:
                yield path
        return
    yield REPO_ROOT / "README.md"
    yield from sorted((REPO_ROOT / "docs").rglob("*.md"))


def matched_words(line: str) -> list[str]:
    matched = [word for word in WORDS if word in line]
    # Drop words fully contained in a longer matched word on the same line
    # (账 inside 台账) so each line reports the narrowest cause once.
    return [
        word
        for word in matched
        if not any(word != other and word in other for other in matched)
    ]


def main() -> int:
    hits: list[tuple[str, int, str, str, str]] = []
    for path in iter_targets(sys.argv[1:]):
        try:
            text = path.read_text(encoding="utf-8")
        except (OSError, UnicodeDecodeError) as exc:
            print(f"skip {path}: {exc}", file=sys.stderr)
            continue
        try:
            shown = path.relative_to(REPO_ROOT)
        except ValueError:
            shown = path
        for number, line in enumerate(text.splitlines(), 1):
            if ALLOW.search(line):
                continue
            for word in matched_words(line):
                hits.append((str(shown), number, "word", word, line.strip()))
            for regex, tag in PATTERNS:
                if re.search(regex, line):
                    hits.append((str(shown), number, tag, regex, line.strip()))
    for shown, number, tag, token, line in hits:
        print(f"{shown}:{number}: [{tag}] {token}: {line}")
    print()
    print(f"命中 {len(hits)} 处。")
    print("说明：本工具只是自动化的检查列表，词表与句式模式不完整，需要随时填充。")
    print("命中仅为候选，逐条人工判定后改写。")
    print("自动检查不替代手动校验。")
    return 1 if hits else 0


if __name__ == "__main__":
    sys.exit(main())
