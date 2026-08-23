import org.gradle.api.tasks.Sync

plugins {
    kotlin("multiplatform")
}

kotlin {
    js {
        // Generated names are package internals consumed by the `@tiqian/ffi`
        // npm package (`ffi/js/npm`) and the plan parity oracle (ADR 0050).
        outputModuleName.set("Tiqian-tiqian-ffi-js")
        nodejs()
        useEsModules()
        binaries.executable()
        // Ship the @JsExport surface with TypeScript definitions next to the
        // executable module (ADR 0053 A4); source maps embed their sources by
        // default, so the published .map files stand alone.
        generateTypeScriptDefinitions()
    }

    sourceSets {
        jsMain.dependencies {
            implementation(project(":engine"))
        }
        jsTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.register<Sync>("assembleNpmPackage") {
    group = "distribution"
    description = "Builds the @tiqian/ffi ESM package runtime."
    dependsOn("jsProductionExecutableCompileSync")
    from(layout.buildDirectory.dir("compileSync/js/main/productionExecutable/kotlin")) {
        include("*.mjs")
        include("*.mjs.map")
        include("*.d.mts")
    }
    into(layout.projectDirectory.dir("npm/runtime"))
}
