plugins {
    kotlin("multiplatform")
}

// Apple (Core Text) rendering frontend for Tiqian. Draws a LayoutResult via
// CTFontDrawGlyphs and wires the engine to the Core Text shaper + metrics
// (AppleParagraphBackend). Kotlin/Native, consuming platform.CoreText bindings.
// Named `coretext-render` (not `coretext`) so its capability differs from the
// `:shaping:coretext` adapter — same leaf name would collide under the shared group.
kotlin {
    macosArm64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain.dependencies {
            api(project(":core"))
            api(project(":layout"))
            api(project(":shaping:coretext"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
