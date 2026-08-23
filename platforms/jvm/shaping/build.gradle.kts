plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies {
            api(project(":engine"))
        }

        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
