# Android 平台字体行为报告

采集器：独立模块 `demo/font-diagnostics`，构建产物是一个 2.3MB 的 APK。装上打开即采集，
点「分享报告」以文件形式发出。

```shell
./gradlew :demo:font-diagnostics:assembleDebug
```

它刻意不依赖 `:demo` 或任何引擎模块，UI 也用纯 View 而非 Compose：报告观测的是平台行为，
引擎一行都用不上；带上引擎会把四套 ABI 的 native 库打进来（实测 154MB），而这个 APK 是要
发给外部设备的人装的。

报告**只观测 Android 平台自身行为**，不评估提椠的实现——实现要照着这些行为写，因此报告里
不掺入对某个实现的判断。全程只读：不安装字体、不改设置、不联网，仅向应用私有 cache 写一份
报告文件供分享。

## 怎么读

第 0 节是设备上算好的结论（F1–F8），先读它，约 60 行就能回答：正文 face 是谁、跨 locale
是否换字形、`Typeface.DEFAULT` 有没有被主题替换、哪些具名家族解析出独立 face、字重是真的
还是合成、哪些配置文件可读、字体池里有哪些非 AOSP 字体、以及覆盖缺口。

第 1 节是设备身份，diff 时整节忽略。第 2–8 节是原始证据，结论意外时才往下翻。

报告输出是确定性的（不含 hashcode、时间戳或任何逐次变化的值，顺序固定），所以 OEM 设备的
报告可以直接和本目录的 AOSP 基线做 diff，差异行就是结论：

```shell
diff docs/research/android-font-reports/2026-08-05-aosp-api37-emulator.txt 新报告.txt
```

## 基线

| 文件 | 设备 | API |
| --- | --- | --- |
| `2026-08-05-aosp-api23-emulator.txt` | AOSP 模拟器 arm64 | 23 |
| `2026-08-05-aosp-api37-emulator.txt` | AOSP 模拟器 `sdk_gphone16k_arm64` | 37 |

两份都是 AOSP，**不含任何 OEM 定制**。它们的作用是给 OEM 报告当参照，本身不能用来推断
真实设备上的字体行为。

## 已经从基线读出的事实

- 同一 `NotoSansCJK-Regular.ttc` 内，`骨` 的 glyph id 随 locale 变化（简 45133 / 繁 45134 /
  港 45132 / 日 45132 / 韩 45132）。**face 身份不足以确定字形，locale 必须进入 shaping。**
- API 37 上中文 400 与 700 的文件、宽度、字形全部相同——该路径没有真中文粗体。
- API 37 上 `/system/etc/font_fallback.xml` 存在但普通应用读不到；`fonts.xml` 可读。
  API 23 上 `fonts.xml`、`system_fonts.xml`、`fallback_fonts.xml` 三份都可读。
- API 37 全机 207 个字体中，`localeList` 自称含 `zh` 的只有 2 个
  （`NotoSansCJK-Regular.ttc`、`NotoSerifCJK-Regular.ttc`）。

## 待采集

OEM 设备（尤其自带中文字体的国产机）。关注 F4 里带 `<== 中文与拉丁落到不同文件` 标记的行：
那就是「该家族只接管西文、中文仍回落 Noto」的直接证据。

F4 探测的家族名从设备的字体配置里现取（`<family name="...">`），不写死任何「常见 OEM 名」
去撞——`Typeface.create()` 认不出名字时会静默回落默认字体，猜错的名字看起来和「这台机器
没有该字体」完全一样，假阴性正好落在最关键的一格。AOSP 基线上这套现取机制自动发现了
`roboto-flex` 与 `source-sans-pro` 两个家族，都是硬编清单不会包含的。
