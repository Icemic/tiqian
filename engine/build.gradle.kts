import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
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

kotlin {
    jvm()
    android {
        namespace = "org.tiqian.engine"
        compileSdk = 36
        minSdk = 23
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
        commonTest.dependencies {
            implementation(kotlin("test"))
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
            implementation(project(":test-support"))
            runtimeOnly("org.jetbrains.skiko:skiko-awt-runtime-macos-arm64:0.144.6")
        }
    }
}

val jvmTestCompilation = kotlin.targets.getByName("jvm").compilations.getByName("test")

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
