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
    # gate / threshold 直译（CI gate、graduation gate）
    "门槛", "毕业门", "硬门", "CI 门",
    # bookkeeping and finance metaphors
    "闭环", "台账", "账", "账目", "兑现", "零钱", "流失",
    # targeting and pinning
    "钉在", "钉住", "钉死", "锁死", "写死", "绑死",
    # motion and body metaphors
    "瘦身", "收口", "兜底", "退路", "穿透", "镜像", "升格", "降格", "摘出",
    "收窄", "寄生", "换装", "落地", "退役", "落档", "留档", "落点", "对拍",
    "钳位", "落盘", "射程",
    "虚胖", "穿帮", "失血", "幻影", "塌成", "塌缩", "腰斩", "砍半", "冲垮",
    "兜住", "掐死", "解冻", "前哨", "病根", "断崖", "元凶", "北极星", "皇冠",
    "犯忌", "无依无靠", "守门", "打回", "污染", "传染",
    "收归", "收档",
    "落为", "落格", "归位",
    "塞回", "硬塞", "误塞", "多塞", "能塞就塞", "插进",
    "吃掉", "吃光", "吃满", "吃饱",
    # internet jargon verbs
    "链路", "打通", "拉齐", "沉淀", "反哺", "赋能", "抓手", "打磨", "深耕",
    "复盘", "一把梭", "弃坑", "跑通", "回流", "通路", "真·",
    # misattributed or vague causal wording
    "根因", "归因", "掩盖", "口径", "挡住", "契约", "缺口", "夹具", "刀次",
    "包袱", "载体", "收束", "下沉", "节拍",
    # measurement metaphors and coined measurement words
    "车道", "lane", "wall", "墙钟", "仪表", "亚毫", "膨胀", "显形", "重录",
    "冷构建", "热构建", "冷热", "全冷", "多重集", "构建链", "排空", "惰性",
    "互不推导", "三面", "三段式", "会话级", "进程级", "字节级", "内容级",
    "全 0",
    "零漂移", "零差异", "零改动", "零引擎", "归零",
    "引擎级", "段落级", "浏览器级", "段级", "帧级", "符号级", "字段级",
    "子串级", "微秒级", "版面级", "站级", "篇级", "元素级", "更末级",
    "document 级", "audit 级", "glyph 级",
    "导出面", "消费面", "使用面", "调用面", "语义面", "运行时面", "改动面",
    "构造面", "不稳定面",
    "证据带", "整数带", "带表", "带条目",
    "发布线", "版本线", "延续线",
    "首绘", "真身", "含射", "发射", "烘焙", "单一事实源", "语义负担", "全链",
    "表路", "读侧", "走表", "填表人", "子片", "进表", "补造",
    "进解", "对赛", "互串", "错层", "加建", "过桥", "反连接", "换带", "收紧",
    # coined technical-sounding words replaced by plain statements
    "失配", "真源", "转出口", "合批", "同批", "执行位", "线格式", "零违例", "违例",
    "伪差异", "偶合", "已真", "换嗓",
    "半提交", "双份实现", "双职", "起测", "必产出", "帧迹", "随迁",
    "信号形状", "自管", "空挂", "替身", "本体", "整根", "挂旗", "处置链",
    "时钟泵", "旅程", "采纳链", "习语", "净胜",
    # colloquial shorthand
    "毛躁", "全绿", "全红", "锁相", "塞进", "收进", "测试绿", "测试红",
    "糊", "照跑", "拍平", "散落", "堆放", "接线", "免费拿到",
    # decorative adjectives and vague quantifiers: judge each line by context
    "恒", "恰好", "巨大的", "完整的", "真实", "合法", "归一", "缝隙", "大概率",
    "当日",
    "真正", "恰", "诱人", "灾难", "今天", "当天",
    # machine-translated or literal-translation feel
    "烘出", "烘入", "烘死", "农场", "裸" "回落",
    "缝", "姿态",
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
    # AI-generated filler transitions: restating without adding information
    (r"换句话说", "ai-filler"),
    (r"也就是说", "ai-filler"),
    (r"这意味着", "ai-filler"),
    (r"实际上[，:：]", "ai-filler"),
    (r"但实际", "ai-filler"),
    (r"看起来(?!像|$)", "ai-filler"),
    (r"但其实|[，。：；——」』]其实", "ai-filler"),
    # put-downs that add no information
    (r"只是把", "putdown"),
    (r"算不上", "putdown"),
    (r"根本不是", "putdown"),
    (r"纯粹是", "putdown"),
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
