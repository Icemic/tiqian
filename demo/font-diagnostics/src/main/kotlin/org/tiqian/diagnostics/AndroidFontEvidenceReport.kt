package org.tiqian.diagnostics

import android.os.Build
import java.util.Locale
import org.tiqian.diagnostics.AndroidFontEvidenceCollector.ConfigArtifact
import org.tiqian.diagnostics.AndroidFontEvidenceCollector.Observation

internal fun AndroidFontEvidenceCollector.manifestJson(
    capturedAt: String,
    observations: List<Observation>,
    entries: Map<String, ByteArray>,
): String {
    val counts = observations.groupingBy(Observation::status).eachCount()
    val evidenceNodeCounts = evidenceStatusCounts(observations)
    val value = linkedMapOf<String, Any?>(
        "schema" to "org.tiqian.android-font-evidence",
        "schemaVersion" to SCHEMA_VERSION,
        "capturedAtUtc" to capturedAt,
        "collector" to linkedMapOf(
            "applicationId" to BuildConfig.APPLICATION_ID,
            "versionName" to BuildConfig.VERSION_NAME,
            "versionCode" to BuildConfig.VERSION_CODE,
            "buildType" to BuildConfig.BUILD_TYPE,
            "gitRevision" to BuildConfig.COLLECTOR_GIT_REVISION,
            "gitDirty" to BuildConfig.COLLECTOR_GIT_DIRTY,
        ),
        "device" to deviceJson(),
        "capabilities" to capabilitiesJson(),
        "observationCounts" to linkedMapOf(
            EvidenceStatus.Observed.wireValue to (counts[EvidenceStatus.Observed] ?: 0),
            EvidenceStatus.Unsupported.wireValue to (counts[EvidenceStatus.Unsupported] ?: 0),
            EvidenceStatus.Error.wireValue to (counts[EvidenceStatus.Error] ?: 0),
        ),
        "evidenceNodeCounts" to linkedMapOf(
            EvidenceStatus.Observed.wireValue to (evidenceNodeCounts[EvidenceStatus.Observed] ?: 0),
            EvidenceStatus.Unsupported.wireValue to (evidenceNodeCounts[EvidenceStatus.Unsupported] ?: 0),
            EvidenceStatus.Error.wireValue to (evidenceNodeCounts[EvidenceStatus.Error] ?: 0),
        ),
        "entries" to entries.map { (name, bytes) ->
            linkedMapOf(
                "name" to name,
                "sizeBytes" to bytes.size,
                "sha256" to sha256(bytes),
            )
        },
    )
    return EvidenceJson.encode(value) + "\n"
}

internal fun AndroidFontEvidenceCollector.deviceJson(): Map<String, Any?> = linkedMapOf(
    "manufacturer" to Build.MANUFACTURER,
    "brand" to Build.BRAND,
    "model" to Build.MODEL,
    "device" to Build.DEVICE,
    "product" to Build.PRODUCT,
    "hardware" to Build.HARDWARE,
    "sdkInt" to Build.VERSION.SDK_INT,
    "release" to Build.VERSION.RELEASE,
    "incremental" to Build.VERSION.INCREMENTAL,
    "securityPatch" to Build.VERSION.SECURITY_PATCH,
    "fingerprint" to Build.FINGERPRINT,
    "defaultLocale" to Locale.getDefault().toLanguageTag(),
    "supportedAbis" to Build.SUPPORTED_ABIS.toList(),
)

internal fun AndroidFontEvidenceCollector.capabilitiesJson(): List<Map<String, Any?>> = listOf(
    capability("paint-run-metrics", 23),
    capability("paint-has-glyph", 23),
    capability("paint-variation-request", 26),
    capability("exact-typeface-weight-request", 28),
    capability("system-fonts-enumeration", 29),
    capability("per-glyph-font-and-position", 31),
    capability("fake-style-and-override-readback", 35),
)

internal fun AndroidFontEvidenceCollector.capability(id: String, minimumApi: Int): Map<String, Any?> = linkedMapOf(
    "id" to id,
    "status" to if (Build.VERSION.SDK_INT >= minimumApi) {
        EvidenceStatus.Observed.wireValue
    } else {
        EvidenceStatus.Unsupported.wireValue
    },
    "minimumApi" to minimumApi,
)

internal fun AndroidFontEvidenceCollector.summaryMarkdown(
    capturedAt: String,
    observations: List<Observation>,
    configArtifacts: List<ConfigArtifact>,
    renderCount: Int,
): String {
    val counts = observations.groupingBy(Observation::status).eachCount()
    val evidenceNodeCounts = evidenceStatusCounts(observations)
    val readableConfigs = configArtifacts.count { artifact -> artifact.bytes != null }
    val parseErrors = configArtifacts.count { artifact -> artifact.parseError != null }
    return buildString {
        appendLine("# Android 字体行为证据包")
        appendLine()
        appendLine("这是一份平台观测，不包含提椠实现的选择，也不在设备端推导 OEM 结论。")
        appendLine("`unsupported` 表示该设备/API 无法观测，不能解释成 false、相同或没有变化。")
        appendLine()
        appendLine("- schema：`org.tiqian.android-font-evidence` v$SCHEMA_VERSION")
        appendLine("- 采集时间（UTC）：$capturedAt")
        appendLine("- 设备：${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("- Android：${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
        appendLine("- build fingerprint：`${Build.FINGERPRINT}`")
        appendLine("- 请求：observed=${counts[EvidenceStatus.Observed] ?: 0}，" +
            "unsupported=${counts[EvidenceStatus.Unsupported] ?: 0}，" +
            "error=${counts[EvidenceStatus.Error] ?: 0}")
        appendLine("- 所有状态节点：observed=${evidenceNodeCounts[EvidenceStatus.Observed] ?: 0}，" +
            "unsupported=${evidenceNodeCounts[EvidenceStatus.Unsupported] ?: 0}，" +
            "error=${evidenceNodeCounts[EvidenceStatus.Error] ?: 0}")
        appendLine("- 实际 PNG：$renderCount")
        appendLine("- 可读字体配置：$readableConfigs/${configArtifacts.size}；解析错误：$parseErrors")
        appendLine()
        appendLine("## 本机能力")
        appendLine()
        capabilitiesJson().forEach { capability ->
            appendLine("- `${capability["id"]}`：${capability["status"]}（API ${capability["minimumApi"]}+）")
        }
        appendLine()
        appendLine("## 文件")
        appendLine()
        appendLine("- `manifest.json`：设备、采集器版本、能力与所有条目的 SHA-256。")
        appendLine("- `observations.jsonl`：逐请求的平台测量、逐 glyph 字体/位置和栅格摘要。")
        appendLine("- `font-config.json`：按来源保留次序的字体配置声明；不是运行时 fallback 真值。")
        appendLine("- `system-fonts.json`：公开 `SystemFonts` 枚举；不含家族归属和 fallback 次序。")
        appendLine("- `font-directories.json`：应用沙箱可见的字体目录元数据。")
        appendLine("- `renders/`：每条成功 shape 请求的实际软件 Bitmap PNG，可与 raster hash 对照。")
        appendLine("- `raw/font-config/`：可读配置文件原文。")
        appendLine()
        appendLine("结论应在电脑上按 schema 做语义比较，并把平台 shaping 读回当作主证据；" +
            "不要直接 diff 整个 ZIP 或原始 XML。")
    }
}

internal fun AndroidFontEvidenceCollector.evidenceStatusCounts(observations: List<Observation>): Map<EvidenceStatus, Int> {
    val counts = mutableMapOf<EvidenceStatus, Int>()
    fun visit(value: Any?) {
        when (value) {
            is Map<*, *> -> {
                val statusValue = value["status"] as? String
                EvidenceStatus.entries.firstOrNull { status -> status.wireValue == statusValue }?.let { status ->
                    counts[status] = (counts[status] ?: 0) + 1
                }
                value.values.forEach(::visit)
            }
            is Iterable<*> -> value.forEach(::visit)
            is Array<*> -> value.forEach(::visit)
        }
    }
    observations.forEach { observation -> visit(observation.value) }
    return counts
}
