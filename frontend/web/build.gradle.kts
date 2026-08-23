import org.gradle.api.tasks.Sync
import org.gradle.language.jvm.tasks.ProcessResources

plugins {
    kotlin("multiplatform")
}

kotlin {
    js {
        // These generated names are package internals. Keep them stable while
        // the repository and Gradle project paths move to the shorter layout.
        outputModuleName.set("Tiqian-tiqian-web")
        browser {
            commonWebpackConfig {
                outputFileName = "tiqian-web.js"
            }
        }
        useEsModules()
        binaries.executable()
    }
    sourceSets {
        jsMain {
            dependencies {
                api(project(":engine"))
                implementation(project(":platforms:web:shaping"))
            }
        }
        jsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.named<ProcessResources>("jsProcessResources") {
    from(layout.projectDirectory.file("npm/styles.css"))
}

tasks.register<Sync>("assembleNpmPackage") {
    group = "distribution"
    description = "Builds the @tiqian/prose ESM package runtime."
    dependsOn("jsBrowserProductionWebpack", "assemblePrecomputeNpmRuntime")
    from(layout.buildDirectory.dir("kotlin-webpack/js/productionExecutable")) {
        include("tiqian-web.js")
    }
    into(layout.projectDirectory.dir("npm/runtime"))
}

tasks.register<Sync>("assemblePrecomputeNpmRuntime") {
    group = "distribution"
    description = "Builds the Node-only engine runtime for the prose layout worker."
    dependsOn(":ffi:js:jsProductionExecutableCompileSync")
    from(
        project(":ffi:js")
            .layout.buildDirectory.dir("compileSync/js/main/productionExecutable/kotlin"),
    ) {
        include("*.mjs")
    }
    into(layout.projectDirectory.dir("npm/precompute-runtime"))
}
