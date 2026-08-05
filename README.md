# 提椠 Tíqiàn

[![npm version](https://img.shields.io/npm/v/%40tiqian%2Fprose?label=npm)](https://www.npmjs.com/package/@tiqian/prose)
[![Telegram Link](https://img.shields.io/badge/Telegram-@tiqian__cjk-blue?logo=telegram&logoColor=fff)](https://t.me/tiqian_cjk)

提椠是一个中日韩段落书写器。

它在平台字体能力和受控字体后端之上，统一处理中文正文里的字体选择、
断行、避头尾、标点空间、两端对齐、行内空间分配与行间注。

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/sample-paragraph-white.svg">
  <img src="docs/images/sample-paragraph-black.svg" alt="提椠简体中文横排样张，包含拼音行间注与着重号">
</picture>

## 当前状态

提椠仍处于早期开发阶段，尚未发布稳定版本，公共 API 和模块结构可能继续调整。

- [x] 简体中文横排
- [ ] 繁体中文横排
  - [x] 注音
- [ ] 简 / 繁直排
- [ ] 日文排版（JLREQ）
- [ ] 韩文排版（KLREQ）

目前可以通过 Compose 和 Web 两种前端使用提椠。Android View 模块只保留了接入接口，
还不是可直接使用的完整前端。

## Compose

`frontend/compose` 支持 Compose Desktop 和 Android 23 及以上版本。普通文本可以直接把
Compose 的 `Text` 换成 `CjkText`，已有的 `AnnotatedString` 和 `TextStyle` 也可以继续使用。

```kotlin
val paragraph = buildAnnotatedString {
    append("编号 A-17 的青铜")
    ruby("盉", "hé")
    append("仍")
    emphasis { append("一并保留") }
    append("。")
}

val scrollState = rememberScrollState()
CjkSelectionContainer(scrollState = scrollState) {
    Column(Modifier.verticalScroll(scrollState)) {
        CjkText(
            text = paragraph,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}
```

Android 默认正确性路径由同一份字体字节驱动 HarfBuzz shaping、FreeType metrics / ink / outline
和最终 glyph replay。API 29+ 可以从公开 `SystemFonts` 建立 catalog；API 23–28 无公开系统字体枚举，
生产宿主应在第一次 `CjkText` 前安装 asset、文件或 `ByteArray` 字体与明确的 fallback 角色，例如：

```kotlin
TiqianAndroidFontBackend.install(
    context,
    AndroidFontCatalog.host(
        listOf(
            AndroidFontFaceSpec(
                source = AndroidFontSource.asset("fonts/NotoSansSC-Regular.otf"),
                familyAliases = setOf("Noto Sans SC", "sans-serif"),
                roles = setOf(
                    FontRole.CjkText,
                    FontRole.CjkPunctuation,
                    FontRole.LatinText,
                    FontRole.Symbol,
                    FontRole.Unknown,
                ),
            ),
        ),
    ),
)
```

未安装宿主 catalog 时，API 23–28 只会尝试具名的 AOSP 字体路径并在 capability report 中报告
`HostFontCatalogRecommendedBelowApi29`；该 report 用于诊断，不能把正文路由回 Compose `Text`。

`CjkText` 会保留源码换行，并支持常用富文本样式、行间注与链接。接入现有富文本渲染器时，
可以用 `cjkTextCompatibility()` 检查当前还不能保真的能力。只读正文需要选择与复制时，用
`CjkSelectionContainer` 包住一个或多个 `CjkText`；鼠标拖选/双击、触摸长按与手柄、复制菜单和
跨 `CjkText` 复制都直接使用提椠的最终布局几何，不会再放一层隐藏的 Compose `Text`。手柄、
鼠标/触摸手势与 Android 文本放大镜复用当前 Compose Foundation 的平台实现，因此外观和交互
跟随宿主 Compose 版本，而不是由提椠另画一套控件。
滚动正文要把同一个 `ScrollState` 同时交给 `CjkSelectionContainer` 和 `verticalScroll`：真实拖选进入
视口边缘后会继续滚动并扩展选区，单纯长按不动或小于 touch slop 的手指抖动不会自行往前选。
当前自动滚动契约面向连续 composition 的 `ScrollState`；虚拟化列表需要单独的 lazy selection 协议。

## Web

`@tiqian/prose` 渐进增强服务器已经输出的正文 HTML。没有 JavaScript、加载失败或遇到暂不支持的
内容时，原文仍由浏览器排版；网站原有的字体、颜色、链接、选择与复制语义继续生效。

静态博客和 SSR 网站可以把现有正文放进 `<tiqian-prose>`，再导入自定义元素入口：

```html
<tiqian-prose class="prose">
  <!-- Markdown 或 SSR 生成的正文 -->
</tiqian-prose>

<style>
  tiqian-prose { display: block; }
</style>

<script type="module">
  import "@tiqian/prose/element";
</script>
```

安装、命令式 API、构建期预排与运行环境见
[`@tiqian/prose` 使用文档](frontend/web/npm/README.md)。

## 体验与构建

项目使用 Gradle Wrapper，并会按需准备 JDK 25 toolchain；JVM 库产物以 Java 17 为目标：

```shell
./gradlew build
./gradlew runComposeDemo
```

## 文档

- [`@tiqian/prose` 使用文档](frontend/web/npm/README.md) 说明 Web 安装、接入方式与构建期预排。
- [Roadmap](docs/roadmap.md) 记录当前进度、已经完成的切片与下一步工作。
- [当前架构](docs/architecture.md) 说明 pipeline、模块边界与平台接入方式。
- [ADR 索引](docs/adr/README.md) 记录已经确定的架构和排版取舍。
- [贡献指南](docs/contributing.md) 说明开发环境、实现约定、验证方式与提交格式。

## 参考资料

- W3C[《中文排版需求》](https://www.w3.org/TR/clreq/)
- The Type[《孔雀计划：中文字体排印的思路》](https://www.thetype.com/kongque/)
- 教育部[《重訂標點符號手冊》（2008 年修訂版）](https://language.moe.gov.tw/001/Upload/FILES/SITE_CONTENT/M0001/HAU/c2.htm)
- 教育部[《國語注音符號手冊》](https://language.moe.gov.tw/001/Upload/files/site_content/M0001/juyin/html_ch/index.html)
- CY/T 154-2017[《中文出版物夹用英文的编辑规范》](https://std.samr.gov.cn/hb/search/stdHBDetailed?id=8B1827F23645BB19E05397BE0A0AB44A)

## 许可证

提椠以 [Mozilla Public License 2.0](LICENSE) 发布。
