import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.dokka)
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.serialization)
    alias(libs.plugins.vanniktech.publish)
    `maven-publish`
    signing
}

val libGroup = "io.tolgee.mobile-kotlin-sdk"
val libName = "compose"

group = libGroup
version = libVersion

dokka {
    moduleName.set("Compose")
    dokkaSourceSets.configureEach {
        includes.from("Module.md")
        sourceLink {
            localDirectory.set(file("src"))
            remoteUrl("https://github.com/tolgee/tolgee-mobile-kotlin-sdk/tree/master/compose/src")
        }
    }
    // See core/build.gradle.kts — module pages carry their own branding.
    pluginsConfiguration.html {
        customAssets.from(rootProject.file("docs/logo-icon.svg"))
        customStyleSheets.from(rootProject.file("docs/tolgee.css"))
        homepageLink.set("https://tolgee.io/")
        footerMessage.set("© 2021-2026 Tolgee s.r.o. All rights reserved")
    }
}

kotlin {
    androidTarget {
        publishAllLibraryVariants()
    }
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
    }
    jvmToolchain(21)

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    macosX64()
    macosArm64()

    js(IR) {
        browser()
        binaries.executable()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        binaries.executable()
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(compose.components.resources)

            implementation(libs.coroutines)
            api(project(":core"))
        }
    }
}

android {
    compileSdk = 36
    namespace = "io.tolgee.mobilekotlinsdk.compose"

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_21
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()

    coordinates(
        groupId = libGroup,
        artifactId = libName,
        version = libVersion
    )

    pom {
        name.set(libName)

        description.set("Compose Multiplatform localization wrapper for Tolgee")
        url.set("https://github.com/tolgee/tolgee-mobile-kotlin-sdk")

        licenses {
            license {
                name.set("Apache License 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        scm {
            url.set("https://github.com/tolgee/tolgee-mobile-kotlin-sdk")
            connection.set("scm:git:git://github.com/tolgee/tolgee-mobile-kotlin-sdk.git")
        }

        developers {
            developer {
                id.set("DatL4g")
                name.set("Jeff Retz (DatLag)")
                url.set("https://github.com/DatL4g")
            }
        }
    }
}