import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.jvm.tasks.Jar
import org.gradle.plugins.signing.SigningExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    kotlin("multiplatform") version "2.3.20" apply false
    kotlin("android") version "2.3.20" apply false
    id("com.android.library") version "9.3.1" apply false
    id("com.android.kotlin.multiplatform.library") version "9.3.1" apply false
    id("com.android.application") version "9.3.1" apply false
    id("com.android.test") version "9.3.1" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
}

group = "org.tiqian"
version = providers.gradleProperty("tiqianVersion")
    .orElse(providers.environmentVariable("TIQIAN_VERSION"))
    .getOrElse("0.1.0-SNAPSHOT")

data class PublishedModule(
    val artifactId: String,
    val displayName: String,
    val description: String,
)

val publishedModules = mapOf(
    ":core" to PublishedModule("tiqian-core", "Tiqian Core", "Core document and layout data types for Tiqian."),
    ":font" to PublishedModule("tiqian-font", "Tiqian Font", "Font selection and metrics contracts for Tiqian."),
    ":linebreak" to PublishedModule("tiqian-linebreak", "Tiqian Line Break", "Line-breaking primitives for Tiqian."),
    ":clreq" to PublishedModule("tiqian-clreq", "Tiqian CLREQ", "Chinese composition rules used by Tiqian."),
    ":layout" to PublishedModule("tiqian-layout", "Tiqian Layout", "The Tiqian CJK paragraph layout engine."),
    ":shaping:api" to PublishedModule("tiqian-shaping-api", "Tiqian Shaping API", "Platform-neutral shaping contracts for Tiqian."),
    ":shaping:jvm" to PublishedModule("tiqian-shaping-jvm", "Tiqian JVM Shaping", "JVM shaping support for Tiqian."),
    ":shaping:skia" to PublishedModule("tiqian-shaping-skia", "Tiqian Skia Shaping", "Skia shaping and glyph replay support for Tiqian."),
    ":shaping:android-adapter" to PublishedModule(
        "tiqian-shaping-android-adapter",
        "Tiqian Android Shaping Adapter",
        "Android shaping and glyph replay adapter for Tiqian.",
    ),
    ":shaping:android-native-font" to PublishedModule(
        "tiqian-shaping-android-native-font",
        "Tiqian Android Native Font",
        "Native Android font discovery and shaping support for Tiqian.",
    ),
    ":frontend:compose" to PublishedModule("tiqian-compose", "Tiqian Compose", "Compose frontend for the Tiqian CJK paragraph layout engine."),
    ":frontend:compose-material3" to PublishedModule(
        "tiqian-compose-material3",
        "Tiqian Compose Material 3",
        "Material 3 context adapter for the Tiqian Compose frontend.",
    ),
)

fun Project.configureMavenPublishing(module: PublishedModule) {
    pluginManager.apply("maven-publish")
    pluginManager.apply("signing")

    extensions.configure<PublishingExtension>("publishing") {
        repositories {
            maven {
                name = "central"
                url = uri("https://ossrh-staging-api.central.sonatype.com/service/local/staging/deploy/maven2/")
                credentials {
                    username = providers.gradleProperty("mavenCentralUsername")
                        .orElse(providers.environmentVariable("MAVEN_CENTRAL_USERNAME"))
                        .orNull
                    password = providers.gradleProperty("mavenCentralPassword")
                        .orElse(providers.environmentVariable("MAVEN_CENTRAL_PASSWORD"))
                        .orNull
                }
            }
        }
    }

    pluginManager.withPlugin("com.android.library") {
        extensions.configure<LibraryExtension>("android") {
            publishing {
                singleVariant("release") {
                    withSourcesJar()
                }
            }
        }
        afterEvaluate {
            extensions.configure<PublishingExtension>("publishing") {
                if (publications.findByName("release") == null) {
                    publications.create<MavenPublication>("release") {
                        from(components["release"])
                    }
                }
            }
        }
    }

    afterEvaluate {
        extensions.configure<PublishingExtension>("publishing") {
            publications.withType(MavenPublication::class.java).configureEach {
                val publicationName = name
                val targetSuffix = artifactId.removePrefix(project.name)
                artifactId = module.artifactId + targetSuffix
                artifact(
                    tasks.register<Jar>("${publicationName}PublicationJavadocJar") {
                        archiveBaseName.set("${project.name}-$publicationName")
                        archiveClassifier.set("javadoc")
                        from(rootProject.file("LICENSE")) {
                            into("META-INF")
                        }
                    },
                )
                pom {
                    name.set(module.displayName)
                    description.set(module.description)
                    url.set("https://github.com/tiqian-cjk/tiqian")
                    licenses {
                        license {
                            name.set("Mozilla Public License 2.0")
                            url.set("https://www.mozilla.org/MPL/2.0/")
                            distribution.set("repo")
                        }
                    }
                    developers {
                        developer {
                            id.set("123Duo3")
                            name.set("123Duo3")
                            email.set("123duo3@gmail.com")
                        }
                    }
                    scm {
                        connection.set("scm:git:https://github.com/tiqian-cjk/tiqian.git")
                        developerConnection.set("scm:git:ssh://git@github.com/tiqian-cjk/tiqian.git")
                        url.set("https://github.com/tiqian-cjk/tiqian")
                    }
                }
            }
        }

        val signingKey = providers.gradleProperty("signingKey")
            .orElse(providers.environmentVariable("SIGNING_KEY"))
            .orNull
        if (!signingKey.isNullOrBlank()) {
            extensions.configure<SigningExtension>("signing") {
                useInMemoryPgpKeys(
                    providers.gradleProperty("signingKeyId")
                        .orElse(providers.environmentVariable("SIGNING_KEY_ID"))
                        .orNull,
                    signingKey,
                    providers.gradleProperty("signingPassword")
                        .orElse(providers.environmentVariable("SIGNING_PASSWORD"))
                        .orNull,
                )
                sign(extensions.getByType(PublishingExtension::class.java).publications)
            }
        }
    }
}

subprojects {
    group = rootProject.group
    version = rootProject.version

    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<KotlinMultiplatformExtension>("kotlin") {
            // Compile and test with the uniform provisioned JDK 25 toolchain, but emit Java 17
            // bytecode so published JVM libraries do not impose the build JDK on consumers.
            jvmToolchain(25)
            jvm {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_17)
                }
            }
        }
    }

    val publishedModule = publishedModules[path]
    if (publishedModule != null) {
        configureMavenPublishing(publishedModule)
    }
}

tasks.register("publishTiqianToMavenLocal") {
    group = "publishing"
    description = "Publishes every public Tiqian module to Maven Local with one lockstep version."
    dependsOn(publishedModules.keys.map { "$it:publishToMavenLocal" })
}

tasks.register("publishTiqianToCentral") {
    group = "publishing"
    description = "Uploads every public Tiqian module to the Central Portal staging API."
    dependsOn(publishedModules.keys.map { "$it:publishAllPublicationsToCentralRepository" })
}

tasks.register("runComposeDemo") {
    group = "application"
    description = "Opens the shared Tiqian Compose demo on Desktop."
    dependsOn(":demo:runComposeDemo")
}
