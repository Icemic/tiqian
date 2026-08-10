import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// Production Apple frontend. Packages the engine and the narrow Swift-facing
// authoring/layout/drawing API as a static XCFramework consumed by the Swift package in this
// directory.
plugins {
    kotlin("multiplatform")
}

kotlin {
    // One static XCFramework named "Tiqian" — Swift does `import Tiqian`. Static so the whole
    // engine klib graph links into the app with nothing to embed/sign at runtime.
    val xcf = XCFramework("Tiqian")
    listOf(macosArm64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Tiqian"
            isStatic = true
            xcf.add(this)
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain.dependencies {
            implementation(project(":frontend:apple:coretext-render"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
