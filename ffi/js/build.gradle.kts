plugins {
    kotlin("multiplatform")
}

kotlin {
    js {
        // Generated names are package internals consumed by
        // `frontend/web/npm` (`precompute.js`, `layout-worker.js`) and the
        // plan parity oracle (ADR 0050).
        outputModuleName.set("Tiqian-tiqian-ffi-js")
        nodejs()
        useEsModules()
        binaries.executable()
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
