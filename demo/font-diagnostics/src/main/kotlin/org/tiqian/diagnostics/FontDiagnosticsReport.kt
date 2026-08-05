package org.tiqian.diagnostics

import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.SystemFonts
import android.graphics.text.TextRunShaper
import android.os.Build
import android.text.TextPaint
import java.io.File
import java.util.Locale

/**
 * 一次性 Android 平台字体行为普查。
 *
 * 只观测平台自身行为，不评估任何排版实现——实现要照着这些行为写，所以这里不能掺入
 * 对某个实现的判断。
 *
 * 三条设计约束（报告只跑一次，回收后要读得动）：
 *
 * 1. **确定性**：不打印对象 hashcode、时间戳或任何逐次变化的值，顺序固定。设备身份只出现在
 *    第 1 节，这样 OEM 报告可以直接和 AOSP 基线做 diff，差异行就是结论。
 * 2. **结论在前**：信息量在「比较」里而不是数据里，而比较完全可以在设备上算完。第 0 节给出
 *    算好的结论，读它就够；意外时才往后翻原始证据。
 * 3. **证据在后**：原始数据一条不删，宁可冗长——重新拿报告的代价远高于文件大小。
 *
 * 全程只读：不安装字体、不改设置、不联网；仅在点分享时向应用私有 cache 写一份报告文件。
 */
object FontDiagnosticsReport {

    // ---- 探针定义 -------------------------------------------------------------------

    private val TEXT_PROBES = listOf(
        "中文" to "汉字正文",
        "骨直海角刃真" to "语言敏感字形（简/繁/日 应不同）",
        "門関覚単" to "简繁日字形差异",
        "。、；：" to "中文点号",
        "「」『』（）" to "中文括号引号",
        "——" to "破折号 U+2014 x2",
        "⸺" to "U+2E3A 两字宽破折号",
        "……" to "省略号",
        "０１２３" to "全角数字",
        "0123" to "半角数字",
        "English" to "拉丁正文",
        "Ĉĝĥĵ" to "拉丁扩展",
        "Привет" to "西里尔",
        "Ελληνικά" to "希腊",
        "한국어" to "韩文",
        "ひらがなカタカナ" to "日文假名",
        "𠮷𡈽" to "CJK 扩展 B",
        "㐀㐁" to "CJK 扩展 A",
        "नमस्ते" to "天城文",
        "العربية" to "阿拉伯",
        "ไทย" to "泰文",
        "😀" to "emoji",
        "🇨🇳" to "区域指示符",
        "☎☑✔" to "符号",
    )

    private val LOCALES = listOf("zh-Hans-CN", "zh-Hant-TW", "zh-Hant-HK", "ja-JP", "ko-KR", "en-US")

    private val PLATFORM_FAMILIES = listOf(
        "sans-serif", "sans-serif-medium", "sans-serif-light", "sans-serif-black",
        "sans-serif-condensed", "sans-serif-thin", "sans-serif-smallcaps",
        "serif", "monospace", "serif-monospace", "casual", "cursive",
    )

    /** OEM 家族名：解析不出来会回落默认，报告里体现为「与 sans-serif 同一 face」——这也是结论。 */
    private val OEM_FAMILIES = listOf(
        "MiSans", "MiSans VF", "MiSans Global", "mipro", "Mi Lan Pro",
        "HarmonyOS Sans", "HarmonyOS Sans SC", "HarmonyOS_Sans_SC",
        "OPPOSans", "OplusSans", "vivo Sans", "vivoType", "OneUI Sans", "SamsungOne",
        "Noto Sans CJK SC", "Source Han Sans", "PingFang SC",
    )

    private val WEIGHTS = listOf(100, 300, 400, 500, 700, 900)

    private val FONT_CONFIG_PATHS = listOf(
        "/system/etc/fonts.xml",
        "/system/etc/fonts_base.xml",
        "/system/etc/fonts_customization.xml",
        "/system/etc/system_fonts.xml",
        "/system/etc/fallback_fonts.xml",
        "/system/etc/font_fallback.xml",
        "/product/etc/fonts_customization.xml",
        "/vendor/etc/fonts_customization.xml",
        "/system_ext/etc/fonts_customization.xml",
        "/data/system/theme/fonts.xml",
    )

    private val FONT_DIRS = listOf(
        "/system/fonts", "/product/fonts", "/vendor/fonts",
        "/system_ext/fonts", "/system/font", "/data/system/theme/fonts",
    )

    /** AOSP 常见字体名前缀。不匹配的一律当作 OEM 附加字体单列出来。 */
    private val AOSP_PREFIXES = listOf(
        "Noto", "Roboto", "Droid", "AndroidClock", "CarroisGothic", "ComingSoon",
        "CutiveMono", "DancingScript", "Zeyada", "SourceSans", "AndroidEmoji", "Clock",
    )

    // ---- 观测原语 -------------------------------------------------------------------

    /** 一次 shaping 的可比较事实。刻意不含任何逐次变化的值。 */
    private data class RunFacts(
        val glyphCount: Int,
        val files: List<String>,
        val glyphIds: List<Int>,
        val advance: Float,
        val error: String? = null,
    ) {
        val fileNames: List<String> get() = files.map { it.substringAfterLast('/') }
        fun oneLine(): String = when {
            error != null -> "THREW $error"
            Build.VERSION.SDK_INT < 31 -> "advance=$advance （glyph 级信息需 API 31+）"
            else -> "glyphs=$glyphCount advance=$advance files=${fileNames.joinToString()} ids=${glyphIds.joinToString()}"
        }
    }

    private fun observe(text: String, locale: Locale, typeface: Typeface?): RunFacts = runCatching {
        val paint = TextPaint().apply {
            textSize = 32f
            textLocale = locale
            this.typeface = typeface
        }
        val advance = paint.measureText(text)
        if (Build.VERSION.SDK_INT < 31) {
            return@runCatching RunFacts(-1, emptyList(), emptyList(), advance)
        }
        val shaped = TextRunShaper.shapeTextRun(text, 0, text.length, 0, text.length, 0f, 0f, false, paint)
        val files = (0 until shaped.glyphCount())
            .map { shaped.getFont(it).file?.absolutePath ?: "(no-file)" }
        val ids = (0 until shaped.glyphCount()).map { shaped.getGlyphId(it) }
        RunFacts(shaped.glyphCount(), files.distinct(), ids, advance)
    }.getOrElse { t ->
        RunFacts(-1, emptyList(), emptyList(), Float.NaN, "${t::class.java.simpleName}: ${t.message}")
    }

    private fun typefaceOf(family: String): Typeface? =
        runCatching { Typeface.create(family, Typeface.NORMAL) }.getOrNull()

    private fun weightedSans(weight: Int): Typeface? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), weight, false)
        } else {
            Typeface.create("sans-serif", if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }
    }.getOrNull()

    private fun String.codePointStrings(): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < length) {
            val cp = codePointAt(i)
            val n = Character.charCount(cp)
            out += substring(i, i + n)
            i += n
        }
        return out
    }

    private fun systemFontsOrEmpty(): List<Font> =
        if (Build.VERSION.SDK_INT < 29) emptyList()
        else runCatching { SystemFonts.getAvailableFonts().toList() }.getOrDefault(emptyList())

    // ---- 报告 -----------------------------------------------------------------------

    fun collect(): String = buildString {
        appendLine("# 提椠 Android 平台字体行为报告")
        appendLine("report-version: 3")
        appendLine("只观测平台行为；输出确定性，可与 AOSP 基线直接 diff（第 1 节含设备身份，diff 时忽略）。")
        appendLine()
        section("0. 结论摘要（机器算出，先读这节）") { summarySection() }
        section("1. 设备与系统（diff 时忽略本节）") { deviceSection() }
        section("2. 原始证据：文本 × locale 渲染矩阵") { renderMatrixSection() }
        section("3. 原始证据：具名家族解析") { familySection() }
        section("4. 原始证据：字重与可变轴") { weightSection() }
        section("5. 原始证据：Paint.hasGlyph 覆盖矩阵") { hasGlyphSection() }
        section("6. 原始证据：字体配置文件全文") { fontConfigSection() }
        section("7. 原始证据：SystemFonts 全量枚举") { systemFontsSection() }
        section("8. 原始证据：字体目录全量列举") { fontDirSection() }
        appendLine("报告结束。")
    }

    private inline fun StringBuilder.section(title: String, body: StringBuilder.() -> Unit) {
        appendLine("## $title")
        appendLine()
        runCatching { body() }.onFailure { t ->
            appendLine("!! 本节采集失败：${t::class.java.name}: ${t.message}")
        }
        appendLine()
    }

    // ---- 第 0 节：结论 ---------------------------------------------------------------

    private fun StringBuilder.summarySection() {
        val glyphReadback = Build.VERSION.SDK_INT >= 31
        appendLine("[能力] SDK=${Build.VERSION.SDK_INT} 逐glyph字体读回=${if (glyphReadback) "可用" else "不可用(需API31+)"} " +
            "SystemFonts枚举=${if (Build.VERSION.SDK_INT >= 29) "可用" else "不可用(需API29+)"}")
        appendLine()

        appendLine("[F1] 正文 face（Typeface.DEFAULT，按 locale）")
        val cjkByLocale = LOCALES.associateWith { observe("中文", Locale.forLanguageTag(it), Typeface.DEFAULT) }
        LOCALES.forEach { tag ->
            val f = cjkByLocale.getValue(tag)
            appendLine("  中文 @$tag -> ${f.fileNames.joinToString().ifEmpty { "?" }} ids=${f.glyphIds.joinToString()}")
        }
        val latin = observe("English", Locale.SIMPLIFIED_CHINESE, Typeface.DEFAULT)
        appendLine("  English -> ${latin.fileNames.joinToString().ifEmpty { "?" }}")
        appendLine()

        appendLine("[F2] 同一文本跨 locale 是否换 face / 换字形（决定 face 身份够不够）")
        val sensitive = LOCALES.associateWith { observe("骨直海角刃真", Locale.forLanguageTag(it), Typeface.DEFAULT) }
        val filesVary = sensitive.values.map { it.fileNames }.distinct().size > 1
        val idsVary = sensitive.values.map { it.glyphIds }.distinct().size > 1
        appendLine("  文件随 locale 变化 : $filesVary")
        appendLine("  字形id随locale变化 : $idsVary" +
            if (idsVary && !filesVary) "  <== 同一文件内靠 locl 换字形：只认 face 身份不足以定字形" else "")
        LOCALES.forEach { tag ->
            val f = sensitive.getValue(tag)
            appendLine("  骨直海角刃真 @$tag -> ${f.fileNames.joinToString()} ids=${f.glyphIds.joinToString()}")
        }
        appendLine()

        appendLine("[F3] Typeface.DEFAULT 是否等同 sans-serif（不等同 = 存在主题/默认字体替换）")
        val sans = typefaceOf("sans-serif")
        val defCjk = observe("中文", Locale.SIMPLIFIED_CHINESE, Typeface.DEFAULT)
        val sansCjk = observe("中文", Locale.SIMPLIFIED_CHINESE, sans)
        val defLatin = observe("English", Locale.SIMPLIFIED_CHINESE, Typeface.DEFAULT)
        val sansLatin = observe("English", Locale.SIMPLIFIED_CHINESE, sans)
        appendLine("  中文    : DEFAULT=${defCjk.fileNames} sans-serif=${sansCjk.fileNames} 相同=${defCjk.fileNames == sansCjk.fileNames}")
        appendLine("  English : DEFAULT=${defLatin.fileNames} sans-serif=${sansLatin.fileNames} 相同=${defLatin.fileNames == sansLatin.fileNames}")
        appendLine()

        appendLine("[F4] 具名家族：哪些解析出与 sans-serif 不同的 face")
        val sansRef = sansCjk.fileNames to sansLatin.fileNames
        (PLATFORM_FAMILIES + OEM_FAMILIES).forEach { name ->
            val tf = typefaceOf(name) ?: return@forEach
            val cjk = observe("中文", Locale.SIMPLIFIED_CHINESE, tf).fileNames
            val lat = observe("English", Locale.SIMPLIFIED_CHINESE, tf).fileNames
            val distinct = (cjk to lat) != sansRef
            val oem = name in OEM_FAMILIES
            if (distinct || oem) {
                appendLine("  ${if (oem) "[OEM] " else "      "}$name -> 中文=${cjk.joinToString()} 拉丁=${lat.joinToString()} " +
                    "独立=${distinct}")
            }
        }
        appendLine()

        appendLine("[F5] 字重：真文件/可变轴 还是 合成")
        WEIGHTS.forEach { w ->
            val tf = weightedSans(w) ?: return@forEach
            val cjk = observe("中文", Locale.SIMPLIFIED_CHINESE, tf)
            val lat = observe("English", Locale.SIMPLIFIED_CHINESE, tf)
            appendLine("  w=$w 中文 ${cjk.fileNames.joinToString()} adv=${cjk.advance} | " +
                "拉丁 ${lat.fileNames.joinToString()} adv=${lat.advance}")
        }
        val cjk400 = observe("中文", Locale.SIMPLIFIED_CHINESE, weightedSans(400))
        val cjk700 = observe("中文", Locale.SIMPLIFIED_CHINESE, weightedSans(700))
        appendLine("  中文 400 vs 700：文件变化=${cjk400.fileNames != cjk700.fileNames} " +
            "宽度变化=${cjk400.advance != cjk700.advance} 字形变化=${cjk400.glyphIds != cjk700.glyphIds}")
        appendLine()

        appendLine("[F6] 可读的字体配置文件（决定能否恢复 fallback 次序）")
        val readable = FONT_CONFIG_PATHS.filter { File(it).canRead() }
        appendLine("  可读: ${readable.ifEmpty { listOf("(无)") }.joinToString()}")
        appendLine("  存在但不可读: ${FONT_CONFIG_PATHS.filter { File(it).exists() && !File(it).canRead() }.ifEmpty { listOf("(无)") }.joinToString()}")
        appendLine()

        appendLine("[F7] 字体池")
        val fonts = systemFontsOrEmpty()
        appendLine("  SystemFonts 数量: ${if (Build.VERSION.SDK_INT >= 29) fonts.size.toString() else "不可用"}")
        val oemFonts = fonts.mapNotNull { it.file?.absolutePath }
            .filter { path -> AOSP_PREFIXES.none { path.substringAfterLast('/').startsWith(it) } }
            .distinct().sorted()
        appendLine("  非 AOSP 命名的字体（疑似 OEM 附加）: ${oemFonts.ifEmpty { listOf("(无)") }.joinToString()}")
        val zhFonts = fonts.filter {
            runCatching { it.localeList.toLanguageTags() }.getOrDefault("").contains("zh", ignoreCase = true)
        }.mapNotNull { it.file?.absolutePath }.distinct().sorted()
        appendLine("  localeList 自称含 zh 的字体: ${zhFonts.ifEmpty { listOf("(无)") }.joinToString()}")
        val unreadable = fonts.mapNotNull { it.file }.filter { !it.canRead() }.map { it.absolutePath }.distinct().sorted()
        appendLine("  枚举到但字节不可读: ${unreadable.ifEmpty { listOf("(无)") }.joinToString()}")
        appendLine()

        appendLine("[F8] 覆盖缺口（Paint.hasGlyph，DEFAULT 字体链）")
        val paint = Paint().apply { textSize = 32f; typeface = Typeface.DEFAULT }
        TEXT_PROBES.forEach { (text, label) ->
            val missing = text.codePointStrings().filter { unit ->
                !runCatching { paint.hasGlyph(unit) }.getOrDefault(false)
            }
            if (missing.isNotEmpty()) {
                appendLine("  缺字: '$text' [$label] -> ${missing.joinToString { u -> "U+" + u.codePointAt(0).toString(16).uppercase() }}")
            }
        }
        appendLine("  （未列出的探针，其全部码点均可渲染）")
    }

    // ---- 第 1 节 --------------------------------------------------------------------

    private fun StringBuilder.deviceSection() {
        appendLine("manufacturer  : ${Build.MANUFACTURER}")
        appendLine("brand         : ${Build.BRAND}")
        appendLine("model         : ${Build.MODEL}")
        appendLine("device        : ${Build.DEVICE}")
        appendLine("product       : ${Build.PRODUCT}")
        appendLine("hardware      : ${Build.HARDWARE}")
        appendLine("sdk_int       : ${Build.VERSION.SDK_INT}")
        appendLine("release       : ${Build.VERSION.RELEASE}")
        appendLine("incremental   : ${Build.VERSION.INCREMENTAL}")
        appendLine("security_patch: ${Build.VERSION.SECURITY_PATCH}")
        appendLine("fingerprint   : ${Build.FINGERPRINT}")
        appendLine("default_locale: ${Locale.getDefault().toLanguageTag()}")
        appendLine("supported_abis: ${Build.SUPPORTED_ABIS.joinToString()}")
    }

    // ---- 第 2-8 节：原始证据 ----------------------------------------------------------

    private fun StringBuilder.renderMatrixSection() {
        if (Build.VERSION.SDK_INT < 31) {
            appendLine("(逐 glyph 字体读回需 API 31+；本机 SDK ${Build.VERSION.SDK_INT}，仅记录宽度。")
            appendLine(" 该版本的覆盖信号见第 5 节，字体清单见第 7/8 节。)")
        }
        appendLine("Typeface.DEFAULT，字号 32px。")
        appendLine()
        for (tag in LOCALES) {
            appendLine("### locale = $tag")
            val locale = Locale.forLanguageTag(tag)
            for ((text, label) in TEXT_PROBES) {
                appendLine("  '$text' [$label]")
                appendLine("    ${observe(text, locale, Typeface.DEFAULT).oneLine()}")
            }
            appendLine()
        }
    }

    private fun StringBuilder.familySection() {
        appendLine("Typeface.create(name, NORMAL) 后渲染中文与拉丁。")
        appendLine()
        for (name in PLATFORM_FAMILIES + OEM_FAMILIES) {
            appendLine("### \"$name\"${if (name in OEM_FAMILIES) "  [OEM 候选名]" else ""}")
            val tf = typefaceOf(name)
            if (tf == null) {
                appendLine("  Typeface.create 失败")
                continue
            }
            appendLine("  中文    : ${observe("中文", Locale.SIMPLIFIED_CHINESE, tf).oneLine()}")
            appendLine("  English : ${observe("English", Locale.SIMPLIFIED_CHINESE, tf).oneLine()}")
        }
    }

    private fun StringBuilder.weightSection() {
        for (w in WEIGHTS) {
            appendLine("### weight=$w")
            val tf = weightedSans(w)
            if (tf == null) {
                appendLine("  构造失败")
                continue
            }
            appendLine("  中文    : ${observe("中文", Locale.SIMPLIFIED_CHINESE, tf).oneLine()}")
            appendLine("  English : ${observe("English", Locale.SIMPLIFIED_CHINESE, tf).oneLine()}")
        }
        appendLine()
        appendLine("### italic (Typeface.ITALIC)")
        val italic = runCatching { Typeface.create("sans-serif", Typeface.ITALIC) }.getOrNull()
        appendLine("  中文    : ${observe("中文", Locale.SIMPLIFIED_CHINESE, italic).oneLine()}")
        appendLine("  English : ${observe("English", Locale.SIMPLIFIED_CHINESE, italic).oneLine()}")
    }

    private fun StringBuilder.hasGlyphSection() {
        appendLine("hasGlyph 自 API 23 起可用，是低版本上唯一能直接观测的覆盖信号。")
        appendLine("注意其语义：多字符串只有在合成单个字形时才为 true，故 whole=false 不代表缺字。")
        appendLine()
        val typefaces = listOf(
            "DEFAULT" to Typeface.DEFAULT,
            "sans-serif" to typefaceOf("sans-serif"),
            "serif" to Typeface.SERIF,
            "monospace" to Typeface.MONOSPACE,
        )
        for ((name, tf) in typefaces) {
            appendLine("### typeface = $name")
            val paint = Paint().apply { textSize = 32f; typeface = tf }
            for ((text, label) in TEXT_PROBES) {
                val per = text.codePointStrings().joinToString(" ") { unit ->
                    val hex = unit.codePointAt(0).toString(16).uppercase()
                    "U+$hex=${runCatching { paint.hasGlyph(unit) }.getOrElse { "?" }}"
                }
                appendLine("  '$text' [$label] whole=${runCatching { paint.hasGlyph(text) }.getOrElse { "?" }}  $per")
            }
            appendLine()
        }
    }

    private fun StringBuilder.fontConfigSection() {
        for (path in FONT_CONFIG_PATHS) {
            val file = File(path)
            appendLine("$path : " + when {
                !file.exists() -> "absent"
                !file.canRead() -> "EXISTS but NOT readable"
                else -> "readable ${file.length()}B"
            })
        }
        appendLine()
        for (path in FONT_CONFIG_PATHS) {
            val file = File(path)
            if (!file.canRead()) continue
            appendLine("### 全文：$path")
            appendLine("```")
            runCatching { appendLine(file.readText().trimEnd()) }
                .onFailure { appendLine("(读取失败：${it.message})") }
            appendLine("```")
            appendLine()
        }
    }

    private fun StringBuilder.systemFontsSection() {
        if (Build.VERSION.SDK_INT < 29) {
            appendLine("(需 API 29+；本机 SDK ${Build.VERSION.SDK_INT}。字体清单见第 8 节。)")
            return
        }
        val fonts = systemFontsOrEmpty()
        appendLine("count: ${fonts.size}（无序集合，不含具名家族归属与 fallback 次序）")
        appendLine()
        // 全序：TTC 的多个 index 共用同一路径，只按路径排会在跨次运行间抖动，
        // 制造与设备无关的假 diff。
        fonts.sortedWith(
            compareBy(
                { it.file?.absolutePath ?: "~" },
                { it.ttcIndex },
                { it.style.weight },
                { it.style.slant },
                { runCatching { it.localeList.toLanguageTags() }.getOrDefault("") },
                { it.axes?.joinToString(",") { axis -> "${axis.tag}=${axis.styleValue}" } ?: "" },
            ),
        ).forEach { font ->
            val file = font.file
            appendLine(file?.absolutePath ?: "(no file)")
            appendLine("    ttcIndex=${font.ttcIndex} weight=${font.style.weight} slant=${font.style.slant}" +
                if (file != null) " readable=${file.canRead()} size=${file.length()}" else "")
            appendLine("    locales=[${runCatching { font.localeList.toLanguageTags() }.getOrDefault("?")}]")
            font.axes?.takeIf { it.isNotEmpty() }?.let { axes ->
                appendLine("    axes=${axes.joinToString(",") { "${it.tag}=${it.styleValue}" }}")
            }
        }
    }

    private fun StringBuilder.fontDirSection() {
        for (path in FONT_DIRS) {
            appendLine("### $path")
            val dir = File(path)
            when {
                !dir.exists() -> appendLine("  absent")
                !dir.isDirectory -> appendLine("  exists but not a directory")
                else -> {
                    val files = dir.listFiles()
                    if (files == null) {
                        appendLine("  存在但无法列出（listFiles 返回 null，通常是权限）")
                    } else {
                        appendLine("  count=${files.size}")
                        files.sortedBy { it.name }.forEach {
                            appendLine("  ${it.name}  ${it.length()}B readable=${it.canRead()}")
                        }
                    }
                }
            }
            appendLine()
        }
    }
}
