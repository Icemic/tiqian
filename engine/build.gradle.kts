import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    // Haxe port lane: coverage baseline for the pre-refactor engine.
    id("org.jetbrains.kotlinx.kover") version "0.9.9"
}

// Web and Kotlin/Native have no synchronous resource loading, so the bundled en-US
// TeX patterns are embedded as a generated Kotlin constant, built from the SAME .tex
// the JVM/Android resource path reads (single source of truth). ADR 0039.
val generateEmbeddedHyphenationPatterns = tasks.register("generateEmbeddedHyphenationPatterns") {
    val patternFile = layout.projectDirectory.file("src/commonMain/resources/hyphenation/hyph-en-us.tex")
    val outputDir = layout.buildDirectory.dir("generated/hyphenation-embedded/kotlin")
    inputs.file(patternFile)
    outputs.dir(outputDir)
    doLast {
        val tex = patternFile.asFile.readText()
        // The raw-string embedding is only safe if the .tex has no `$` (Kotlin template)
        // or `"""` (raw-string terminator). The vendored file has neither; fail loudly if a
        // future update introduces them rather than silently corrupting the patterns.
        require(!tex.contains("\"\"\"") && !tex.contains('$')) {
            "hyph-en-us.tex contains a \$ or triple-quote — the raw-string embedding needs escaping"
        }
        val file = outputDir.get().file("org/tiqian/linebreak/EnUsHyphenationPatterns.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            "package org.tiqian.linebreak\n\n" +
                "// GENERATED from src/commonMain/resources/hyphenation/hyph-en-us.tex — do not edit.\n" +
                "internal val EN_US_HYPHENATION_PATTERNS: String = \"\"\"\n" +
                tex +
                "\"\"\"\n",
        )
    }
}

// The layout-dump goldens are language-neutral text files; the JVM golden test
// owns and regenerates them, and commonTest replays the same corpus on every
// target. They are embedded as generated chunked constants (same mechanism as
// the hyphenation patterns) because only the JVM can read files at test time.
val generateLayoutDumpGoldens = tasks.register("generateLayoutDumpGoldens") {
    val goldenDir = layout.projectDirectory.dir("src/jvmTest/resources/golden/layout-dumps")
    val recordedGoldenDir = layout.projectDirectory.dir("src/jvmTest/resources/golden/layout-dumps-recorded")
    val evidenceFile = layout.projectDirectory.file("src/jvmTest/resources/golden/shaping-evidence.json")
    val outputDir = layout.buildDirectory.dir("generated/layout-dump-goldens/kotlin")
    inputs.dir(goldenDir)
    inputs.files(fileTree(recordedGoldenDir))
    inputs.files(evidenceFile)
    outputs.dir(outputDir)
    doLast {
        // Chunked so no literal exceeds the JVM constant-pool string limit
        // (65535 modified-UTF-8 bytes); never cuts between surrogate halves.
        fun escapedChunks(text: String): List<String> {
            val chunks = mutableListOf<String>()
            val current = StringBuilder()
            var bytes = 0
            for (ch in text) {
                val escaped = when (ch) {
                    '\\' -> "\\\\"
                    '"' -> "\\\""
                    '$' -> "\\$"
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    else -> ch.toString()
                }
                current.append(escaped)
                bytes += escaped.sumOf { c -> if (c.code <= 0x7F) 1 else 3L }.toInt()
                if (bytes >= 40000 && !ch.isHighSurrogate()) {
                    chunks += current.toString()
                    current.setLength(0)
                    bytes = 0
                }
            }
            if (current.isNotEmpty() || chunks.isEmpty()) chunks += current.toString()
            return chunks
        }
        fun goldenMapSource(objectName: String, sourceDir: File): String = buildString {
            val files = sourceDir.listFiles { f -> f.extension == "txt" }?.sortedBy { it.name }.orEmpty()
            appendLine("package org.tiqian.layout")
            appendLine()
            appendLine("// GENERATED from src/jvmTest/resources/golden — do not edit.")
            appendLine("internal object $objectName {")
            appendLine("    val byId: Map<String, String> = buildMap {")
            for (file in files) {
                appendLine("        put(")
                appendLine("            \"${file.nameWithoutExtension}\",")
                appendLine("            listOf(")
                for (chunk in escapedChunks(file.readText())) {
                    appendLine("                \"$chunk\",")
                }
                appendLine("            ).joinToString(\"\"),")
                appendLine("        )")
            }
            appendLine("    }")
            appendLine("}")
        }

        val packageDir = outputDir.get().file("org/tiqian/layout").asFile
        packageDir.mkdirs()
        File(packageDir, "LayoutDumpGoldenData.kt")
            .writeText(goldenMapSource("LayoutDumpGoldens", goldenDir.asFile))
        File(packageDir, "RecordedLayoutDumpGoldenData.kt")
            .writeText(goldenMapSource("RecordedLayoutDumpGoldens", recordedGoldenDir.asFile))
        File(packageDir, "RecordedShapingEvidenceData.kt").writeText(
            buildString {
                appendLine("package org.tiqian.layout")
                appendLine()
                appendLine("// GENERATED from src/jvmTest/resources/golden/shaping-evidence.json — do not edit.")
                appendLine("internal object RecordedShapingEvidenceData {")
                val evidence = evidenceFile.asFile
                if (evidence.isFile) {
                    appendLine("    val EVIDENCE_JSON: String = listOf(")
                    for (chunk in escapedChunks(evidence.readText())) {
                        appendLine("        \"$chunk\",")
                    }
                    appendLine("    ).joinToString(\"\")")
                } else {
                    appendLine("    val EVIDENCE_JSON: String = \"\"")
                }
                appendLine("}")
            },
        )
    }
}

// commonTest is shared with the Android host-test compilation. AGP's lint model tasks do not
// infer the generated-source task dependency from Kotlin's source-set provider, so declare it.
tasks.matching {
    it.name == "generateAndroidHostTestLintModel" || it.name == "lintAnalyzeAndroidHostTest"
}.configureEach {
    dependsOn(generateLayoutDumpGoldens)
}

kotlin {
    jvm()
    android {
        namespace = "org.tiqian.engine"
        compileSdk = 36
        minSdk = 23
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
        withHostTest {}
    }
    js {
        browser()
        useEsModules()
    }
    macosArm64()
    iosArm64()
    iosSimulatorArm64()
    linuxX64()
    linuxArm64()
    mingwX64()

    // Font backend vtable protocol (ADR 0050): the same C header feeds
    // cinterop here and the Rust binding, so both sides share one layout.
    targets.withType<KotlinNativeTarget>().configureEach {
        compilations.getByName("main").cinterops.create("tiqianFontBackend") {
            defFile(project.file("src/nativeInterop/cinterop/tiqianFontBackend.def"))
            includeDirs(project.file("src/nativeInterop/cinterop"))
        }
    }

    sourceSets {
        commonTest {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
            }
            kotlin.srcDir(generateLayoutDumpGoldens)
        }

        jsMain {
            kotlin.srcDir(generateEmbeddedHyphenationPatterns)
        }

        // nativeMain is an intermediate source set from the default hierarchy template; it is
        // realized lazily, so configure it via matching{} rather than an eager named() lookup.
        matching { it.name == "nativeMain" }.configureEach {
            kotlin.srcDir(generateEmbeddedHyphenationPatterns)
        }

        jvmTest.dependencies {
            implementation(project(":platforms:jvm:shaping"))
            implementation(project(":platforms:jvm:skia"))
            runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-macos-arm64:0.144.6")
        }
    }
}

val jvmTestCompilation = kotlin.targets.getByName("jvm").compilations.getByName("test")

val fixtureId = providers.gradleProperty("fixtureId")

tasks.register<JavaExec>("exportLayoutFixture") {
    group = "verification"
    description = "Writes one EarlyLayoutFixture's effective layout input as JSON to standard output."
    dependsOn("jvmTestClasses")
    mainClass.set("org.tiqian.layout.tooling.FixtureJsonMainKt")
    classpath = files(jvmTestCompilation.output.allOutputs) +
        configurations.named("jvmTestRuntimeClasspath").get()
    doFirst {
        check(fixtureId.isPresent) {
            "Pass one fixture id with -PfixtureId=<id>."
        }
        args = listOf(fixtureId.get())
    }
}

tasks.register<JavaExec>("generateLayoutReport") {
    group = "verification"
    description = "Generates the layout decision dump and diagnostic HTML report."
    dependsOn("jvmTestClasses")
    mainClass.set("org.tiqian.layout.tooling.LayoutReportMainKt")
    classpath = files(jvmTestCompilation.output.allOutputs) +
        configurations.named("jvmTestRuntimeClasspath").get()
    // BufferedImage/font probing is fully off-screen. Headless mode prevents macOS AWT's
    // non-daemon auto-shutdown thread from keeping the completed CI task alive indefinitely.
    jvmArgs("-Djava.awt.headless=true", "--enable-native-access=ALL-UNNAMED")
}

val readmeSampleBlackSvg = rootProject.layout.projectDirectory.file("docs/images/sample-paragraph-black.svg")
val readmeSampleWhiteSvg = rootProject.layout.projectDirectory.file("docs/images/sample-paragraph-white.svg")

tasks.register<JavaExec>("generateReadmeSample") {
    group = "documentation"
    description = "Generates the README paragraph sample from a real Tiqian LayoutResult."
    dependsOn("jvmTestClasses")
    mainClass.set("org.tiqian.layout.tooling.ReadmeSampleMainKt")
    classpath = files(jvmTestCompilation.output.allOutputs) +
        configurations.named("jvmTestRuntimeClasspath").get()
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    args(
        readmeSampleBlackSvg.asFile.absolutePath,
        readmeSampleWhiteSvg.asFile.absolutePath,
    )
    outputs.files(readmeSampleBlackSvg, readmeSampleWhiteSvg)
}
