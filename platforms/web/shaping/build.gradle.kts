plugins {
    kotlin("multiplatform")
}

kotlin {
    js {
        outputModuleName.set("Tiqian-tiqian-shaping-web")
        browser()
        useEsModules()
    }

    sourceSets {
        jsMain {
            dependencies {
                api(project(":engine"))
            }
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
