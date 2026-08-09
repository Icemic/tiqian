import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

// Packages Tiqian's Core Text frontend as a Swift-consumable XCFramework for the
// macOS demo app (demo/macos-app). Kotlin/Native, macos-arm64 only. It wraps
// `:frontend:coretext-render` behind a small Swift-facing facade (TiqianTypesetter /
// TiqianParagraph) so the Swift side only measures and draws.
plugins {
    kotlin("multiplatform")
}

kotlin {
    // One static XCFramework named "Tiqian" — Swift does `import Tiqian`. Static so the
    // whole engine klib graph links into the app with nothing to embed/sign at runtime.
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
            implementation(project(":frontend:coretext-render"))
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
