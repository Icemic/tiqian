#if canImport(AppKit)
import AppKit
#elseif canImport(UIKit)
import UIKit
#endif
import TiqianUI

/// A demo passage, authored **here in the app** with native `AttributedString` + `CJKBlock` (this is
/// the point of the demo: show how a Swift app builds rich CJK documents). `build(base:)` returns the
/// blocks at a given body point size so relative sizes (title, big words) resolve to real fonts.
struct DemoSample {
    let title: String
    let subtitle: String
    let build: (CGFloat) -> [CJKBlock]
}

// A plain fragment, and a paragraph = concatenation of fragments.
private func t(_ s: String) -> AttributedString { AttributedString(s) }
private func para(_ parts: AttributedString...) -> AttributedString {
    var result = AttributedString()
    for part in parts { result.append(part) }
    return result
}
private func link(_ s: String, to destination: String) -> AttributedString {
    var result = AttributedString(s)
    result.link = URL(string: destination)
    return result
}

private let accentRed: PlatformColor = {
    #if os(macOS)
    NSColor(srgbRed: 176.0 / 255, green: 0, blue: 32.0 / 255, alpha: 1)
    #else
    UIColor(red: 176.0 / 255, green: 0, blue: 32.0 / 255, alpha: 1)
    #endif
}()
private let accentGreen: PlatformColor = {
    #if os(macOS)
    NSColor(srgbRed: 26.0 / 255, green: 110.0 / 255, blue: 60.0 / 255, alpha: 1)
    #else
    UIColor(red: 26.0 / 255, green: 110.0 / 255, blue: 60.0 / 255, alpha: 1)
    #endif
}()
private let serif = "Songti SC" // 宋体
private let mono = "Menlo"

let demoSamples: [DemoSample] = [
    overviewSample,
    richTextSample,
    linkSample,
    listsSample,
    indentSample,
    mixedScriptSample,
    hyphenationSample,
    lineBreakSample,
    justifySample,
    pinyinSample,
    bopomofoSample,
    decorationSample,
    mourningSample,
]

// MARK: - The all-in-one essay

private let overviewSample = DemoSample(
    title: "引擎自述",
    subtitle: "一次用全:富文本 · 列表 · 缩进 · 拼音 · 注音 · 着重号 · 专名号 · 书名号 · 示亡号",
) { base in
    [
        .paragraph(t("一台排版引擎的自述").styled(size: base * 1.9, bold: true), indent: .flush),
        .paragraph(para(
            t("诸位好。我叫"), t("提椠").ruby("tíqiàn"),
            t("，一台对中文正文"), t("斤斤计较").emphasis(),
            t("的排版引擎。别家把 espresso 和汉字一锅乱炖，我偏要在中西之间留出"), t("四分之一个字").emphasis(),
            t("的体面距离——你瞧，连这句里的 OpenType，我都没让它贴脸。"),
        )),
        .section,
        .paragraph(para(t("我的"), t("家规").styled(size: base, bold: true), t("不多，列在下面：")), indent: .flush),
        .list([
            para(t("标点不许在行首撒野：逗号句号一律"), t("避头尾").emphasis(), t("，该挤就挤，该悬就悬。")),
            // Menlo is Latin-only, so style only the Latin word with it; keep 的拙 in the CJK face
            // (a Latin family on CJK glyphs would mis-position them — one metric per span).
            para(t("字体随你挑——"), t("宋体的雅").styled(size: base, family: serif), t("、"), t("Menlo").styled(size: base, family: mono), t(" 的拙，按角色各取所需。")),
            para(t("注音拼音都伺候，连"), t("生僻字").ruby("shēngpì zì"), t("也给你标得明明白白。")),
        ], marker: .cjkNumber),
        .section,
        .paragraph(para(t("上周我还痛失一员旧部："), t("双面印刷").mourning(), t("。屏幕没有背面，只好请它"), t("先走一步").emphasis(), t("。"))),
        .paragraph(para(
            t("台湾来的朋友也照顾周到——"),
            t("您").bopomofo("ㄋㄧㄣˊ"), t("好").bopomofo("ㄏㄠˇ"), t("，"), t("请").bopomofo("ㄑㄧㄥˇ"),
            t("坐：ㄅㄆㄇ 竖在字旁，平上去入标得"), t("分毫不差").emphasis(), t("。"),
        )),
        .section,
        .paragraph(para(
            t("我奉"), t("CLREQ").properNoun(), t("——也就是"), t("《中文排版需求》").bookTitle(),
            t("——为圭臬，闲来也翻翻"), t("Unicode").properNoun(), t("的家底。"),
        ), indent: .quote(2)),
        .paragraph(t("顺带一提，这些我也顺手包办："), indent: .flush),
        .list([
            t("整数字格行长，正文严丝合缝落在格子上；"),
            t("行尾标点悬挂、中西自动间距，统统全自动；"),
            t("挤一挤放得下的，绝不硬把一整行拉稀。"),
        ], marker: .bullet),
        .section,
        .paragraph(para(
            t("有人嫌我"), t("龟毛").styled(size: base, italic: true), t("，我只当是"), t("褒奖").foreground(accentRed),
            t("。毕竟，好看的中文，是"), t("一个字一个字").styled(size: base * 1.3, bold: true, color: accentGreen), t("抠出来的。"),
        )),
    ]
}

// MARK: - Focused single-topic samples

private let richTextSample = DemoSample(
    title: "富文本",
    subtitle: "字重 · 斜体 · 颜色 · 字号 · 字体族",
) { base in
    [
        .paragraph(para(
            t("正文里可以夹"), t("粗体").styled(size: base, bold: true), t("、"), t("斜体").styled(size: base, italic: true),
            t("、"), t("红字").foreground(accentRed), t("与"), t("绿字").foreground(accentGreen),
            t("，也能把某几个字"), t("放大").styled(size: base * 1.6),
            t("，或局部换成"), t("宋体").styled(size: base, family: serif), t("、"), t("Menlo").styled(size: base, family: mono),
            t("。每一种都由引擎按 span 真实测量、真实绘制——测量即绘制。"),
        )),
        .paragraph(para(
            t("组合也行："), t("加粗的宋体大字").styled(size: base * 1.3, bold: true, family: serif), t("，或"),
            t("斜的绿字").styled(size: base, italic: true, color: accentGreen), t("，随你搭配。"),
        )),
    ]
}

private let linkSample = DemoSample(
    title: "链接",
    subtitle: "AttributedString.link · SwiftUI OpenURLAction · UIKit / AppKit",
) { _ in
    [
        .paragraph(para(
            t("链接直接用 Apple 原生 AttributedString 属性创作：请参阅「"),
            link("CLREQ", to: "https://www.w3.org/TR/clreq/"),
            t("」与「"),
            link("Unicode", to: "https://www.unicode.org/"),
            t("」。链接跨行时仍由同一 source range 命中，拖选不会开启页面。"),
        )),
    ]
}

private let listsSample = DemoSample(
    title: "列表",
    subtitle: "编号 (汉字数字) · 项目符号 · 凸排对齐",
) { _ in
    [
        .paragraph(t("编号列表（汉字数字，续行与正文同列对齐）："), indent: .flush),
        .list([
            t("标记左对齐顶格，正文整列缩进到标记列宽，续行自然落在同一列，读起来清清爽爽。"),
            t("列宽按列表里最宽的标记自动取整字数，每一项都对齐。"),
            t("汉字数字到十、二十一都没问题。"),
        ], marker: .cjkNumber),
        .section,
        .paragraph(t("项目符号列表："), indent: .flush),
        .list([
            t("整数字格行长，正文严丝合缝落在格子上；"),
            t("行尾标点悬挂、中西自动间距，统统全自动；"),
            t("挤一挤放得下的，绝不硬把一整行拉稀。"),
        ], marker: .bullet),
    ]
}

private let indentSample = DemoSample(
    title: "段落缩进",
    subtitle: "首行缩进 · 引用块 · 悬挂",
) { _ in
    [
        .paragraph(t("这是普通段落，首行按行长自适应缩进两个汉字的宽度（CLREQ「段首缩排以两个汉字的空间为标准」）。窄行会自动改缩一字，行长足够时缩两字。"), indent: .firstLine),
        .paragraph(t("这是整段缩进的引用块：所有行的始端都内移两个字，常用于引文、诗词或题解，与正文拉开层次。"), indent: .quote(2)),
        .paragraph(t("这是悬挂缩进：首行顶格，其余各行起缩两字——常用于对话、法条或术语解释，让每一条的正文对齐成列。"), indent: .hanging(2)),
    ]
}

private let mixedScriptSample = DemoSample(
    title: "中西混排",
    subtitle: "CJK / 拉丁 / 数字自动间距",
) { _ in
    [.paragraph(t(
        "诸位好。我叫提椠，一台对中文正文斤斤计较的排版引擎。别家把 espresso 和汉字一锅乱炖，" +
        "我偏要在中西之间留出四分之一个字的体面距离——你瞧，连这句里的 OpenType 与 CLREQ，" +
        "我都没让它们贴着汉字。数字也一样：全书 1024 页、第 42 章、公元 2026 年，边界处都自动透着气。"))]
}

private let hyphenationSample = DemoSample(
    title: "西文断词",
    subtitle: "音节断开 · 行尾连字符 · 源文本不变",
) { _ in
    [
        .paragraph(t(
            "中文夹用英文时先尽量保持整词；整词放不下或会把上一行拉得过松，才按英文音节断开，并在行尾补显示层连字符。"
        )),
        .paragraph(t(
            "窄栏可用 internationalization 验证正常音节断词，也可用 " +
            "pneumonoultramicroscopicsilicovolcanoconiosis 验证超长词不会突出版心；复制所得仍是原词。"
        )),
    ]
}

private let lineBreakSample = DemoSample(
    title: "避头尾 · 标点挤压",
    subtitle: "行首禁则与标点半角",
) { _ in
    [.paragraph(t(
        "标点不许在行首撒野：逗号、句号、顿号一律避头尾，该挤就挤，该悬就悬。你无论把窗口拉多窄，" +
        "「引号」、『书名号』、（括号）与省略号……都不会孤零零地掉到下一行的开头去。" +
        "句末的句号。问号？叹号！也会在行尾收半角，让每一行的右边界都咬得整整齐齐。"))]
}

private let justifySample = DemoSample(
    title: "两端对齐 · 长正文",
    subtitle: "整数字格行长 + 双齐",
) { _ in
    [.paragraph(t(
        "这是一段足够长的中文正文，用来看引擎如何在窄栏里做两端对齐。它先把可用宽度按字号整数取整，" +
        "得到严丝合缝的版心；再把每一行里可伸缩的标点空隙与中西间距均匀摊开，让除末行之外的每一行都恰好顶到右边界。" +
        "把窗口宽度慢慢改变，整段会跟着重新断行、重新对齐，而每个字始终落在它该在的格子上。"))]
}

private let pinyinSample = DemoSample(
    title: "拼音 ruby",
    subtitle: "注文在基字上方、水平居中、字重加粗",
) { _ in
    [.paragraph(para(
        t("提椠").ruby("tíqiàn"), t("为中文正文而生，连"), t("生僻字").ruby("shēngpì zì"), t("也能标得明明白白。"),
    ))]
}

private let bopomofoSample = DemoSample(
    title: "注音",
    subtitle: "ㄅㄆㄇ 右侧竖排 + 调号",
) { _ in
    [.paragraph(para(
        t("您").bopomofo("ㄋㄧㄣˊ"), t("好").bopomofo("ㄏㄠˇ"), t("，"),
        t("请").bopomofo("ㄑㄧㄥˇ"), t("坐").bopomofo("ㄗㄨㄛˋ"), t("。"),
    ))]
}

private let decorationSample = DemoSample(
    title: "着重号 · 专名号 · 书名号",
    subtitle: "CLREQ 行间装饰",
) { _ in
    [.paragraph(para(
        t("我奉"), t("CLREQ").properNoun(), t("为"), t("圭臬").emphasis(),
        t("，也就是"), t("《中文排版需求》").bookTitle(), t("。"),
    ))]
}

private let mourningSample = DemoSample(
    title: "示亡号",
    subtitle: "姓名整体加框 (CLREQ)",
) { _ in
    [.paragraph(para(
        t("示亡号是把姓名整体框起来的记号。譬如追怀"), t("鲁迅").mourning(),
        t("先生、纪念"), t("钱锺书").mourning(), t("先生时用它；框若落在换行处，会拆成两段开口的边。"),
    ))]
}
